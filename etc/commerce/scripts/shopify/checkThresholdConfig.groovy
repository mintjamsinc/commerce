// Check whether a Shopify product has an EFFECTIVE threshold (= reorder point),
// so the onboarding workflow can decide whether the manual "Set Inventory
// Threshold" task is needed.
//
// With the planning layer (commerce.Planning — the rule engine was retired) a
// threshold can come from three places: an explicit per-variant planning
// value (pim.planning), the legacy per-variant manual override
// (inventory_level_config, written by the threshold form), or the configured
// global default. If any variant resolves, the product is already
// monitorable and the manual setup task is skipped.
//
// Requires exchange headers:
//   - productPath: repository path to the product resource
//
// Sets exchange headers:
//   - hasThresholdConfig: true if a usable (effective) threshold exists

import commerce.InventoryAlert
import commerce.Planning

if (!productPath) {
    throw new IllegalArgumentException("Required header 'productPath' is missing")
}

def resource = repositorySession.getResource(productPath)
if (!resource.exists()) {
    throw new RuntimeException("Product resource not found: ${productPath}")
}

def hasThresholdConfig = false
try {
    def productJson = JSON.parse(resource.content.toString())
    def variantIds = (productJson?.variants ?: []).collect { it?.id?.toString() }.findAll { it }
    def cfg = Planning.config(repositorySession)

    // unconfiguredPolicy: "prompt" (default) raises the manual Set
    // Inventory Threshold task when nothing is configured; "silent" skips it (the item is
    // simply not monitored).
    hasThresholdConfig = Planning.hasEffectiveThreshold(resource, variantIds, cfg) ||
        (loadUnconfiguredPolicy() != "prompt")
} catch (Exception e) {
    log.warn("Failed to resolve thresholds at ${productPath}: ${e.message} - treating as unconfigured")
}

context.setAttribute("hasThresholdConfig", hasThresholdConfig)
log.info("Threshold config check for ${productPath}: hasThresholdConfig=${hasThresholdConfig}")

// --- Helpers -----------------------------------------------------------------

String loadUnconfiguredPolicy() {
    try {
        def node = repositorySession.getResource(InventoryAlert.CONFIG_PATH)
        if (node != null && node.exists()) {
            return InventoryAlert.unconfiguredPolicy(YAML.parse(node) ?: [:])
        }
    } catch (Exception e) {
        log.warn("checkThresholdConfig: could not parse inventory-alert.yml: ${e.message} - assuming prompt")
    }
    return "prompt"
}
