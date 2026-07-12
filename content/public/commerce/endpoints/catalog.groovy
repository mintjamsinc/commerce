// Public sanitized catalog read (self-EC embed toolkit).
//
//   GET ?id={id} | ?handle={handle}                — one sanitized product detail (live stock)
//   GET ?view=list[&tag=&type=&vendor=&q=&limit=]  — sanitized product cards
//
// Reads the admin product mirror (/content/commerce/products) DIRECTLY with the
// caller's session. Every session — including anonymous — has repository READ on
// /content (granted to 'everyone', inherited from the repository's access-control
// provisioning), so no privileged delegation is needed. Only /etc is denied to anonymous, and nothing here
// reads it (the checkout shop domain is a public value configured on the client via
// data-commerce-shop-domain). Returns ONLY customer-safe fields
// (commerce.Catalog.detail/card) for Shopify-active, non-deleted products — admin
// metadata, cost and internal PIM never leave here. Same-origin (no CORS).
//
// This replaces the retired catalog projection under /content/public/commerce/catalog/
// (no pre-built projection; reads are on-demand and always fresh).
import commerce.Api
import commerce.Catalog
import commerce.Pim
import commerce.Locations
import com.fasterxml.jackson.databind.ObjectMapper
import javax.jcr.query.Query

if (request.getMethod() != "GET") {
    response.setStatus(405)
    return
}

def MAPPER = new ObjectMapper()

int status = 200
def result = null
try {
    def view = param("view").toLowerCase()
    if (view == "list") {
        result = [products: listCards(param("tag"), param("type"), param("vendor"), param("q"), parseLimit(param("limit")))]
    } else {
        // The wire id form is the Shopify GID — peel to the numeric storage key
        // HERE (commerce.Api), never in the client.
        String pid = Api.legacyId(param("id")) ?: ""
        if (pid.isEmpty()) {
            def h = param("handle")
            if (!h.isEmpty()) pid = resolveHandle(h) ?: ""
        }
        if (pid.isEmpty()) {
            status = 400; result = [error: "id or handle is required"]
        } else {
            def detail = readDetail(pid)
            if (detail == null) { status = 404; result = [error: "Product not found"] }
            else { result = detail }
        }
    }
} catch (Exception e) {
    status = 500; result = [error: "Internal error"]
    try { log.warn("catalog: ${e.message}") } catch (Exception ignore) {}
}

response.setStatus(status)
response.setHeader("Content-Type", "application/json; charset=UTF-8")
// Public, sanitized data — safe to cache briefly at the edge / browser.
response.setHeader("Cache-Control", "public, max-age=30")
response.getWriter().write(MAPPER.writeValueAsString(result))


// --- helpers (repositorySession = the caller's session; has READ on /content) ---

String param(String name) {
    def v = request.getParameter(name)
    return v == null ? "" : v.toString().trim()
}

// Full sanitized detail for one active, non-deleted product id, or null.
Map readDetail(String productId) {
    def res = repositorySession.getResource("/content/commerce/products/product_${productId}.json")
    if (res == null || !res.exists()) return null
    if (propOf(res, "commerce:status") == "deleted") return null
    if (propOf(res, "commerce:source_status") != "active") return null
    def product = JSON.parse(res.content.toString())
    def pim = Pim.read(repositorySession, productId)
    return Catalog.detail(product, pim, availabilityFor(product))
}

// Resolve a handle to a product id via the auto-indexed commerce:handle property.
String resolveHandle(String h) {
    if (h == null || h.trim().isEmpty()) return null
    def esc = h.trim().replace("'", "''")
    def stmt = "/jcr:root/content/commerce/products//element(*, nt:file)[@commerce:handle='${esc}' and @commerce:source_status='active']"
    def query = repositorySession.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
    query.setLimit(1L)
    def nodes = query.execute().getNodes()
    if (!nodes.hasNext()) return null
    def name = nodes.nextNode().getName()   // product_{id}.json
    return name.replace("product_", "").replace(".json", "")
}

// Cards for active, non-deleted products (bounded), filtered. Cards omit live per-variant
// availability for speed — a single-product detail fetch carries it.
List listCards(String tagF, String typeF, String vendorF, String qF, int max) {
    def cards = []
    def t = blankLower(tagF); def ty = blankLower(typeF); def ve = blankLower(vendorF); def qq = blankLower(qF)
    def stmt = "/jcr:root/content/commerce/products//element(*, nt:file)[@commerce:source_status='active']"
    def query = repositorySession.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
    def nodes = query.execute().getNodes()
    while (nodes.hasNext() && cards.size() < max) {
        try {
            def node = nodes.nextNode()
            def res = repositorySession.getResource(node.getPath())
            if (res == null || !res.exists()) continue
            if (propOf(res, "commerce:status") == "deleted") continue
            def product = JSON.parse(res.content.toString())
            def card = Catalog.card(Catalog.detail(product, [:], [:]))
            if (cardMatches(card, t, ty, ve, qq)) cards << card
        } catch (Exception ignore) {}
    }
    return cards
}

boolean cardMatches(Map card, String t, String ty, String ve, String qq) {
    if (t && !((card.tags ?: []).any { it?.toString()?.toLowerCase() == t })) return false
    if (ty && card.productType?.toString()?.toLowerCase() != ty) return false
    if (ve && card.vendor?.toString()?.toLowerCase() != ve) return false
    if (qq && !(card.title?.toString()?.toLowerCase()?.contains(qq))) return false
    return true
}

Map availabilityFor(product) {
    def availByItem = [:]
    if (product?.variants instanceof List) {
        product.variants.each { v ->
            def itemId = v?.inventory_item_id?.toString()
            if (itemId && !availByItem.containsKey(itemId)) {
                def levels = Locations.levels(repositorySession, itemId)
                if (levels != null && !levels.isEmpty()) {
                    availByItem[itemId] = Locations.aggregate(repositorySession, itemId)
                }
            }
        }
    }
    return availByItem
}

String propOf(res, String name) {
    try { if (res.hasProperty(name)) return res.getProperty(name).getValue()?.toString() } catch (Exception ignore) {}
    return null
}

String blankLower(x) { def s = (x == null ? "" : x.toString().trim().toLowerCase()); return s.isEmpty() ? null : s }
int parseLimit(String x) {
    try {
        int n = (x == null || x.trim().isEmpty()) ? 50 : Integer.parseInt(x.trim())
        if (n <= 0) n = 50
        if (n > 200) n = 200
        return n
    } catch (Exception e) { return 50 }
}
