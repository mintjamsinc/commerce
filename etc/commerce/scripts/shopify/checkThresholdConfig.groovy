// Check whether a Shopify product already has an inventory alert threshold
// configuration stored on its repository resource.
//
// The first time a product is seen there is no threshold configured yet, so the
// inventory alert workflow cannot decide whether stock is "low". In that case
// the BPMN routes the flow to a manual task where an operator sets the
// thresholds. On subsequent updates the configuration already exists and the
// flow proceeds straight to the inventory check.
//
// A configuration is considered present only when at least one variant has a
// numeric `inventory_alert_threshold`. An empty or malformed property is treated
// as "not configured" so the operator gets a chance to fix it.
//
// Requires exchange headers:
//   - productPath: repository path to the product resource
//
// Sets exchange headers:
//   - hasThresholdConfig: true if a usable threshold configuration exists

if (!productPath) {
    throw new IllegalArgumentException("Required header 'productPath' is missing")
}

def resource = repositorySession.getResource(productPath)
if (!resource.exists()) {
    throw new RuntimeException("Product resource not found: ${productPath}")
}

def hasThresholdConfig = false

if (resource.hasProperty("inventory_level_config")) {
    try {
        def config = JSON.parse(resource.getProperty("inventory_level_config").getValue())
        def variants = config?.variants
        if (variants) {
            for (v in variants) {
                if (v?.id != null && v?.inventory_alert_threshold != null) {
                    hasThresholdConfig = true
                    break
                }
            }
        }
    } catch (Exception e) {
        log.warn("Failed to parse inventory_level_config at ${productPath}: ${e.message} - treating as unconfigured")
    }
}

context.setAttribute("hasThresholdConfig", hasThresholdConfig)
log.info("Threshold config check for ${productPath}: hasThresholdConfig=${hasThresholdConfig}")
