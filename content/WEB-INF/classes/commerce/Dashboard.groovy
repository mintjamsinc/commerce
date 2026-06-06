package commerce

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Read-only aggregations for the Commerce dashboard: sales and inventory KPIs
 * derived from the stored order and product resources. Pure JCR traversal (no
 * Camunda / external calls) so it stays simple and testable; task and health
 * KPIs are assembled by the dashboard endpoint from the engine and
 * {@link Health}.
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
     * Sales snapshot over the last {@code days} days (by ingestion time): order
     * count, revenue per currency, and a breakdown by processing status. Only the
     * month folders overlapping the window are scanned.
     */
    static Map salesSummary(session, int days = 30) {
        int window = Math.max(days, 1)
        def today = LocalDate.now(ZoneId.systemDefault())
        long cutoff = today.minusDays(window - 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        long orders = 0
        def revenue = [:]   // currency -> BigDecimal
        def byStatus = [:]

        def ym = DateTimeFormatter.ofPattern("yyyy/MM")
        def month = today.minusDays(window - 1).withDayOfMonth(1)
        def lastMonth = today.withDayOfMonth(1)
        while (!month.isAfter(lastMonth)) {
            def folder = safeGet(session, "${ORDERS_RAW}/${month.format(ym)}")
            if (folder != null && folder.exists()) {
                children(folder).each { child ->
                    try {
                        if (!child.getName().endsWith(".json")) {
                            return
                        }
                        if (child.getCreated().getTime() < cutoff) {
                            return
                        }
                        orders++
                        def status = prop(child, "commerce:status") ?: "unknown"
                        byStatus[status] = ((byStatus[status] ?: 0) as long) + 1

                        def priceStr = prop(child, "commerce:total_price")
                        if (priceStr != null) {
                            def cur = prop(child, "commerce:currency") ?: "?"
                            try {
                                def amount = new BigDecimal(priceStr.trim())
                                revenue[cur] = ((revenue[cur] ?: BigDecimal.ZERO) as BigDecimal).add(amount)
                            } catch (Exception ignore) {}
                        }
                    } catch (Exception ignore) {}
                }
            }
            month = month.plusMonths(1)
        }

        // Render revenue amounts as plain strings for JSON.
        def revenueOut = [:]
        revenue.each { k, v -> revenueOut[k] = v.toPlainString() }

        return [
            from    : today.minusDays(window - 1).toString(),
            to      : today.toString(),
            days    : window,
            orders  : orders,
            revenue : revenueOut,
            byStatus: byStatus,
        ]
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
