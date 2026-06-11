// Sales velocity & stockout forecast batch.
//
// Invoked periodically by the commerce-velocity timer route (as the service
// user). Computes each variant's sales velocity (units/day) from the order
// history and caches it to /content/commerce/analytics/velocity.json (consumed
// cheaply by the inventory threshold rules), then predicts stockouts and alerts
// on variants running out soon (commerce.Alerts → notifications.yml channels).
//
// Best-effort: a failure is logged, never thrown.

import commerce.SalesVelocity
import commerce.Alerts
import commerce.NotificationMessage
import commerce.SimpleYaml

// Cluster guard: the timer fires on every node of a cluster, so only the
// node that wins this lease runs the task; the others skip this tick.
// Manual triggers are asynchronous fire-and-forget, so skipping while a
// run is already in flight on another node is correct for them as well.
// In a standalone deployment the lease is always granted immediately.
def __clusterLease = cluster.tryLock("commerce-velocity", 1800000)
if (__clusterLease == null) {
    log.info("computeVelocity: another cluster node is running this task - skipping")
    return
}
try {
    def STATE_PATH = "/content/commerce/analytics/forecast-state.json"

    try {
        def cfgRes = repositorySession.getResource("/etc/commerce/config/velocity.yml")
        if (cfgRes == null || !cfgRes.exists()) {
            return
        }
        def cfg = SimpleYaml.parse(cfgRes.content?.toString())
        if (cfg == null || cfg.enabled?.toString()?.toLowerCase() == "false") {
            return
        }

        int windowDays = intOr(cfg.windowDays, 30)

        // --- Compute + cache velocity --------------------------------------------
        def byVariant = SalesVelocity.computeByVariant(repositorySession, log, windowDays)
        SalesVelocity.writeCache(repositorySession, windowDays, byVariant)
        log.info("computeVelocity: cached velocity for ${byVariant.size()} variant(s) over ${windowDays}d")

        // --- Stockout forecast + alerts ------------------------------------------
        def stockout = cfg.stockout
        if (stockout == null || stockout.enabled?.toString()?.toLowerCase() == "false") {
            return
        }
        int warnDays = intOr(stockout.warnDays, 7)
        long cooldownMs = intOr(cfg.cooldownMinutes, 720) * 60_000L

        def perDay = [:]
        byVariant.each { vid, v -> if (v?.perDay != null) perDay[vid] = v.perDay }

        def atRisk = SalesVelocity.forecast(repositorySession, perDay, warnDays)
        def openKeys = [] as Set
        atRisk.each { f ->
            def vid = f.variantId
            openKeys << "stockout:${vid}".toString()
            def message = NotificationMessage.create()
                .title("📉", "Stockout forecast")
                .status("⚠", "Variant running out")
                .field("Product", f.title ?: (f.productId ? "Product ${f.productId}" : "a product"))
            if (named(f.variantTitle)) {
                message.field("Variant", f.variantTitle)
            }
            message
                .field("In stock", f.quantity)
                .field("Velocity", "${f.perDay}/day")
                .field("Days left", Math.round(f.days as double))
            Alerts.fire(repositorySession, log, STATE_PATH, "stockout:${vid}".toString(), cooldownMs, message)
        }

        // Drop cooldown state for variants no longer at risk (restocked / slowed).
        Alerts.pruneState(repositorySession, log, STATE_PATH) { key -> openKeys.contains(key?.toString()) }

        if (!atRisk.isEmpty()) {
            log.info("computeVelocity: ${atRisk.size()} variant(s) predicted to run out within ${warnDays}d")
        }
    } catch (Exception e) {
        try { log.warn("computeVelocity: ${e.message}") } catch (Exception ignore) {}
    }
} finally {
    __clusterLease.close()
}


boolean named(t) {
    return t != null && !t.toString().trim().isEmpty() && t.toString() != "Default Title"
}

int intOr(v, int dflt) {
    if (v == null) return dflt
    try { return v.toString().trim() as int } catch (Exception e) { return dflt }
}
