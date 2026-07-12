// Record a per-location inventory level from the Shopify inventory_levels/update
// webhook. Merges the incoming { inventory_item_id, location_id, available,
// updated_at } into the item's level file, newest update winning so an
// out-of-order redelivery cannot overwrite a fresher value:
//
//   /content/commerce/inventory/levels/{inventory_item_id}.json
//     { inventory_item_id, locations: { "<location_id>": { available, updatedAt } } }
//
// Input (script attribute, mapped from the exchange body):
//   shopifyPayload : the raw webhook JSON

import commerce.Api
import commerce.Jcr

def LEVELS_DIR = "/content/commerce/inventory/levels"
def MAX_RETRIES = 6

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }

def raw = hv("shopifyPayload")
if (!raw) {
    log.warn("recordInventoryLevel: no payload - skipping")
    return
}

def payload = JSON.parse(raw.toString())
def itemId = payload?.inventory_item_id?.toString()
def locId = payload?.location_id?.toString()
if (!itemId || !locId) {
    log.warn("recordInventoryLevel: missing inventory_item_id / location_id - skipping")
    return
}
// Coerce at the door (commerce.Api / same rule as Locations.replaceLevels):
// available is a NUMBER, updated_at collapses to the ms-precision ISO form —
// the same field must not change type/precision with the write path.
def available = Api.num(payload?.available)
def updatedAt = Api.instant(payload?.updated_at) ?: Api.now()

def path = "${LEVELS_DIR}/${itemId}.json".toString()
for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
    try {
        def doc = Jcr.readMap(repositorySession, path)
        if (doc.isEmpty()) {
            doc = [inventory_item_id: itemId, locations: [:]]
        }
        if (!(doc.locations instanceof Map)) {
            doc.locations = [:]
        }
        def existing = doc.locations[locId]
        // Skip a stale (older) update for this location.
        if (existing instanceof Map && existing.updatedAt && updatedAt && existing.updatedAt.toString() > updatedAt) {
            log.info("recordInventoryLevel: ignoring stale update for item ${itemId} @ ${locId}")
            return
        }
        doc.locations[locId] = [available: available, updatedAt: updatedAt]

        def res = Jcr.getOrCreateFile(repositorySession, path)
        res.write(Jcr.toJson(doc))
        repositorySession.commit()
        log.info("recordInventoryLevel: item ${itemId} @ ${locId} = ${available}")
        return
    } catch (Exception e) {
        try { repositorySession.rollback() } catch (Exception ignore) {}
        if (attempt == MAX_RETRIES - 1) {
            log.warn("recordInventoryLevel: gave up after ${MAX_RETRIES} attempts: ${e.message}")
        } else {
            try { Thread.sleep(20L * (attempt + 1)) } catch (Exception ignore) {}
        }
    }
}
