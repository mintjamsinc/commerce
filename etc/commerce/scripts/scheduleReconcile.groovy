// Wall-clock scheduler for reconciliation.
//
// The CMS provides only fixed-interval timers, so this script runs on a 1-minute heartbeat
// (etc/eip/routes/commerce/reconcile-scheduler.xml) and emulates clock-time scheduling: it
// reads reconcile.yml `schedules` (a list of { at: "HH:mm", scope: diff|inventory }) and, for
// each entry whose time is due this minute and has not yet fired today, fires that pass once:
// `diff` triggers direct:commerce-reconcile; `inventory` enqueues a full audit via the Bulk
// job broker. Per-slot "fired today" state lives in JCR
// so a restart does not double-fire, and the whole tick is cluster-locked so only one node
// fires each slot.
//
// The hourly round-robin baseline was retired on 2026-06-30, so these schedules are now the
// only periodic reconciliation trigger (not additive to any baseline). Times are UTC, fixed
// (matching the general convention that server-side times are always expressed and stored in
// UTC): behavior does not depend on the server's default timezone. The Commerce app
// displays/edits these times in the operator's Preferences time zone, converting to/from UTC
// automatically.
// Best-effort: a failure is logged, never thrown.

import commerce.Jcr
import commerce.Locks

def STATE_PATH = "/content/commerce/reconciliation/schedule-state.json"

// A schedule fires when the current minute is at or just past its time (covers heartbeat
// drift / a skipped minute), but not hours later — so a node starting mid-day does not
// replay this morning's slots.
int GRACE_MINUTES = 2

def cfg
try {
    def res = repositorySession.getResource("/etc/commerce/config/reconcile.yml")
    cfg = (res != null && res.exists()) ? YAML.parse(res) : null
} catch (Exception e) {
    log.warn("scheduleReconcile: could not read reconcile.yml: ${e.message}")
    return
}
if (cfg == null || cfg.enabled?.toString()?.toLowerCase() == "false") {
    return
}
def schedules = cfg.schedules
if (!(schedules instanceof List) || schedules.isEmpty()) {
    return
}

// Only one execution evaluates + fires each tick. The reconcile run itself is also
// guarded, but locking here avoids N nodes racing on the per-slot state file.
def lock = Locks.tryLock(repositorySession, "commerce-reconcile-scheduler", 50)
if (lock == null) {
    return
}
try {
    // Evaluate in UTC — schedule times and the per-slot "fired today" day key are both UTC,
    // so firing is deterministic across nodes/deployments regardless of the JVM's default zone.
    def nowTime = java.time.LocalTime.now(java.time.ZoneOffset.UTC)
    def today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
    int nowMinutes = nowTime.getHour() * 60 + nowTime.getMinute()

    def state = Jcr.readMap(repositorySession, STATE_PATH)
    if (state == null) state = [:]
    boolean changed = false

    schedules.each { s ->
        try {
            def at = s?.at?.toString()?.trim()
            if (!at) return
            def scope = s?.scope?.toString()?.trim()?.toLowerCase()
            if (scope != "inventory") scope = "diff"

            def parts = at.split(":")
            if (parts.length != 2) return
            int schedMinutes = (parts[0].trim() as int) * 60 + (parts[1].trim() as int)
            int delta = nowMinutes - schedMinutes
            if (delta < 0 || delta > GRACE_MINUTES) return

            def slotKey = "${at}|${scope}".toString()
            if (state[slotKey]?.toString() == today) {
                return  // already fired this slot today
            }

            if (scope == "inventory") {
                // Full inventory audit via the Bulk job broker:
                // enqueue an inventory-full bulk job rather than the per-product reconcile.
                IntegrationAPI.createMessageSender()
                    .setEndpointURI("direct:commerce-shopify-bulk-enqueue")
                    .setBody("")
                    .setHeader("runAs", "commerce-service-user")
                    .setHeader("bulkJobType", "inventory-full")
                    .sendAsync()
            } else {
                IntegrationAPI.createMessageSender()
                    .setEndpointURI("direct:commerce-reconcile")
                    .setBody("")
                    .setHeader("runAs", "commerce-service-user")
                    .setHeader("scope", scope)
                    .sendAsync()
            }

            state[slotKey] = today
            changed = true
            log.info("scheduleReconcile: triggered ${scope} reconcile for slot ${at}")
        } catch (Exception e) {
            log.warn("scheduleReconcile: schedule ${s}: ${e.message}")
        }
    }

    if (changed) {
        try {
            def res = Jcr.getOrCreateFile(repositorySession, STATE_PATH)
            res.write(Jcr.toJson(state))
            repositorySession.commit()
        } catch (Exception e) {
            try { repositorySession.rollback() } catch (Exception ignore) {}
            log.warn("scheduleReconcile: could not persist schedule state: ${e.message}")
        }
    }
} finally {
    Locks.unlock(lock)
}
