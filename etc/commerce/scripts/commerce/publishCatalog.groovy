// Publish the public storefront catalog (category F, #20).
//
// Invoked by the commerce-catalog-publish timer (as the service user) and on demand
// from the storefront endpoint. Builds a sanitized, anonymous-readable projection of
// the active catalog under /content/public/commerce/catalog/ — index (cards), one
// detail file per product, the store/checkout descriptor, and the inventory map —
// which the ichigo.js storefront reads directly. The admin product data is never
// exposed.
//
// Best-effort: a failure is logged, never thrown. Settings: storefront.yml.

import commerce.Catalog
import commerce.Pim
import commerce.Locations
import commerce.Jcr

def PRODUCTS_DIR = "/content/commerce/products"
def OUT = Catalog.PUBLIC_DIR

try {
    def cfg = readYaml("/etc/commerce/config/storefront.yml")
    if (cfg == null || cfg.enabled?.toString()?.toLowerCase() == "false") {
        return
    }
    def shop = readYaml("/etc/commerce/config/shopify.yml")
    def adminApi = shop?.adminApi ?: shop
    def lowStock = intOr(cfg.lowStock, 5)
    def currency = (cfg.currency ?: "").toString()
    def storeName = (cfg.storeName ?: "Store").toString()
    def shopDomain = adminApi?.shopDomain?.toString() ?: ""

    // 1. Store / checkout descriptor.
    writeJson("${OUT}/store.json", [
        name      : storeName,
        shopDomain: shopDomain,
        currency  : currency,
        lowStock  : lowStock,
        generatedAt: java.time.Instant.now().toString(),
    ])

    // 2. Per-product detail + cards + inventory map.
    def cards = []
    def items = [:]                 // inventory_item_id -> available (tracked only)
    def liveIds = [] as Set

    def dir = repositorySession.getResource(PRODUCTS_DIR)
    if (dir != null && dir.exists()) {
        def it = dir.list()
        while (it.hasNext()) {
            def child = it.next()
            try {
                def name = child.getName()
                if (!name.startsWith("product_") || !name.endsWith(".json")) continue
                // Only publish active, non-deleted products.
                if (prop(child, "commerce:status") == "deleted") continue
                if (prop(child, "commerce:source_status") != "active") continue

                def product = JSON.parse(child.content.toString())
                def productId = (product?.id ?: name.replace("product_", "").replace(".json", "")).toString()

                // Per-item availability (tracked only) for this product's variants.
                def availByItem = [:]
                if (product?.variants instanceof List) {
                    product.variants.each { v ->
                        def itemId = v?.inventory_item_id?.toString()
                        if (itemId && !availByItem.containsKey(itemId)) {
                            def levels = Locations.levels(repositorySession, itemId)
                            if (levels != null && !levels.isEmpty()) {
                                int agg = Locations.aggregate(repositorySession, itemId)
                                availByItem[itemId] = agg
                                items[itemId] = agg
                            }
                        }
                    }
                }

                def pim = Pim.read(repositorySession, productId)
                def detail = Catalog.detail(product, pim, availByItem)
                writeJson("${OUT}/products/${productId}.json", detail)
                cards << Catalog.card(detail)
                liveIds << productId
            } catch (Exception e) {
                log.warn("publishCatalog: product ${child?.getName()} failed: ${e.message}")
            }
        }
    }

    // Stable order: newest-ish by title for now (storefront sorts client-side too).
    cards.sort { a, b -> (a.title?.toString() ?: "") <=> (b.title?.toString() ?: "") }

    // 3. Index.
    writeJson("${OUT}/index.json", [
        meta    : [generatedAt: java.time.Instant.now().toString(), currency: currency, lowStock: lowStock, storeName: storeName, count: cards.size()],
        products: cards,
    ])

    // 4. Inventory map (realtime polling source, #21).
    writeJson("${OUT}/inventory.json", [updatedAt: java.time.Instant.now().toString(), items: items])

    // 5. Prune detail files for products no longer published.
    pruneDetails("${OUT}/products", liveIds)

    log.info("publishCatalog: published ${cards.size()} product(s), ${items.size()} tracked item(s)")
} catch (Exception e) {
    try { log.warn("publishCatalog: ${e.message}") } catch (Exception ignore) {}
}

// --- Helpers -----------------------------------------------------------------

void writeJson(String path, Object value) {
    def res = Jcr.getOrCreateFile(repositorySession, path)
    res.write(Catalog.toJson(value))
    try { res.setProperty("jcr:mimeType", "application/json") } catch (Exception ignore) {}
    repositorySession.commit()
}

void pruneDetails(String dirPath, Set liveIds) {
    try {
        def dir = repositorySession.getResource(dirPath)
        if (dir == null || !dir.exists()) return
        def victims = []
        def it = dir.list()
        while (it.hasNext()) {
            def c = it.next()
            def n = c.getName()
            if (n.endsWith(".json")) {
                def id = n.replace(".json", "")
                if (!liveIds.contains(id)) victims << c.getPath()
            }
        }
        if (!victims.isEmpty()) {
            victims.each { p -> try { def r = repositorySession.getResource(p); if (r != null && r.exists()) r.remove() } catch (Exception ignore) {} }
            repositorySession.commit()
            log.info("publishCatalog: pruned ${victims.size()} stale detail file(s)")
        }
    } catch (Exception e) {
        try { repositorySession.rollback() } catch (Exception ignore) {}
        log.warn("publishCatalog: prune failed: ${e.message}")
    }
}

def readYaml(String path) {
    try {
        def res = repositorySession.getResource(path)
        if (res != null && res.exists()) return YAML.parse(res)
    } catch (Exception e) {
        log.warn("publishCatalog: could not read ${path}: ${e.message}")
    }
    return null
}

String prop(res, String name) {
    try { if (res.hasProperty(name)) return res.getProperty(name).getValue()?.toString() } catch (Exception ignore) {}
    return null
}

int intOr(v, int dflt) { if (v == null) return dflt; try { return v.toString().trim() as int } catch (Exception e) { return dflt } }
