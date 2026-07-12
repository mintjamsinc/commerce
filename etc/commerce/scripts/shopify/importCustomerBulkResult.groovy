// Download a completed customers-backfill Bulk Operation's JSONL and IMPORT it into the customer
// mirror, STREAMING line-by-line so memory stays constant (a few MB) regardless of how many
// historical customers the range covers. This is the CUSTOMER counterpart of the
// bulk-result importer that imports the ORDER mirror; it shares the same broker scaffolding.
//
// The bulk query (BulkQueries.CUSTOMERS_BACKFILL_TEMPLATE) is rooted at customers with NO nested
// connection (Customer.addresses is a plain LIST field that inlines), so the JSONL is a FLAT stream
// in GRAPHQL shape — one line per customer, no __parentId children:
//   {"id":"gid://shopify/Customer/111","legacyResourceId":"111", ... ,"addresses":[ ... ]}   // customer
// We process each line directly: NORMALIZE the GraphQL customer node to the REST (webhook) body
// shape every mirror consumer expects (step 1: camelCase/UPPERCASE enums/MoneyV2 -> snake_case/lowercase)
// and UPSERT the customer file, committing in batches (step 2).
//
// BACKFILL SEMANTICS (operator-sovereignty; historical data is NOT "new work"):
//   - Mirror-only: this NEVER starts any BPMN flow.
//   - Idempotent + lifecycle-preserving: the customer store is FLAT and keyed by id
//     (Customers.pathFor("customer_"+id)); an already-mirrored customer is located first
//     (Jcr.safeGet on the deterministic path) and overwritten in place; commerce:status is set to
//     "received" ONLY for a genuinely NEW node — an existing node's integration status is left
//     untouched. Re-running is therefore safe (re-writes the same body/props).
//   - GDPR GUARD: a customer node the operator has locally REDACTED (commerce:status="redacted" or
//     commerce:gdpr_redacted=true) is SKIPPED, never overwritten. A backfill
//     must not resurrect PII a GDPR redact removed.
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
import commerce.Customers
import commerce.Jcr
import commerce.Reconciliation
import commerce.ShopifyAdmin

final int BATCH = 200

def jobId = (binding.hasVariable("bulkJobId") ? binding.getVariable("bulkJobId")?.toString() : null)
if (!jobId) {
    log.warn("importCustomerBulkResult: no bulkJobId")
    return
}
def job = BulkJobs.list(repositorySession).find { it.jobId?.toString() == jobId }
if (job == null) {
    log.warn("importCustomerBulkResult: job ${jobId} not found")
    return
}
def gid = job.bulkOperationGid?.toString()
if (!gid) {
    log.warn("importCustomerBulkResult: job ${jobId} has no bulk gid")
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
            log.warn("importCustomerBulkResult: job ${jobId} transient (${reason}) - attempt ${attempts}/${MAX_RECONCILE_ATTEMPTS}, marking READY to retry")
            // The job is PROCESSING at this point (the CMS lane claimed it before dispatch), so use
            // markReadyForRetry (RUNNING|PROCESSING -> READY); a plain markReady (RUNNING-only) would
            // be a no-op and freeze the job PROCESSING until the watchdog fails it.
            BulkJobs.markReadyForRetry(repositorySession, log, jobId)
        } else {
            log.warn("importCustomerBulkResult: job ${jobId} transient (${reason}) - ${attempts} attempts exhausted, failing")
            BulkJobs.markFailed(repositorySession, log, jobId, "import retries exhausted: ${reason}")
        }
    } catch (Exception ex) {
        log.warn("importCustomerBulkResult: retryOrFail for job ${jobId}: ${ex.message}")
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
        log.warn("importCustomerBulkResult: bulk ${gid} terminal status=${bulkStatus} - failing job ${jobId}")
        BulkJobs.markFailed(repositorySession, log, jobId, "bulk terminal status ${bulkStatus}")
        return
    }
    if (bulkStatus != "COMPLETED" || !url) {
        // COMPLETED-but-url-not-yet-populated, or a momentary status/read blip: TRANSIENT - retry.
        retryOrFail("not yet downloadable (status=${bulkStatus}, url=${url != null})")
        return
    }

    // (The CMS lane already marked this job PROCESSING before dispatch; do not re-claim here.)

    // Streaming import state (one flat customer line at a time = constant memory).
    def state = [customers: 0, created: 0, skipped: 0, sinceCommit: 0]

    def importCustomer = { Map node ->
        // One malformed / unwritable customer must not sink a range-wide historical import: a
        // per-customer failure (a JCR write / lookup glitch, or a surprise in one node) is logged and
        // SKIPPED so the rest of the range still imports. Stream-level IO errors are raised by
        // eachJsonlLine (outside this closure) and still reach the transient-retry path.
        try {
            def body = normalizeCustomer(node)
            def id = body.id?.toString()
            if (id == null || id.trim().isEmpty()) return

            // Locate the (deterministic) existing node first: the customer store is a FLAT folder
            // keyed by id, so the path is fixed regardless of the run — overwrite in place
            // (idempotent) and preserve the node's integration status. Only a genuinely NEW node gets
            // commerce:status="received".
            def path = Customers.pathFor("customer_${id}".toString())
            def existing = Jcr.safeGet(repositorySession, path)
            boolean isNew = (existing == null || !existing.exists())

            // GDPR GUARD: never overwrite a locally-redacted customer shell with re-fetched PII — a
            // backfill must not resurrect what a GDPR redact anonymized. Count it as skipped.
            if (!isNew && isRedacted(existing)) {
                state.skipped++
                return
            }

            def res = isNew ? Jcr.getOrCreateFile(repositorySession, path) : existing
            res.write(Jcr.toJson(body))
            res.setProperty("jcr:mimeType", Customers.CUSTOMER_MIME)

            // Typed, auto-indexed props — EXACTLY the set Customers.upsertFromWebhook stamps
            // (lifecycle + profile). Missing values are omitted; the two Booleans are always set (the
            // webhook does the same). commerce:status is claimed for a NEW node only.
            res.setProperty("commerce:customer_id", id)
            setStr(res, "commerce:source_status", body.state)
            setStr(res, "commerce:email", body.email)
            def name = [body.first_name, body.last_name].findAll { !blank(it) }.join(" ").trim()
            if (name) res.setProperty("commerce:name", name)
            def consent = body.email_marketing_consent?.state?.toString()
            if (consent != null) res.setProperty("commerce:marketing_consent", consent)
            res.setProperty("commerce:marketing_enabled", consent == "subscribed")
            if (body.tags != null) res.setProperty("commerce:tags", body.tags.toString())
            res.setProperty("commerce:tax_exempt", body.tax_exempt == true)
            setDate(res, "commerce:updated_at", body.updated_at)
            setDate(res, "commerce:created_at", body.created_at)
            // Lifecycle: claim "received" ONLY for a new node; NEVER reset an existing customer's
            // integration status.
            if (isNew) {
                res.setProperty("commerce:status", "received")
                state.created++
            }

            state.customers++
            if (++state.sinceCommit >= BATCH) {
                repositorySession.commit()
                state.sinceCommit = 0
            }
        } catch (Exception ex) {
            state.skipped++
            try { log.warn("importCustomerBulkResult: skipping customer ${node?.id}: ${ex.message}") } catch (Exception ignore) {}
        }
    }

    eachJsonlLine(httpClient, url) { line ->
        def o = JSON.parse(line)
        // FLAT stream: each line is a full customer node. Defensively ignore any stray child line
        // carrying __parentId (the customer query declares no nested connection, but a future field
        // could add one — never mistake such a child line for a customer).
        if (o instanceof Map && o["__parentId"] == null) {
            importCustomer(o)
        }
    }
    repositorySession.commit()

    BulkJobs.markCompleted(repositorySession, log, jobId)
    log.info("importCustomerBulkResult: job ${jobId} - ${state.customers} customer(s) imported, ${state.created} new, ${state.skipped} skipped")
} catch (Exception e) {
    try { repositorySession.rollback() } catch (Exception ignore) {}
    if (isTransient(e)) {
        // A network drop / stream reset / server 5xx mid-download is transient: retry via READY
        // rather than burning the whole schedule cycle. The staged (uncommitted) writes were just
        // rolled back and the import is idempotent, so the retry re-imports cleanly.
        log.warn("importCustomerBulkResult: job ${jobId} transient download error: ${e.message}")
        retryOrFail("download error: ${e.class?.simpleName}: ${e.message}")
    } else {
        log.warn("importCustomerBulkResult: job ${jobId}: ${e.message}")
        try { BulkJobs.markFailed(repositorySession, log, jobId, e.message) } catch (Exception ignore) {}
    }
}

// --- Normalization: GraphQL customer node -> REST (webhook) body shape ----------

// Map one Shopify Bulk customer GraphQL node to the REST customer body every mirror consumer reads
// (customer editor / crm / Gdpr). PURE + null-safe: a missing field becomes null / [] / omit, it
// NEVER throws (only JSON.parse or a JCR write can throw, and those are handled by the caller's
// transient/terminal split). The key set matches what commerce.Customers.upsertFromWebhook persists
// (raw REST customer JSON) so a backfilled node is indistinguishable from a webhook-mirrored one.
Map normalizeCustomer(Map node) {
    def body = [:]

    // Identity. Numeric id from legacyResourceId (preferred) or the gid tail; REST body.id is a
    // number, but commerce:customer_id is the String form (set by the caller).
    def cidStr = firstNonBlank(str(node.legacyResourceId), Reconciliation.numericId(node.id))
    body.id = toNumericIdValue(cidStr)

    // Profile.
    body.email = str(node.email)
    body.first_name = str(node.firstName)
    body.last_name = str(node.lastName)
    body.phone = str(node.phone)
    body.note = str(node.note)
    body.tags = tagsToString(node.tags)                 // [String] -> "a, b, c"
    body.tax_exempt = (node.taxExempt == true)          // GraphQL Boolean! -> REST bool
    body.verified_email = (node.verifiedEmail == true)

    // Account state enum is UPPERCASE in GraphQL; the mirror stores lowercase (matching the REST
    // webhook + commerce:source_status).
    body.state = lower(node.state)

    // Timestamps (kept as the source ISO strings; also drive commerce:created_at / :updated_at).
    body.created_at = str(node.createdAt)
    body.updated_at = str(node.updatedAt)

    // Lifetime figures — DISPLAYED from Shopify's own numbers, not recomputed. numberOfOrders is an
    // UnsignedInt64 (JSON string) -> orders_count as a number; amountSpent (MoneyV2) -> total_spent +
    // currency (the customer's own currency the editor formats against).
    body.orders_count = asLong(node.numberOfOrders)
    def spent = node.amountSpent
    body.total_spent = str(spent?.amount)
    body.currency = str(spent?.currencyCode)

    // Marketing consent: GraphQL emailMarketingConsent{marketingState,marketingOptInLevel}
    // (UPPERCASE enums) -> REST email_marketing_consent{state,opt_in_level} (lowercase). Always
    // emitted (parity with the webhook body) even when absent.
    def consent = node.emailMarketingConsent
    body.email_marketing_consent = [
        state       : lower(consent?.marketingState),
        opt_in_level: lower(consent?.marketingOptInLevel),
    ]

    // Addresses. Customer.addresses is a LIST field (inlined on the node line) — REST default_address
    // + addresses[]. The editor flags the default by matching default_address.id against addresses[].id.
    body.default_address = address(node.defaultAddress)
    body.addresses = (node.addresses instanceof List ? node.addresses : []).findAll { it != null }.collect { a -> address(a) }
    return body
}

// GraphQL MailingAddress (camelCase) -> REST address (snake_case). Null address -> null. Carries the
// numeric id (unlike the orders address mapper) so the editor can flag the default address row.
Map address(a) {
    if (a == null) return null
    // MailingAddress gids carry a query segment (gid://shopify/MailingAddress/123?model_name=
    // CustomerAddress); numericId keeps the tail after the last '/', so strip the '?...' before
    // coercing to a clean numeric id matching the webhook mirror.
    def addrId = Reconciliation.numericId(a.id)
    if (addrId != null) { int q = addrId.indexOf('?'); if (q >= 0) addrId = addrId.substring(0, q) }
    return [
        id          : toNumericIdValue(addrId),
        first_name  : str(a.firstName),
        last_name   : str(a.lastName),
        name        : str(a.name),
        company     : str(a.company),
        address1    : str(a.address1),
        address2    : str(a.address2),
        city        : str(a.city),
        province    : str(a.province),
        province_code: str(a.provinceCode),
        zip         : str(a.zip),
        country     : str(a.country),
        country_code: str(a.countryCodeV2),
        phone       : str(a.phone),
    ]
}

// tags: a GraphQL [String] joined into the REST comma+space string; a bare string passes through.
String tagsToString(tags) {
    if (tags == null) return null
    if (tags instanceof List) return tags.findAll { it != null }.collect { it.toString() }.join(", ")
    return tags.toString()
}

// --- Typed props / GDPR guard --------------------------------------------------

// True when an existing customer node has been locally REDACTED — either the shell marker
// (commerce:status="redacted", set by Gdpr.writeShell) or the scrubbed-body marker
// (commerce:gdpr_redacted=true). Such a node must not be overwritten with re-fetched PII.
boolean isRedacted(res) {
    try {
        if (res.hasProperty("commerce:status") && res.getProperty("commerce:status").getValue()?.toString() == "redacted") return true
    } catch (Exception ignore) {}
    try {
        if (res.hasProperty("commerce:gdpr_redacted")) {
            def v = res.getProperty("commerce:gdpr_redacted").getValue()
            if (v != null && v.toString().equalsIgnoreCase("true")) return true
        }
    } catch (Exception ignore) {}
    return false
}

// Set a String prop ONLY when a value is present (missing -> omit), mirroring the typed-prop idioms
// of the commerce classes.
void setStr(res, String name, v) {
    def s = (v == null) ? null : v.toString()
    if (s != null && !s.trim().isEmpty()) res.setProperty(name, s)
}

// Set a Date prop from an ISO string / epoch value — omit when unparseable. Copies the setDate
// approach of Customers.upsertFromWebhook (parse to epoch ms, then a java.util.Date).
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

// --- Small value helpers -------------------------------------------------------

def str(v) { v == null ? null : v.toString() }

def lower(v) { v == null ? null : v.toString().trim().toLowerCase() }

boolean blank(v) { v == null || v.toString().trim().isEmpty() }

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

// --- Streaming / IO helpers (shared shape with the other bulk-result importers) ---------------

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
        log.warn("importCustomerBulkResult: could not read ${path}: ${e.message}")
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
