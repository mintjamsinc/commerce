// Refresh the public storefront inventory map on a stock change (category F, #21).
//
// Invoked from the inventory_levels/update route (after the level is recorded) as
// the service user. Incrementally updates /content/public/commerce/catalog/inventory.json
// for the affected item so the storefront — which polls that file — reflects
// "low stock" / "sold out" within seconds. Best-effort; never breaks the route.
//
// Input (?inputs=shopifyPayload=@body): the raw inventory_levels/update JSON.

import commerce.Catalog
import commerce.Locations
import commerce.Jcr

def INVENTORY = "${Catalog.PUBLIC_DIR}/inventory.json"
def MAX_RETRIES = 6

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }

try {
    if (!enabled()) {
        return
    }
    def raw = hv("shopifyPayload")
    if (!raw) {
        return
    }
    def payload = JSON.parse(raw.toString())
    def itemId = payload?.inventory_item_id?.toString()
    if (!itemId) {
        return
    }
    int agg = Locations.aggregate(repositorySession, itemId)

    for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
        try {
            def doc = Jcr.readMap(repositorySession, INVENTORY)
            if (!(doc.items instanceof Map)) doc.items = [:]
            doc.items[itemId] = agg
            doc.updatedAt = java.time.Instant.now().toString()
            def res = Jcr.getOrCreateFile(repositorySession, INVENTORY)
            res.write(Jcr.toJson(doc))
            try { res.setProperty("jcr:mimeType", "application/json") } catch (Exception ignore) {}
            repositorySession.commit()
            return
        } catch (Exception e) {
            try { repositorySession.rollback() } catch (Exception ignore) {}
            if (attempt == MAX_RETRIES - 1) {
                log.warn("publishInventory: gave up after ${MAX_RETRIES} attempts: ${e.message}")
            } else {
                try { Thread.sleep(20L * (attempt + 1)) } catch (Exception ignore) {}
            }
        }
    }
} catch (Exception e) {
    try { log.warn("publishInventory: ${e.message}") } catch (Exception ignore) {}
}

boolean enabled() {
    try {
        def res = repositorySession.getResource("/etc/commerce/config/storefront.yml")
        if (res == null || !res.exists()) return false
        def cfg = YAML.parse(res)
        return !(cfg.enabled != null && cfg.enabled.toString().toLowerCase() == "false")
    } catch (Exception e) {
        return false
    }
}
