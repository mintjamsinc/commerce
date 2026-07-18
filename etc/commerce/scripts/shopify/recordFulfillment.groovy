import commerce.Api
import java.net.http.HttpClient
import commerce.ShopifyAdmin
import commerce.Health
import commerce.SyncAudit

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
//   - commerce:fulfilled_at        fulfilment timestamp (typed Date)
//   - commerce:fulfillment_writeback   ok | skipped | failed
//   - commerce:fulfillment_id      Shopify fulfillment id, numeric (on a successful write-back)
//   - commerce:fulfillment_error   error detail (on a failed write-back)
//
// Every write-back outcome is ALSO recorded in the outbound-write audit trail
// (commerce.SyncAudit, action "fulfillment") so the ops "operation log" app can answer
// who / when / against what / what action: WHO = the fulfiller captured on the order node
// (commerce:fulfilled_by, "workflow" if absent), WHAT-TARGET = this order.

if (!orderPath) {
    throw new IllegalArgumentException("Required variable 'orderPath' is missing")
}

def orderResource = repositorySession.getResource(orderPath)
if (orderResource == null || !orderResource.exists()) {
    // The order vanished; nothing we can do, but don't break the workflow.
    log.warn("recordFulfillment: order resource not found: ${orderPath} - skipping")
    return
}

// --- 0. Never fulfill a cancelled order (defence in depth) -------------------
// The flow's close branch normally bypasses this task, but this task can still
// be reached for a cancelled order by a process instance started on an older
// definition (deployed versions keep their original topology) or by a
// cancellation racing task completion. Shopify would reject the write anyway;
// skip it — and the fulfilled-at stamp — outright, with the outcome audited.
try {
    if (orderResource.hasProperty("commerce:cancelled_at")) {
        setWriteback(orderPath, "skipped", null, "order is cancelled in Shopify - fulfillment bypassed")
        log.info("recordFulfillment: ${orderPath} is cancelled in Shopify - skipping fulfillment")
        return
    }
} catch (Exception e) {
    log.warn("recordFulfillment: cancelled_at check failed for ${orderPath}: ${e.message} - continuing")
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
    orderResource.setProperty("commerce:fulfilled_at", new java.util.Date())
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
  order(id: "${Api.gid('Order', order_id)}") {
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

    // Storage keeps the NUMERIC id (same convention as commerce:order_id — the
    // GraphQL GID is peeled here, the wire re-GIDs on the way out; commerce.Api).
    def fulfillmentId = Api.legacyId(result?.fulfillment?.id)
    setWriteback(orderPath, "ok", fulfillmentId, null)
    log.info("recordFulfillment: created Shopify fulfillment ${fulfillmentId} for order ${order_id}")
} catch (Exception e) {
    // Best-effort: never break the workflow on a write-back failure.
    setWriteback(orderPath, "failed", null, e.message)
    log.warn("recordFulfillment: Shopify write-back failed for order ${order_id}: ${e.message}")
}

// --- Helpers -----------------------------------------------------------------

// Persist the write-back outcome on the order resource. Swallows its own errors.
// This is the single funnel every terminal outcome (ok / skipped / failed)
// routes through, so it also emits the outbound-write audit record here.
void setWriteback(String orderPath, String state, String fulfillmentId, String error) {
    try {
        def r = repositorySession.getResource(orderPath)
        if (r != null && r.exists()) {
            r.setProperty("commerce:fulfillment_writeback", state)
            // Lifecycle rule: a state mutation records WHEN it happened.
            r.setProperty("commerce:fulfillment_writeback_at", new java.util.Date())
            if (fulfillmentId != null) r.setProperty("commerce:fulfillment_id", fulfillmentId)
            if (error != null) {
                def msg = error.length() > 2048 ? error.substring(0, 2048) : error
                r.setProperty("commerce:fulfillment_error", msg)
            }
            repositorySession.commit()
        }
    } catch (Exception e) {
        try { repositorySession.rollback() } catch (Exception ignore) {}
        log.warn("recordFulfillment: could not record write-back state '${state}': ${e.message}")
    }
    auditFulfillment(orderPath, state, fulfillmentId, error)
}

// Record the fulfillment write-back in the outbound-write audit trail with
// WHO (the fulfiller captured on the order node, else "workflow") + WHAT-TARGET
// (this order). Best-effort — SyncAudit.record itself never throws, but the
// actor read / binding lookups are guarded so this can never break the workflow.
void auditFulfillment(String orderPath, String state, String fulfillmentId, String error) {
    try {
        def actor = "workflow"
        try {
            def r = repositorySession.getResource(orderPath)
            if (r != null && r.exists() && r.hasProperty("commerce:fulfilled_by")) {
                def v = r.getProperty("commerce:fulfilled_by").getValue()?.toString()
                if (v != null && !v.trim().isEmpty()) actor = v
            }
        } catch (Exception ignore) {}
        def oid = binding.hasVariable("order_id") ? binding.getVariable("order_id")?.toString() : null
        def request = [orderId: oid, fulfillmentId: fulfillmentId]
        SyncAudit.record(repositorySession, log, "fulfillment", request, state, fulfillmentId, error,
            actor, "order", oid)
    } catch (Exception ignore) {}
}

String trimToNull(value) {
    if (value == null) return null
    def s = value.toString().trim()
    return s.isEmpty() ? null : s
}
