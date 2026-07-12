package commerce

import com.fasterxml.jackson.databind.ObjectMapper
import javax.jcr.query.Query

/**
 * Operations / audit export.
 *
 * Turns the outbound-write audit trail the platform keeps in JCR
 * (/content/commerce/sync) into an operator-facing report: who pushed what
 * to Shopify, when, and the outcome.
 *
 * Sales reporting does NOT live here: every sales read is the index-backed facet
 * aggregation over the sales facts — {@link commerce.SalesQuery} (single source
 * of truth; the pre-fact per-order folder walk has been removed).
 *
 * Defensive (a bad resource is skipped, never thrown). The endpoint renders
 * these as JSON or CSV. Lives under /content/WEB-INF/classes; use via
 * {@code import commerce.Reports}.
 */
class Reports {

    static final String SYNC_DIR = "/content/commerce/sync"

    private static final ObjectMapper MAPPER = new ObjectMapper()

    /**
     * The outbound-write audit trail, newest first, filtered server-side by an
     * index-backed XPath query over the typed audit props. Each row answers
     * who / when / against what / what action, WIRE-SHAPED (commerce.Api: camelCase keys,
     * GID entity id, ms-precision ISO timestamps):
     *   { at (WHEN), actor (WHO), action (WHAT-ACTION), entity + entityId
     *     (WHAT-TARGET), status, error, request }.
     * WHO / WHAT-TARGET come from the typed props (queryable/filterable) with a
     * JSON-body fallback for older records.
     *
     * Filters (all optional; null/blank = unconstrained):
     *   - {@code actor}   → {@code @commerce:actor = '…'}
     *   - {@code fromIso} → {@code @commerce:created_at >= xs:dateTime('…')}
     *   - {@code toIso}   → {@code @commerce:created_at <= xs:dateTime('…')}
     *   - {@code statusFilter} → {@code @commerce:status = '…'}
     * {@code fromIso}/{@code toIso} MUST be full xs:dateTime literals (with zone).
     */
    static List operations(session, String actor, String fromIso, String toIso, String statusFilter, int limit) {
        def preds = []
        def a = xpathSafe(actor)
        if (!a.isEmpty()) preds << "@commerce:actor = '${a}'".toString()
        def s = xpathSafe(statusFilter)
        if (!s.isEmpty()) preds << "@commerce:status = '${s}'".toString()
        // Date range: cast the LITERAL (not the property) to xs:dateTime — the property
        // is a real JCR Date, so the string bound must be promoted for a date-typed
        // comparison (the correct form for a range predicate over a typed Date property).
        if (fromIso != null && !fromIso.isEmpty()) preds << "@commerce:created_at >= xs:dateTime('${fromIso}')".toString()
        if (toIso != null && !toIso.isEmpty()) preds << "@commerce:created_at <= xs:dateTime('${toIso}')".toString()

        def where = preds.isEmpty() ? "" : "[${preds.join(' and ')}]"
        // Index-backed, newest-first. MUST cast the Date sort key with xs:dateTime(): a
        // BARE order-by makes Lucene pick the String (SORTED) comparator against this
        // field's date (SORTED_NUMERIC) docvalues and throws — same gotcha the order /
        // customer browses cast around.
        def stmt = "/jcr:root${SYNC_DIR}//element(*, nt:file)${where}" +
                   " order by xs:dateTime(@commerce:created_at) descending"
        def jq = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
        if (limit > 0) jq.limit((long) limit)

        def rows = []
        def resources = jq.execute().getResources()
        if (resources != null) {
            resources.each { res ->
                try {
                    if (!res.getName().endsWith(".json")) return
                    def rec = parse(res)
                    if (rec == null) return
                    def entity = rec.entity ?: prop(res, "commerce:entity")
                    rows << [
                        at      : Api.instant(rec.at ?: propVal(res, "commerce:created_at")),
                        actor   : rec.actor ?: prop(res, "commerce:actor"),
                        action  : rec.action ?: prop(res, "commerce:action"),
                        entity  : entity,
                        entityId: Api.gid(Api.gidTypeFor(entity), rec.entity_id ?: prop(res, "commerce:entity_id")),
                        status  : rec.status ?: prop(res, "commerce:status"),
                        error   : rec.error,
                        request : rec.request,
                    ]
                } catch (Exception ignore) {}
            }
        }
        return rows
    }

    // --- Helpers ---------------------------------------------------------------

    // Keep a user value safe inside an XPath string literal: drop the characters
    // that would break out of the quoted term or the expression (mirrors the
    // orders/crm endpoints' xpathSafe).
    private static String xpathSafe(String s) {
        if (s == null) return ""
        return s.replaceAll("['\"\\[\\]\\(\\)\\\\]", " ").replaceAll("\\s+", " ").trim()
    }

    private static Map parse(res) {
        try {
            def c = res.content?.toString()
            return (c == null || c.trim().isEmpty()) ? null : MAPPER.readValue(c, Map.class)
        } catch (Exception e) { return null }
    }

    private static String prop(res, String name) {
        try { if (res.hasProperty(name)) return res.getProperty(name).getValue()?.toString() } catch (Exception ignore) {}
        return null
    }

    // The raw typed property value (Calendar for Date props) — Api.instant
    // normalizes it to the wire timestamp format.
    private static Object propVal(res, String name) {
        try { if (res.hasProperty(name)) return res.getProperty(name).getValue() } catch (Exception ignore) {}
        return null
    }
}
