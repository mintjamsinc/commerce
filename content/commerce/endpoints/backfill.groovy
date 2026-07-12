// Backfill endpoint (admin). Shopify → CMS initial/historical import.
//
//   GET  — list the backfill jobs (newest first) so the operator can watch progress.
//   POST — trigger a date-ranged orders, customers or products backfill now (fire-and-forget;
//          the bulk job runs through the Shopify Bulk broker as the operator who triggered it).
//
// Backfill is the INITIAL-IMPORT counterpart to reconcile: reconcile only diff-updates data
// that is already mirrored, while backfill populates a mirror (orders / customers / products) with
// HISTORICAL records over an operator-supplied created_at range. One-way (Shopify is the source
// of truth) and mirror-only (it never starts a review/BPMN flow — historical records are not
// "new work").
//
// An ORDERS backfill is the whole historical sales import in ONE trigger: the bulk export flags
// refund-bearing orders (refunds { id }), the import fetches those orders' refund details via the
// foreground Admin API and mirrors the missing ones, and on completion it kicks the sales-fact
// seed, which enqueues every order for the single-writer fact drainer. Refunds and sales facts are
// therefore no longer separately triggerable — they chain off this endpoint's orders backfill.
// Per-job counters (orders / refunds stored / skipped) surface on the job list as `stats`.
//
// Enqueue is idempotent per type (BulkJobs.hasActive counts QUEUED/RUNNING/READY/PROCESSING),
// so a second trigger while one is active is a no-op on the broker side — we surface that as
// {triggered:false, alreadyRunning:true} rather than piling up a duplicate job.
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.
//
//   GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/backfill.groovy
//   POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/backfill.groovy

import commerce.Api
import commerce.BulkJobs
import com.fasterxml.jackson.databind.ObjectMapper

def mapper = new ObjectMapper()

// Accepted backfill entity types → their bulk job type. By convention a backfill job type is
// "{domain}-backfill" (and each has a matching TYPES row in BulkQueries). A bare POST (no type)
// defaults to orders. A future backfill type adds one row here (and one there).
// "inventory" is a FULL SNAPSHOT (not date-ranged): its from/to are ignored (a static bulk query),
// and it refreshes the location-metadata mirror first so the reorder destination picker is populated.
def JOB_TYPES = [ "orders": "orders-backfill", "customers": "customers-backfill", "products": "products-backfill", "inventory": "inventory-backfill" ]
def DATE_RE   = ~/\d{4}-\d{2}-\d{2}/

try {
    if (request.getMethod() == "GET") {
        respond(200, listBackfills())
        return
    }

    if (request.getMethod() == "POST") {
        // Parse the (optional) JSON body. An empty body is treated as {} so a bare POST
        // defaults to an all-orders backfill.
        def req
        try {
            def body = new String(request.getInputStream().readAllBytes(), "UTF-8")
            req = body.trim().isEmpty() ? [:] : mapper.readValue(body, Map.class)
        } catch (Exception e) {
            respond(400, [error: "Invalid JSON body"])
            return
        }

        // Resolve the entity type to its bulk job type; reject anything unknown with a clear 400.
        // A bare POST (no type) defaults to an all-orders backfill.
        def type = blankToNull(req.type?.toString()) ?: "orders"
        def jobType = JOB_TYPES[type]
        if (jobType == null) {
            respond(400, [error: "unknown backfill type (expected orders, customers, products or inventory)"])
            return
        }

        // Optional created_at bounds (either/both/neither). Validate the format here so a bad
        // value can never reach the bulk query (the producer lane injects them into a Shopify
        // created_at search filter).
        def from = blankToNull(req.from?.toString())
        def to   = blankToNull(req.to?.toString())
        // Validate FORMAT and calendar VALIDITY: a bad-but-formatted date (2026-13-45) must 400 here
        // rather than reach the bulk query and waste a whole Bulk cycle on a rejected search filter.
        if (from != null && (!(from ==~ DATE_RE) || !parseableDate(from))) { respond(400, [error: "from must be a valid yyyy-MM-dd date"]); return }
        if (to   != null && (!(to   ==~ DATE_RE) || !parseableDate(to)))   { respond(400, [error: "to must be a valid yyyy-MM-dd date"]); return }

        // Idempotent per RESOLVED job type: if a backfill of THIS job type is already active, don't
        // enqueue another — the broker would drop it anyway, so surface the running state instead.
        // Keying off jobType (not the raw entity) means an active customers-backfill blocks another
        // customers-backfill but NOT an orders-backfill, and vice-versa.
        if (BulkJobs.hasActive(repositorySession, jobType)) {
            respond(202, [triggered: false, alreadyRunning: true, type: type, from: from, to: to])
            return
        }

        try {
            // Run the enqueue (and the resulting bulk job) AS the operator who triggered it, so
            // the mirror writes carry their identity (jcr:createdBy etc.). The route falls back
            // to the service user if runAs is blank. Absent bounds are sent as "" (never null),
            // matching the webhook sender's null-guard; the enqueue script treats blank as omit.
            IntegrationAPI.createMessageSender()
                .setEndpointURI("direct:commerce-shopify-bulk-enqueue")
                .setBody("")
                .setHeader("bulkJobType", jobType)
                .setHeader("bulkFrom", from == null ? "" : from)
                .setHeader("bulkTo", to == null ? "" : to)
                .setHeader("runAs", repositorySession.getUserID())
                .sendAsync()
            respond(202, [triggered: true, type: type, from: from, to: to])
        } catch (Exception e) {
            log.warn("backfill: could not trigger run: ${e.message}")
            respond(500, [triggered: false, error: e.message])
        }
        return
    }

    response.setStatus(405)
} catch (Exception e) {
    log.error("backfill endpoint error: ${e.message}", e)
    respond(500, [error: "Internal error"])
}

// --- Views -------------------------------------------------------------------

// The backfill jobs (any "*-backfill" type), newest first, projected to the fields an operator
// watches, plus a small summary. Reads the durable broker job docs via BulkJobs.list.
Map listBackfills() {
    def active = [BulkJobs.QUEUED, BulkJobs.RUNNING, BulkJobs.READY, BulkJobs.PROCESSING]
    def jobs = BulkJobs.list(repositorySession)
                       .findAll { it.type?.toString()?.endsWith("-backfill") }
                       .sort { a, b -> (b.enqueuedAt?.toString() ?: "") <=> (a.enqueuedAt?.toString() ?: "") }
    def items = jobs.collect { j ->
        [
            jobId              : j.jobId,
            // Wire values are camelCase identifiers ("ordersBackfill"); the broker's
            // internal lane names stay "{domain}-backfill" in storage/headers.
            type               : Api.camelValue(j.type),
            status             : j.status,
            params             : j.params,
            enqueuedAt         : Api.instant(j.enqueuedAt),
            startedAt          : Api.instant(j.startedAt),
            readyAt            : Api.instant(j.readyAt),
            processingStartedAt: Api.instant(j.processingStartedAt),
            finishedAt         : Api.instant(j.finishedAt),
            error              : j.error,
            // Free-form result counters from the processor (e.g. orders / refundsStored) — absent
            // until the job completes.
            stats              : (j.stats instanceof Map) ? j.stats : null,
        ]
    }
    int activeCount = jobs.count { active.contains(it.status?.toString()) }
    return [
        generatedAt: Api.now(),
        summary    : [activeCount: activeCount],
        jobs       : items,
    ]
}

// --- Helpers -----------------------------------------------------------------

void respond(int status, Map body) {
    response.setStatus(status)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(body))
}

String blankToNull(String s) { (s == null || s.trim().isEmpty()) ? null : s.trim() }

// True when s is a REAL calendar date (rejects 2026-13-45 / 2026-02-30 that the format regex passes).
boolean parseableDate(String s) {
    try { java.time.LocalDate.parse(s); return true } catch (Exception e) { return false }
}
