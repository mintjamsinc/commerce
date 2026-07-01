// Bulk lane worker (timer-driven, cluster-guarded): start AT MOST ONE Shopify bulk operation
// at a time. While a job is RUNNING the lane is busy; it is freed only when the result route
// (or the watchdog) reaches a terminal state. Defensive: never throws.

import java.net.http.HttpClient
import commerce.BulkJobs
import commerce.BulkQueries
import commerce.ShopifyAdmin

def lease = cluster.tryLock("commerce-shopify-bulk-lane", 60_000)
if (lease == null) {
    return
}
try {
    if (BulkJobs.laneBusy(repositorySession)) {
        return  // a job is RUNNING (awaiting Shopify) or PROCESSING (reconciling)
    }
    def job = BulkJobs.nextQueued(repositorySession)
    if (job == null) {
        return
    }

    def shopCfg = readYaml("/etc/commerce/config/shopify.yml")
    def adminApi = shopCfg?.adminApi ?: shopCfg
    if (!ShopifyAdmin.adminApiEnabled(shopCfg)) {
        log.info("runBulkLane: Admin API not configured - leaving job ${job.jobId} queued")
        return
    }
    def endpoint = ShopifyAdmin.endpoint(adminApi)
    def token = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
    def httpClient = HttpClient.newHttpClient()

    // Belt and suspenders against the singleton constraint: never start a second bulk.
    if (ShopifyAdmin.currentBulkRunning(httpClient, endpoint, token)) {
        log.info("runBulkLane: a Shopify bulk operation is already running - waiting")
        return
    }

    def query = BulkQueries.forType(job.type?.toString())
    def gid = ShopifyAdmin.startBulk(httpClient, endpoint, token, query)
    BulkJobs.markRunning(repositorySession, log, job.jobId?.toString(), gid)
    log.info("runBulkLane: started bulk ${gid} for job ${job.jobId} (${job.type})")
} catch (Exception e) {
    log.warn("runBulkLane: ${e.message}")
} finally {
    lease.close()
}

def readYaml(String path) {
    try {
        def res = repositorySession.getResource(path)
        if (res != null && res.exists()) return YAML.parse(res)
    } catch (Exception e) {
        log.warn("runBulkLane: could not read ${path}: ${e.message}")
    }
    return null
}
