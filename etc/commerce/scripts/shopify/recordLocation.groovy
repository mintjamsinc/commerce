// Record Shopify location metadata from the locations/create & locations/update
// webhooks, so per-location stock can be shown with human-readable names:
//
//   /content/commerce/inventory/locations/{location_id}.json   (raw Shopify payload)
//
// Input (script attribute, mapped from the exchange body):
//   shopifyPayload : the raw webhook JSON

import commerce.Jcr

def LOCATIONS_DIR = "/content/commerce/inventory/locations"

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }

def raw = hv("shopifyPayload")
if (!raw) {
    log.warn("recordLocation: no payload - skipping")
    return
}

def payload = JSON.parse(raw.toString())
def id = payload?.id?.toString()
if (!id) {
    log.warn("recordLocation: missing location id - skipping")
    return
}

try {
    def res = Jcr.getOrCreateFile(repositorySession, "${LOCATIONS_DIR}/${id}.json".toString())
    res.write(raw.toString())
    repositorySession.commit()
    log.info("recordLocation: stored location ${id} (${payload?.name})")
} catch (Exception e) {
    try { repositorySession.rollback() } catch (Exception ignore) {}
    log.warn("recordLocation: failed to store location ${id}: ${e.message}")
}
