// Download a completed Bulk Operation's JSONL and reconcile the inventory mirror against it,
// STREAMING line-by-line so memory stays constant (a few MB) regardless of catalog size.
//
// The bulk query (BulkQueries.INVENTORY_FULL) is rooted at inventoryItems, so the JSONL is a
// flat 2-level stream:
//   {"id":"gid://shopify/InventoryItem/111"}                                 // item (no __parentId)
//   {"location":{"id":"..."},"quantities":[...],"__parentId":".../InventoryItem/111"}  // level
// We accumulate one item's levels, then on the next item line flush+compare it against the
// mirror (① O(1) path lookup), re-mirroring only changed items and committing in batches (②).
// Defensive: a failure marks the job FAILED (releasing the lane); the compare is idempotent
// so a partial run is simply re-reconciled next cycle.

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

try {
    def shopCfg = readYaml("/etc/commerce/config/shopify.yml")
    def adminApi = shopCfg?.adminApi ?: shopCfg
    def endpoint = ShopifyAdmin.endpoint(adminApi)
    def token = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
    def httpClient = HttpClient.newHttpClient()

    def bulk = ShopifyAdmin.bulkByGid(httpClient, endpoint, token, gid)
    def url = bulk?.url
    if (bulk?.status != "COMPLETED" || !url) {
        log.warn("reconcileBulkResult: bulk ${gid} not downloadable (status=${bulk?.status})")
        BulkJobs.markFailed(repositorySession, log, jobId, "no result url (status ${bulk?.status})")
        return
    }

    // Claim the job for processing so the watchdog does not re-dispatch it mid-reconcile.
    BulkJobs.markProcessing(repositorySession, log, jobId)

    // Streaming reconcile state (one item's subtree at a time = constant memory).
    def state = [items: 0, changed: 0, sinceCommit: 0, curItemId: null, curLevels: [:]]

    def flush = {
        if (state.curItemId != null) {
            state.items++
            def current = Locations.levels(repositorySession, state.curItemId)
            if (!Locations.sameLevels(current, state.curLevels)) {
                // Stage writes without committing; batch-commit every BATCH changes (②).
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

    BulkJobs.markCompleted(repositorySession, log, jobId)
    log.info("reconcileBulkResult: job ${jobId} - ${state.items} item(s) checked, ${state.changed} re-mirrored")
} catch (Exception e) {
    try { repositorySession.rollback() } catch (Exception ignore) {}
    log.warn("reconcileBulkResult: job ${jobId}: ${e.message}")
    try { BulkJobs.markFailed(repositorySession, log, jobId, e.message) } catch (Exception ignore) {}
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
