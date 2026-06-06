// Reports & audit export endpoint (admin). Category G (#25).
//
//   GET ?type=sales&days=30[&format=json|csv]
//        — daily orders + revenue (per currency) and top products.
//   GET ?type=operations&days=30[&status=ok|failed|dryrun][&format=json|csv]
//        — the outbound CMS → Shopify write audit trail (#2).
//
// CSV is offered for spreadsheet export; JSON is the default. Built from the audit
// trails the platform already keeps in JCR (commerce.Reports). Lives OUTSIDE
// /content/public, so the CGI enforces authentication and ACLs.
//
//   GET /bin/cms.cgi/{workspace}/content/commerce/endpoints/reports.groovy?type=sales&days=30&format=csv

import commerce.Reports
import com.fasterxml.jackson.databind.ObjectMapper

if (request.getMethod() != "GET") {
    response.setStatus(405)
    return
}

def type = (request.getParameter("type") ?: "sales").trim().toLowerCase()
def format = (request.getParameter("format") ?: "json").trim().toLowerCase()
int days = paramInt("days", 30, 1, 365)

try {
    if (type == "sales") {
        def report = Reports.sales(repositorySession, days)
        if (format == "csv") {
            def sb = new StringBuilder()
            sb.append("date,orders,currency,revenue\n")
            report.daily.each { row ->
                def rev = row.revenue ?: [:]
                if (rev.isEmpty()) {
                    sb.append(csv(row.date)).append(",").append(row.orders).append(",,\n")
                } else {
                    rev.each { cur, amt ->
                        sb.append(csv(row.date)).append(",").append(row.orders).append(",")
                          .append(csv(cur)).append(",").append(csv(amt)).append("\n")
                    }
                }
            }
            sendCsv("sales_${days}d.csv", sb.toString())
        } else {
            sendJson(report)
        }
        return
    }

    if (type == "operations") {
        def status = blankToNull(request.getParameter("status"))
        def rows = Reports.operations(repositorySession, days, status, 5000)
        if (format == "csv") {
            def sb = new StringBuilder()
            sb.append("at,action,status,error\n")
            rows.each { r ->
                sb.append(csv(r.at)).append(",").append(csv(r.action)).append(",")
                  .append(csv(r.status)).append(",").append(csv(r.error)).append("\n")
            }
            sendCsv("operations_${days}d.csv", sb.toString())
        } else {
            sendJson([days: days, count: rows.size(), operations: rows])
        }
        return
    }

    response.setStatus(400)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"unknown type (use sales|operations)"}')
} catch (Exception e) {
    log.error("reports endpoint error: ${e.message}", e)
    response.setStatus(500)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"Internal error"}')
}

// --- Helpers -----------------------------------------------------------------

void sendJson(Object body) {
    response.setStatus(200)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(body))
}

void sendCsv(String filename, String content) {
    response.setStatus(200)
    response.setHeader("Content-Type", "text/csv; charset=UTF-8")
    response.setHeader("Content-Disposition", "attachment; filename=\"${filename}\"")
    response.getWriter().write(content)
}

// RFC-4180-ish escaping: quote a field that contains comma/quote/newline.
String csv(v) {
    if (v == null) return ""
    def s = v.toString()
    if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
        return "\"" + s.replace("\"", "\"\"") + "\""
    }
    return s
}

String blankToNull(String s) { (s == null || s.trim().isEmpty()) ? null : s.trim() }

int paramInt(String name, int dflt, int lo, int hi) {
    try {
        def v = request.getParameter(name)
        if (v != null && !v.trim().isEmpty()) return Math.max(lo, Math.min(hi, v.trim() as int))
    } catch (Exception ignore) {}
    return dflt
}
