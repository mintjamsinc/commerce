// Manual business-data purge endpoint (admin).
//
//   GET                 — recent purge history (newest first): one row per past purge.
//   GET ?days=N         — count-only PREVIEW: how many orders / payments / refunds are
//                         older than N days, plus the resolved cutoff instant. No writes.
//   POST { days: N }    — PERFORM the purge (irreversible): delete orders / payments /
//                         refunds older than N days, write an audit record, and return
//                         the deleted counts. Runs synchronously as the calling operator.
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs. The
// purge writes carry the operator's identity (repositorySession).
//
//   GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/retention-purge.groovy
//   POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/retention-purge.groovy

import commerce.Api
import commerce.MaintenanceAudit
import commerce.Purge
import com.fasterxml.jackson.databind.ObjectMapper

final int HISTORY_LIMIT = 50

def mapper = new ObjectMapper()

try {
    if (request.getMethod() == "GET") {
        def daysParam = blankToNull(request.getParameter("days"))
        if (daysParam == null) {
            // History view.
            respond(200, [generatedAt: Api.now(), history: MaintenanceAudit.listRecent(repositorySession, HISTORY_LIMIT)])
            return
        }
        // Preview view.
        def days = parseDays(daysParam)
        if (days == null) {
            respond(400, [error: "days must be an integer >= 1"])
            return
        }
        def preview = Purge.preview(repositorySession, days)
        respond(200, [
            days    : days,
            cutoff  : preview.cutoff,
            orders  : preview.orders ?: 0,
            payments: preview.payments ?: 0,
            refunds : preview.refunds ?: 0,
        ])
        return
    }

    if (request.getMethod() == "POST") {
        def req
        try {
            def body = new String(request.getInputStream().readAllBytes(), "UTF-8")
            req = body.trim().isEmpty() ? [:] : (mapper.readValue(body, Map.class) ?: [:])
        } catch (Exception e) {
            respond(400, [error: "Invalid JSON body"])
            return
        }
        def days = parseDays(req.days?.toString())
        if (days == null) {
            respond(400, [error: "days must be an integer >= 1"])
            return
        }
        try {
            def counts = Purge.run(repositorySession, log, days, repositorySession.getUserID())
            respond(200, [
                days    : days,
                cutoff  : counts.cutoff,
                orders  : counts.orders ?: 0,
                payments: counts.payments ?: 0,
                refunds : counts.refunds ?: 0,
            ])
        } catch (Exception e) {
            log.warn("retention-purge: purge failed: ${e.message}")
            try {
                MaintenanceAudit.recordPurge(repositorySession, log, repositorySession.getUserID(), days,
                    Purge.cutoffIso(days, System.currentTimeMillis()), 0, 0, 0, "failed", e.message)
            } catch (Exception ignore) {}
            respond(500, [error: e.message])
        }
        return
    }

    response.setStatus(405)
} catch (Exception e) {
    log.error("retention-purge endpoint error: ${e.message}", e)
    respond(500, [error: "Internal error"])
}

// --- Helpers -----------------------------------------------------------------

// Parse a positive day count; null when blank / non-integer / < 1.
Integer parseDays(String s) {
    if (s == null || s.trim().isEmpty()) return null
    try {
        int n = Integer.parseInt(s.trim())
        return n >= 1 ? n : null
    } catch (Exception e) {
        return null
    }
}

void respond(int status, Map body) {
    response.setStatus(status)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(body))
}

String blankToNull(String s) { (s == null || s.trim().isEmpty()) ? null : s.trim() }
