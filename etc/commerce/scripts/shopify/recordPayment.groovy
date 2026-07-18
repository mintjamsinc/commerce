// Derive + persist the payment facts on a payment (order transaction) node, from the
// order-transaction-created route right after the raw body + core props are stored.
//
// The route's JSONPath cannot compute money decisions or day buckets, so this script applies the
// SHARED typed-prop writer (commerce.PaymentMirror.applyProps) — the same one the bulk import
// uses — to the webhook-stored node:
//   - commerce:payment_amount (Decimal, native) / commerce:payment_amount_base (Decimal)
//   - commerce:paid_at (Date) — the occurrence-date axis
//   - commerce:kind / commerce:gateway / commerce:currency
//
// The BASE amount follows the single-currency-shop policy (commerce.Payments.amountBase): a REST
// webhook transaction carries no shop_money, so native stands in for base when the transaction
// currency matches the shop currency (resolved from the parent order's mirror) or either is
// unknown; a KNOWN cross-currency transaction gets NO base prop (warned below — the occurrence
// report's facet SUM must never read a fake base).
//
// Defensive: a failure is logged and rolled back; the route's error handler owns the terminal
// error path (status=error + move to the error folder).
//
// Input (script attributes, mapped from exchange headers):
//   paymentPath : repository path to the stored payment resource
//   order_id    : Shopify order numeric id (used to resolve the shop currency; may be absent)

import commerce.Payments
import commerce.PaymentMirror

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }
def paymentPath = hv("paymentPath")?.toString()
if (!paymentPath || paymentPath.trim().isEmpty()) {
    throw new IllegalArgumentException("Required variable 'paymentPath' is missing")
}

def paymentResource = repositorySession.getResource(paymentPath)
if (paymentResource == null || !paymentResource.exists()) {
    log.warn("recordPayment: payment resource not found: ${paymentPath} - skipping")
    return
}

def txn
try {
    txn = JSON.parse(paymentResource.content.toString())
} catch (Exception e) {
    log.warn("recordPayment: could not parse transaction JSON at ${paymentPath}: ${e.message} - skipping")
    return
}

// Authoritative cash-in judgment (the route's JSONPath gate is a mirror of this; re-check so a
// gate/judgment drift can never stamp payment facts on a non-payment).
if (!Payments.isCashIn(txn)) {
    log.warn("recordPayment: ${paymentPath} kind=${txn?.kind} status=${txn?.status} is not a cash-in event - no payment facts stamped")
    return
}

try {
    def orderId = hv("order_id")?.toString() ?: txn.order_id?.toString()
    def shopCurrency = PaymentMirror.shopCurrencyOf(repositorySession, orderId)
    boolean baseWritten = PaymentMirror.applyProps(paymentResource, txn, shopCurrency)
    if (!baseWritten && Payments.amount(txn) != null) {
        // Known cross-currency without shop_money: the native amount is stored, the base prop is
        // omitted (never faked). Surfaced here so the operator can spot a base-currency gap.
        log.warn("recordPayment: ${paymentPath} currency ${Payments.currency(txn)} != shop ${shopCurrency} and no shop_money - payment_amount_base omitted")
    }
    repositorySession.commit()
} catch (Exception e) {
    try { repositorySession.rollback() } catch (Exception ignore) {}
    log.warn("recordPayment: failed to persist payment facts for ${paymentPath}: ${e.message}")
}
