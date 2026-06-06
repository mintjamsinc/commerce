// Integration health snapshot endpoint (admin).
//
// Returns an aggregated JSON view of the commerce integration health metrics
// recorded under /content/commerce/health/metrics/. Intended for operators and
// for the Commerce dashboard (a future Webtop app) to poll.
//
// This script lives OUTSIDE /content/public, so the CGI enforces authentication
// and ACLs - only users who may read the commerce health data can call it.
//
//   GET /bin/cms.cgi/{workspace}/content/commerce/endpoints/health.groovy?days=7
//
// Query parameters:
//   days : number of days to aggregate, including today (default 7, max 90)

import commerce.Health
import com.fasterxml.jackson.databind.ObjectMapper

if (request.getMethod() != "GET") {
    response.setStatus(405)
    return
}

int days = 7
try {
    def raw = request.getParameter("days")
    if (raw != null && !raw.trim().isEmpty()) {
        days = Math.max(1, Math.min(90, raw.trim() as int))
    }
} catch (Exception ignore) {
    days = 7
}

try {
    def snapshot = Health.snapshot(repositorySession, days)
    response.setStatus(200)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(snapshot))
} catch (Exception e) {
    log.error("health endpoint error: ${e.message}", e)
    response.setStatus(500)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"Internal error"}')
}
