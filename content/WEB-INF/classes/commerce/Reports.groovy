package commerce

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reporting & audit export (category G, #25).
 *
 * Turns the audit trails the platform already keeps in JCR into operator-facing
 * reports:
 *   - sales      — daily orders + revenue (per currency) and top products, from the
 *                  stored order resources (/content/commerce/orders/raw).
 *   - operations — the outbound-write audit trail (/content/commerce/sync, #2):
 *                  who pushed what to Shopify, when, and the outcome.
 *
 * Pure JCR traversal, defensive (a bad resource is skipped, never thrown). The
 * endpoint renders these as JSON or CSV. Lives under /content/WEB-INF/classes; use
 * via {@code import commerce.Reports}.
 */
class Reports {

    static final String ORDERS_DIR = "/content/commerce/orders/raw"
    static final String SYNC_DIR = "/content/commerce/sync"

    private static final ObjectMapper MAPPER = new ObjectMapper()
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy/MM")

    /**
     * Sales over the last {@code days}, by day. Shape:
     *   { days, from, to, totals: { orders, revenue: { CUR: amount } },
     *     daily: [ { date, orders, revenue: { CUR: amount } } ],
     *     topProducts: [ { key, title, sku, quantity, revenue, currency } ] }
     */
    static Map sales(session, int days) {
        long cutoff = System.currentTimeMillis() - (long) days * 86_400_000L
        def daily = new TreeMap()                 // date -> [orders, revenue: {cur: BigDecimal}]
        def totalsRevenue = [:]                   // cur -> BigDecimal
        long totalOrders = 0
        def products = [:]                        // key -> [title, sku, qty, revenue, currency]

        eachOrderFile(session, days) { res ->
            def order = parse(res)
            if (order == null) return
            long t = orderTimeMs(order, res)
            if (t < cutoff) return

            String date = dateOf(order, res)
            def cur = order.currency?.toString() ?: ""
            def total = Money.toNumber(order.total_price) ?: BigDecimal.ZERO

            totalOrders++
            addRevenue(totalsRevenue, cur, total)

            def d = daily.get(date)
            if (d == null) { d = [orders: 0L, revenue: [:]]; daily.put(date, d) }
            d.orders = ((d.orders ?: 0L) as long) + 1L
            addRevenue(d.revenue, cur, total)

            def items = order.line_items
            if (items instanceof List) {
                items.each { li ->
                    def key = (li?.sku ?: li?.title ?: "item").toString()
                    def qty = Money.toNumber(li?.quantity) ?: BigDecimal.ZERO
                    def price = Money.toNumber(li?.price) ?: BigDecimal.ZERO
                    def rev = price.multiply(qty)
                    def p = products.get(key)
                    if (p == null) {
                        p = [key: key, title: li?.title?.toString(), sku: li?.sku?.toString(),
                             quantity: BigDecimal.ZERO, revenue: BigDecimal.ZERO, currency: cur]
                        products.put(key, p)
                    }
                    p.quantity = p.quantity.add(qty)
                    p.revenue = p.revenue.add(rev)
                }
            }
        }

        def dailyList = daily.collect { date, v ->
            [date: date, orders: v.orders, revenue: stringifyRevenue(v.revenue)]
        }
        def topProducts = products.values().toList()
            .sort { a, b -> b.revenue <=> a.revenue }
            .take(20)
            .collect { [key: it.key, title: it.title, sku: it.sku,
                        quantity: it.quantity.toBigInteger().toString(),
                        revenue: it.revenue.toString(), currency: it.currency] }

        return [
            days       : days,
            to         : java.time.Instant.now().toString(),
            from       : java.time.Instant.ofEpochMilli(cutoff).toString(),
            totals     : [orders: totalOrders, revenue: stringifyRevenue(totalsRevenue)],
            daily      : dailyList,
            topProducts: topProducts,
        ]
    }

    /**
     * The outbound-write audit trail over the last {@code days}, newest first. Each
     * row: { at, action, status, error, request }. {@code statusFilter} null = all.
     */
    static List operations(session, int days, String statusFilter, int limit) {
        long cutoff = System.currentTimeMillis() - (long) days * 86_400_000L
        def rows = []
        eachJsonFile(session, SYNC_DIR, days) { res ->
            def rec = parse(res)
            if (rec == null) return
            long t = createdMs(res)
            if (t > 0 && t < cutoff) return
            if (statusFilter != null && rec.status?.toString() != statusFilter) return
            rows << [
                at    : rec.at ?: prop(res, "commerce:created_at"),
                action: rec.action,
                status: rec.status,
                error : rec.error,
                request: rec.request,
            ]
        }
        rows.sort { a, b -> (b.at?.toString() ?: "") <=> (a.at?.toString() ?: "") }
        return limit > 0 && rows.size() > limit ? rows.subList(0, limit) : rows
    }

    // --- Traversal -------------------------------------------------------------

    // Walk order files in the month folders covering the last `days`.
    private static void eachOrderFile(session, int days, Closure cb) {
        eachJsonInMonths(session, ORDERS_DIR, days, cb)
    }

    // Walk *.json files under dir/{yyyy}/{MM} for the months covering the last days.
    private static void eachJsonFile(session, String dir, int days, Closure cb) {
        eachJsonInMonths(session, dir, days, cb)
    }

    private static void eachJsonInMonths(session, String dir, int days, Closure cb) {
        def today = LocalDate.now(ZoneId.systemDefault())
        def start = today.minusDays(Math.max(0, days))
        def seen = [] as Set
        // Iterate each month from start..today inclusive.
        def cursor = start.withDayOfMonth(1)
        while (!cursor.isAfter(today)) {
            def ym = cursor.format(YM)
            if (seen.add(ym)) {
                def folder = safeGet(session, "${dir}/${ym}")
                if (folder != null && folder.exists()) {
                    def it = folder.list()
                    while (it.hasNext()) {
                        def c = it.next()
                        try { if (c.getName().endsWith(".json")) cb(c) } catch (Exception ignore) {}
                    }
                }
            }
            cursor = cursor.plusMonths(1)
        }
    }

    // --- Helpers ---------------------------------------------------------------

    private static void addRevenue(Map revenue, String cur, BigDecimal amount) {
        if (amount == null) return
        def prev = revenue[cur] ?: BigDecimal.ZERO
        revenue[cur] = prev.add(amount)
    }

    private static Map stringifyRevenue(Map revenue) {
        def out = [:]
        revenue.each { k, v -> out[k] = (v as BigDecimal).toString() }
        return out
    }

    private static long orderTimeMs(Map order, res) {
        def created = order?.created_at
        if (created != null) {
            try { return java.time.OffsetDateTime.parse(created.toString()).toInstant().toEpochMilli() } catch (Exception ignore) {}
        }
        return createdMs(res)
    }

    private static String dateOf(Map order, res) {
        def created = order?.created_at?.toString()
        if (created != null && created.length() >= 10) return created.substring(0, 10)
        try { return new java.text.SimpleDateFormat("yyyy-MM-dd").format(res.getCreated().getTime()) } catch (Exception e) { return "unknown" }
    }

    private static Map parse(res) {
        try {
            def c = res.content?.toString()
            return (c == null || c.trim().isEmpty()) ? null : MAPPER.readValue(c, Map.class)
        } catch (Exception e) { return null }
    }

    private static safeGet(session, String path) {
        try { return session.getResource(path) } catch (Exception e) { return null }
    }

    private static long createdMs(res) {
        try { return res.getCreated().getTime() } catch (Exception e) { return 0L }
    }

    private static String prop(res, String name) {
        try { if (res.hasProperty(name)) return res.getProperty(name).getValue()?.toString() } catch (Exception ignore) {}
        return null
    }
}
