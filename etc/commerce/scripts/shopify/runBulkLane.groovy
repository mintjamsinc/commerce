// Shopify PRODUCER lane (timer-driven, cluster-guarded): start AT MOST ONE Shopify Bulk
// Operation at a time. Shopify allows only ONE bulk query RUNNING per app (hard constraint),
// so while any job is RUNNING this lane starts nothing. Beyond that, a new bulk is started
// only for a QUEUED job whose data DOMAINS do NOT overlap any domain that is awaiting (READY)
// or undergoing (PROCESSING) CMS ingest - a domain must never be re-fetched while its previous
// result is still being ingested. Completion is handled via READY: the CMS consumer lane
// (runBulkCmsLane) downloads+reconciles domain-safely. Defensive: never throws.

import java.net.http.HttpClient
import commerce.BulkJobs
import commerce.BulkQueries
import commerce.ShopifyAdmin
import commerce.Locks

def lock = Locks.tryLock(repositorySession, "commerce-shopify-bulk-lane", 60)
if (lock == null) {
    return
}
try {
    // Shopify singleton: only one bulk query may be RUNNING at a time.
    if (BulkJobs.hasRunning(repositorySession)) {
        return
    }
    // Domains awaiting (READY) or undergoing (PROCESSING) CMS ingest must not be re-fetched.
    def blockedDomains = BulkJobs.domainsInStatuses(repositorySession, ["READY", "PROCESSING"])
    // Oldest QUEUED job (FIFO by jobId) whose domains are disjoint from the blocked set.
    def job = BulkJobs.list(repositorySession)
                      .findAll { it.status?.toString() == "QUEUED" }
                      .sort { it.jobId?.toString() }
                      .find { !BulkJobs.overlaps(BulkJobs.domainsOf(it), blockedDomains) }
    if (job == null) {
        return  // nothing queued, or every queued job's domain is still awaiting/ingesting
    }
    def domains = BulkJobs.domainsOf(job)

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

    // forJob (not forType): resolves the STATIC query for static types and BUILDS the DYNAMIC query
    // (e.g. orders-backfill's created_at date filter from job.params) for dynamic types.
    def query = BulkQueries.forJob(job)
    def gid = ShopifyAdmin.startBulk(httpClient, endpoint, token, query)
    BulkJobs.markRunning(repositorySession, log, job.jobId?.toString(), gid)
    log.info("runBulkLane: started bulk ${gid} for job ${job.jobId} (${job.type}) locking domains=${domains}")
} catch (Exception e) {
    log.warn("runBulkLane: ${e.message}")
} finally {
    Locks.unlock(lock)
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
