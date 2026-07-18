// Mirror a Shopify fulfillment-hold state change onto the parent order node.
//
// Invoked from the fulfillment_orders/placed_on_hold | hold_released Camel route
// (fulfillment-hold.xml). Shopify models "hold" on the fulfillment order; the
// order payload itself never carries it, so this mirror is the only place the
// rest of the app (the Fulfill Order task form in particular) can read it from.
//
// These webhook topics deliver a SLIM payload — the fulfillment order's GraphQL
// gid and status, no parent order id — so the parent order is resolved through
// the Admin API (fulfillmentOrder → order.legacyResourceId). When the Admin API
// is not configured the event is skipped with a warning: the hold mirror is an
// Admin-API-dependent feature, like every other outbound/lookup integration.
//
// Writes on the EXISTING order mirror node only — an order we never ingested is
// skipped, not created (the mirror store is owned by orders/paid | updated):
//   - commerce:fulfillment_hold_fo_ids     (JSON array of held FO ids, numeric strings)
//   - commerce:fulfillment_hold            (Boolean; derived — true while ANY FO is held)
//   - commerce:fulfillment_hold_updated_at (Date; when the mirror last changed)
//
// Tracking the SET of held fulfillment orders (not a bare boolean) keeps a
// multi-FO order (e.g. split across locations) held until every hold is
// released. A payload without a usable FO id degrades to boolean semantics.
//
// Mirror-only: commerce:status and the workflow are never touched here.
//
// Inputs (?inputs=order_id,fulfillment_order_id,shopify_topic):
//   - order_id             : parent order id if the payload carried one (usually absent)
//   - fulfillment_order_id : the fulfillment order gid ($.fulfillment_order.id)
//   - shopify_topic        : the webhook topic that fired (decides add vs remove)
//
// A missing order node returns silently; an Admin API / repository failure
// PROPAGATES so the route's redelivery policy can retry the mirror write.

import commerce.Api
import commerce.Orders
import commerce.ShopifyAdmin
import java.net.http.HttpClient

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }

def topic = hv("shopify_topic")?.toString()
def foGid = hv("fulfillment_order_id")?.toString()
def orderId = hv("order_id")?.toString()
if (!topic || (!foGid && !orderId)) {
    log.warn("setFulfillmentHold: fulfillment_order_id / shopify_topic missing - skipping")
    return
}
boolean onHold = (topic == "fulfillment_orders/placed_on_hold")
def foId = Api.legacyId(foGid)

// --- Resolve the parent order (Admin API; the slim payload has no order id) --
if (!orderId) {
    def config = null
    try {
        config = YAML.parse(repositorySession.getResource("/etc/commerce/config/shopify.yml"))
    } catch (Exception e) {
        log.warn("setFulfillmentHold: could not read shopify.yml: ${e.message} - treating Admin API as disabled")
    }
    if (!ShopifyAdmin.adminApiEnabled(config)) {
        log.warn("setFulfillmentHold: Admin API disabled - cannot resolve the parent order of ${foGid}; hold not mirrored")
        return
    }
    def adminApi = config?.adminApi ?: config
    def endpoint = ShopifyAdmin.endpoint(adminApi)
    def accessToken = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
    def query = """
query {
  fulfillmentOrder(id: "${Api.gid('FulfillmentOrder', foGid)}") {
    order { legacyResourceId }
  }
}
""".trim()
    def resp = ShopifyAdmin.graphql(HttpClient.newHttpClient(), endpoint, accessToken, [query: query])
    orderId = resp?.data?.fulfillmentOrder?.order?.legacyResourceId?.toString()
    if (!orderId) {
        // The FO vanished between the webhook and the lookup (e.g. the order was
        // cancelled, which closes its FOs). Nothing to mirror onto.
        log.info("setFulfillmentHold: could not resolve the parent order of ${foGid} - skipping")
        return
    }
}

def resource = Orders.findResource(repositorySession, orderId)
if (resource == null || !resource.exists()) {
    // Not an error: holds can fire for orders predating this integration (or
    // ahead of the orders/paid ingest - rare, and the paid flow starts unheld).
    log.info("setFulfillmentHold: no mirror node for order ${orderId} - skipping")
    return
}

// --- Update the held-FO set (degrade to boolean when the FO id is unusable) --
def held = [] as Set
try {
    if (resource.hasProperty("commerce:fulfillment_hold_fo_ids")) {
        def parsed = JSON.parse(resource.getProperty("commerce:fulfillment_hold_fo_ids").getValue().toString())
        if (parsed instanceof List) parsed.each { if (it != null) held << it.toString() }
    }
} catch (Exception e) {
    log.warn("setFulfillmentHold: unparseable held-FO set on order ${orderId}: ${e.message} - rebuilding")
}
if (foId) {
    if (onHold) held << foId else held.remove(foId)
} else {
    // No FO id in the payload: boolean semantics (whole-order set/clear).
    if (!onHold) held.clear()
}
boolean flag = onHold || !held.isEmpty()

resource.setProperty("commerce:fulfillment_hold_fo_ids", JSON.stringify(held as List))
resource.setProperty("commerce:fulfillment_hold", flag)
// Lifecycle rule: every state mutation records WHEN it happened (typed Date).
resource.setProperty("commerce:fulfillment_hold_updated_at", new java.util.Date())
repositorySession.commit()
log.info("setFulfillmentHold: order ${orderId} commerce:fulfillment_hold -> ${flag} (held FOs: ${held.size()}, ${topic})")
