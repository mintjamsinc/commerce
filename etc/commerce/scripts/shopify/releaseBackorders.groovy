// Release covered backorders when stock arrives.
//
// Invoked from the inventory_levels/update Camel route (inventory-level.xml) as the
// service user, right after the new per-location level is recorded. It looks at the
// open backorders waiting on the affected inventory item and, while the aggregate
// available stock covers them, raises each as a "Release Backorder" task for an
// operator (the backorder-release-flow workflow).
//
// Allocation is FIFO: the oldest backorder is served first, and we stop at the
// first one the arriving stock cannot cover, so an older, larger backorder is never
// jumped by a newer, smaller one.
//
// Input (mapped from the exchange body via ?inputs=shopifyPayload=@body):
//   - shopifyPayload : the raw inventory_levels/update webhook JSON
//
// Fully DEFENSIVE: a failure must never break the inventory route.

import commerce.Backorders
import commerce.Locations

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

    def open = Backorders.findOpenForItem(repositorySession, itemId)
    if (open.isEmpty()) {
        return
    }

    // Current aggregate available across all locations for this item.
    int available = Locations.aggregate(repositorySession, itemId)
    if (available <= 0) {
        return
    }

    def engine = ProcessAPI.getEngine()
    def runtime = engine.getRuntimeService()
    int released = 0

    for (rec in open) {
        int need = (rec.quantity ?: 0) as int
        if (need <= 0) {
            continue
        }
        if (available < need) {
            // FIFO: do not skip ahead to a smaller, newer backorder.
            break
        }

        def businessKey = "backorder:${rec.id}".toString()
        long active = 0
        try {
            active = runtime.createProcessInstanceQuery()
                .processDefinitionKey("backorder-release-flow")
                .processInstanceBusinessKey(businessKey)
                .active().count()
        } catch (Exception e) {
            log.warn("releaseBackorders: process query failed for ${businessKey}: ${e.message}")
        }
        if (active > 0) {
            // A release workflow is already running for this backorder.
            available -= need
            continue
        }

        try {
            ProcessAPI.createProcessStarter()
                .setProcessDefinitionKey("backorder-release-flow")
                .setBusinessKey(businessKey)
                .setVariables([
                    backorderPath: rec.path,
                    backorder_id : rec.id,
                    order_id     : rec.order_id,
                ])
                .start()
            available -= need
            released++
        } catch (Exception e) {
            log.warn("releaseBackorders: could not start release flow for ${rec.path}: ${e.message}")
        }
    }

    if (released > 0) {
        log.info("releaseBackorders: raised ${released} release task(s) for item ${itemId}")
    }
} catch (Exception e) {
    try { log.warn("releaseBackorders: ${e.message}") } catch (Exception ignore) {}
}

// --- Helpers -----------------------------------------------------------------

boolean enabled() {
    try {
        def res = repositorySession.getResource("/etc/commerce/config/backorder.yml")
        if (res == null || !res.exists()) {
            return false
        }
        def cfg = YAML.parse(res)
        return !(cfg.enabled != null && cfg.enabled.toString().toLowerCase() == "false")
    } catch (Exception e) {
        return false
    }
}
