// Customer CRM endpoint (admin). Category D (#13 / #14 / #15).
//
//   GET ?view=segments                       — counts by segment / recency / vip
//   GET ?view=customers[&segment=vip][&limit] — CRM records (highest spend first)
//   GET ?view=customer&key=id_123            — one customer's CRM record
//   GET ?view=abandoned                      — abandoned checkouts (idle, un-completed)
//   POST                                     — recompute segments now (202)
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.

import commerce.Customers
import commerce.Checkouts
import com.fasterxml.jackson.databind.ObjectMapper

def mapper = new ObjectMapper()

try {
    if (request.getMethod() == "POST") {
        try {
            // Recompute as the operator who triggered it, so the CRM records written
            // under /content/commerce carry their identity. The route falls back to
            // the service user if this is blank.
            IntegrationAPI.createMessageSender()
                .setEndpointURI("direct:commerce-crm-segment")
                .setBody("")
                .setHeader("runAs", repositorySession.getUserID())
                .sendAsync()
            respond(202, [triggered: true])
        } catch (Exception e) {
            respond(500, [triggered: false, error: e.message])
        }
        return
    }

    if (request.getMethod() != "GET") {
        response.setStatus(405)
        return
    }

    def view = (request.getParameter("view") ?: "segments").trim().toLowerCase()
    switch (view) {
        case "segments":
            respond(200, Customers.summary(repositorySession))
            break
        case "customers":
            def segment = blankToNull(request.getParameter("segment"))
            int limit = paramInt("limit", 200, 1, 2000)
            respond(200, [segment: segment, customers: Customers.list(repositorySession, segment, limit)])
            break
        case "customer":
            def key = blankToNull(request.getParameter("key"))
            if (key == null) { respond(400, [error: "key is required"]); break }
            def rec = Customers.read(repositorySession, key)
            if (rec.isEmpty()) { respond(404, [error: "Customer not found: ${key}".toString()]); break }
            respond(200, rec)
            break
        case "abandoned":
            long afterMs = abandonedAfterMs()
            respond(200, [abandoned: Checkouts.findAbandoned(repositorySession, afterMs, System.currentTimeMillis())])
            break
        default:
            respond(400, [error: "unknown view (segments|customers|customer|abandoned)"])
    }
} catch (Exception e) {
    log.error("crm endpoint error: ${e.message}", e)
    respond(500, [error: "Internal error"])
}

// --- Helpers -----------------------------------------------------------------

void respond(int status, Object body) {
    response.setStatus(status)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(body))
}

long abandonedAfterMs() {
    long minutes = 60L
    try {
        def res = repositorySession.getResource("/etc/commerce/config/crm.yml")
        if (res != null && res.exists()) {
            def cfg = YAML.parse(res)
            def m = cfg?.abandonedCart?.abandonedAfterMinutes
            if (m != null) minutes = m.toString().trim() as long
        }
    } catch (Exception ignore) {}
    return minutes * 60_000L
}

String blankToNull(String s) { (s == null || s.trim().isEmpty()) ? null : s.trim() }

int paramInt(String name, int dflt, int lo, int hi) {
    try {
        def v = request.getParameter(name)
        if (v != null && !v.trim().isEmpty()) return Math.max(lo, Math.min(hi, v.trim() as int))
    } catch (Exception ignore) {}
    return dflt
}
