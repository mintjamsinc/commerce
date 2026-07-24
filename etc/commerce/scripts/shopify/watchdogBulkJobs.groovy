// Liveness watchdog for the bulk lanes. A lost bulk_operations/finish webhook must NOT
// deadlock a domain forever, so this low-frequency timer checks:
//   • RUNNING jobs older than bulkWatchdogTimeoutMinutes — re-checks the bulk against Shopify;
//     if COMPLETED it recovers by marking the job READY (the webhook was likely lost) so the
//     CMS consumer lane ingests it domain-safely, if terminally failed it releases the job;
//   • PROCESSING jobs stuck longer than bulkProcessingTimeoutMinutes — fails them (releasing
//     their domains so blocked READY jobs of the same domain can proceed). The compare is
//     idempotent, so the next scheduled bulk re-reconciles cleanly.
// READY jobs need no watchdog handling (the CMS lane retries them every tick) but count as
// active for the "nothing active" early return so an in-flight domain is not mis-reported idle.
// Timeouts are read from reconcile.yml (defaults 90 / 180 min). Defensive: never throws.

import java.net.http.HttpClient
import commerce.BulkJobs
import commerce.ShopifyAdmin
import commerce.Locks

def lock = Locks.tryLock(repositorySession, "commerce-shopify-bulk-watchdog", 60)
if (lock == null) {
    return
}
try {
    def running = BulkJobs.running(repositorySession)
    def procJobs = BulkJobs.processing(repositorySession)
    def readyJobs = BulkJobs.ready(repositorySession)
    // READY jobs are handled by the CMS lane, but they are still active: do not early-return
    // (and mis-report the system idle) while any job is RUNNING / PROCESSING / READY.
    if (running.isEmpty() && procJobs.isEmpty() && readyJobs.isEmpty()) {
        return
    }

    def recCfg = readYaml("/etc/commerce/config/reconcile.yml")
    long TIMEOUT_MS = ((long) intOr(recCfg?.bulkWatchdogTimeoutMinutes, 90)) * 60_000L
    long PROC_TIMEOUT_MS = ((long) intOr(recCfg?.bulkProcessingTimeoutMinutes, 180)) * 60_000L
    // Absolute RUNNING ceiling: past this a bulk that Shopify still reports CREATED/RUNNING (or
    // that we cannot read) is treated as permanently stuck — cancel it + move the job to TIMED_OUT.
    long HARD_CAP_MS = ((long) intOr(recCfg?.bulkRunningHardCapMinutes, 720)) * 60_000L
    long now = System.currentTimeMillis()

    // --- Stuck PROCESSING jobs (timestamp-only; no Shopify call needed) -----------
    procJobs.each { job ->
        try {
            def ps = parseMs(job.processingStartedAt)
            if (ps > 0 && (now - ps) > PROC_TIMEOUT_MS) {
                log.warn("watchdogBulkJobs: job ${job.jobId} stuck PROCESSING > ${PROC_TIMEOUT_MS / 60000}min - failing (lane released)")
                BulkJobs.markFailed(repositorySession, log, job.jobId?.toString(), "watchdog: processing timeout")
            }
        } catch (Exception e) {
            log.warn("watchdogBulkJobs: processing job ${job.jobId}: ${e.message}")
        }
    }

    // --- RUNNING jobs need a Shopify status check (recover a lost finish webhook) --
    if (running.isEmpty()) {
        return
    }
    def shopCfg = readYaml("/etc/commerce/config/shopify.yml")
    def adminApi = shopCfg?.adminApi ?: shopCfg
    if (!ShopifyAdmin.adminApiEnabled(shopCfg)) {
        return
    }
    def endpoint = ShopifyAdmin.endpoint(adminApi)
    def token = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
    def httpClient = HttpClient.newHttpClient()

    running.each { job ->
        try {
            def started = parseMs(job.startedAt)
            if (started <= 0 || (now - started) < TIMEOUT_MS) {
                return  // still within the expected window
            }
            def gid = job.bulkOperationGid?.toString()
            if (!gid) {
                BulkJobs.markFailed(repositorySession, log, job.jobId?.toString(), "running with no gid")
                return
            }
            def bulk = ShopifyAdmin.bulkByGid(httpClient, endpoint, token, gid)
            def st = bulk?.status
            log.info("watchdogBulkJobs: job ${job.jobId} past timeout (RUNNING ${((now - started) / 60000L) as long}min) - Shopify bulk ${gid} status=${st}, errorCode=${bulk?.errorCode}, url=${bulk?.url != null}")
            if (st == "COMPLETED") {
                log.warn("watchdogBulkJobs: job ${job.jobId} bulk COMPLETED but still RUNNING (lost webhook?) - marking READY for the CMS lane")
                BulkJobs.markReady(repositorySession, log, job.jobId?.toString())
            } else if (st == "FAILED") {
                BulkJobs.markFailed(repositorySession, log, job.jobId?.toString(), "watchdog: bulk status ${st}")
            } else if (st == "CANCELED" || st == "EXPIRED") {
                // A Shopify-side cancellation / expiry is not our failure - record it as CANCELED.
                BulkJobs.markCanceled(repositorySession, log, job.jobId?.toString(), "watchdog: bulk status ${st}")
            } else {
                // CREATED / RUNNING / null: Shopify is still generating the export. A null read is a
                // TRANSIENT API blip, NOT a failure — do NOT markFailed here (that would kill a live
                // bulk). Keep waiting, UNLESS the job has blown the absolute RUNNING hard cap: then
                // the bulk is treated as permanently stuck. Cancel it best-effort (releasing the
                // Shopify producer-lane singleton) and move the job to the TIMED_OUT terminal state
                // (releasing the enqueue idempotency guard so the next schedule can re-enqueue).
                if ((now - started) > HARD_CAP_MS) {
                    log.warn("watchdogBulkJobs: job ${job.jobId} exceeded RUNNING hard cap ${(HARD_CAP_MS / 60000L) as long}min (Shopify status=${st}) - canceling bulk ${gid} and marking TIMED_OUT")
                    try {
                        ShopifyAdmin.cancelBulk(httpClient, endpoint, token, gid)
                    } catch (Exception ce) {
                        log.warn("watchdogBulkJobs: best-effort cancelBulk for ${gid} failed: ${ce.message}")
                    }
                    BulkJobs.markTimedOut(repositorySession, log, job.jobId?.toString())
                }
            }
        } catch (Exception e) {
            log.warn("watchdogBulkJobs: job ${job.jobId}: ${e.message}")
        }
    }
} catch (Exception e) {
    log.warn("watchdogBulkJobs: ${e.message}")
} finally {
    Locks.unlock(lock)
}

long parseMs(v) {
    if (v == null) return 0
    try { return java.time.Instant.parse(v.toString()).toEpochMilli() } catch (Exception e) { return 0 }
}

def readYaml(String path) {
    try {
        def res = repositorySession.getResource(path)
        if (res != null && res.exists()) return YAML.parse(res)
    } catch (Exception e) {}
    return null
}

int intOr(v, int dflt) {
    if (v == null) return dflt
    try { return v.toString().trim() as int } catch (Exception e) { return dflt }
}
