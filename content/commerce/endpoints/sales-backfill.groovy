// Sales-fact backfill seed PROGRESS endpoint (admin, read-only).
//
//   GET — the seed progress (status / scanned / enqueued / distinctOrders / remaining pending /
//         timestamps) so the operator can watch the historical seed drain to remaining=0.
//
// The seed itself is NO LONGER operator-triggered here: it is CHAINED off the orders backfill —
// when an orders-backfill bulk import completes, importBulkResult kicks
// direct:commerce-sales-backfill-seed (which walks the ENTIRE order mirror, enqueues every distinct
// order for the single-writer drainer, and kicks that drainer once at the end). This endpoint only
// reports that chained run's progress; POST is not supported (405).
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.
//
//   GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/sales-backfill.groovy

import commerce.Api
import commerce.SalesFactBackfill
import com.fasterxml.jackson.databind.ObjectMapper

try {
    if (request.getMethod() == "GET") {
        def out = new LinkedHashMap()
        out.generatedAt = Api.now()
        out.putAll(SalesFactBackfill.progress(repositorySession))
        respond(200, out)
        return
    }

    response.setStatus(405)
} catch (Exception e) {
    log.error("sales-backfill endpoint error: ${e.message}", e)
    respond(500, [error: "Internal error"])
}

// --- Helpers -----------------------------------------------------------------

void respond(int status, Map body) {
    response.setStatus(status)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(body))
}
