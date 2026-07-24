// Data-retention housekeeping — prunes accumulated history stores.
//
// Invoked periodically by the commerce-housekeeping timer route (as the service
// user). Reads /etc/commerce/config/retention.yml and, for each store with a
// positive retention (in days), deletes records older than that window:
//
//   eventLog       → /content/commerce/events            (Events.prune, status-independent)
//   webhookMarkers → /content/commerce/history/webhooks
//   bulkJobs       → /content/commerce/jobs/shopify
//   reconciliation → /content/commerce/reconciliation     (run reports; state.json is kept)
//   health         → /content/commerce/health/metrics
//
// A value of 0 (or a missing key / disabled batch) means "keep forever" and the
// store is skipped. Business data (orders / payments / refunds) is NEVER pruned
// here — it is removed only by the explicit, audited manual purge.
//
// Best-effort throughout: a failure is logged, never thrown.

import commerce.Events
import commerce.SimpleYaml
import commerce.BulkJobs
import commerce.Reconciliation
import commerce.Health
import commerce.Locks
import javax.jcr.query.Query

// NOTE: this is a Groovy *script*, so a top-level typed constant would not be
// visible inside the helper methods below (they only see binding vars). The
// day-in-ms literal (86_400_000L) is therefore inlined at each use site.

// Task guard: the timer fires on every node; only the lock winner runs.
def __lock = Locks.tryLock(repositorySession, "commerce-housekeeping", 600)
if (__lock == null) {
    log.info("houseKeeping: another execution is already running this task - skipping")
    return
}
try {
    def cfg = readConfig()
    if (cfg == null) {
        return
    }
    // Master switch: false = nothing is auto-pruned.
    if (cfg.enabled?.toString()?.toLowerCase() == "false") {
        return
    }

    long now = System.currentTimeMillis()

    // A. Event log — status-independent (processed AND error), via the shared pruner.
    long eventLogDays = longOr(cfg.eventLog, 0L)
    if (eventLogDays > 0L) {
        try { Events.prune(repositorySession, log, eventLogDays * 86_400_000L, now) }
        catch (Exception e) { log.warn("houseKeeping: eventLog prune failed: ${e.message}") }
    }

    // B. Webhook idempotency markers.
    pruneByCreated("/content/commerce/history/webhooks", longOr(cfg.webhookMarkers, 0L), now, null, "webhookMarkers")

    // G. Bulk job records. Retention (90d default) far exceeds a job's lifetime, so
    //    anything this old is terminal — pruning by created date needs no status read.
    pruneByCreated(BulkJobs.JOBS_DIR, longOr(cfg.bulkJobs, 0L), now, null, "bulkJobs")

    // J. Reconciliation run reports. state.json (the live cursor, directly under the
    //    dir) must survive, so its path is excluded.
    pruneByCreated(Reconciliation.RECON_DIR, longOr(cfg.reconciliation, 0L), now, ["/state.json"] as Set, "reconciliation")

    // H. Health daily metrics (one small file per day). state.json lives OUTSIDE the
    //    metrics dir, so no exclusion is needed here.
    pruneByCreated(Health.METRICS_DIR, longOr(cfg.health, 0L), now, null, "health")
} catch (Exception e) {
    try { log.warn("houseKeeping: ${e.message}") } catch (Exception ignore) {}
} finally {
    Locks.unlock(__lock)
}


// --- Helpers -----------------------------------------------------------------

def readConfig() {
    try {
        def res = repositorySession.getResource("/etc/commerce/config/retention.yml")
        if (res == null || !res.exists()) {
            return null
        }
        return SimpleYaml.parse(res.content?.toString())
    } catch (Exception e) {
        log.warn("houseKeeping: could not read retention.yml: ${e.message}")
        return null
    }
}

// Delete every nt:file under baseDir whose creation time is older than
// retentionDays, skipping any node whose path ends with a suffix in skipSuffixes
// (e.g. "/state.json"). Committed once at the end. Defensive: a bad node is
// skipped, never fatal. No-op when days <= 0.
void pruneByCreated(String baseDir, long retentionDays, long nowMs, Set skipSuffixes, String label) {
    if (retentionDays <= 0L) {
        return
    }
    try {
        long cutoff = nowMs - retentionDays * 86_400_000L
        def base = repositorySession.getResource(baseDir)
        if (base == null || !base.exists()) {
            return
        }
        def stmt = "/jcr:root${baseDir}//element(*, nt:file)".toString()
        def q = repositorySession.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
        def rs = q.execute().getResources()
        int removed = 0
        if (rs != null) {
            rs.each { res ->
                try {
                    def path = res.getPath()
                    if (skipSuffixes != null && skipSuffixes.any { path.endsWith(it) }) return
                    long t = createdMs(res)
                    if (t > 0 && t < cutoff) { res.remove(); removed++ }
                } catch (Exception ignore) {}
            }
        }
        if (removed > 0) {
            try { repositorySession.commit(); log.info("houseKeeping: ${label} removed ${removed} record(s)") }
            catch (Exception e) { try { repositorySession.rollback() } catch (Exception ignore) {} }
        }
    } catch (Exception e) {
        log.warn("houseKeeping: ${label} prune failed: ${e.message}")
    }
}

long createdMs(res) {
    try { return res.getCreated().getTime() } catch (Exception e) { return 0L }
}

long longOr(v, long dflt) {
    if (v == null) return dflt
    try { return v.toString().trim() as long } catch (Exception e) { return dflt }
}
