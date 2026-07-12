// Product media (live read) endpoint (admin). Product 360.
//
// The REST/webhook product mirror carries image {src, alt} but NOT the MediaImage
// gids the editor needs to delete / reorder / alt-edit media. So the editable Media
// section reads media LIVE from the Admin GraphQL API to get those ids, while the
// Overview thumbnail keeps using the mirror (Pim.view.images). This endpoint is the
// live read; the writes go through the sync endpoint ({"action":"media",...}).
//
//   GET ?productId=123 — live media list for a product:
//     { productId, enabled:true, media:[{ id, alt, status, url, width, height }] }
//   When the Admin API is not configured: 200 { enabled:false, media:[] } (the editor
//   degrades to the mirror thumbnail only).
//
// Gated on adminApi.enabled (same switch as the sync endpoint). Read-only: no writes,
// no mutations. Best-effort at the endpoint layer — a live fetch error yields 502.
//
// Media query shape (product.media nodes with the MediaImage inline fragment) is
// stable at the configured 2026-01 Admin API; verify with a live smoke test.
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.
//
//   GET /bin/cms.cgi/{workspace}/content/commerce/endpoints/product-media.groovy?productId=123

import java.net.http.HttpClient
import commerce.Api
import commerce.ShopifyAdmin
import commerce.ShopifyWrite
import com.fasterxml.jackson.databind.ObjectMapper

def mapper = new ObjectMapper()

if (request.getMethod() != "GET") {
    response.setStatus(405)
    return
}

// The wire id form is the Shopify GID — peel to the numeric form HERE
// (commerce.Api), never in the client.
def productId = Api.legacyId(request.getParameter("productId"))
if (productId == null || productId.trim().isEmpty()) {
    respond(400, [error: "productId is required"])
    return
}

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
    log.warn("product-media: could not read shopify.yml: ${e.message}")
}

// Not configured: degrade cleanly — the editor falls back to the mirror thumbnail.
if (!enabled) {
    respond(200, [id: Api.gid("Product", productId), enabled: false, media: []])
    return
}

try {
    def endpoint = ShopifyAdmin.endpoint(adminApi)
    def token = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
    def httpClient = HttpClient.newHttpClient()

    def query = '''
query productMedia($id: ID!) {
  product(id: $id) {
    media(first: 100) {
      nodes {
        id
        alt
        status
        mediaContentType
        ... on MediaImage { image { url width height } }
      }
    }
  }
}
'''.trim()
    def resp = ShopifyAdmin.graphql(httpClient, endpoint, token,
        [query: query, variables: [id: ShopifyWrite.gid("Product", productId)]])

    def nodes = resp?.data?.product?.media?.nodes ?: []
    // Image management — keep only MediaImage nodes (drop video / 3D / external
    // video, which resolve no image{} and would otherwise render as phantom tiles).
    def media = nodes.findAll { it?.mediaContentType == "IMAGE" }.collect { n ->
        [
            id    : n?.id,
            alt   : n?.alt,
            status: n?.status,
            url   : n?.image?.url,
            width : n?.image?.width,
            height: n?.image?.height,
        ]
    }
    respond(200, [id: Api.gid("Product", productId), enabled: true, media: media])
} catch (Exception e) {
    log.warn("product-media: live fetch failed for ${productId}: ${e.message}")
    respond(502, [error: e.message])
}

// --- Helpers -----------------------------------------------------------------

void respond(int status, Map body) {
    response.setStatus(status)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(body))
}
