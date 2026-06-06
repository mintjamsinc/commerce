// Storefront publish endpoint (admin). Category F (#20).
//
//   GET  — publish status: store descriptor, published product count, inventory freshness.
//   POST — rebuild the public catalog now (202; runs as the service user).
//
// The storefront itself is served from the public projection at
// /content/public/commerce/storefront/ + /content/public/commerce/catalog/; this
// endpoint is the admin control surface. Lives OUTSIDE /content/public.

import commerce.Jcr
import com.fasterxml.jackson.databind.ObjectMapper

def CATALOG = "/content/public/commerce/catalog"

try {
    if (request.getMethod() == "POST") {
        try {
            // Rebuild the catalog, then the landing pages (which resolve against it).
            // Run as the operator who triggered it so the public projection under
            // /content/public/commerce is attributed to them (requires write access
            // there); the route falls back to the service user if this is blank.
            def runAs = repositorySession.getUserID()
            ["direct:commerce-catalog-publish", "direct:commerce-pages-publish"].each { uri ->
                IntegrationAPI.createMessageSender().setEndpointURI(uri).setBody("").setHeader("runAs", runAs).sendAsync()
            }
            respond(202, [triggered: true])
        } catch (Exception e) {
            respond(500, [triggered: false, error: e.message])
        }
        return
    }

    if (request.getMethod() != "GET") {
        response.setStatus(405)
        return
    }

    def index = Jcr.readMap(repositorySession, "${CATALOG}/index.json")
    def store = Jcr.readMap(repositorySession, "${CATALOG}/store.json")
    def inventory = Jcr.readMap(repositorySession, "${CATALOG}/inventory.json")
    def pages = Jcr.readMap(repositorySession, "/content/public/commerce/pages/index.json")
    def items = (inventory.items instanceof Map) ? inventory.items.size() : 0

    respond(200, [
        generatedAt      : java.time.Instant.now().toString(),
        store            : store,
        publishedProducts: (index.meta?.count ?: (index.products instanceof List ? index.products.size() : 0)),
        catalogGeneratedAt: index.meta?.generatedAt,
        inventoryItems   : items,
        inventoryUpdatedAt: inventory.updatedAt,
        publishedPages   : (pages.meta?.count ?: (pages.pages instanceof List ? pages.pages.size() : 0)),
    ])
} catch (Exception e) {
    log.error("storefront endpoint error: ${e.message}", e)
    respond(500, [error: "Internal error"])
}

void respond(int status, Map body) {
    response.setStatus(status)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(body))
}
