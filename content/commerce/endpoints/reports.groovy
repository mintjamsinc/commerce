// Reports & audit export endpoint (admin).
//
//   GET ?type=occurrence&days=30[&tz=<IANA zone>][&format=json|csv]
//   GET ?type=occurrence&from=<ISO-8601>&to=<ISO-8601>[&tz=<IANA zone>][&format=json|csv]
//        — the occurrence-date summary from the index-backed sales facts + the refund
//          and payment stores (commerce.SalesQuery.occurrenceSummary, facet accumulate —
//          uncapped, exact): new orders (ordered_at) / full cancels (cancelled_at) /
//          payments (paid_at, cash in) / refunds (refunded_at), each counted on its OWN
//          event date; confirmedSales = paymentAmount + refundAmount (the payment basis:
//          cash in minus cash out; refundAmount is NEGATIVE). No population params — the
//          report counts every event on its date.
//          `from`/`to` are ISO-8601 instants (the client sends new Date(...).toISOString())
//          and take precedence over `days`.
//   GET ?type=operations[&actor=][&from=<ISO-8601>][&to=<ISO-8601>][&status=ok|failed|dryrun][&format=json|csv]
//        — the outbound CMS → Shopify write audit trail (commerce.Reports).
//
// CSV is offered for spreadsheet export; JSON is the default. Lives OUTSIDE
// /content/public, so the CGI enforces authentication and ACLs.
//
//   GET /bin/cms.cgi/{workspace}/content/commerce/endpoints/reports.groovy?type=occurrence&days=30&format=csv

import commerce.Reports
import commerce.SalesQuery
import com.fasterxml.jackson.databind.ObjectMapper

if (request.getMethod() != "GET") {
    response.setStatus(405)
    return
}

def type = (request.getParameter("type") ?: "occurrence").trim().toLowerCase()
def format = (request.getParameter("format") ?: "json").trim().toLowerCase()
int days = paramInt("days", 30, 1, 365)

try {
    if (type == "occurrence") {
        // The occurrence-date summary (new orders / cancellations / refunds, each counted on its OWN
        // event date). Explicit from/to instants (operator-picked) win over the rolling `days` window.
        // No population params — this report counts every event on its date. The `tz` param (IANA id,
        // sent by the client from the user's Preferences timezone) drives the day-bucket boundaries
        // AND the default-window day arithmetic; absent/invalid falls back to UTC, never the server
        // default, so the report is server-timezone independent.
        Long fromMs = instantMs("from")
        Long toMs = instantMs("to")
        String tz = blankToNull(request.getParameter("tz"))
        def zone = SalesQuery.zoneOf(tz)
        long hi, lo
        String label
        if (fromMs != null || toMs != null) {
            hi = toMs != null ? toMs.longValue() : System.currentTimeMillis()
            lo = fromMs != null ? fromMs.longValue()
                    : java.time.Instant.ofEpochMilli(hi).atZone(zone).toLocalDate().minusDays(days - 1).atStartOfDay(zone).toInstant().toEpochMilli()
            if (lo > hi) { long t = lo; lo = hi; hi = t }
            def fromD = java.time.Instant.ofEpochMilli(lo).atZone(zone).toLocalDate()
            def toD = java.time.Instant.ofEpochMilli(hi).atZone(zone).toLocalDate()
            label = "occurrence_${fromD}_${toD}.csv".toString()
        } else {
            hi = System.currentTimeMillis()
            lo = hi - (long) Math.max(days, 0) * 86_400_000L
            label = "occurrence_${days}d.csv".toString()
        }

        def report = SalesQuery.occurrenceSummary(repositorySession, lo, hi, tz)
        if (format == "csv") {
            sendCsv(label, occurrenceCsv(report))
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
    response.getWriter().write('{"error":"unknown type (use occurrence|operations)"}')
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

// The occurrence-date CSV — 1:1 with the on-screen 発生日サマリ tab: one row per day plus a totals row.
// Line 1 records the period and the occurrence-date basis so an exported file is self-describing.
// paymentAmount is POSITIVE (cash in); refundAmount is NEGATIVE (cash out);
// confirmedSales = paymentAmount + refundAmount.
String occurrenceCsv(Map report) {
    def sb = new StringBuilder()
    sb.append("# period=${report.from ?: ''}..${report.to ?: ''}; basis=occurrence; baseCurrency=${report.baseCurrency ?: ''}\n".toString())

    def cols = ["newOrderCount", "newOrderAmount", "cancelledCount", "paymentCount", "paymentAmount", "refundCount", "refundAmount", "confirmedSales"]
    sb.append((["date"] + cols).join(",")).append("\n")

    (report.daily ?: []).each { row ->
        def cells = [row.date] + cols.collect { row[it] }
        sb.append(cells.collect { csv(it) }.join(",")).append("\n")
    }
    def t = report.totals ?: [:]
    def totalCells = ["total"] + cols.collect { t[it] }
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
// occurrence date range; the client sends new Date(...).toISOString()).
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
