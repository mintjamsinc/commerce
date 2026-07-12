// Backorder snapshot endpoint (admin).
//
// A read-only JSON view of the backorder book for operators / the Commerce
// dashboard: counts by status, total units still awaited, and the most recent open
// (backordered / ready) records. Derived from the stored backorder resources
// (commerce.Backorders, pure JCR traversal).
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.
//
//   GET /bin/cms.cgi/{workspace}/content/commerce/endpoints/backorders.groovy?limit=50

import commerce.Api
import commerce.Backorders
import com.fasterxml.jackson.databind.ObjectMapper

if (request.getMethod() != "GET") {
    response.setStatus(405)
    return
}

int limit = paramInt("limit", 50, 1, 500)

try {
    def out = [generatedAt: Api.now()]
    out.summary = Backorders.summary(repositorySession)
    out.open = Backorders.list(repositorySession, Backorders.OPEN_STATUSES, limit)

    response.setStatus(200)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(out))
} catch (Exception e) {
    log.error("backorders endpoint error: ${e.message}", e)
    response.setStatus(500)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"Internal error"}')
}

int paramInt(String name, int dflt, int lo, int hi) {
    try {
        def raw = request.getParameter(name)
        if (raw != null && !raw.trim().isEmpty()) {
            return Math.max(lo, Math.min(hi, raw.trim() as int))
        }
    } catch (Exception ignore) {}
    return dflt
}
