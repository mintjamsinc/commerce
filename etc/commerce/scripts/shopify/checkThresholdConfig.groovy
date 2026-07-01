// Check whether a Shopify product has an EFFECTIVE inventory alert threshold, so
// the workflow can decide whether the manual "Set Inventory Threshold" task is
// needed.
//
// With the rule engine (commerce.InventoryRules) a threshold can come from three
// places: a manual per-variant override, a matching rule in inventory-rules.yml,
// or the configured default. If any variant resolves to a threshold, the product
// is already monitorable and the manual setup task is skipped (the operator can
// still set explicit overrides later via the form).
//
// Backward compatible: when inventory-rules.yml is absent (no rules, no default),
// only a manual configuration counts — exactly the original behaviour, so a
// brand-new product still routes to the manual setup task.
//
// Requires exchange headers:
//   - productPath: repository path to the product resource
//
// Sets exchange headers:
//   - hasThresholdConfig: true if a usable (effective) threshold exists

import commerce.Inventory
import commerce.InventoryAlert
import commerce.InventoryRules
import commerce.SalesVelocity

if (!productPath) {
    throw new IllegalArgumentException("Required header 'productPath' is missing")
}

def resource = repositorySession.getResource(productPath)
if (!resource.exists()) {
    throw new RuntimeException("Product resource not found: ${productPath}")
}

def hasThresholdConfig = false
try {
    def manual = Inventory.thresholdsByVariantId(resource)
    def rulesConfig = loadRulesConfig()

    def productJson = JSON.parse(resource.content.toString())
    def variants = productJson?.variants ?: []
    def product = [
        productType: productJson?.product_type,
        vendor     : productJson?.vendor,
        tags       : splitTags(productJson?.tags),
        variants   : variants.collect { [id: it.id?.toString(), quantity: null] },
    ]

    def resolved = InventoryRules.resolve(product, rulesConfig, manual, SalesVelocity.loadPerDay(repositorySession))
    // unconfiguredPolicy (inventory-alert.yml): "prompt" (default) raises the manual Set
    // Inventory Threshold task when nothing is configured; "default" / "silent" skip it
    // (the sweep then applies the default threshold, or stays quiet, respectively).
    hasThresholdConfig = InventoryRules.hasEffectiveThreshold(resolved) || (loadUnconfiguredPolicy() != "prompt")
} catch (Exception e) {
    log.warn("Failed to resolve thresholds at ${productPath}: ${e.message} - treating as unconfigured")
}

context.setAttribute("hasThresholdConfig", hasThresholdConfig)
log.info("Threshold config check for ${productPath}: hasThresholdConfig=${hasThresholdConfig}")

// --- Helpers -----------------------------------------------------------------

def loadRulesConfig() {
    try {
        def node = repositorySession.getResource(InventoryRules.CONFIG_PATH)
        if (node != null && node.exists()) {
            return YAML.parse(node)
        }
    } catch (Exception e) {
        log.warn("checkThresholdConfig: could not parse inventory-rules.yml: ${e.message} - ignoring rules")
    }
    return null
}

List splitTags(value) {
    if (value == null) return []
    return value.toString().split(",").collect { it.trim() }.findAll { it }
}

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
