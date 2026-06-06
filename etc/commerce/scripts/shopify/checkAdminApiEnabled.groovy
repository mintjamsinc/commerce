// Determine whether the Shopify Admin API integration is enabled.
//
// The Admin API is optional: when enabled, the product webhook pipeline enriches
// products with metafields fetched from the Shopify Admin API; when disabled, no
// Admin API calls are made. This script is the single source of truth for that
// decision so the Camel route can branch on a simple boolean header (mirroring
// how checkThresholdConfig.groovy gates the BPMN flow).
//
// Reads:
//   /etc/commerce/config/shopify.yml -> adminApi.enabled
//
// Sets exchange attribute:
//   - adminApiEnabled: true only when adminApi.enabled is explicitly true.
//
// The integration is treated as DISABLED unless explicitly turned on. Any other
// state (flag absent, config missing/unreadable) yields false so that product
// webhooks are still saved but no Admin API call is attempted with placeholder
// or incomplete credentials.

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
log.info("Shopify Admin API enabled: ${adminApiEnabled}")
