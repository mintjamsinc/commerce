// Screen a Shopify order against the configured review rules.
//
// First step of order-review-flow.bpmn. Reads the order JSON (delivered via the
// orders/paid webhook) from the repository, evaluates it against the rules in
// /etc/commerce/config/order-review.yml, and decides whether the order needs a
// human review. The decision (and the human-readable reasons behind it) are
// exposed as process variables so the gateway, the review form, and the
// notification all read the same source of truth.
//
// Requires exchange/process variable:
//   - orderPath: repository path to the order resource
//                (e.g. /content/commerce/orders/raw/2026/06/order_12345.json)
//
// Sets process variables (declared via the service task's `outputs` field):
//   - requiresReview: true if at least one enabled rule matched
//   - reviewReasons : JSON array string of structured reason descriptors, e.g.
//                     [{"code":"highValue",
//                       "params":{"total":120000,"currency":"JPY","threshold":100000}},
//                      {"code":"addressMismatch","params":{"billing":"JP","shipping":"US"}}]
//                     Each consumer (review form / notification) renders these in
//                     its own locale context; we deliberately do NOT pre-render
//                     text here. See commerce.ReviewReasons.
//
// Screening is fail-open: a missing/unparseable config or order is logged and
// treated as "no review required" rather than blocking or flooding operators.

// Shared commerce helpers (see /content/WEB-INF/classes/commerce/).
import commerce.Money
import commerce.ReviewReasons

if (!orderPath) {
    throw new IllegalArgumentException("Required variable 'orderPath' is missing")
}

def resource = repositorySession.getResource(orderPath)
if (resource == null || !resource.exists()) {
    throw new RuntimeException("Order resource not found: ${orderPath}")
}

// --- Parse the order JSON (Shopify webhook payload) --------------------------
def order
try {
    order = JSON.parse(resource.content.toString())
} catch (Exception e) {
    log.warn("screenOrder: could not parse order JSON at ${orderPath}: ${e.message} - auto-approving")
    context.setAttribute("requiresReview", false)
    context.setAttribute("reviewReasons", "[]")
    return
}

// --- Load the screening configuration ----------------------------------------
def config = null
try {
    def configNode = repositorySession.getResource("/etc/commerce/config/order-review.yml")
    if (configNode != null && configNode.exists()) {
        config = YAML.parse(configNode)
    }
} catch (Exception e) {
    log.warn("screenOrder: could not read order-review.yml: ${e.message}")
}

if (config == null) {
    log.warn("screenOrder: order-review.yml not found or unparseable - auto-approving order at ${orderPath}")
    context.setAttribute("requiresReview", false)
    context.setAttribute("reviewReasons", "[]")
    return
}

// Master switch: when disabled, auto-approve everything.
if (config.enabled != null && config.enabled.toString().toLowerCase() == "false") {
    log.info("screenOrder: screening disabled - auto-approving order at ${orderPath}")
    context.setAttribute("requiresReview", false)
    context.setAttribute("reviewReasons", "[]")
    return
}

def rules = config.rules ?: [:]
def reasons = []

// --- Rule: high-value order --------------------------------------------------
def highValue = rules.highValue
if (ruleEnabled(highValue)) {
    def total = Money.toNumber(order.total_price)
    def currency = order.currency?.toString()?.trim()
    def threshold = resolveThreshold(highValue, currency)
    if (total != null && threshold != null && total >= threshold) {
        reasons << ReviewReasons.highValue(total, currency, threshold)
    }
}

// --- Rule: flagged financial status ------------------------------------------
def flaggedFin = rules.flaggedFinancialStatus
if (ruleEnabled(flaggedFin)) {
    def statuses = (flaggedFin.statuses ?: []).collect { it?.toString()?.toLowerCase() }
    def fin = order.financial_status?.toString()?.toLowerCase()
    if (fin != null && statuses.contains(fin)) {
        reasons << ReviewReasons.flaggedFinancialStatus(fin)
    }
}

// --- Rule: large single-line quantity ----------------------------------------
def largeQty = rules.largeQuantity
if (ruleEnabled(largeQty)) {
    def max = Money.toNumber(largeQty.maxLineQuantity)
    if (max != null) {
        def lineItems = order.line_items ?: []
        for (li in lineItems) {
            def qty = Money.toNumber(li?.quantity)
            if (qty != null && qty >= max) {
                reasons << ReviewReasons.largeQuantity(li?.title?.toString(), qty, max)
                break
            }
        }
    }
}

// --- Rule: new / first-time customer -----------------------------------------
def newCustomer = rules.newCustomer
if (ruleEnabled(newCustomer)) {
    def maxOrders = Money.toNumber(newCustomer.maxOrdersCount)
    def ordersCount = Money.toNumber(order.customer?.orders_count)
    if (maxOrders != null && ordersCount != null && ordersCount <= maxOrders) {
        reasons << ReviewReasons.newCustomer(ordersCount, maxOrders)
    }
}

// --- Rule: billing / shipping country mismatch -------------------------------
def addressMismatch = rules.addressMismatch
if (ruleEnabled(addressMismatch)) {
    def billCountry = countryCode(order.billing_address)
    def shipCountry = countryCode(order.shipping_address)
    if (billCountry && shipCountry && billCountry != shipCountry) {
        reasons << ReviewReasons.addressMismatch(billCountry, shipCountry)
    }
}

// --- Publish the decision ----------------------------------------------------
def requiresReview = !reasons.isEmpty()
context.setAttribute("requiresReview", requiresReview)
context.setAttribute("reviewReasons", JSON.stringify(reasons))
log.info("screenOrder: ${orderPath} requiresReview=${requiresReview} reasons=${reasons}")

// --- Helpers -----------------------------------------------------------------

// A rule applies unless it is explicitly disabled (enabled: false). A missing
// `enabled` flag means the rule block's presence implies it is on.
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

String countryCode(address) {
    if (address == null) return null
    def code = address.country_code ?: address.country
    return code?.toString()?.trim()?.toUpperCase() ?: null
}
