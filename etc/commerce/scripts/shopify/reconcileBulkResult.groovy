// Download a completed Bulk Operation's JSONL and reconcile the inventory mirror against it,
// STREAMING line-by-line so memory stays constant (a few MB) regardless of catalog size.
//
// The bulk query (BulkQueries.INVENTORY_FULL) is rooted at inventoryItems, so the JSONL is a
// flat 2-level stream:
//   {"id":"gid://shopify/InventoryItem/111"}                                 // item (no __parentId)
//   {"location":{"id":"..."},"quantities":[...],"__parentId":".../InventoryItem/111"}  // level
// We accumulate one item's levels, then on the next item line flush+compare it against the
// mirror (step 1: O(1) path lookup), re-mirroring only changed items and committing in batches (step 2).
// Defensive: a failure marks the job FAILED (releasing its domains); the compare is idempotent
// so a partial run is simply re-reconciled next cycle.
//
// PRECONDITION: the CMS consumer lane (runBulkCmsLane) has ALREADY marked this job PROCESSING
// before dispatching here (that is how it claims a domain-safe ingest slot). This script only
// downloads + reconciles and sets the terminal state; it must NOT re-mark PROCESSING.

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.GZIPInputStream
import commerce.BulkJobs
import commerce.Locations
import commerce.InventoryAlert
import commerce.Reconciliation
import commerce.ShopifyAdmin

final int BATCH = 300

def jobId = (binding.hasVariable("bulkJobId") ? binding.getVariable("bulkJobId")?.toString() : null)
if (!jobId) {
    log.warn("reconcileBulkResult: no bulkJobId")
    return
}
def job = BulkJobs.list(repositorySession).find { it.jobId?.toString() == jobId }
if (job == null) {
    log.warn("reconcileBulkResult: job ${jobId} not found")
    return
}
def gid = job.bulkOperationGid?.toString()
if (!gid) {
    log.warn("reconcileBulkResult: job ${jobId} has no bulk gid")
    BulkJobs.markFailed(repositorySession, log, jobId, "no bulk gid")
    return
}

// A TRANSIENT failure (a completed bulk whose result URL is momentarily null, or a network /
// stream / 5xx blip mid-download) must not cost a whole ~24h schedule cycle. Bump the persisted
// reconcile attempt counter and, while under the cap, put the job back to READY so runBulkCmsLane
// re-dispatches it on the next 30s tick — READY keeps the domain blocked in the Shopify producer
// lane, so no duplicate bulk is started. Once attempts are exhausted, fail it (terminal). The
// reconcile compare is idempotent, so a retry re-runs safely.
final int MAX_RECONCILE_ATTEMPTS = 3
def retryOrFail = { String reason ->
    try {
        int attempts = (BulkJobs.incrementReconcileAttempts(repositorySession, log, jobId) ?: 0) as int
        if (attempts < MAX_RECONCILE_ATTEMPTS) {
            log.warn("reconcileBulkResult: job ${jobId} transient (${reason}) - attempt ${attempts}/${MAX_RECONCILE_ATTEMPTS}, marking READY to retry")
            // The CMS lane already marked this job PROCESSING before dispatch, so use markReadyForRetry
            // (RUNNING|PROCESSING -> READY); a plain markReady (RUNNING-only) would be a no-op and
            // freeze the job PROCESSING until the watchdog fails it.
            BulkJobs.markReadyForRetry(repositorySession, log, jobId)
        } else {
            log.warn("reconcileBulkResult: job ${jobId} transient (${reason}) - ${attempts} attempts exhausted, failing")
            BulkJobs.markFailed(repositorySession, log, jobId, "reconcile retries exhausted: ${reason}")
        }
    } catch (Exception ex) {
        log.warn("reconcileBulkResult: retryOrFail for job ${jobId}: ${ex.message}")
    }
}

try {
    def shopCfg = readYaml("/etc/commerce/config/shopify.yml")
    def adminApi = shopCfg?.adminApi ?: shopCfg
    def endpoint = ShopifyAdmin.endpoint(adminApi)
    def token = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
    def httpClient = HttpClient.newHttpClient()

    def bulk = ShopifyAdmin.bulkByGid(httpClient, endpoint, token, gid)
    def bulkStatus = bulk?.status
    def url = bulk?.url
    if (bulkStatus == "FAILED" || bulkStatus == "CANCELED" || bulkStatus == "EXPIRED") {
        // Shopify says this bulk is TERMINALLY un-downloadable - fail the job (terminal, as before).
        log.warn("reconcileBulkResult: bulk ${gid} terminal status=${bulkStatus} - failing job ${jobId}")
        BulkJobs.markFailed(repositorySession, log, jobId, "bulk terminal status ${bulkStatus}")
        return
    }
    if (bulkStatus != "COMPLETED" || !url) {
        // COMPLETED-but-url-not-yet-populated, or a momentary status/read blip: TRANSIENT - retry.
        retryOrFail("not yet downloadable (status=${bulkStatus}, url=${url != null})")
        return
    }

    // (The CMS lane already marked this job PROCESSING before dispatch; do not re-claim here.)

    // Locations FIRST: a shop's locations are almost always created BEFORE the app is installed
    // (and rarely edited after), so the locations/* webhook never fires and the location-metadata
    // mirror stays empty — which leaves the reorder destination picker with nothing to pick and
    // per-location names showing raw ids. Refresh it from the Admin API on every full inventory pull
    // (reconcile inventory-full AND the operator's inventory-backfill both land here) so the names +
    // destinations are always populated. Best-effort: a failure here must not abort the reconcile.
    try {
        int locCount = Locations.backfillFromAdmin(repositorySession, log, httpClient, endpoint, token)
        log.info("reconcileBulkResult: job ${jobId} - refreshed ${locCount} location(s) from Admin API")
    } catch (Exception e) {
        log.warn("reconcileBulkResult: job ${jobId} - location backfill failed (continuing): ${e.message}")
    }

    // Streaming reconcile state (one item's subtree at a time = constant memory).
    def state = [items: 0, changed: 0, sinceCommit: 0, curItemId: null, curLevels: [:]]

    def flush = {
        if (state.curItemId != null) {
            state.items++
            def current = Locations.levels(repositorySession, state.curItemId)
            if (!Locations.sameLevels(current, state.curLevels)) {
                // Stage writes without committing; batch-commit every BATCH changes (step 2).
                Locations.writeLevels(repositorySession, log, state.curItemId, state.curLevels)
                InventoryAlert.writePending(repositorySession, log, state.curItemId)
                state.changed++
                if (++state.sinceCommit >= BATCH) {
                    repositorySession.commit()
                    state.sinceCommit = 0
                }
            }
        }
        state.curItemId = null
        state.curLevels = [:]
    }

    eachJsonlLine(httpClient, url) { line ->
        def o = JSON.parse(line)
        if (o["__parentId"] == null) {
            // inventory item line
            flush()
            state.curItemId = Reconciliation.numericId(o.id)
            state.curLevels = [:]
        } else if (Reconciliation.numericId(o["__parentId"]) == state.curItemId) {
            // inventory level line for the current item
            def locId = Reconciliation.numericId(o?.location?.id)
            def avail = availableFrom(o?.quantities)
            if (locId != null && avail != null) {
                state.curLevels[locId] = avail
            }
        }
    }
    flush()
    repositorySession.commit()

    // Counters ride on the terminal transition into the job doc and the inventory
    // run-history report (Reconciliation.recordBulkAudit off the broker hook).
    BulkJobs.markCompleted(repositorySession, log, jobId, [checked: state.items, updated: state.changed])
    log.info("reconcileBulkResult: job ${jobId} - ${state.items} item(s) checked, ${state.changed} re-mirrored")
} catch (Exception e) {
    try { repositorySession.rollback() } catch (Exception ignore) {}
    if (isTransient(e)) {
        // A network drop / stream reset / server 5xx mid-download is transient: retry via READY
        // rather than burning the whole schedule cycle. The staged (uncommitted) writes were just
        // rolled back and the compare is idempotent, so the retry re-reconciles cleanly.
        log.warn("reconcileBulkResult: job ${jobId} transient download error: ${e.message}")
        retryOrFail("download error: ${e.class?.simpleName}: ${e.message}")
    } else {
        log.warn("reconcileBulkResult: job ${jobId}: ${e.message}")
        try { BulkJobs.markFailed(repositorySession, log, jobId, e.message) } catch (Exception ignore) {}
    }
}

// --- Helpers -----------------------------------------------------------------

// "available" quantity from a Shopify InventoryQuantity list, or null.
def availableFrom(quantities) {
    if (!(quantities instanceof List)) return null
    def q = quantities.find { it?.name?.toString() == "available" }
    if (q?.quantity == null) return null
    try { return (q.quantity as int) } catch (Exception e) { return null }
}

// Stream a (possibly gzipped) JSONL URL line by line, never holding the whole file.
void eachJsonlLine(HttpClient httpClient, String url, Closure handle) {
    def req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build()
    def resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream())
    // A 5xx while fetching the (pre-signed) result URL is a transient server-side blip: surface it
    // as an IOException so the caller's isTransient(...) path retries instead of failing terminally.
    if (resp.statusCode() >= 500) {
        try { resp.body()?.close() } catch (Exception ignore) {}
        throw new java.io.IOException("download HTTP ${resp.statusCode()}")
    }
    def raw = resp.body()
    // Auto-detect gzip (magic 0x1f 0x8b) so a plain or gzipped JSONL both stream.
    def pin = new java.io.PushbackInputStream(raw, 2)
    byte[] head = new byte[2]
    int n = 0
    while (n < 2) {
        int r = pin.read(head, n, 2 - n)
        if (r == -1) break
        n += r
    }
    boolean gz = (n == 2 && (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b)
    if (n > 0) pin.unread(head, 0, n)
    def ins = gz ? new GZIPInputStream(pin) : pin
    new java.io.BufferedReader(new java.io.InputStreamReader(ins, "UTF-8")).withCloseable { reader ->
        String line
        while ((line = reader.readLine()) != null) {
            line = line.trim()
            if (!line.isEmpty()) handle(line)
        }
    }
}

def readYaml(String path) {
    try {
        def res = repositorySession.getResource(path)
        if (res != null && res.exists()) return YAML.parse(res)
    } catch (Exception e) {
        log.warn("reconcileBulkResult: could not read ${path}: ${e.message}")
    }
    return null
}

// A download-time failure is TRANSIENT (worth retrying) when it is an I/O / network error
// (connection or stream reset, read timeout) or a server 5xx surfaced by eachJsonlLine. A data
// error (JSON parse/mapping, from jackson) is NOT transient - it falls through to a terminal
// FAILED so a genuinely corrupt export does not loop forever.
boolean isTransient(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
        if (t.getClass().getName().startsWith("com.fasterxml.jackson")) return false
        if (t instanceof java.io.IOException) return true
    }
    return false
}
