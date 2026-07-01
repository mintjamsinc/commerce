// Remove the reverse-index entries for a deleted product (products/delete). The delete
// webhook payload carries only the product id, but the product is soft-deleted (the JSON
// at productPath survives with commerce:status=deleted), so we read it to discover the
// variants' inventory_item_ids and drop their index files:
//
//   /content/commerce/inventory/index/{inventory_item_id}.json
//
// Defensive: never throws - a failed cleanup must not break delete processing.
//
// Input (script attribute, mapped from an exchange header):
//   productPath : repository path to the (soft-deleted) product JSON

import commerce.Jcr

def INDEX_DIR = "/content/commerce/inventory/index"

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }

def productPath = hv("productPath")?.toString()
if (!productPath) {
    log.warn("removeInventoryItemIndex: no productPath - skipping")
    return
}

def productJson = Jcr.readMap(repositorySession, productPath)
def variants = productJson?.variants
if (!(variants instanceof List)) {
    log.info("removeInventoryItemIndex: no variants at ${productPath} - nothing to clean")
    return
}

def removed = 0
variants.each { v ->
    def itemId = v?.inventory_item_id?.toString()
    if (!itemId) {
        return
    }
    def path = "${INDEX_DIR}/${itemId}.json".toString()
    try {
        def res = repositorySession.getResource(path)
        if (res != null && res.exists()) {
            res.remove()
            repositorySession.commit()
            removed++
        }
    } catch (Exception e) {
        try { repositorySession.rollback() } catch (Exception ignore) {}
        log.warn("removeInventoryItemIndex: failed to remove index for item ${itemId}: ${e.message}")
    }
}
log.info("removeInventoryItemIndex: ${productPath} - removed ${removed} index entr(y/ies)")
