// Mark an inventory item as pending an alert evaluation, from the inventory_levels/update
// route (inventory-level.xml), right after the per-location level is recorded. The actual
// evaluation is done asynchronously by the inventory-alert sweep,
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

        // Kick the drain asynchronously so the materialized total (commerce:available_total) and
        // the alert evaluation run within milliseconds instead of waiting for the next timer
        // heartbeat. The sweep's task lock coalesces a burst of kicks into one drain; a
        // kick failure must never break the inventory route (the timer sweep is the backstop).
        try {
            IntegrationAPI.createMessageSender()
                .setEndpointURI("direct:commerce-inventory-alert-sweep")
                .setBody("")
                .setHeader("runAs", "commerce-service-user")
                .sendAsync()
        } catch (Exception ke) {
            try { log.warn("markInventoryAlertPending: kick failed (timer sweep will pick it up): ${ke.message}") } catch (Exception ignore) {}
        }
    }
} catch (Exception e) {
    try { log.warn("markInventoryAlertPending: ${e.message}") } catch (Exception ignore) {}
}
