// One-time admin migration: rename the legacy non-namespaced commerce metadata
// (product_id, title, status, ...) on the mirrored product / order / refund nodes
// to the canonical commerce: namespace. Needed for data ingested before the
// Shopify routes were corrected to write commerce:* (they previously used
// includes=commerce_~, which strips the prefix).
//
//   GET                         — DRY RUN: report what WOULD change, write nothing
//   POST {}                     — APPLY the migration (commits per area)
//   POST {"dryRun": true}       — preview via POST (writes nothing)
//
// GET never writes, so it is always safe to inspect first. Lives OUTSIDE
// /content/public, so the CGI enforces authentication and ACLs; the caller needs
// write access to /content/commerce (commerce-operators / administrators).

import commerce.migration.NamespaceMigration
import com.fasterxml.jackson.databind.ObjectMapper

def mapper = new ObjectMapper()

try {
    boolean dryRun
    if (request.getMethod() == "GET") {
        dryRun = true
    } else if (request.getMethod() == "POST") {
        def body = new String(request.getInputStream().readAllBytes(), "UTF-8")
        def reqMap = body.trim().isEmpty() ? [:] : mapper.readValue(body, Map.class)
        // POST applies by default; pass {"dryRun":true} to preview without writing.
        dryRun = reqMap.dryRun != null && reqMap.dryRun.toString().toLowerCase() == "true"
    } else {
        response.setStatus(405)
        return
    }

    def report = NamespaceMigration.run(repositorySession, log, dryRun)
    respond(200, report)
} catch (Exception e) {
    log.error("migrate-namespace endpoint error: ${e.message}", e)
    respond(500, [error: "Internal error: ${e.message}".toString()])
}

// --- Helpers -----------------------------------------------------------------

void respond(int status, Map body) {
    response.setStatus(status)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(body))
}
