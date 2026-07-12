// Download a completed products-backfill Bulk Operation's JSONL and IMPORT it into the product
// mirror, STREAMING line-by-line so memory stays constant (a few MB) regardless of how many
// historical products the range covers. This is the PRODUCT counterpart of the
// order and customer bulk-result importers; it shares the same broker scaffolding.
//
// The bulk query (BulkQueries.PRODUCTS_BACKFILL_TEMPLATE) is rooted at products with TWO nested
// connections — variants and media — so the JSONL is a 3-level stream in GRAPHQL shape, with BOTH
// child kinds carrying __parentId = the product gid:
//   {"id":"gid://shopify/Product/111","legacyResourceId":"111", ... ,"options":[ ... ]}          // product (no __parentId)
//   {"id":"gid://shopify/ProductVariant/222", ... ,"__parentId":".../Product/111"}                // variant child
//   {"id":"gid://shopify/MediaImage/333","image":{ ... },"__parentId":".../Product/111"}          // image child
// We accumulate one product's variant AND image children — telling the two apart by the gid TYPE
// in each child's id (ProductVariant → a variant, MediaImage → an image; any other media
// (video/3d) has no image block and is ignored) — then on the next product line flush it: NORMALIZE
// the GraphQL product node (+ its variants + images) to the REST (webhook) body shape every mirror
// consumer expects (step 1: camelCase/connections/UPPERCASE enums → snake_case/lists/lowercase) and UPSERT
// the product file, committing in batches (step 2).
//
// BACKFILL SEMANTICS (operator-sovereignty; historical data is NOT "new work"):
//   - Mirror-only: this NEVER starts product-update-flow / any BPMN, and NEVER calls the Admin API.
//   - Idempotent + lifecycle-preserving: the product store is FLAT and keyed by id
//     (/content/commerce/products/product_{id}.json — same path product-update.xml / Pim build); an
//     already-mirrored product is located first (Jcr.safeGet on the deterministic path) and
//     overwritten in place; commerce:status is set to "received" ONLY for a genuinely NEW node — an
//     existing node's integration status is left untouched (it may be "deleted" from products/delete
//     or an advanced lifecycle, and a backfill must not resurrect/reset it). Re-running is therefore
//     safe (re-writes the same body/props). Corollary: unlike product-update.xml this does NOT clear
//     commerce:errorMessage/stackTrace, so a product previously left commerce:status="error" re-
//     mirrors its body/props but keeps showing "error" (with the stale message) in Pim.browse until a
//     later products/update webhook self-heals it — an accepted stale-error window (status-preserving).
//   - No GDPR guard: products are not PII (a plus vs the orders/customers backfills).
//
// LIMITATION — METAFIELDS ARE OUT OF SCOPE (v1 backfill): the products/update webhook route
// (product-update.xml) enriches each product with a per-product Admin API metafields fetch; this
// mirror-only backfill deliberately does NOT (no Admin API call per product). Backfilled products
// carry base + variants + images but no metafields mirror until a products/update webhook (or a
// reconcile pass) fills them. The PIM overlay (the `pim` property) is CMS-authored and untouched
// here either way.
//
// Defensive: a MISSING/null GraphQL field maps to null/omit and NEVER throws mid-stream; only an
// I/O error is transient-retryable (retry via READY), while a data/parse error fails the job
// terminally (isTransient decides) — a genuinely corrupt export must not loop forever.
//
// PRECONDITION: the CMS consumer lane (runBulkCmsLane) has ALREADY marked this job PROCESSING
// before dispatching here (that is how it claims a domain-safe ingest slot). This script only
// downloads + imports and sets the terminal state; it must NOT re-mark PROCESSING.

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.GZIPInputStream
import commerce.BulkJobs
import commerce.Jcr
import commerce.Pim
import commerce.ProductMimeTypeMigration
import commerce.Reconciliation
import commerce.ShopifyAdmin

final int BATCH = 200

def jobId = (binding.hasVariable("bulkJobId") ? binding.getVariable("bulkJobId")?.toString() : null)
if (!jobId) {
    log.warn("importProductBulkResult: no bulkJobId")
    return
}
def job = BulkJobs.list(repositorySession).find { it.jobId?.toString() == jobId }
if (job == null) {
    log.warn("importProductBulkResult: job ${jobId} not found")
    return
}
def gid = job.bulkOperationGid?.toString()
if (!gid) {
    log.warn("importProductBulkResult: job ${jobId} has no bulk gid")
    BulkJobs.markFailed(repositorySession, log, jobId, "no bulk gid")
    return
}

// A TRANSIENT failure (a completed bulk whose result URL is momentarily null, or a network /
// stream / 5xx blip mid-download) must not cost a whole schedule cycle. Bump the persisted
// reconcile attempt counter and, while under the cap, put the job back to READY so runBulkCmsLane
// re-dispatches it on the next tick — READY keeps the domain blocked in the Shopify producer lane,
// so no duplicate bulk is started. Once attempts are exhausted, fail it (terminal). The import is
// idempotent (locate-then-overwrite, lifecycle-preserving), so a retry re-runs safely.
final int MAX_RECONCILE_ATTEMPTS = 3
def retryOrFail = { String reason ->
    try {
        int attempts = (BulkJobs.incrementReconcileAttempts(repositorySession, log, jobId) ?: 0) as int
        if (attempts < MAX_RECONCILE_ATTEMPTS) {
            log.warn("importProductBulkResult: job ${jobId} transient (${reason}) - attempt ${attempts}/${MAX_RECONCILE_ATTEMPTS}, marking READY to retry")
            // The job is PROCESSING at this point (the CMS lane claimed it before dispatch), so use
            // markReadyForRetry (RUNNING|PROCESSING -> READY); a plain markReady (RUNNING-only) would
            // be a no-op and freeze the job PROCESSING until the watchdog fails it.
            BulkJobs.markReadyForRetry(repositorySession, log, jobId)
        } else {
            log.warn("importProductBulkResult: job ${jobId} transient (${reason}) - ${attempts} attempts exhausted, failing")
            BulkJobs.markFailed(repositorySession, log, jobId, "import retries exhausted: ${reason}")
        }
    } catch (Exception ex) {
        log.warn("importProductBulkResult: retryOrFail for job ${jobId}: ${ex.message}")
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
        // Shopify says this bulk is TERMINALLY un-downloadable - fail the job (terminal).
        log.warn("importProductBulkResult: bulk ${gid} terminal status=${bulkStatus} - failing job ${jobId}")
        BulkJobs.markFailed(repositorySession, log, jobId, "bulk terminal status ${bulkStatus}")
        return
    }
    if (bulkStatus != "COMPLETED" || !url) {
        // COMPLETED-but-url-not-yet-populated, or a momentary status/read blip: TRANSIENT - retry.
        retryOrFail("not yet downloadable (status=${bulkStatus}, url=${url != null})")
        return
    }

    // (The CMS lane already marked this job PROCESSING before dispatch; do not re-claim here.)

    // Streaming import state (one product's subtree at a time = constant memory).
    def state = [products: 0, created: 0, skipped: 0, sinceCommit: 0,
                 curProduct: null, curProductGid: null, curVariants: [], curImages: []]

    def flush = {
        def node = state.curProduct
        if (node != null) {
            // One malformed / unwritable product must not sink a catalog-wide historical import: a
            // per-product failure (a JCR write / lookup glitch, or a surprise in one node) is logged
            // and SKIPPED so the rest of the range still imports. Stream-level IO errors are raised
            // by eachJsonlLine (outside this closure) and still reach the transient-retry path.
            try {
                def body = normalizeProduct(node, state.curVariants, state.curImages)
                def pid = body.id?.toString()
                if (pid != null && !pid.trim().isEmpty()) {
                    // FLAT store keyed by id: the path is deterministic regardless of the run, so
                    // locate the existing node first and overwrite in place (idempotent), preserving
                    // its integration lifecycle. Only a genuinely NEW node gets commerce:status="received".
                    def path = "${Pim.PRODUCTS_DIR}/product_${pid}.json".toString()
                    def existing = Jcr.safeGet(repositorySession, path)
                    boolean isNew = (existing == null || !existing.exists())

                    def res = isNew ? Jcr.getOrCreateFile(repositorySession, path) : existing
                    res.write(Jcr.toJson(body))
                    res.setProperty("jcr:mimeType", ProductMimeTypeMigration.PRODUCT_MIME)

                    // Typed, auto-indexed props — EXACTLY the set product-update.xml stamps
                    // (identity + profile + source status). Missing values are omitted.
                    // commerce:source_status is the product's own Shopify state (active/archived/
                    // draft); commerce:status is the integration lifecycle, claimed for a NEW node only.
                    res.setProperty("commerce:product_id", pid)
                    setStr(res, "commerce:title", body.title)
                    setStr(res, "commerce:handle", body.handle)
                    setStr(res, "commerce:source_status", body.status)
                    setStr(res, "commerce:vendor", body.vendor)
                    setStr(res, "commerce:product_type", body.product_type)
                    setStr(res, "commerce:tags", body.tags)
                    setDate(res, "commerce:updated_at", body.updated_at)
                    // Lifecycle: claim "received" ONLY for a new node; NEVER reset an existing
                    // product's integration status (it may be "deleted" or an advanced lifecycle).
                    if (isNew) {
                        res.setProperty("commerce:status", "received")
                        state.created++
                    }

                    state.products++
                    if (++state.sinceCommit >= BATCH) {
                        repositorySession.commit()
                        state.sinceCommit = 0
                    }
                }
            } catch (Exception ex) {
                state.skipped++
                try { log.warn("importProductBulkResult: skipping product ${state.curProductGid}: ${ex.message}") } catch (Exception ignore) {}
            }
        }
        state.curProduct = null
        state.curProductGid = null
        state.curVariants = []
        state.curImages = []
    }

    eachJsonlLine(httpClient, url) { line ->
        def o = JSON.parse(line)
        if (!(o instanceof Map)) return
        def parentId = o["__parentId"]
        if (parentId == null) {
            // product root line
            flush()
            state.curProduct = o
            state.curProductGid = o.id?.toString()
            state.curVariants = []
            state.curImages = []
        } else if (state.curProductGid != null && parentId.toString() == state.curProductGid) {
            // A child of the current product — variants and media share the product's __parentId, so
            // classify by the gid TYPE in the child's id: ProductVariant -> a variant, MediaImage ->
            // an image. A non-MediaImage media child (video/3d) has a Video/Model3d gid (not
            // /MediaImage/), so it (and any other/deeper unknown child) is harmlessly ignored.
            def cid = o.id?.toString()
            if (cid != null) {
                if (cid.contains("/ProductVariant/")) {
                    state.curVariants << o
                } else if (cid.contains("/MediaImage/")) {
                    state.curImages << o
                }
            }
        }
    }
    flush()
    repositorySession.commit()

    BulkJobs.markCompleted(repositorySession, log, jobId)
    log.info("importProductBulkResult: job ${jobId} - ${state.products} product(s) imported, ${state.created} new, ${state.skipped} skipped")
} catch (Exception e) {
    try { repositorySession.rollback() } catch (Exception ignore) {}
    if (isTransient(e)) {
        // A network drop / stream reset / server 5xx mid-download is transient: retry via READY
        // rather than burning the whole schedule cycle. The staged (uncommitted) writes were just
        // rolled back and the import is idempotent, so the retry re-imports cleanly.
        log.warn("importProductBulkResult: job ${jobId} transient download error: ${e.message}")
        retryOrFail("download error: ${e.class?.simpleName}: ${e.message}")
    } else {
        log.warn("importProductBulkResult: job ${jobId}: ${e.message}")
        try { BulkJobs.markFailed(repositorySession, log, jobId, e.message) } catch (Exception ignore) {}
    }
}

// --- Normalization: GraphQL product node -> REST (webhook) body shape ------------

// Map one Shopify Bulk product GraphQL node (+ its accumulated variant and image child nodes) to
// the REST product body every mirror consumer reads (product browser Pim.browse / product editor /
// Reconciliation). PURE + null-safe: a missing field becomes null / [] / omit, it NEVER throws
// (only JSON.parse or a JCR write can throw, and those are handled by the caller's transient/
// terminal split). The key set matches what product-update.xml persists (raw REST product JSON) so
// a backfilled node is indistinguishable from a webhook-mirrored one.
Map normalizeProduct(Map node, List variantNodes, List imageNodes) {
    def body = [:]

    // Identity. Numeric id from legacyResourceId (preferred) or the gid tail; REST body.id is a
    // number, but commerce:product_id is the String form (set by the caller).
    def pidStr = firstNonBlank(str(node.legacyResourceId), Reconciliation.numericId(node.id))
    body.id = toNumericIdValue(pidStr)

    body.title = str(node.title)
    body.handle = str(node.handle)
    body.body_html = str(node.descriptionHtml)          // GraphQL descriptionHtml -> REST body_html
    body.vendor = str(node.vendor)
    body.product_type = str(node.productType)
    body.tags = tagsToString(node.tags)                 // [String] -> "a, b, c"

    // Status enum is UPPERCASE in GraphQL (ACTIVE/ARCHIVED/DRAFT); the mirror stores lowercase
    // (matching the REST webhook + commerce:source_status).
    body.status = lower(node.status)

    // Timestamps (kept as the source ISO strings; updated_at also drives commerce:updated_at).
    body.created_at = str(node.createdAt)
    body.updated_at = str(node.updatedAt)

    body.variants = (variantNodes ?: []).findAll { it != null }.collect { v -> variant(v) }
    body.images = imagesList(imageNodes)
    return body
}

// One REST variant from a GraphQL ProductVariant node. price is the source string; option1/2/3 are
// derived from selectedOptions, which Shopify returns in the product's option ORDER (so index 0 ->
// option1, 1 -> option2, 2 -> option3), matching the REST variant shape the editor / Reconciliation
// read.
Map variant(v) {
    def vidStr = firstNonBlank(str(v?.legacyResourceId), Reconciliation.numericId(v?.id))
    def itemStr = firstNonBlank(str(v?.inventoryItem?.legacyResourceId), Reconciliation.numericId(v?.inventoryItem?.id))
    def out = [
        id                : toNumericIdValue(vidStr),
        title             : str(v?.title),
        sku               : str(v?.sku),
        price             : str(v?.price),
        position          : asLong(v?.position),
        inventory_quantity: asLong(v?.inventoryQuantity),
        inventory_item_id : toNumericIdValue(itemStr),
        option1           : null,
        option2           : null,
        option3           : null,
    ]
    def sel = v?.selectedOptions
    if (sel instanceof List) {
        def vals = sel.findAll { it != null }.collect { str(it.value) }
        if (vals.size() > 0) out.option1 = vals[0]
        if (vals.size() > 1) out.option2 = vals[1]
        if (vals.size() > 2) out.option3 = vals[2]
    }
    return out
}

// The REST images[] from the accumulated MediaImage child nodes. `alt` is on the media node
// (interface level, mirroring the proven product-media query pattern used elsewhere) while `image { url width
// height }` is inlined from the MediaImage fragment; a media child WITHOUT an image block (a video/3d,
// or an image not yet PROCESSED) is skipped (no REST image to mirror). position is 1-based in the
// media (display) order. NOTE: image.id is the MediaImage gid tail, NOT the legacy REST image id —
// the consumers (Pim.view thumbnail) read only src/alt, so it is informational.
List imagesList(List imageNodes) {
    def out = []
    int pos = 0
    (imageNodes ?: []).findAll { it != null }.each { m ->
        def img = m.image
        if (img == null) return
        pos++
        out << [
            id      : toNumericIdValue(Reconciliation.numericId(m.id)),
            src     : str(img.url),
            alt     : str(m.alt),
            width   : asLong(img.width),
            height  : asLong(img.height),
            position: (long) pos,
        ]
    }
    return out
}

// tags: a GraphQL [String] joined into the REST comma+space string; a bare string passes through.
String tagsToString(tags) {
    if (tags == null) return null
    if (tags instanceof List) return tags.findAll { it != null }.collect { it.toString() }.join(", ")
    return tags.toString()
}

// --- Typed props --------------------------------------------------------------

// Set a String prop ONLY when a value is present (missing -> omit), mirroring the typed-prop idioms
// of the commerce classes.
void setStr(res, String name, v) {
    def s = (v == null) ? null : v.toString()
    if (s != null && !s.trim().isEmpty()) res.setProperty(name, s)
}

// Set a Date prop from an ISO string / epoch value — omit when unparseable. Matches the toDate the
// product-update route applies to commerce:updated_at (a Date, so Pim.browse's xs:dateTime sort works).
void setDate(res, String name, value) {
    long ms = parseMs(value)
    if (ms > 0) res.setProperty(name, new java.util.Date(ms))
}

long parseMs(v) {
    if (v == null) return 0L
    if (v instanceof java.util.Calendar) return ((java.util.Calendar) v).getTimeInMillis()
    if (v instanceof java.util.Date) return ((java.util.Date) v).getTime()
    if (v instanceof Number) return ((Number) v).longValue()
    def s = v.toString().trim()
    if (s.isEmpty()) return 0L
    try { return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (Exception ignore) {}
    try { return java.time.Instant.parse(s).toEpochMilli() } catch (Exception ignore) {}
    return 0L
}

// --- Small value helpers ------------------------------------------------------

def str(v) { v == null ? null : v.toString() }

def lower(v) { v == null ? null : v.toString().trim().toLowerCase() }

def firstNonBlank(a, b) {
    if (a != null && !a.toString().trim().isEmpty()) return a
    return (b != null && !b.toString().trim().isEmpty()) ? b : null
}

Long asLong(v) {
    if (v == null) return null
    if (v instanceof Number) return ((Number) v).longValue()
    def s = v.toString().trim()
    if (s.isEmpty()) return null
    try { return Long.parseLong(s) } catch (Exception ignore) { return null }
}

// A Shopify numeric id string -> a Long when it parses (REST ids are numbers), else the String
// form. Guarded so a "0"-valued id does not trip Groovy's falsy-on-zero elvis.
def toNumericIdValue(s) {
    if (s == null || s.toString().trim().isEmpty()) return null
    def l = asLong(s)
    return (l != null) ? l : s.toString()
}

// --- Streaming / IO helpers (shared shape with the other bulk-result importers) --------------

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
        log.warn("importProductBulkResult: could not read ${path}: ${e.message}")
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
