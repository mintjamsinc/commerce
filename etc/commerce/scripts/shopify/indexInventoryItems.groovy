// Build/refresh the reverse index  inventory_item_id -> { product, variant }  from a
// Shopify product payload (products/create & products/update). An inventory_levels/update
// webhook carries only an inventory_item_id; this index lets the inventory-alert path
// resolve it back to its product and variant:
//
//   /content/commerce/inventory/index/{inventory_item_id}.json
//     { inventory_item_id, product_id, product_path, variant_id, variant_title, updatedAt }
//
// Defensive: a failure to index must never break product ingestion, so the script never throws.
//
// Note: a product update that DROPS a variant leaves that variant's old index entry in
// place (we only see the new payload here). Consumers re-validate via product_path /
// variant_id, so a stale entry resolves to a missing variant and is skipped.
//
// Input (script attributes, mapped from exchange headers):
//   shopify_payload : the raw product webhook JSON
//   productPath     : repository path where the product JSON is stored

import commerce.Jcr

def INDEX_DIR = "/content/commerce/inventory/index"
def MAX_RETRIES = 6

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }

def raw = hv("shopify_payload")
if (!raw) {
    log.warn("indexInventoryItems: no payload - skipping")
    return
}

def payload
try {
    payload = JSON.parse(raw.toString())
} catch (Exception e) {
    log.warn("indexInventoryItems: unparseable payload - skipping: ${e.message}")
    return
}

def productId = payload?.id?.toString()
def variants = payload?.variants
if (!productId || !(variants instanceof List)) {
    log.warn("indexInventoryItems: missing product id / variants - skipping")
    return
}

def productPath = hv("productPath")?.toString() ?: "/content/commerce/products/product_${productId}.json".toString()
def updatedAt = payload?.updated_at?.toString()

def indexed = 0
variants.each { v ->
    def itemId = v?.inventory_item_id?.toString()
    if (!itemId) {
        return
    }
    def doc = [
        inventory_item_id: itemId,
        product_id       : productId,
        product_path     : productPath,
        variant_id       : v?.id?.toString(),
        variant_title    : v?.title?.toString(),
        updatedAt        : updatedAt,
    ]
    def path = "${INDEX_DIR}/${itemId}.json".toString()
    for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
        try {
            def res = Jcr.getOrCreateFile(repositorySession, path)
            res.write(Jcr.toJson(doc))
            // Queryable identity axes (auto-indexed): lets consumers resolve
            // variant -> item or facet by product without parsing the body.
            res.setProperty("commerce:product_id", productId)
            if (doc.variant_id != null) res.setProperty("commerce:variant_id", doc.variant_id.toString())
            repositorySession.commit()
            indexed++
            break
        } catch (Exception e) {
            try { repositorySession.rollback() } catch (Exception ignore) {}
            if (attempt == MAX_RETRIES - 1) {
                log.warn("indexInventoryItems: gave up indexing item ${itemId}: ${e.message}")
            } else {
                try { Thread.sleep(20L * (attempt + 1)) } catch (Exception ignore) {}
            }
        }
    }
}
log.info("indexInventoryItems: product ${productId} - indexed ${indexed} inventory item(s)")
