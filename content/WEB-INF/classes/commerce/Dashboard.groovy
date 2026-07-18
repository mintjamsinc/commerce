package commerce

import java.time.LocalDate

/**
 * Read-only aggregations for the Commerce dashboard: inventory and fulfillment KPIs
 * (the sales-trend figures come from {@link commerce.SalesQuery#occurrenceSummary},
 * assembled by the dashboard endpoint).
 *
 * The fulfillment backlog is a facet COUNT over the typed {@code commerce:status}
 * prop of the raw order store (the internal lifecycle status, not the Shopify
 * financial status).
 *
 * Defensive: a read error on one resource is skipped, never thrown — a dashboard
 * must degrade gracefully rather than fail wholesale.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.Dashboard}.
 */
class Dashboard {

    static final String ORDERS_RAW = "/content/commerce/orders/raw"
    static final String PRODUCTS_DIR = "/content/commerce/products"

    /**
     * Inventory snapshot: total products and a breakdown by processing status
     * (commerce:status), plus a convenience lowStock = review_pending count.
     */
    static Map inventorySummary(session) {
        def byStatus = [:]
        long total = 0
        def dir = safeGet(session, PRODUCTS_DIR)
        if (dir != null && dir.exists()) {
            children(dir).each { child ->
                try {
                    if (!child.getName().endsWith(".json")) {
                        return
                    }
                    total++
                    def status = prop(child, "commerce:status") ?: "unknown"
                    byStatus[status] = ((byStatus[status] ?: 0) as long) + 1
                } catch (Exception ignore) {}
            }
        }
        return [total: total, byStatus: byStatus, lowStock: (byStatus.review_pending ?: 0)]
    }

    /**
     * Start-of-day epoch ms of the N-day dashboard window in the given zone (the
     * viewer's timezone, passed by the endpoint; UTC when the client sent none —
     * never the server default).
     */
    static long windowStartMs(int days, zone) {
        int window = Math.max(days, 1)
        return LocalDate.now(zone).minusDays(window - 1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * Fulfillment backlog: orders currently waiting to be picked, packed and shipped
     * ({@code commerce:status = fulfillment_pending} — the order workflow's "Fulfill
     * Order" stage). Counted over the WHOLE order store, not a date window: an old
     * order still waiting is exactly the one the card must not hide. A single
     * index-backed facet COUNT over the typed status prop (no folder walk). NB: this
     * counts raw NODES — an order that (rarely) has a node in two month folders
     * counts twice; acceptable for a backlog widget.
     */
    static Map fulfillmentSummary(session) {
        def stmt = "/jcr:root${ORDERS_RAW}//element(*, nt:file)[@commerce:status]" +
                   " facet accumulate ${SalesQuery.countExpr('commerce:status')}".toString()
        def fr = SalesQuery.facets(session, stmt)
        def by = SalesQuery.groupNumbers(fr, SalesQuery.countDim("commerce:status"))
        long pending = (by["fulfillment_pending"]?.longValue()) ?: 0L
        return [pending: pending]
    }

    // --- Helpers ---------------------------------------------------------------

    private static safeGet(session, String path) {
        try {
            return session.getResource(path)
        } catch (Exception e) {
            return null
        }
    }

    /** Children of a resource as a List (defensive; empty on error). */
    private static List children(resource) {
        def out = []
        try {
            def it = resource.list()
            while (it.hasNext()) {
                out << it.next()
            }
        } catch (Exception ignore) {}
        return out
    }

    private static String prop(resource, String name) {
        try {
            if (resource.hasProperty(name)) {
                def v = resource.getProperty(name).getValue()
                return v == null ? null : v.toString()
            }
        } catch (Exception ignore) {}
        return null
    }
}
