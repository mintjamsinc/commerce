// Liveness watchdog for the bulk lane. A lost bulk_operations/finish webhook must NOT
// deadlock the single lane forever, so this low-frequency timer checks:
//   • RUNNING jobs older than bulkWatchdogTimeoutMinutes — re-checks the bulk against Shopify;
//     if COMPLETED it recovers by dispatching the result route (the webhook was likely lost),
//     if terminally failed it releases the lane;
//   • PROCESSING jobs stuck longer than bulkProcessingTimeoutMinutes — fails them (lane
//     released). The compare is idempotent, so the next scheduled bulk re-reconciles cleanly.
// Timeouts are read from reconcile.yml (defaults 90 / 180 min). Defensive: never throws.

import java.net.http.HttpClient
import commerce.BulkJobs
import commerce.ShopifyAdmin

def lease = cluster.tryLock("commerce-shopify-bulk-watchdog", 60_000)
if (lease == null) {
    return
}
try {
    def running = BulkJobs.running(repositorySession)
    def procJobs = BulkJobs.processing(repositorySession)
    if (running.isEmpty() && procJobs.isEmpty()) {
        return
    }

    def recCfg = readYaml("/etc/commerce/config/reconcile.yml")
    long TIMEOUT_MS = ((long) intOr(recCfg?.bulkWatchdogTimeoutMinutes, 90)) * 60_000L
    long PROC_TIMEOUT_MS = ((long) intOr(recCfg?.bulkProcessingTimeoutMinutes, 180)) * 60_000L
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
                log.warn("watchdogBulkJobs: job ${job.jobId} bulk COMPLETED but still RUNNING (lost webhook?) - recovering")
                IntegrationAPI.createMessageSender()
                    .setEndpointURI("direct:commerce-shopify-bulk-result")
                    .setBody("")
                    .setHeader("runAs", "commerce-service-user")
                    .setHeader("bulkJobId", job.jobId?.toString())
                    .sendAsync()
            } else if (st == "FAILED" || st == "CANCELED" || st == "EXPIRED" || st == null) {
                BulkJobs.markFailed(repositorySession, log, job.jobId?.toString(), "watchdog: bulk status ${st}")
            }
            // else CREATED / RUNNING: Shopify is still generating - keep waiting.
        } catch (Exception e) {
            log.warn("watchdogBulkJobs: job ${job.jobId}: ${e.message}")
        }
    }
} catch (Exception e) {
    log.warn("watchdogBulkJobs: ${e.message}")
} finally {
    lease.close()
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
