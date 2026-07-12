// Inventory-alert sweep + total materializer. Drains the pending queue; runs on a short timer
// (inventory-alert-sweep.xml) and is also kicked asynchronously by inventory_levels/update
// (direct:commerce-inventory-alert-sweep) for near-immediate processing. It is the SINGLE writer
// of both the alert state and the materialized per-variant total, serialized cluster-wide by a
// cluster.tryLock lease. For each pending inventory item it:
//   1. deletes the pending marker FIRST (delete-before-evaluate: a concurrent update
//      re-creates the marker, so the next tick re-evaluates and nothing is lost);
//   2. resolves the item to its product/variant via the reverse index;
//   3. materializes the multi-location total onto the index node (commerce:available_total) for
//      every live indexed item, so screens read it 1:1 (Locations.readTotal);
//   4. judges that MIRROR total against the variant's FIXED threshold (a plain unit
//      count, resolved per variant by commerce.Planning: explicit value → global
//      default → none) with an EDGE trigger (commerce.InventoryAlert) — fire only
//      on ok→low, never re-fire while still low, re-arm on recovery;
//   5. on ok→low, starts inventory-alert-flow — ONE "stock check + reorder" task (stock <
//      fixed threshold). The operator enters the order quantity manually (no system
//      suggestion); the flow is guarded per product by an active-instance query.
//
// velocity / the 24h reorder-proposal batch are retired — this is event-driven and
// compares against a fixed threshold only.
//
// Items not yet in the reverse index (an inventory event arrived before products/* onboarded
// the product) are skipped; products/* ingestion (and reconcile) will index them.
//
// Defensive throughout: one item's failure must never stop the sweep.

import commerce.InventoryAlert
import commerce.Locations
import commerce.Planning

// Single-writer guard: the sweep is now the sole writer of both the alert state AND the
// materialized per-variant total (commerce:available_total on the index node), so exactly one
// cluster node may drain the pending queue at a time. In a standalone deployment the lease is a
// no-op. TTL is ~2x the worst-case drain; an overrun is safe (total is recompute-from-source and
// idempotent, and alert firing is guarded by an active-instance query).
def __lease = cluster.tryLock("commerce-inventory-alert-sweep", 120000)
if (__lease == null) {
    log.info("sweepInventoryAlerts: another cluster node is draining - skipping")
    return
}
try {

def pending = InventoryAlert.pendingItemIds(repositorySession)
if (pending == null || pending.isEmpty()) {
    return
}

// Config (YAML) loaded once per sweep.
def alertCfg = [:]
try {
    def cn = repositorySession.getResource(InventoryAlert.CONFIG_PATH)
    if (cn != null && cn.exists()) {
        alertCfg = YAML.parse(cn) ?: [:]
    }
} catch (Exception e) {
    log.warn("sweepInventoryAlerts: could not parse inventory-alert.yml: ${e.message}")
}

// Debounce: skip this tick if the previous sweep ran within the configured window (seconds).
// Skipped ticks leave pending markers untouched, so bursts coalesce into one evaluation.
// 0 (default) evaluates on every heartbeat — identical to the pre-debounce behaviour.
int debounceSeconds = InventoryAlert.sweepDebounceSeconds(alertCfg)
if (debounceSeconds > 0) {
    long nowMs = System.currentTimeMillis()
    long lastMs = InventoryAlert.lastSweepAtMillis(repositorySession)
    if (lastMs > 0L && (nowMs - lastMs) < (debounceSeconds * 1000L)) {
        return
    }
    InventoryAlert.recordSweepAt(repositorySession, log, nowMs)
}

def planningCfg = Planning.config(repositorySession)

int evaluated = 0
int fired = 0
for (itemId in pending) {
    try {
        // delete-before-evaluate
        InventoryAlert.clearPending(repositorySession, log, itemId)

        def decision = evaluateItem(itemId, planningCfg)
        evaluated++
        if (decision != null && decision.action == "fire" && decision.productId != null) {
            // Start inventory-alert-flow (the unified Inventory & Reorder Review) for
            // the product, guarded against duplicates by an active-instance query
            // (the same pattern used for releasing backorders). ProcessAPI is used here at the top
            // level (not a helper) to match the established invocation pattern.
            def productId = decision.productId
            // Reorder flows are PER INVENTORY ITEM (variant), keyed by inventory_item_id.
            // Deduping by PRODUCT would drop a second variant's alert while the first
            // variant's flow is open (writeState already marked it low, so ok→low never
            // re-fires) — a silent miss. Per-item keying gives each variant an independent
            // reorder→receive cycle. The transient draft / receipt handoff lives in PROCESS
            // VARIABLES (not shared product-node props), so concurrent same-product flows
            // never collide.
            def businessKey = itemId.toString()
            def runtime = ProcessAPI.getEngine().getRuntimeService()
            long active = 0
            try {
                active = runtime.createProcessInstanceQuery()
                    .processDefinitionKey("inventory-alert-flow")
                    .processInstanceBusinessKey(businessKey)
                    .active().count()
            } catch (Exception qe) {
                log.warn("sweepInventoryAlerts: process query failed for item ${itemId} (product ${productId}): ${qe.message}")
            }
            if (active > 0) {
                log.info("sweepInventoryAlerts: inventory-alert-flow already running for item ${itemId} (product ${productId}) - not starting another")
            } else {
                ProcessAPI.createProcessStarter()
                    .setProcessDefinitionKey("inventory-alert-flow")
                    .setBusinessKey(businessKey)
                    .setVariables([
                        productID       : productId,
                        productPath     : decision.productPath,
                        inventoryItemId : itemId.toString(),
                        variantId       : decision.variantId,
                        variantTitle    : decision.variantTitle,
                        availableTotal  : decision.total,
                        threshold       : decision.threshold,
                        // Gateway signal (createIncomingTransfer overwrites via its `outputs`).
                        // Initialized so the exclusive gateway ${reorderPlaced == true} always
                        // resolves, even if the service task returns early. (The reorder draft /
                        // receipt handoff itself is NOT a process variable — the forms write it to
                        // the product node's commerce:reorder_draft / _receipt maps, because a form's
                        // setProcessVariables has REPLACE semantics and would drop these vars.)
                        reorderPlaced               : false,
                    ])
                    .start()
                fired++
            }
        }
    } catch (Exception e) {
        try { log.warn("sweepInventoryAlerts: item ${itemId}: ${e.message}") } catch (Exception ignore) {}
    }
}
log.info("sweepInventoryAlerts: evaluated ${evaluated} item(s), raised ${fired} review(s)")

} finally {
    __lease.close()
}

// --- Per-item evaluation -----------------------------------------------------

// Returns a decision map [action, productId, productPath, variantId, variantTitle,
// total, threshold] for a monitored item, or null when the item is skipped
// (not indexed / product gone / not monitored).
def evaluateItem(itemId, planningCfg) {
    def idx = Locations.resolveItem(repositorySession, itemId)
    if (idx == null) {
        log.info("sweepInventoryAlerts: item ${itemId} not indexed yet - skipping (will be indexed by products/* or reconcile)")
        return null
    }
    def productPath = idx.product_path?.toString()
    def productId = idx.product_id?.toString()
    def variantId = idx.variant_id?.toString()
    if (!productPath) {
        return null
    }

    def resource = repositorySession.getResource(productPath)
    if (resource == null || !resource.exists()) {
        log.info("sweepInventoryAlerts: product ${productPath} missing - skipping item ${itemId}")
        return null
    }
    // Do not alert on a product deleted in Shopify.
    try {
        if (resource.hasProperty("commerce:status") &&
            resource.getProperty("commerce:status").getValue()?.toString() == "deleted") {
            return null
        }
    } catch (Exception ignore) {}

    // Materialize the per-variant total onto the index node (commerce:available_total) so screens
    // can read it 1:1 without re-aggregating the per-location levels. Done
    // for EVERY live indexed item, before the threshold gate, so unmonitored variants also get a
    // fresh total. Recompute-from-source keeps it idempotent under coalescing / re-ordering.
    int total = Locations.materializeTotal(repositorySession, log, itemId)

    // Resolve the planning values for this variant (explicit → default → none).
    // threshold IS the reorder point (ROP): the alert line and the reorder-proposal
    // line are the same line.
    def plan = Planning.resolve(resource, variantId, planningCfg)
    Integer threshold = Planning.value(plan, "threshold")
    String thresholdSource = plan.threshold?.source

    if (threshold == null) {
        // No ROP set: both "prompt" (onboarding raises a Set Inventory Threshold
        // task) and "silent" mean the sweep does not monitor this item.
        return null
    }

    def state = InventoryAlert.readState(repositorySession, itemId)
    def res = InventoryAlert.decide(state.alertState, total, threshold)
    def action = res[0]
    def newState = res[1]

    InventoryAlert.writeState(repositorySession, log, itemId, newState, total, threshold, thresholdSource, null)

    if (action == "fire") {
        log.info("sweepInventoryAlerts: ok->low item ${itemId} total ${total} < threshold ${threshold} (${thresholdSource}) - product ${productId}")
        return [action: action, productId: productId, productPath: productPath,
                variantId: variantId, variantTitle: idx.variant_title?.toString(),
                total: total, threshold: threshold]
    }
    if (action == "recover") {
        log.info("sweepInventoryAlerts: low->ok item ${itemId} total ${total} >= ROP ${threshold} - recovered, product ${productId}")
    }
    return [action: action, productId: productId, productPath: productPath]
}
