// Mark an inventory item as pending an alert evaluation, from the inventory_levels/update
// route (inventory-level.xml), right after the per-location level is recorded. The actual
// evaluation is done asynchronously by the inventory-alert sweep (sweepInventoryAlerts.groovy),
// so bursts of updates for the same item collapse into a single evaluation.
//
// Defensive: never breaks the inventory route.
//
// Input (script attribute, mapped from an exchange header):
//   shopify_payload : the raw inventory_levels/update webhook JSON

import commerce.InventoryAlert

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }

def raw = hv("shopify_payload")
if (!raw) {
    return
}
try {
    def payload = JSON.parse(raw.toString())
    def itemId = payload?.inventory_item_id?.toString()
    if (itemId) {
        InventoryAlert.markPending(repositorySession, log, itemId)
    }
} catch (Exception e) {
    try { log.warn("markInventoryAlertPending: ${e.message}") } catch (Exception ignore) {}
}
