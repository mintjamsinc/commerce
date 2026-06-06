// Obtain a Shopify Admin API access token (Client Credentials Grant) and expose
// it to the route as the `shopifyAccessToken` context attribute.
//
// The token handling and JCR caching live in commerce.ShopifyAdmin, shared with
// recordFulfillment.groovy. A cached token is reused while still fresh; caching
// is best-effort, so a valid token is still returned (and set) even if it could
// not be persisted.
import commerce.ShopifyAdmin

def configNode = repositorySession.getResource("/etc/commerce/config/shopify.yml")
def config = YAML.parse(configNode)

// Admin API connection settings live under the `adminApi` group. Fall back to
// the top level so a legacy flat shopify.yml keeps working.
def adminApi = config.adminApi ?: config

def accessToken = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
context.setAttribute("shopifyAccessToken", accessToken)
