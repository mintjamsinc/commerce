import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

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
def shopDomain = adminApi.shopDomain
def apiVersion = adminApi.apiVersion

// Build GraphQL query
def gqlString = """
query {
  product(id: "gid://shopify/Product/${productID}") {
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
def requestPayload = JSON.stringify([query: gqlString])

// Send request
def httpClient = HttpClient.newHttpClient()
def request = HttpRequest.newBuilder()
    .uri(URI.create("https://${shopDomain}/admin/api/${apiVersion}/graphql.json"))
    .header("Content-Type", "application/json")
    .header("X-Shopify-Access-Token", shopifyAccessToken)
    .POST(HttpRequest.BodyPublishers.ofString(requestPayload))
    .build()

def httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

if (httpResponse.statusCode() != 200) {
    throw new RuntimeException("Shopify GraphQL API error: ${httpResponse.statusCode()} - ${httpResponse.body()}")
}

// Parse response
def responseJson = JSON.parse(httpResponse.body())

// Check for GraphQL errors
def errors = responseJson.errors
if (errors != null) {
    throw new RuntimeException("Shopify GraphQL errors: ${JSON.stringify(errors)}")
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
