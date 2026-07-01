// CMS → Shopify outbound sync endpoint (admin). Category A (#2 bidirectional sync).
//
// Lets operators / tooling push corrections from the CMS back to Shopify via the
// Admin API: set a variant's stock at a location, set a variant's price, or
// publish/unpublish a product. Gated on adminApi.enabled (same switch as metafield
// enrichment and fulfillment write-back); supports dryRun for safe rollout (#28).
//
//   GET  — capability/status: whether the Admin API is enabled + the actions.
//   POST — perform an action:
//     {"action":"inventory","inventoryItemId":123,"locationId":456,"quantity":10}
//     {"action":"price","productId":1,"variantId":2,"price":"19.99"}
//     {"action":"publish","productId":1,"published":true}
//     (add "dryRun":true to validate + echo the plan without calling Shopify)
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.
//
//   POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/sync.groovy

import java.net.http.HttpClient
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
        actions   : ["inventory", "price", "publish", "metafields"],
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

try {
    // dryRun: validate + echo the plan without touching Shopify (#28 safe rollout).
    if (dryRun) {
        def plan = planFor(action, req)
        writeAudit(action, req, "dryrun", plan, null)
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
                // Push the product's CMS-authored PIM metafields (#23) to Shopify.
                def mfs = Pim.metafieldsToPush(Pim.read(repositorySession, req.productId))
                if (!mfs) throw new IllegalArgumentException("product ${req.productId} has no PIM metafields to push")
                return ShopifyWrite.setMetafields(httpClient, endpoint, token, req.productId, mfs)
            default:
                throw new IllegalArgumentException("Unknown action: ${action}")
        }
    }

    writeAudit(action, req, "ok", result, null)
    respond(200, [ok: true, action: action, result: result])
} catch (IllegalArgumentException e) {
    writeAudit(action, req, "failed", null, e.message)
    respond(400, [ok: false, action: action, error: e.message])
} catch (Exception e) {
    log.warn("sync: ${action} failed: ${e.message}")
    writeAudit(action, req, "failed", null, e.message)
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
        default:
            throw new IllegalArgumentException("Unknown action: ${action}")
    }
}

// Best-effort audit trail of outbound writes (#25). Never breaks the response.
void writeAudit(String action, Map req, String status, Object result, String error) {
    try {
        def now = java.time.Instant.now().toString()
        def ts = System.currentTimeMillis()
        def ym = new java.text.SimpleDateFormat("yyyy/MM").format(new Date())
        def path = "/content/commerce/sync/${ym}/sync_${ts}.json".toString()
        def record = [
            at    : now,
            source: "cms",
            action: action,
            request: req,
            status: status,
            result: result,
            error : error,
        ]
        def res = Jcr.getOrCreateFile(repositorySession, path)
        res.write(Jcr.toJson(record))
        res.setProperty("commerce:status", status)
        res.setProperty("commerce:action", action ?: "")
        res.setProperty("commerce:source", "cms")
        res.setProperty("commerce:created_at", now)
        repositorySession.commit()
    } catch (Exception e) {
        try { repositorySession.rollback() } catch (Exception ignore) {}
        log.warn("sync: could not write audit record: ${e.message}")
    }
}
