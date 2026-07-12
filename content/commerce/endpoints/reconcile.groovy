// Reconciliation endpoint (admin).
//
//   GET  — the reconciliation state (last run) + the run history over a selectable window
//          (?window=24h|7d|30d, default 24h): one row per run for BOTH scopes — the
//          status/price diff batch ("diff") and the full inventory audit ("inventory") —
//          newest-started first, from ONE index-backed XPath query over the typed report
//          properties (Reconciliation.listRuns; report bodies are never parsed).
//   POST — trigger a run now (fire-and-forget), scoped by the JSON body:
//            { scope: "diff" }      (default) the diff batch via direct:commerce-reconcile
//            { scope: "inventory" } a full inventory audit via the Bulk job broker
//                                   (enqueues an inventory-full job; idempotent per type)
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.
//
//   GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/reconcile.groovy
//   POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/reconcile.groovy

import commerce.Api
import commerce.BulkJobs
import commerce.Jcr
import commerce.Reconciliation
import com.fasterxml.jackson.databind.ObjectMapper

def mapper = new ObjectMapper()

// The selectable history windows (the operator UI's period selector). Keys are the wire
// values; values are the window length in ms.
def WINDOWS = [
    "24h": 24L * 3600_000L,
    "7d" : 7L * 24L * 3600_000L,
    "30d": 30L * 24L * 3600_000L,
]
// Backstop row cap — a window is at most 30 days, so this is far above any real run count
// (runs are schedule- or operator-triggered, not per-event).
final long HISTORY_MAX_ROWS = 1000L

try {
    if (request.getMethod() == "GET") {
        def windowKey = (blankToNull(request.getParameter("window")) ?: "24h")
        def windowMs = WINDOWS[windowKey]
        if (windowMs == null) {
            respond(400, [error: "unknown window (expected 24h, 7d or 30d)"])
            return
        }
        def fromIso = Api.instant(System.currentTimeMillis() - windowMs)
        def runs = Reconciliation.listRuns(repositorySession, [fromIso: fromIso, limit: HISTORY_MAX_ROWS])
        def out = [
            generatedAt: Api.now(),
            window     : windowKey,
            state      : Jcr.readMap(repositorySession, "${Reconciliation.RECON_DIR}/state.json"),
            history    : runs.collect { r ->
                [
                    scope     : r.scope,
                    startedAt : r.startedAt,
                    finishedAt: r.finishedAt,
                    updated   : r.updated,
                    result    : r.result,
                ]
            },
        ]
        // Older stored docs carry nanosecond timestamps — collapse to the wire
        // format (ms-precision ISO) at the exit.
        if (out.state?.lastRunAt) out.state.lastRunAt = Api.instant(out.state.lastRunAt)
        respond(200, out)
        return
    }

    if (request.getMethod() == "POST") {
        // Parse the (optional) JSON body; an empty body defaults to a diff run so the
        // pre-scope clients keep working unchanged.
        def req
        try {
            def body = new String(request.getInputStream().readAllBytes(), "UTF-8")
            // readValue returns null for the JSON literal "null" — fold it into {} too.
            req = body.trim().isEmpty() ? [:] : (mapper.readValue(body, Map.class) ?: [:])
        } catch (Exception e) {
            respond(400, [error: "Invalid JSON body"])
            return
        }
        def scope = (blankToNull(req.scope?.toString()) ?: Reconciliation.SCOPE_DIFF)
        if (!(scope in [Reconciliation.SCOPE_DIFF, Reconciliation.SCOPE_INVENTORY])) {
            respond(400, [error: "unknown scope (expected diff or inventory)"])
            return
        }

        try {
            if (scope == Reconciliation.SCOPE_INVENTORY) {
                // Full inventory audit via the Bulk job broker (same path the inventory
                // schedule takes). Idempotent across EVERY audit-equivalent job type
                // (inventory-full AND the operator's inventory-backfill — the identical
                // full audit on the same domain): if one is already active, surface that
                // instead of enqueuing a second redundant full scan behind it.
                if (Reconciliation.INVENTORY_AUDIT_TYPES.any { BulkJobs.hasActive(repositorySession, it.toString()) }) {
                    respond(202, [triggered: false, alreadyRunning: true, scope: scope])
                    return
                }
                // Run the enqueue (and the resulting bulk job) AS the operator who
                // triggered it; the route falls back to the service user if blank.
                IntegrationAPI.createMessageSender()
                    .setEndpointURI("direct:commerce-shopify-bulk-enqueue")
                    .setBody("")
                    .setHeader("bulkJobType", Reconciliation.INVENTORY_FULL_JOB_TYPE)
                    .setHeader("runAs", repositorySession.getUserID())
                    .sendAsync()
            } else {
                // Run the on-demand diff batch as the operator who triggered it, so the
                // reports / refreshed products written under /content/commerce carry
                // their identity (jcr:createdBy etc.).
                IntegrationAPI.createMessageSender()
                    .setEndpointURI("direct:commerce-reconcile")
                    .setBody("")
                    .setHeader("runAs", repositorySession.getUserID())
                    .sendAsync()
            }
            respond(202, [triggered: true, scope: scope])
        } catch (Exception e) {
            log.warn("reconcile: could not trigger ${scope} run: ${e.message}")
            respond(500, [triggered: false, error: e.message])
        }
        return
    }

    response.setStatus(405)
} catch (Exception e) {
    log.error("reconcile endpoint error: ${e.message}", e)
    respond(500, [error: "Internal error"])
}

// --- Helpers -----------------------------------------------------------------

void respond(int status, Map body) {
    response.setStatus(status)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(body))
}

String blankToNull(String s) { (s == null || s.trim().isEmpty()) ? null : s.trim() }
