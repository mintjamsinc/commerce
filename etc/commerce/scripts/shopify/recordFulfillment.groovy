import java.net.http.HttpClient
import commerce.ShopifyAdmin
import commerce.Health

// Record the fulfillment of an order at the end of order-review-flow.bpmn.
//
// Two responsibilities, in order of importance:
//   1. ALWAYS persist the fulfillment outcome on the order resource (tracking
//      number / carrier entered by the fulfiller, fulfilled-at timestamp). This
//      is the CMS-side source of truth and never depends on Shopify.
//   2. OPTIONALLY write the fulfillment back to Shopify via the Admin API
//      (GraphQL fulfillmentCreateV2). This is gated on adminApi.enabled - exactly
//      like the product metafield enrichment - and is best-effort: a failure is
//      recorded on the order and logged, but never breaks the workflow (the
//      operator can still fulfil manually in Shopify).
//
// The fulfiller enters tracking details in the Fulfill Order form, which writes
// them to the order node's `fulfillment` JSON property (mirroring how the
// inventory threshold form writes `inventory_level_config`). We read them here
// rather than from process variables.
//
// Required process variables (mapped in via the service task's `inputs` field):
//   - orderPath: repository path to the order resource
//   - order_id : Shopify numeric order ID (for the Admin API write-back)
//
// Records on the order resource:
//   - commerce:tracking_number, commerce:tracking_company  (when provided)
//   - commerce:fulfilled_at        ISO timestamp of fulfilment
//   - commerce:fulfillment_writeback   ok | skipped | failed
//   - commerce:fulfillment_id      Shopify fulfillment GID (on a successful write-back)
//   - commerce:fulfillment_error   error detail (on a failed write-back)

if (!orderPath) {
    throw new IllegalArgumentException("Required variable 'orderPath' is missing")
}

def orderResource = repositorySession.getResource(orderPath)
if (orderResource == null || !orderResource.exists()) {
    // The order vanished; nothing we can do, but don't break the workflow.
    log.warn("recordFulfillment: order resource not found: ${orderPath} - skipping")
    return
}

// --- Read tracking details entered by the fulfiller --------------------------
def trackingNumber = null
def trackingCompany = null
def trackingUrl = null
try {
    if (orderResource.hasProperty("fulfillment")) {
        def f = JSON.parse(orderResource.getProperty("fulfillment").getValue())
        trackingNumber = trimToNull(f?.trackingNumber)
        trackingCompany = trimToNull(f?.trackingCompany)
        trackingUrl = trimToNull(f?.trackingUrl)
    }
} catch (Exception e) {
    log.warn("recordFulfillment: could not parse `fulfillment` property: ${e.message}")
}

// --- 1. Persist the CMS-side fulfillment outcome (always) --------------------
try {
    if (trackingNumber != null) orderResource.setProperty("commerce:tracking_number", trackingNumber)
    if (trackingCompany != null) orderResource.setProperty("commerce:tracking_company", trackingCompany)
    orderResource.setProperty("commerce:fulfilled_at", java.time.Instant.now().toString())
    repositorySession.commit()
} catch (Exception e) {
    try { repositorySession.rollback() } catch (Exception ignore) {}
    log.warn("recordFulfillment: failed to persist fulfillment metadata for ${orderPath}: ${e.message}")
}

// --- 2. Optional Shopify Admin API write-back (gated, best-effort) -----------
def adminApiEnabled = false
def adminApi = null
try {
    def configNode = repositorySession.getResource("/etc/commerce/config/shopify.yml")
    def config = YAML.parse(configNode)
    adminApi = config?.adminApi ?: config
    adminApiEnabled = ShopifyAdmin.adminApiEnabled(config)
} catch (Exception e) {
    log.warn("recordFulfillment: could not read shopify.yml: ${e.message} - treating Admin API as disabled")
}

if (!adminApiEnabled) {
    setWriteback(orderPath, "skipped", null, null)
    log.info("recordFulfillment: Admin API disabled - recorded CMS-side fulfillment only for ${orderPath}")
    return
}

if (!order_id) {
    setWriteback(orderPath, "failed", null, "order_id is missing; cannot address the order in Shopify")
    log.warn("recordFulfillment: order_id missing - cannot write fulfillment back to Shopify for ${orderPath}")
    return
}

try {
    def endpoint = ShopifyAdmin.endpoint(adminApi)
    def accessToken = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
    def httpClient = HttpClient.newHttpClient()

    // Step 1: resolve the order's fulfillment orders (Shopify groups line items
    // into fulfillment orders; a fulfillment is created against those).
    def foQuery = """
query {
  order(id: "gid://shopify/Order/${order_id}") {
    fulfillmentOrders(first: 10, query: "status:open OR status:in_progress OR status:scheduled") {
      edges { node { id status } }
    }
  }
}
""".trim()
    def foResp = Health.timeApi(repositorySession, log, "fulfillmentOrders") {
        ShopifyAdmin.graphql(httpClient, endpoint, accessToken, [query: foQuery])
    }
    def edges = foResp?.data?.order?.fulfillmentOrders?.edges
    if (!edges) {
        // Nothing left to fulfil (already fulfilled, or none open).
        setWriteback(orderPath, "skipped", null, "no open fulfillment orders in Shopify")
        log.info("recordFulfillment: no open fulfillment orders for order ${order_id} - nothing to write back")
        return
    }

    def lineItemsByFO = edges.collect { e -> [fulfillmentOrderId: e.node.id] }

    // Step 2: build trackingInfo only when the fulfiller supplied any.
    def trackingInfo = [:]
    if (trackingNumber != null) trackingInfo.number = trackingNumber
    if (trackingCompany != null) trackingInfo.company = trackingCompany
    if (trackingUrl != null) trackingInfo.url = trackingUrl

    // Whether to have Shopify email the customer a shipping notification. This is
    // an outward-facing side effect, so it defaults to OFF and is opt-in via
    // shopify.yml -> adminApi.notifyCustomer: true (avoids surprise / duplicate
    // emails when the store already notifies on fulfillment).
    def notifyCustomer = adminApi?.notifyCustomer != null &&
        adminApi.notifyCustomer.toString().trim().toLowerCase() == "true"

    def fulfillmentInput = [
        lineItemsByFulfillmentOrder: lineItemsByFO,
        notifyCustomer             : notifyCustomer,
    ]
    if (!trackingInfo.isEmpty()) {
        fulfillmentInput.trackingInfo = trackingInfo
    }

    def mutation = """
mutation fulfillmentCreate(\$fulfillment: FulfillmentV2Input!) {
  fulfillmentCreateV2(fulfillment: \$fulfillment) {
    fulfillment { id status trackingInfo { number company url } }
    userErrors { field message }
  }
}
""".trim()
    def mutResp = Health.timeApi(repositorySession, log, "fulfillmentCreate") {
        ShopifyAdmin.graphql(httpClient, endpoint, accessToken,
            [query: mutation, variables: [fulfillment: fulfillmentInput]])
    }

    def result = mutResp?.data?.fulfillmentCreateV2
    def userErrors = result?.userErrors
    if (userErrors) {
        setWriteback(orderPath, "failed", null, "userErrors: ${JSON.stringify(userErrors)}")
        log.warn("recordFulfillment: Shopify rejected the fulfillment for order ${order_id}: ${JSON.stringify(userErrors)}")
        return
    }

    def fulfillmentId = result?.fulfillment?.id
    setWriteback(orderPath, "ok", fulfillmentId?.toString(), null)
    log.info("recordFulfillment: created Shopify fulfillment ${fulfillmentId} for order ${order_id}")
} catch (Exception e) {
    // Best-effort: never break the workflow on a write-back failure.
    setWriteback(orderPath, "failed", null, e.message)
    log.warn("recordFulfillment: Shopify write-back failed for order ${order_id}: ${e.message}")
}

// --- Helpers -----------------------------------------------------------------

// Persist the write-back outcome on the order resource. Swallows its own errors.
void setWriteback(String orderPath, String state, String fulfillmentId, String error) {
    try {
        def r = repositorySession.getResource(orderPath)
        if (r == null || !r.exists()) return
        r.setProperty("commerce:fulfillment_writeback", state)
        if (fulfillmentId != null) r.setProperty("commerce:fulfillment_id", fulfillmentId)
        if (error != null) {
            def msg = error.length() > 2048 ? error.substring(0, 2048) : error
            r.setProperty("commerce:fulfillment_error", msg)
        }
        repositorySession.commit()
    } catch (Exception e) {
        try { repositorySession.rollback() } catch (Exception ignore) {}
        log.warn("recordFulfillment: could not record write-back state '${state}': ${e.message}")
    }
}

String trimToNull(value) {
    if (value == null) return null
    def s = value.toString().trim()
    return s.isEmpty() ? null : s
}
