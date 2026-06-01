// Check inventory alert for a Shopify product stored in the repository.
//
// Reads the product JSON (delivered via Shopify webhook) from the repository,
// compares each variant's inventory_quantity against the inventory_alert_threshold
// stored in the resource property "inventory_level_config", and sets a Camel
// exchange header to indicate whether any variant is below its threshold.
//
// Requires exchange headers:
//   - productPath: repository path to the product resource (e.g. /content/commerce/products/12345)
//
// Sets exchange headers:
//   - isInventoryLow: true if any variant is below its alert threshold, false otherwise

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

// Parse inventory_level_config property (JSON with variants[].inventory_alert_threshold)
def thresholdProp = resource.hasProperty("inventory_level_config") ? resource.getProperty("inventory_level_config") : null
def thresholdByVariantID = [:]
if (thresholdProp != null) {
    def thresholdJson = JSON.parse(thresholdProp.getValue())
    thresholdJson?.variants?.each { tv ->
        if (tv.id != null && tv.inventory_alert_threshold != null) {
            thresholdByVariantID[tv.id.toString()] = tv.inventory_alert_threshold as int
        }
    }
}

// Compare inventory_quantity against threshold for each variant
def isInventoryLow = false
for (variant in variants) {
    def variantID = variant.id?.toString()
    def threshold = thresholdByVariantID[variantID]

    // Skip variants without a configured threshold
    if (threshold == null) {
        continue
    }

    def quantity = variant.inventory_quantity as int
    if (quantity < threshold) {
        log.info("Inventory alert: variant ${variantID} has quantity ${quantity} below threshold ${threshold}")
        isInventoryLow = true
        break
    }
}

context.setAttribute("isInventoryLow", isInventoryLow)
log.info("Inventory alert check for ${productPath}: isInventoryLow=${isInventoryLow}")
