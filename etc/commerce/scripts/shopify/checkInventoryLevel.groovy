// Check inventory alert for a Shopify product stored in the repository.
//
// Reads the product JSON (delivered via Shopify webhook) from the repository and
// compares each variant's inventory_quantity against its EFFECTIVE alert
// threshold, then sets a Camel exchange header indicating whether any variant is
// below its threshold.
//
// The effective threshold is resolved by the rule engine (commerce.InventoryRules):
// a manual per-variant override wins, else the first matching rule in
// inventory-rules.yml, else the configured default, else the variant is not
// monitored. This generalises the original "manual threshold per product" model
// to dynamic thresholds (category / tag / vendor / season / velocity) while
// staying backward compatible (manual overrides still win; with no rules file,
// only manually-configured variants are monitored).
//
// Requires exchange headers:
//   - productPath: repository path to the product resource
//
// Sets exchange headers:
//   - isInventoryLow: true if any variant is below its effective threshold

import commerce.Inventory
import commerce.InventoryRules
import commerce.SalesVelocity

if (!productPath) {
    throw new IllegalArgumentException("Required header 'productPath' is missing")
}

def resource = repositorySession.getResource(productPath)
if (!resource.exists()) {
    throw new RuntimeException("Product resource not found: ${productPath}")
}

// Parse product JSON (Shopify webhook payload)
def productJson = JSON.parse(resource.content.toString())
def variants = productJson?.variants
if (!variants) {
    context.setAttribute("isInventoryLow", false)
    log.warn("No variants found in product JSON at ${productPath}")
    return
}

// Manual per-variant overrides + the rule config (a list structure, so parsed
// here with the YAML binding and handed to the pure rule engine).
def manual = Inventory.thresholdsByVariantId(resource)
def rulesConfig = loadRulesConfig()

def product = [
    productType: productJson.product_type,
    vendor     : productJson.vendor,
    tags       : splitTags(productJson.tags),
    variants   : variants.collect { [id: it.id?.toString(), quantity: toIntOrNull(it.inventory_quantity)] },
]

// Sales velocity from the cached analytics (cheap read), so velocity-based rules
// (minVelocityPerDay) can apply.
def resolved = InventoryRules.resolve(product, rulesConfig, manual, SalesVelocity.loadPerDay(repositorySession))

def isInventoryLow = false
for (variant in variants) {
    def variantID = variant.id?.toString()
    def eff = resolved[variantID]
    def threshold = eff?.threshold
    if (threshold == null) {
        continue
    }
    def quantity = toIntOrNull(variant.inventory_quantity)
    if (quantity != null && quantity < threshold) {
        log.info("Inventory alert: variant ${variantID} qty ${quantity} < threshold ${threshold} (${eff.source}${eff.rule ? ': ' + eff.rule : ''})")
        isInventoryLow = true
        break
    }
}

context.setAttribute("isInventoryLow", isInventoryLow)
log.info("Inventory alert check for ${productPath}: isInventoryLow=${isInventoryLow}")

// --- Helpers -----------------------------------------------------------------

def loadRulesConfig() {
    try {
        def node = repositorySession.getResource(InventoryRules.CONFIG_PATH)
        if (node != null && node.exists()) {
            return YAML.parse(node)
        }
    } catch (Exception e) {
        log.warn("checkInventoryLevel: could not parse inventory-rules.yml: ${e.message} - ignoring rules")
    }
    return null
}

List splitTags(value) {
    if (value == null) return []
    return value.toString().split(",").collect { it.trim() }.findAll { it }
}

Integer toIntOrNull(value) {
    if (value == null) return null
    try { return value as int } catch (Exception e) { return null }
}
