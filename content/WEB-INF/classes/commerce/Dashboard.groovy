package commerce

import java.time.LocalDate
import java.time.ZoneId

/**
 * Read-only aggregations for the Commerce dashboard: sales and inventory KPIs.
 *
 * Sales figures come from the index-backed sales facts via
 * {@link commerce.SalesQuery} (facet accumulate — uncapped, exact, single
 * source of truth). The lifecycle byStatus breakdown is a facet COUNT over the
 * typed {@code commerce:status} prop of the raw order store (the facts carry
 * the Shopify financial status, not the internal lifecycle status).
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
     * Sales snapshot over the last {@code days} days by ORDERED_AT (business date):
     * order count, revenue per currency (native), the base-currency rollup and the
     * component/metric breakdown — all from the index-backed sales facts
     * ({@link commerce.SalesQuery#salesRange}, uncapped, exact), with the lifecycle
     * byStatus breakdown counted over the raw order store's typed props.
     *
     * Pass {@code range} when the caller already aggregated the same window (the
     * dashboard endpoint shares ONE salesRange between this card and the trend
     * chart) — it saves a full facet pass.
     */
    static Map salesSummary(session, int days = 30, Map range = null) {
        int window = Math.max(days, 1)
        def today = LocalDate.now(ZoneId.systemDefault())
        long cutoff = windowStartMs(days)
        long now = System.currentTimeMillis()
        if (range == null) {
            def opts = SalesQuery.defaults(SalesQuery.config(session)); opts.daily = false
            range = SalesQuery.salesRange(session, cutoff, now, opts)
        }
        return [
            from        : today.minusDays(window - 1).toString(),
            to          : today.toString(),
            days        : window,
            orders      : range?.totals?.orders ?: 0L,
            revenue     : range?.totals?.revenue ?: [],
            baseRevenue : range?.totals?.baseRevenue ?: 0,
            baseCurrency: range?.totals?.baseCurrency,
            metrics     : range?.totals?.metrics ?: [:],
            byStatus    : statusBreakdown(session, cutoff, now),
        ]
    }

    /** Start-of-day epoch ms of the N-day dashboard window (server zone) — shared with the endpoint. */
    static long windowStartMs(int days) {
        int window = Math.max(days, 1)
        return LocalDate.now(ZoneId.systemDefault()).minusDays(window - 1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /**
     * Lifecycle status counts (commerce:status) over the window — the byStatus widget.
     * A single index-backed facet COUNT over the raw order store, keyed on the same
     * ordered_at business date the money aggregates use (no folder walk). NB: this
     * counts raw NODES — an order that (rarely) has a node in two month folders
     * (e.g. when a re-file lands right at a month boundary) counts twice here
     * while the fact-based order count stays deduped; acceptable for a status
     * widget.
     */
    private static Map statusBreakdown(session, long fromMs, long toMs) {
        def stmt = "/jcr:root${ORDERS_RAW}//element(*, nt:file)" +
                   "[${SalesQuery.rangePredicate('commerce:ordered_at', fromMs, toMs)}]" +
                   " facet accumulate ${SalesQuery.countExpr('commerce:status')}".toString()
        def fr = SalesQuery.facets(session, stmt)
        def byStatus = [:]
        SalesQuery.groupNumbers(fr, SalesQuery.countDim("commerce:status")).each { label, n ->
            byStatus[label] = n.longValue()
        }
        return byStatus
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
