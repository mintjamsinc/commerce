import commerce.Api
import java.net.http.HttpClient
import commerce.ShopifyAdmin
import commerce.Health

// Fetch product metafields from Shopify GraphQL API
//
// Requires parameters:
//   - productID: Shopify product ID (numeric)
//   - shopifyAccessToken: Shopify Admin API access token

if (!productID) {
    throw new IllegalArgumentException("Required header 'productID' is missing")
}
if (!shopifyAccessToken) {
    throw new IllegalArgumentException("Required header 'shopifyAccessToken' is missing")
}

// Default to empty map if no metafields found
context.setAttribute("metafields", [:])

// Load shop config. Admin API connection settings live under the `adminApi`
// group; fall back to the top level so a legacy flat shopify.yml keeps working.
def configNode = repositorySession.getResource("/etc/commerce/config/shopify.yml")
def config = YAML.parse(configNode)
def adminApi = config.adminApi ?: config

// Build GraphQL query
def gqlString = """
query {
  product(id: "${Api.gid('Product', productID)}") {
    metafields(first: 100) {
      edges {
        node {
          namespace
          key
          value
          type
        }
      }
    }
  }
}
""".trim()
// Send request via the shared Admin API helper (handles status / GraphQL errors).
// Wrapped in Health.timeApi so the call's outcome + latency feed the health monitor.
def httpClient = HttpClient.newHttpClient()
def responseJson = Health.timeApi(repositorySession, log, "getMetafields") {
    ShopifyAdmin.graphql(httpClient, ShopifyAdmin.endpoint(adminApi), shopifyAccessToken, [query: gqlString])
}

def edges = responseJson.data?.product?.metafields?.edges
if (edges == null) {
    log.warn("No metafields found for product ${productID}")
    return
}

def metafields = [:]
for (edge in edges) {
    def node = edge.node
    def namespace = node.namespace
    def key = node.key
    def value = node.value
    if (value && value.startsWith("[") && value.endsWith("]")) {
        // Try to parse JSON array value
        try {
            value = JSON.parse(value)
        } catch (Exception e) {
            log.warn("Failed to parse metafield value as JSON array: ${value} - using raw string")
        }
    }

    // Set as header: {namespace}.{key}
    def headerName = "${namespace}.${key}"
    metafields[headerName] = value
}

context.setAttribute("metafields", metafields)
log.info("Fetched ${edges.size()} metafields for product ${productID}")
