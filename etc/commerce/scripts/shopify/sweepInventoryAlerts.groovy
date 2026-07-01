// Inventory-alert sweep. Runs on a short timer
// (inventory-alert-sweep.xml). For each pending inventory item it:
//   1. deletes the pending marker FIRST (delete-before-evaluate: a concurrent update
//      re-creates the marker, so the next tick re-evaluates and nothing is lost);
//   2. resolves the item to its product/variant via the reverse index;
//   3. judges the multi-location MIRROR total against the variant's effective threshold
//      with an EDGE trigger (commerce.InventoryAlert) — fire only on ok→low, never re-fire
//      while still low, re-arm on recovery;
//   4. on ok→low, starts inventory-alert-flow (Manual Inventory Check) for the product via
//      ProcessAPI, guarded against duplicates by an active-instance query (one open review
//      per product, like releaseBackorders.groovy guards the release flow).
//
// Items not yet in the reverse index (an inventory event arrived before products/* onboarded
// the product) are skipped; products/* ingestion (and reconcile) will index them.
//
// Defensive throughout: one item's failure must never stop the sweep.

import commerce.InventoryAlert
import commerce.Inventory
import commerce.InventoryRules
import commerce.Locations
import commerce.SalesVelocity

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
def policy = InventoryAlert.unconfiguredPolicy(alertCfg)
def defaultThreshold = InventoryAlert.defaultThreshold(alertCfg)

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

def rulesConfig = loadRulesConfig()
def velocity = SalesVelocity.loadPerDay(repositorySession)

int evaluated = 0
int fired = 0
for (itemId in pending) {
    try {
        // delete-before-evaluate
        InventoryAlert.clearPending(repositorySession, log, itemId)

        def decision = evaluateItem(itemId, rulesConfig, velocity, policy, defaultThreshold)
        evaluated++
        if (decision != null && decision.action == "fire" && decision.productId != null) {
            // Start inventory-alert-flow for the product, guarded against duplicates by an
            // active-instance query (like releaseBackorders.groovy). ProcessAPI is used here
            // at the top level (not a helper) to match the established invocation pattern.
            def productId = decision.productId
            def runtime = ProcessAPI.getEngine().getRuntimeService()
            long active = 0
            try {
                active = runtime.createProcessInstanceQuery()
                    .processDefinitionKey("inventory-alert-flow")
                    .processInstanceBusinessKey(productId.toString())
                    .active().count()
            } catch (Exception qe) {
                log.warn("sweepInventoryAlerts: process query failed for product ${productId}: ${qe.message}")
            }
            if (active > 0) {
                log.info("sweepInventoryAlerts: inventory-alert-flow already running for product ${productId} - not starting another")
            } else {
                ProcessAPI.createProcessStarter()
                    .setProcessDefinitionKey("inventory-alert-flow")
                    .setBusinessKey(productId.toString())
                    .setVariables([productID: productId, productPath: decision.productPath])
                    .start()
                fired++
            }
        }
    } catch (Exception e) {
        try { log.warn("sweepInventoryAlerts: item ${itemId}: ${e.message}") } catch (Exception ignore) {}
    }
}
log.info("sweepInventoryAlerts: evaluated ${evaluated} item(s), raised ${fired} alert(s)")

// --- Per-item evaluation -----------------------------------------------------

// Returns a decision map [action, productId, productPath] for a monitored item, or null
// when the item is skipped (not indexed / product gone / not monitored).
def evaluateItem(itemId, rulesConfig, velocity, String policy, Integer defaultThreshold) {
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

    def productJson = JSON.parse(resource.content.toString())

    // Resolve the EFFECTIVE threshold for this variant (manual → rule → default → none).
    def manual = Inventory.thresholdsByVariantId(resource)
    def product = [
        productType: productJson?.product_type,
        vendor     : productJson?.vendor,
        tags       : splitTags(productJson?.tags),
        variants   : [[id: variantId]],
    ]
    def resolved = InventoryRules.resolve(product, rulesConfig, manual, velocity)
    def eff = resolved[variantId]
    Integer threshold = eff?.threshold
    String thresholdSource = eff?.source
    String thresholdRule = eff?.rule

    if (threshold == null) {
        // unconfiguredPolicy: "default" monitors at defaultThreshold; "prompt"/"silent"
        // are not monitored here ("prompt" is handled by the onboarding flow's task).
        if (policy == "default" && defaultThreshold != null) {
            threshold = defaultThreshold
            thresholdSource = "policy-default"
            thresholdRule = null
        } else {
            return null
        }
    }

    int total = Locations.aggregate(repositorySession, itemId)
    def state = InventoryAlert.readState(repositorySession, itemId)
    def res = InventoryAlert.decide(state.alertState, total, threshold)
    def action = res[0]
    def newState = res[1]

    InventoryAlert.writeState(repositorySession, log, itemId, newState, total, threshold, thresholdSource, thresholdRule)

    if (action == "fire") {
        log.info("sweepInventoryAlerts: ok->low item ${itemId} total ${total} < threshold ${threshold} (${eff?.source ?: 'default-policy'}) - product ${productId}")
    } else if (action == "recover") {
        log.info("sweepInventoryAlerts: low->ok item ${itemId} total ${total} >= threshold ${threshold} - recovered, product ${productId}")
    }
    return [action: action, productId: productId, productPath: productPath]
}

// --- Helpers -----------------------------------------------------------------

def loadRulesConfig() {
    try {
        def node = repositorySession.getResource(InventoryRules.CONFIG_PATH)
        if (node != null && node.exists()) {
            return YAML.parse(node)
        }
    } catch (Exception e) {
        log.warn("sweepInventoryAlerts: could not parse inventory-rules.yml: ${e.message} - ignoring rules")
    }
    return null
}

List splitTags(value) {
    if (value == null) return []
    return value.toString().split(",").collect { it.trim() }.findAll { it }
}
