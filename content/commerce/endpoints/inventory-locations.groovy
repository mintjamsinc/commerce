// Per-location inventory breakdown + allocation endpoint (admin).
//
// For a product, returns each variant's per-location stock (with names). When a
// variantId + qty are given, also returns an allocation plan (which locations to
// draw from) per locations.yml. Read-only decision support.
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.
//
//   GET /bin/cms.cgi/{workspace}/content/commerce/endpoints/inventory-locations.groovy?productId=123
//   GET ...?productId=123&variantId=456&qty=10

import commerce.Api
import commerce.Locations
import commerce.Allocation
import commerce.SimpleYaml
import com.fasterxml.jackson.databind.ObjectMapper

if (request.getMethod() != "GET") {
    response.setStatus(405)
    return
}

try {
    // Ids arrive in the wire GID form (or legacy numeric) — peel to the numeric
    // storage key HERE (commerce.Api), never in the client.
    def productId = Api.legacyId(request.getParameter("productId")?.trim())
    if (!productId) {
        response.setStatus(400)
        response.setHeader("Content-Type", "application/json")
        response.getWriter().write('{"error":"productId is required"}')
        return
    }

    def node = repositorySession.getResource("/content/commerce/products/product_${productId}.json")
    if (node == null || !node.exists()) {
        response.setStatus(404)
        response.setHeader("Content-Type", "application/json")
        response.getWriter().write('{"error":"product not found"}')
        return
    }
    def product = JSON.parse(node.content.toString())

    def out = [
        id       : Api.gid("Product", productId),
        title    : product?.title,
        variants : Locations.breakdown(repositorySession, product),
    ]

    // Optional allocation plan for a specific variant + quantity.
    def variantId = Api.legacyId(request.getParameter("variantId")?.trim())
    def qtyRaw = request.getParameter("qty")?.trim()
    if (variantId && qtyRaw) {
        int qty = 0
        try { qty = qtyRaw as int } catch (Exception ignore) {}
        def variant = (product?.variants ?: []).find { it?.id?.toString() == variantId }
        if (variant != null) {
            def itemId = variant.inventory_item_id
            def levels = itemId == null ? [:] : Locations.levels(repositorySession, itemId)
            def cfg = [:]
            def cfgRes = repositorySession.getResource("/etc/commerce/config/locations.yml")
            if (cfgRes != null && cfgRes.exists()) {
                cfg = SimpleYaml.parse(cfgRes.content?.toString())
            }
            def alloc = Allocation.plan(levels, qty, cfg)
            // GID-shape the location draws for the wire (storage keys are numeric).
            alloc.allocations = (alloc.allocations ?: []).collect {
                it + [locationId: Api.gid("Location", it.locationId)]
            }
            out.allocation = alloc + [variantId: Api.gid("ProductVariant", variantId)]
        }
    }

    response.setStatus(200)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(out))
} catch (Exception e) {
    log.error("inventory-locations endpoint error: ${e.message}", e)
    response.setStatus(500)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"Internal error"}')
}
