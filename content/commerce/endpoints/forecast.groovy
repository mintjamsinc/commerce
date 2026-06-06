// Stockout forecast endpoint (admin).
//
// Returns the variants predicted to run out within the configured warn window,
// soonest first, from the cached sales velocity. Read-only.
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.
//
//   GET /bin/cms.cgi/{workspace}/content/commerce/endpoints/forecast.groovy?warnDays=7

import commerce.SalesVelocity
import commerce.SimpleYaml
import com.fasterxml.jackson.databind.ObjectMapper

if (request.getMethod() != "GET") {
    response.setStatus(405)
    return
}

try {
    int warnDays = 7
    def cfgRes = repositorySession.getResource("/etc/commerce/config/velocity.yml")
    if (cfgRes != null && cfgRes.exists()) {
        def cfg = SimpleYaml.parse(cfgRes.content?.toString())
        if (cfg?.stockout?.warnDays != null) {
            try { warnDays = cfg.stockout.warnDays.toString().trim() as int } catch (Exception ignore) {}
        }
    }
    // Explicit override via query parameter.
    try {
        def raw = request.getParameter("warnDays")
        if (raw != null && !raw.trim().isEmpty()) {
            warnDays = Math.max(1, Math.min(365, raw.trim() as int))
        }
    } catch (Exception ignore) {}

    def perDay = SalesVelocity.loadPerDay(repositorySession)
    def atRisk = SalesVelocity.forecast(repositorySession, perDay, warnDays)

    def out = [
        generatedAt: java.time.Instant.now().toString(),
        warnDays   : warnDays,
        count      : atRisk.size(),
        atRisk     : atRisk,
    ]
    response.setStatus(200)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(out))
} catch (Exception e) {
    log.error("forecast endpoint error: ${e.message}", e)
    response.setStatus(500)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"Internal error"}')
}
