// Reports & audit export endpoint (admin).
//
//   GET ?type=sales&days=30[&format=json|csv]
//   GET ?type=sales&from=<ISO-8601>&to=<ISO-8601>[&format=json|csv]
//        — the full sales report from the index-backed sales facts (commerce.SalesQuery,
//          facet accumulate — uncapped, exact): daily series + native per-currency revenue +
//          base-currency rollup + the raw component breakdown (gross / discounts /
//          returns / tax / shipping / tips / duties) and the synthesized metrics
//          (grossSales / netSales / totalSales) — the operator picks which figure to read.
//          `from`/`to` are ISO-8601 instants (the client sends new Date(...).toISOString())
//          and take precedence over `days`.
//          Population / axis params (all optional; sales.yml supplies the defaults):
//            financialStatus=paid,partially_refunded,…  includeCancelled=true|false
//            returnsBasis=order|refund                  groupBy=product|customer&top=N
//            compare=1 (period-over-period)
//   GET ?type=operations[&actor=][&from=<ISO-8601>][&to=<ISO-8601>][&status=ok|failed|dryrun][&format=json|csv]
//        — the outbound CMS → Shopify write audit trail (commerce.Reports).
//
// CSV is offered for spreadsheet export; JSON is the default. Lives OUTSIDE
// /content/public, so the CGI enforces authentication and ACLs.
//
//   GET /bin/cms.cgi/{workspace}/content/commerce/endpoints/reports.groovy?type=sales&days=30&format=csv

import commerce.Reports
import commerce.SalesQuery
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
        // Explicit date range (operator-picked) wins over the rolling window. from/to arrive as ISO-8601
        // instants (platform wire convention; the client resolves the datetime-local wall-clock in the
        // effective timezone). Absent → the rolling `days` window ending now.
        Long fromMs = instantMs("from")
        Long toMs = instantMs("to")
        def zone = java.time.ZoneId.systemDefault()
        long hi, lo
        String label
        if (fromMs != null || toMs != null) {
            hi = toMs != null ? toMs.longValue() : System.currentTimeMillis()
            lo = fromMs != null ? fromMs.longValue()
                    : java.time.Instant.ofEpochMilli(hi).atZone(zone).toLocalDate().minusDays(days - 1).atStartOfDay(zone).toInstant().toEpochMilli()
            if (lo > hi) { long t = lo; lo = hi; hi = t }
            def fromD = java.time.Instant.ofEpochMilli(lo).atZone(zone).toLocalDate()
            def toD = java.time.Instant.ofEpochMilli(hi).atZone(zone).toLocalDate()
            label = "sales_${fromD}_${toD}.csv".toString()
        } else {
            hi = System.currentTimeMillis()
            lo = hi - (long) Math.max(days, 0) * 86_400_000L
            label = "sales_${days}d.csv".toString()
        }

        // Operator-chosen population / metric axes (all optional; sales.yml supplies the defaults).
        // Every sales read is the index-backed facet aggregation over the sales facts (commerce.SalesQuery).
        def cfg = SalesQuery.config(repositorySession)
        def overrides = [
            financialStatus : blankToNull(request.getParameter("financialStatus")),
            includeCancelled: request.getParameter("includeCancelled"),
            returnsBasis    : blankToNull(request.getParameter("returnsBasis")),
        ]
        String groupBy = blankToNull(request.getParameter("groupBy"))
        boolean compare = isTrue("compare")
        int top = paramInt("top", 20, 1, 500)

        def opts = SalesQuery.resolveOpts(cfg, overrides)
        def report = SalesQuery.salesRange(repositorySession, lo, hi, opts)
        if (groupBy == "product") report.topProducts = SalesQuery.topProducts(repositorySession, lo, hi, top, opts)
        if (groupBy == "customer") report.byCustomer = SalesQuery.byCustomer(repositorySession, lo, hi, top, opts)
        // The current window is already aggregated above — pop() reuses it (one facet pass saved).
        if (compare) report.pop = SalesQuery.pop(repositorySession, lo, hi, opts, report)

        if (format == "csv") {
            // The CSV follows the on-screen tab: the sales P/L (order date) or the refund cash-out
            // (refund date). Each carries its OWN basis in the comment line; taxMode rides the sales CSV
            // only (the refund list is cash, so a tax mode would only mislead).
            if (blankToNull(request.getParameter("csvView")) == "refunds") {
                sendCsv(label.replace("sales_", "refunds_"), refundsCsv(report))
            } else {
                String taxMode = (blankToNull(request.getParameter("taxMode")) == "incl") ? "incl" : "excl"
                sendCsv(label, salesCsv(report, taxMode))
            }
        } else {
            sendJson(report)
        }
        return
    }

    if (type == "operations") {
        def status = blankToNull(request.getParameter("status"))
        def actor = blankToNull(request.getParameter("actor"))
        // from/to arrive as ISO-8601 instants — the client sends
        // new Date(wall-clock resolved in the effective TZ).toISOString(), per the
        // platform wire convention (cf. content-browser). Validate + normalize each
        // to an xs:dateTime literal for the XPath range predicate.
        String fromIso = paramInstant("from")
        String toIso = paramInstant("to")
        def rows = Reports.operations(repositorySession, actor, fromIso, toIso, status, 5000)
        // Resolve actor ids -> display names the same way content-browser's "updated-by"
        // column does (IdentityProvider.getUser().getDisplayName()); attach as
        // actor_label so the list/detail render the name with a raw-id fallback.
        def actorNames = resolveDisplayNames(rows.collect { it.actor } as Set)
        rows.each { it.actorLabel = actorNames[it.actor] }
        if (format == "csv") {
            def sb = new StringBuilder()
            // who / when / against what / what action / result — the audit-actor columns
            // (actor/entity/entityId) must ride the export, not just the list.
            // Column names follow the wire key convention (camelCase, commerce.Api).
            sb.append("at,actor,entity,entityId,action,status,error\n")
            rows.each { r ->
                sb.append(csv(r.at)).append(",").append(csv(r.actorLabel ?: r.actor)).append(",")
                  .append(csv(r.entity)).append(",").append(csv(r.entityId)).append(",")
                  .append(csv(r.action)).append(",")
                  .append(csv(r.status)).append(",").append(csv(r.error)).append("\n")
            }
            sendCsv("operations.csv", sb.toString())
        } else {
            sendJson([count: rows.size(), operations: rows])
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

// True when a boolean-ish query param is 1/true/yes.
boolean isTrue(String name) {
    def v = request.getParameter(name)
    if (v == null) return false
    def s = v.trim().toLowerCase()
    return s == "1" || s == "true" || s == "yes"
}

// The sales daily CSV — 1:1 with the on-screen P/L table (the `pl` block, order-date basis): one row per
// day plus a totals row, the same columns the screen shows. Line 1 is a self-describing comment (period /
// population / basis / tax mode) so an exported file records the aggregation conditions it was cut under.
// The tax-exclusive P/L carries `tax` as its own column, so the tax mode only records which headline the
// operator was reading — the columns are identical either way.
String salesCsv(Map report, String taxMode) {
    def sb = new StringBuilder()
    def pop = (report.population ?: "").toString().replace("\n", " ").replace("\r", " ")
    sb.append("# period=${report.from ?: ''}..${report.to ?: ''}; population=${pop}; basis=order; taxMode=${taxMode}\n".toString())

    def plCols = ["grossSales", "discounts", "returns", "netSales", "shipping", "otherIncome", "totalRevenue", "tax", "totalCharged"]
    sb.append((["date", "orders"] + plCols).join(",")).append("\n")

    (report.daily ?: []).each { row ->
        def pl = row.pl ?: [:]
        def cells = [row.date, row.orders] + plCols.collect { pl[it] }
        sb.append(cells.collect { csv(it) }.join(",")).append("\n")
    }
    // Totals row (matches the on-screen totals row).
    def tpl = report.totals?.pl ?: [:]
    def totalCells = ["total", report.totals?.orders] + plCols.collect { tpl[it] }
    sb.append(totalCells.collect { csv(it) }.join(",")).append("\n")
    return sb.toString()
}

// The refund cash-out CSV — 1:1 with the on-screen Refunds tab (the `refunds` block, refund-date basis):
// one row per refund day plus a totals row. Line 1 records period / population / basis=refund (NO taxMode
// — this is cash, not a P/L). `crossPeriod` rides as its own column: the monthly-close reader needs to
// know, from the exported file alone, that a day includes a refund for an order outside the window.
String refundsCsv(Map report) {
    def sb = new StringBuilder()
    def pop = (report.population ?: "").toString().replace("\n", " ").replace("\r", " ")
    sb.append("# period=${report.from ?: ''}..${report.to ?: ''}; population=${pop}; basis=refund\n".toString())

    def cols = ["cashOut", "goods", "tax", "shipping", "restockingFeeIncome"]
    sb.append((["refundedDay", "refundCount"] + cols + ["crossPeriod"]).join(",")).append("\n")

    (report.refunds?.daily ?: []).each { row ->
        def cells = [row.refundedDay, row.refundCount] + cols.collect { row[it] } + [row.crossPeriod]
        sb.append(cells.collect { csv(it) }.join(",")).append("\n")
    }
    def t = report.totals?.refunds ?: [:]
    def totalCells = ["total", t.refundCount] + cols.collect { t[it] } + [""]
    sb.append(totalCells.collect { csv(it) }.join(",")).append("\n")
    return sb.toString()
}

int paramInt(String name, int dflt, int lo, int hi) {
    try {
        def v = request.getParameter(name)
        if (v != null && !v.trim().isEmpty()) return Math.max(lo, Math.min(hi, v.trim() as int))
    } catch (Exception ignore) {}
    return dflt
}

// ISO-8601 instant parameter → epoch ms, or null when absent/invalid (used by the
// sales date range; the client sends new Date(...).toISOString()).
Long instantMs(String name) {
    def v = request.getParameter(name)
    if (v == null || v.trim().isEmpty()) return null
    try { return java.time.OffsetDateTime.parse(v.trim()).toInstant().toEpochMilli() } catch (Exception ignore) { return null }
}

// from/to arrive as ISO-8601 instants (the client sends new Date(...).toISOString(),
// per the platform wire convention). Validate + normalize to an xs:dateTime literal
// for the XPath range predicate. Null when absent/invalid — parsing via OffsetDateTime
// (never string-concatenating the raw value) keeps the query injection-safe.
String paramInstant(String name) {
    def v = request.getParameter(name)
    if (v == null || v.trim().isEmpty()) return null
    try {
        return java.time.OffsetDateTime.parse(v.trim())
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    } catch (Exception ignore) { return null }
}

// Resolve user ids -> display names exactly like content-browser's "updated-by" column
// (IdentityProvider.getUser().getDisplayName()). Only RESOLVED names are returned;
// unresolved ids and pseudo-actors (cms/workflow/system) are omitted so the caller
// falls back to the raw id. Uses only the exported org.mintjams.jcr public API — the
// IdentityProvider performs its own system-workspace lookup, so the operator session
// needs no extra privileges.
Map resolveDisplayNames(Collection ids) {
    def out = [:]
    if (ids == null || ids.isEmpty()) return out
    def idp = null
    try {
        def ws = repositorySession.adaptTo(javax.jcr.Session.class)?.getWorkspace()
        if (ws instanceof org.mintjams.jcr.Workspace) {
            idp = ((org.mintjams.jcr.Workspace) ws).getIdentityProvider()
        }
    } catch (Throwable ignore) {}
    if (idp == null) return out
    ids.findAll { it }.unique().each { id ->
        try {
            def u = idp.getUser(id.toString())
            def name = u?.getDisplayName()
            if (name != null && !name.toString().isEmpty()) out[id] = name.toString()
        } catch (Throwable ignore) {}
    }
    return out
}
