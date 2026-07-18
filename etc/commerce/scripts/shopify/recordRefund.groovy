// Record a refund in refund-review-flow.bpmn, right after screening and BEFORE
// the review gate: the occurrence sales report aggregates the props stamped here
// (commerce:refund_amount_base and friends), and the review rules flag
// exactly the refunds that matter most to it (fullRefund / highRefundValue — e.g.
// every full-cancellation refund), so a refund parked at the manual review task
// must already be report-visible. The review is audit-only and reverses nothing,
// so there is nothing to record after it. This also keeps the webhook path
// symmetric with the backfill path (RefundMirror.storeRefund stamps the same
// props at store time). Idempotent: re-running restamps the same fact values and
// the order-summary increment is guarded by commerce:order_updated.
//
// Two responsibilities:
//   1. Persist the computed refund facts on the refund resource (amount,
//      currency, whether inventory was restocked, line-item count, note). These
//      are derived from the transactions / line items in the webhook payload,
//      which the EIP route cannot sum with JSONPath.
//   2. Update the ORIGINAL order's cumulative refund summary (best-effort): add
//      this refund to commerce:refunded_amount, bump commerce:refund_count, and
//      reflect the order's business status (refunded / partially_refunded).
//
// A refund is already executed in Shopify, so nothing is written back to Shopify
// here - this is bookkeeping only.
//
// Required process variables (mapped in via the service task's `inputs` field):
//   - refundPath: repository path to the refund resource
//   - order_id  : Shopify order ID the refund belongs to (may be absent)
//
// Records on the refund resource (TYPED: money Decimal, flags Boolean, counts Long):
//   - commerce:refund_amount (Decimal), commerce:currency, commerce:restocked (Boolean),
//     commerce:line_item_count (Long), commerce:refund_note
//   - commerce:order_updated (Boolean)  guard so the order summary is applied at most once
//
// Updates on the order resource (when locatable):
//   - commerce:refunded_amount (cumulative), commerce:refund_count,
//     commerce:source_status (refunded | partially_refunded)

import commerce.Money
import commerce.Refunds
import commerce.Orders
import commerce.SalesReconcile
import commerce.RefundMirror
import commerce.Api

if (!refundPath) {
    throw new IllegalArgumentException("Required variable 'refundPath' is missing")
}

def refundResource = repositorySession.getResource(refundPath)
if (refundResource == null || !refundResource.exists()) {
    log.warn("recordRefund: refund resource not found: ${refundPath} - skipping")
    return
}

def refund
try {
    refund = JSON.parse(refundResource.content.toString())
} catch (Exception e) {
    log.warn("recordRefund: could not parse refund JSON at ${refundPath}: ${e.message} - skipping")
    return
}

// --- Derive refund facts -----------------------------------------------------
def amount = Refunds.amount(refund)
def amountBase = Refunds.amountBase(refund)
def currency = Refunds.currency(refund)
def lineItems = refund.refund_line_items ?: []
def restocked = lineItems.any { Refunds.isRestocked(it) }
def note = refund.note?.toString()

// --- 1. Persist the facts on the refund resource -----------------------------
try {
    if (amount != null) refundResource.setProperty("commerce:refund_amount", (BigDecimal) amount)
    // Base-currency total for the refund-period sales view (returnsBasis=refund) — from Shopify's
    // own shop_money on the refund line items / adjustments (no external FX). Omitted when absent.
    if (amountBase != null) refundResource.setProperty("commerce:refund_amount_base", (BigDecimal) amountBase)
    // Tax portion of the refund (base) so the refund-period view can split the tax-inclusive
    // returns figure into goods + tax without re-reading bodies. Omitted when absent.
    def taxBase = Refunds.taxBase(refund)
    if (taxBase != null) refundResource.setProperty("commerce:refund_tax_base", (BigDecimal) taxBase)
    if (currency != null) refundResource.setProperty("commerce:currency", currency)
    refundResource.setProperty("commerce:restocked", (boolean) restocked)
    refundResource.setProperty("commerce:line_item_count", (long) lineItems.size())

    // A' (refund-side recon): persist the cash-anchored residual + classification so the report reads it
    // from a facet (never re-scanning bodies), and WARN when the returned value does not match the cash
    // refunded (a restocking fee the store kept, etc.). The value is surfaced, not asserted.
    def recon = SalesReconcile.reconProps(refund)
    recon.props.each { k, v ->
        if (v instanceof Boolean) refundResource.setProperty(k.toString(), (boolean) v)
        else if (v != null) refundResource.setProperty(k.toString(), (BigDecimal) v)
    }
    // Cash-out (refunds block) dimension: the parent order's ordered_at (so the report can
    // flag a refund whose order fell outside the window — crossPeriod). The refund's own date
    // axis is commerce:refunded_at alone (query-time day bucketing).
    def orderedAt = RefundMirror.orderedAtOf(repositorySession, refund.order_id)
    if (orderedAt != null) refundResource.setProperty("commerce:refund_ordered_at", new java.util.Date(orderedAt))
    def rc = recon.reconcile
    def base = SalesReconcile.baseCurrencyOf(refund)
    if (rc.currency != null && base != null && rc.currency != base) {
        log.warn("recordRefund: A' cross-currency refund ${refundPath} (${rc.currency} != base ${base}) - cash anchor is native, base ladder has no anchor")
    }
    if (rc.rings) {
        log.warn("recordRefund: A' refund ${refundPath} residual ${rc.delta} (returned ${rc.refundExpected} vs cash ${rc.cash})")
    } else if (rc.classification == SalesReconcile.TRANSACTIONLESS_WITH_VALUE) {
        log.warn("recordRefund: A' refund ${refundPath} transactionless with value ${rc.refundExpected} (no cash transaction)")
    }
    if (note != null && !note.trim().isEmpty()) {
        def trimmed = note.length() > 2048 ? note.substring(0, 2048) : note
        refundResource.setProperty("commerce:refund_note", trimmed)
    }
    repositorySession.commit()
} catch (Exception e) {
    try { repositorySession.rollback() } catch (Exception ignore) {}
    log.warn("recordRefund: failed to persist refund facts for ${refundPath}: ${e.message}")
}

// --- 2. Update the original order's cumulative refund summary -----------------
// Guard so re-running the workflow (e.g. a retried instance) cannot double-count.
if (refundResource.hasProperty("commerce:order_updated")
        && refundResource.getProperty("commerce:order_updated").getValue()?.toString() == "true") {
    log.info("recordRefund: order summary already applied for ${refundPath} - skipping")
    return
}

def orderId = context.hasAttribute("order_id") ? context.getAttribute("order_id") : null
if (amount == null || !orderId) {
    log.info("recordRefund: no amount or order_id - skipping order summary for ${refundPath}")
    return
}

try {
    def orderResource = Orders.findResource(repositorySession, orderId)
    if (orderResource == null) {
        log.info("recordRefund: original order ${orderId} not found - refund recorded without order summary")
        return
    }

    def previous = orderResource.hasProperty("commerce:refunded_amount")
        ? Money.toNumber(orderResource.getProperty("commerce:refunded_amount").getValue())
        : null
    def refundedTotal = (previous ?: BigDecimal.ZERO).add(amount)

    def previousCount = orderResource.hasProperty("commerce:refund_count")
        ? Money.toNumber(orderResource.getProperty("commerce:refund_count").getValue())
        : null
    def refundCount = (previousCount ?: BigDecimal.ZERO).add(BigDecimal.ONE)

    orderResource.setProperty("commerce:refunded_amount", (BigDecimal) refundedTotal)
    orderResource.setProperty("commerce:refund_count", refundCount.toBigInteger().longValue())

    // Reflect the order's business status based on how much has been refunded.
    def orderTotal = orderTotalOf(orderResource)
    if (orderTotal != null && orderTotal > 0) {
        def status = refundedTotal >= orderTotal ? "refunded" : "partially_refunded"
        orderResource.setProperty("commerce:source_status", status)
    }

    // Mark the refund as applied in the SAME transaction as the order increment,
    // so a crash can never leave the order updated without the guard set (which
    // would let a re-run double-count this refund). Both nodes commit atomically.
    refundResource.setProperty("commerce:order_updated", true)
    repositorySession.commit()

    log.info("recordRefund: order ${orderId} refunded_amount -> ${refundedTotal} (count ${refundCount.toBigInteger()})")
} catch (Exception e) {
    try { repositorySession.rollback() } catch (Exception ignore) {}
    log.warn("recordRefund: failed to update order summary for order ${orderId}: ${e.message}")
}

// --- Helpers -----------------------------------------------------------------

// Read the original order's total_price from its resource. Best-effort.
Number orderTotalOf(orderResource) {
    try {
        def order = JSON.parse(orderResource.content.toString())
        return Orders.totalPrice(order)
    } catch (Exception e) {
        return null
    }
}
