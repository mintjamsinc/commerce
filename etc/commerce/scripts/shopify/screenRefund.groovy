// Screen a Shopify refund against the configured review rules.
//
// First step of refund-review-flow.bpmn. Reads the refund JSON (delivered via
// the refunds/create webhook) from the repository, evaluates it against the
// rules in /etc/commerce/config/refund-review.yml, and decides whether the
// refund needs a human review. The decision (and the human-readable reasons
// behind it) are exposed as process variables so the gateway, the review form,
// and the notification all read the same source of truth.
//
// A refund is already executed in Shopify by the time this runs, so the review
// it gates is for audit / fraud-monitoring, not for issuing money.
//
// Requires process variables (mapped via the service task's `inputs` field):
//   - refundPath: repository path to the refund resource
//                 (e.g. /content/commerce/refunds/raw/2026/06/refund_12345.json)
//   - order_id  : Shopify order ID the refund belongs to (used to locate the
//                 original order for the fullRefund rule). May be absent.
//
// Sets process variables (declared via the service task's `outputs` field):
//   - requiresReview: true if at least one enabled rule matched
//   - reviewReasons : JSON array string of human-readable reasons
//
// Screening is fail-open: a missing/unparseable config or refund is logged and
// treated as "no review required" rather than blocking or flooding operators.

// Shared commerce helpers (see /content/WEB-INF/classes/commerce/).
import commerce.Money
import commerce.Refunds
import commerce.Orders

if (!refundPath) {
    throw new IllegalArgumentException("Required variable 'refundPath' is missing")
}

def resource = repositorySession.getResource(refundPath)
if (resource == null || !resource.exists()) {
    throw new RuntimeException("Refund resource not found: ${refundPath}")
}

// --- Parse the refund JSON (Shopify webhook payload) -------------------------
def refund
try {
    refund = JSON.parse(resource.content.toString())
} catch (Exception e) {
    log.warn("screenRefund: could not parse refund JSON at ${refundPath}: ${e.message} - auto-acknowledging")
    context.setAttribute("requiresReview", false)
    context.setAttribute("reviewReasons", "[]")
    return
}

// --- Load the screening configuration ----------------------------------------
def config = null
try {
    def configNode = repositorySession.getResource("/etc/commerce/config/refund-review.yml")
    if (configNode != null && configNode.exists()) {
        config = YAML.parse(configNode)
    }
} catch (Exception e) {
    log.warn("screenRefund: could not read refund-review.yml: ${e.message}")
}

if (config == null) {
    log.warn("screenRefund: refund-review.yml not found or unparseable - auto-acknowledging refund at ${refundPath}")
    context.setAttribute("requiresReview", false)
    context.setAttribute("reviewReasons", "[]")
    return
}

// Master switch: when disabled, auto-acknowledge everything.
if (config.enabled != null && config.enabled.toString().toLowerCase() == "false") {
    log.info("screenRefund: screening disabled - auto-acknowledging refund at ${refundPath}")
    context.setAttribute("requiresReview", false)
    context.setAttribute("reviewReasons", "[]")
    return
}

def rules = config.rules ?: [:]
def reasons = []

// --- Derive the refunded amount and currency from the transactions -----------
def refundAmount = Refunds.amount(refund)
def currency = Refunds.currency(refund)

// --- Rule: high-value refund -------------------------------------------------
def highValue = rules.highRefundValue
if (ruleEnabled(highValue)) {
    def threshold = resolveThreshold(highValue, currency)
    if (refundAmount != null && threshold != null && refundAmount >= threshold) {
        reasons << "High-value refund: ${Money.format(refundAmount)} ${currency ?: ''}".trim() + " >= ${Money.format(threshold)}"
    }
}

// --- Rule: full refund (refund >= original order total) ----------------------
// order_id is mapped in via `inputs` but may be absent on an odd payload, so
// read it defensively from the script context rather than as a bare binding.
def orderId = context.hasAttribute("order_id") ? context.getAttribute("order_id") : null
def fullRefund = rules.fullRefund
if (ruleEnabled(fullRefund) && refundAmount != null) {
    def orderTotal = orderTotalPrice(orderId)
    if (orderTotal != null && orderTotal > 0 && refundAmount >= orderTotal) {
        reasons << "Full refund: ${Money.format(refundAmount)} of ${Money.format(orderTotal)} ${currency ?: ''}".trim()
    }
}

// --- Rule: items returned without restocking ---------------------------------
def noRestock = rules.noRestock
if (ruleEnabled(noRestock)) {
    def lineItems = refund.refund_line_items ?: []
    if (!lineItems.isEmpty() && !lineItems.any { Refunds.isRestocked(it) }) {
        reasons << "Items refunded without restocking: ${lineItems.size()} line item(s)"
    }
}

// --- Publish the decision ----------------------------------------------------
def requiresReview = !reasons.isEmpty()
context.setAttribute("requiresReview", requiresReview)
context.setAttribute("reviewReasons", JSON.stringify(reasons))
log.info("screenRefund: ${refundPath} requiresReview=${requiresReview} reasons=${reasons}")

// --- Helpers -----------------------------------------------------------------

// Locate the original order by its node name (order_{id}.json) and read its
// total_price. Best-effort: returns null if the order cannot be found/read.
Number orderTotalPrice(orderId) {
    try {
        def orderResource = Orders.findResource(repositorySession, orderId)
        if (orderResource == null) {
            log.info("screenRefund: original order ${orderId} not found - skipping fullRefund rule")
            return null
        }
        def order = JSON.parse(orderResource.content.toString())
        return Orders.totalPrice(order)
    } catch (Exception e) {
        log.warn("screenRefund: could not resolve order ${orderId} total: ${e.message}")
        return null
    }
}

// A rule applies unless it is explicitly disabled (enabled: false).
boolean ruleEnabled(rule) {
    if (rule == null) return false
    def enabled = rule.enabled
    if (enabled == null) return true
    return enabled.toString().toLowerCase() == "true"
}

// Resolve the high-value threshold for a currency, falling back to `default`.
Number resolveThreshold(rule, String currency) {
    def thresholds = rule.thresholds
    if (thresholds != null && currency) {
        def byCurrency = thresholds[currency]
        if (byCurrency != null) return Money.toNumber(byCurrency)
    }
    return Money.toNumber(rule.default)
}
