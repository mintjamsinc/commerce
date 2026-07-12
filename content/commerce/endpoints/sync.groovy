// CMS → Shopify outbound sync endpoint (admin).
//
// Lets operators / tooling push corrections from the CMS back to Shopify via the
// Admin API: set a variant's stock at a location, set a variant's price, or
// publish/unpublish a product. Gated on adminApi.enabled (same switch as metafield
// enrichment and fulfillment write-back); supports dryRun for safe rollout.
//
//   GET  — capability/status: whether the Admin API is enabled + the actions.
//   POST — perform an action:
//     {"action":"inventory","inventoryItemId":123,"locationId":456,"quantity":10}
//     {"action":"price","productId":1,"variantId":2,"price":"19.99"}
//     {"action":"publish","productId":1,"published":true}
//     {"action":"customer","customerId":123,"fields":{"tags":["vip"],"note":"..."}}
//     {"action":"product","productId":1,"fields":{"title":"...","status":"ACTIVE"}}
//     {"action":"media","op":"add","productId":1,"originalSource":"https://...","alt":"..."}
//     {"action":"media","op":"delete","productId":1,"mediaIds":["gid://.../MediaImage/1"]}
//     {"action":"media","op":"reorder","productId":1,"orderedMediaIds":["gid://..."]}
//     {"action":"media","op":"updateAlt","mediaId":"gid://.../MediaImage/1","alt":"..."}
//     {"action":"order","orderId":123,"fields":{"note":"...","tags":["vip"],"customAttributes":[{"key":"delivery_note","value":"..."}]}}
//     (add "dryRun":true to validate + echo the plan without calling Shopify)
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.
//
//   POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/sync.groovy

import java.net.http.HttpClient
import commerce.Api
import commerce.ShopifyAdmin
import commerce.ShopifyWrite
import commerce.Pim
import commerce.Health
import commerce.Jcr
import com.fasterxml.jackson.databind.ObjectMapper

def mapper = new ObjectMapper()

// --- Resolve Admin API config (shared with the rest of the integration) ------
def config = null
def adminApi = null
boolean enabled = false
try {
    def cfgNode = repositorySession.getResource("/etc/commerce/config/shopify.yml")
    config = YAML.parse(cfgNode)
    adminApi = config?.adminApi ?: config
    enabled = ShopifyAdmin.adminApiEnabled(config)
} catch (Exception e) {
    log.warn("sync: could not read shopify.yml: ${e.message}")
}

if (request.getMethod() == "GET") {
    def out = [
        enabled   : enabled,
        shopDomain: adminApi?.shopDomain,
        apiVersion: adminApi?.apiVersion,
        actions   : ["inventory", "price", "publish", "metafields", "customer", "product", "media", "order"],
    ]
    response.setStatus(200)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(mapper.writeValueAsString(out))
    return
}

if (request.getMethod() != "POST") {
    response.setStatus(405)
    return
}

if (!enabled) {
    response.setStatus(409)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"Shopify Admin API is not configured (set the adminApi connection fields in shopify.yml)"}')
    return
}

// --- Parse the request -------------------------------------------------------
def req
try {
    def body = new String(request.getInputStream().readAllBytes(), "UTF-8")
    req = mapper.readValue(body, Map.class)
} catch (Exception e) {
    response.setStatus(400)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"Invalid JSON body"}')
    return
}

def action = req.action?.toString()
boolean dryRun = req.dryRun != null && req.dryRun.toString().toLowerCase() == "true"
// Some edits are audited under a distinct action name (reports key off it):
// customer edits, product base-field edits, per-op media edits, and order metadata edits.
String auditAction = action
if (action == "customer") {
    auditAction = "customer_update"
} else if (action == "product") {
    auditAction = "product_update"
} else if (action == "media") {
    auditAction = "media_" + (req.op ?: "op")
} else if (action == "order") {
    auditAction = "order_update"
} else if (action == "publish") {
    // Distinguish publish vs unpublish so the ops log can show
    // "product published" / "product unpublished". Same boolean coercion as the switch
    // handler below, so the audit label can never disagree with the write.
    boolean published = req.published != null && req.published.toString().toLowerCase() == "true"
    auditAction = published ? "product_publish" : "product_unpublish"
}

// WHO: HTTP admin endpoints run AS the logged-in operator (same identity the PIM
// / reconcile endpoints attribute writes to). WHAT-TARGET: the Shopify entity
// this action mutates, so the audit is queryable by target.
String actor = null
try { actor = repositorySession.getUserID()?.toString() } catch (Exception ignore) {}
// Ids may arrive from the client as GIDs (the wire form) — peel to the numeric
// REST form for the audit props (storage keeps numeric ids; the wire re-GIDs
// them via commerce.Api on the way out).
String entity = null
String entityId = null
switch (action) {
    case "inventory":
        entity = "inventory_item"; entityId = Api.legacyId(req.inventoryItemId); break
    case "price":
    case "publish":
    case "metafields":
    case "product":
    case "media":
        entity = "product"; entityId = Api.legacyId(req.productId); break
    case "customer":
        entity = "customer"; entityId = Api.legacyId(req.customerId); break
    case "order":
        entity = "order"; entityId = Api.legacyId(req.orderId); break
}

try {
    // dryRun: validate + echo the plan without touching Shopify.
    if (dryRun) {
        def plan = planFor(action, req)
        writeAudit(auditAction, req, "dryrun", plan, null, actor, entity, entityId)
        respond(200, [ok: true, dryRun: true, action: action, plan: plan])
        return
    }

    def endpoint = ShopifyAdmin.endpoint(adminApi)
    def token = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
    def httpClient = HttpClient.newHttpClient()

    def result = Health.timeApi(repositorySession, log, "sync:${action}") {
        switch (action) {
            case "inventory":
                return ShopifyWrite.setInventory(httpClient, endpoint, token,
                    req.inventoryItemId, req.locationId, asInt(req.quantity),
                    req.reason?.toString() ?: "correction")
            case "price":
                return ShopifyWrite.updatePrice(httpClient, endpoint, token,
                    req.productId, req.variantId, req.price?.toString())
            case "publish":
                return ShopifyWrite.setPublished(httpClient, endpoint, token,
                    req.productId, req.published != null && req.published.toString().toLowerCase() == "true")
            case "metafields":
                // Push the product's CMS-authored PIM metafields to Shopify.
                def mfs = Pim.metafieldsToPush(Pim.read(repositorySession, req.productId))
                if (!mfs) throw new IllegalArgumentException("product ${req.productId} has no PIM metafields to push")
                return ShopifyWrite.setMetafields(httpClient, endpoint, token, req.productId, mfs)
            case "customer":
                // Edit a customer's tags / note / tax exemption / marketing consent via Admin API.
                return ShopifyWrite.updateCustomer(httpClient, endpoint, token,
                    req.customerId?.toString(), (req.fields instanceof Map) ? req.fields : [:])
            case "product":
                // Edit a product's base fields (title / descriptionHtml / vendor /
                // productType / tags / handle / status) via Admin API (product 360).
                return ShopifyWrite.updateProduct(httpClient, endpoint, token,
                    req.productId?.toString(), (req.fields instanceof Map) ? req.fields : [:])
            case "media":
                // Edit a product's media (product 360): add-by-URL / delete /
                // reorder / alt-edit. reorder is ASYNC (mirror follows via job).
                switch (req.op) {
                    case "add":
                        return ShopifyWrite.addProductMedia(httpClient, endpoint, token,
                            req.productId?.toString(), req.originalSource?.toString(), req.alt?.toString())
                    case "delete":
                        return ShopifyWrite.deleteProductMedia(httpClient, endpoint, token,
                            req.productId?.toString(), req.mediaIds)
                    case "reorder":
                        return ShopifyWrite.reorderProductMedia(httpClient, endpoint, token,
                            req.productId?.toString(), req.orderedMediaIds)
                    case "updateAlt":
                        return ShopifyWrite.updateProductMediaAlt(httpClient, endpoint, token,
                            req.mediaId?.toString(), req.alt?.toString())
                    default:
                        throw new IllegalArgumentException("Unknown media op: ${req.op}")
                }
            case "order":
                // Edit an order's metadata (order 3-piece set, v1 = metadata ONLY):
                // note / tags / customAttributes via Admin API. Line-item / quantity
                // editing is DEFERRED (no Order Editing session here).
                return ShopifyWrite.updateOrder(httpClient, endpoint, token,
                    req.orderId?.toString(), (req.fields instanceof Map) ? req.fields : [:])
            default:
                throw new IllegalArgumentException("Unknown action: ${action}")
        }
    }

    writeAudit(auditAction, req, "ok", result, null, actor, entity, entityId)
    respond(200, [ok: true, action: action, result: result])
} catch (IllegalArgumentException e) {
    writeAudit(auditAction, req, "failed", null, e.message, actor, entity, entityId)
    respond(400, [ok: false, action: action, error: e.message])
} catch (Exception e) {
    log.warn("sync: ${action} failed: ${e.message}")
    writeAudit(auditAction, req, "failed", null, e.message, actor, entity, entityId)
    respond(502, [ok: false, action: action, error: e.message])
}

// --- Helpers -----------------------------------------------------------------

void respond(int status, Map body) {
    response.setStatus(status)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(body))
}

int asInt(v) {
    if (v == null) throw new IllegalArgumentException("quantity is required")
    try { return v.toString().trim() as int } catch (Exception e) { throw new IllegalArgumentException("quantity must be an integer") }
}

// The mutation target a request resolves to (for dryRun + audit), with validation.
Map planFor(String action, Map req) {
    switch (action) {
        case "inventory":
            return [action: "inventory",
                    inventoryItemId: ShopifyWrite.gid("InventoryItem", req.inventoryItemId),
                    locationId: ShopifyWrite.gid("Location", req.locationId),
                    quantity: asInt(req.quantity)]
        case "price":
            if (req.price == null) throw new IllegalArgumentException("price is required")
            return [action: "price",
                    productId: ShopifyWrite.gid("Product", req.productId),
                    variantId: ShopifyWrite.gid("ProductVariant", req.variantId),
                    price: req.price.toString()]
        case "publish":
            return [action: "publish",
                    productId: ShopifyWrite.gid("Product", req.productId),
                    status: (req.published != null && req.published.toString().toLowerCase() == "true") ? "ACTIVE" : "DRAFT"]
        case "metafields":
            def mfs = Pim.metafieldsToPush(Pim.read(repositorySession, req.productId))
            if (!mfs) throw new IllegalArgumentException("product ${req.productId} has no PIM metafields to push")
            return [action: "metafields", productId: ShopifyWrite.gid("Product", req.productId),
                    count: mfs.size(), metafields: mfs]
        case "customer":
            def fields = (req.fields instanceof Map) ? req.fields : [:]
            return [action: "customer",
                    customerId: ShopifyWrite.gid("Customer", req.customerId),
                    fields: fields.keySet().findAll { it in ["tags", "note", "taxExempt", "marketingConsent"] }]
        case "product":
            def productFields = (req.fields instanceof Map) ? req.fields : [:]
            return [action: "product",
                    productId: ShopifyWrite.gid("Product", req.productId),
                    fields: productFields.keySet().findAll { it in ["title", "descriptionHtml", "vendor", "productType", "tags", "handle", "status"] }]
        case "media":
            def mediaOp = req.op?.toString()
            def mediaPlan = [action: "media", op: mediaOp]
            switch (mediaOp) {
                case "add":
                    mediaPlan.productId = ShopifyWrite.gid("Product", req.productId)
                    mediaPlan.originalSource = req.originalSource?.toString()
                    break
                case "delete":
                    mediaPlan.productId = ShopifyWrite.gid("Product", req.productId)
                    mediaPlan.mediaIds = (req.mediaIds instanceof List) ? req.mediaIds.collect { ShopifyWrite.gid("MediaImage", it) } : []
                    break
                case "reorder":
                    mediaPlan.productId = ShopifyWrite.gid("Product", req.productId)
                    mediaPlan.orderedMediaIds = (req.orderedMediaIds instanceof List) ? req.orderedMediaIds.collect { ShopifyWrite.gid("MediaImage", it) } : []
                    break
                case "updateAlt":
                    mediaPlan.mediaId = ShopifyWrite.gid("MediaImage", req.mediaId)
                    mediaPlan.alt = req.alt?.toString()
                    break
                default:
                    throw new IllegalArgumentException("Unknown media op: ${req.op}")
            }
            return mediaPlan
        case "order":
            def orderFields = (req.fields instanceof Map) ? req.fields : [:]
            return [action: "order",
                    orderId: ShopifyWrite.gid("Order", req.orderId),
                    fields: orderFields.keySet().findAll { it in ["note", "tags", "customAttributes"] }]
        default:
            throw new IllegalArgumentException("Unknown action: ${action}")
    }
}

// Best-effort audit trail of outbound writes. Never breaks the response.
// Carries WHO (actor) + WHAT-TARGET (entity / entityId) so the record answers
// who / against what and stays queryable by operator and target.
void writeAudit(String action, Map req, String status, Object result, String error,
                String actor, String entity, String entityId) {
    commerce.SyncAudit.record(repositorySession, log, action, req, status, result, error, actor, entity, entityId)
}
