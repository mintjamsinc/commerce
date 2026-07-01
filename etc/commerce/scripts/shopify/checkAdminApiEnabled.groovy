// Determine whether the Shopify Admin API is CONFIGURED.
//
// The Admin API is REQUIRED by the commerce integration, but a fresh deployment may not
// have filled the credentials yet. This script reports whether the four connection fields
// are configured, so the product webhook route can skip metafield enrichment with a clear
// signal (rather than calling Shopify with empty credentials) until shopify.yml is filled.
//
// Reads:
//   /etc/commerce/config/shopify.yml -> adminApi.{shopDomain, apiVersion, clientID, clientSecret}
//
// Sets exchange attribute:
//   - adminApiEnabled: true when all four connection fields are configured.

import commerce.ShopifyAdmin

def adminApiEnabled = false
try {
    def configNode = repositorySession.getResource("/etc/commerce/config/shopify.yml")
    def config = YAML.parse(configNode)
    adminApiEnabled = ShopifyAdmin.adminApiEnabled(config)
} catch (Exception e) {
    log.warn("checkAdminApiEnabled: could not determine Admin API state from shopify.yml: ${e.message} - treating as disabled")
}

context.setAttribute("adminApiEnabled", adminApiEnabled)
log.info("Shopify Admin API configured: ${adminApiEnabled}")
