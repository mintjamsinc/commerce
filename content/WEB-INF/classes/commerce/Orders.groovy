package commerce

import javax.jcr.query.Query

/**
 * Reader for the first-class order store — the nested Shopify order mirror.
 *
 * One file per order under {@code /content/commerce/orders/raw/{yyyy}/{MM}/}:
 *
 *   order_{id}.json   the order (Shopify order id known)
 *
 * <b>Body = the raw Shopify order JSON only</b> (same convention as the product
 * and customer mirrors). This platform DISPLAYS Shopify's own numbers and EDITS a
 * small, safe set of order metadata (note / tags / customAttributes) through the
 * Admin API; the mirror follows via webhook. The fields promoted to TYPED,
 * auto-indexed JCR properties (so browse / facets / lookups work) are stamped by
 * the ingest route ({@code etc/eip/routes/commerce/shopify/order-paid.xml}) and
 * the refund / fulfillment / cancel scripts:
 *
 *   (a) identity : commerce:order_id (String), commerce:order_number (Long),
 *                  commerce:customer_email (String), commerce:customer_id (String)
 *   (b) money    : commerce:total_price / commerce:total_price_base (Decimal),
 *                  commerce:currency / commerce:base_currency (String),
 *                  commerce:refunded_amount (Decimal), commerce:refund_count (Long)
 *   (c) lifecycle: commerce:status (integration lifecycle) / commerce:source_status
 *                  (Shopify financial_status), commerce:ordered_at (Date — the
 *                  business date, from created_at), commerce:cancelled_at /
 *                  commerce:fulfilled_at (Date), commerce:tracking_number /
 *                  commerce:tracking_company (String)
 *
 * commerce:order_number is the typed Long chronological key the browser sorts by
 * (descending); commerce:ordered_at is the index-backed date axis for range
 * filtering, and commerce:customer_id the customer drill-down axis. Unlike the
 * flat customer store, orders are NESTED by year/month, so lookups / walks go
 * through an XPath query, not a folder list.
 *
 * JCR methods are defensive. Lives under /content/WEB-INF/classes; use via
 * {@code import commerce.Orders}.
 */
class Orders {

    static final String STORE_DIR = "/content/commerce/orders/raw"
    static final String ORDER_MIME = "application/vnd.mintjams.commerce.order+json"

    // Bound the walk backing the (admin) search view — the store is one node per
    // order and grows unbounded, so cap the scan like the browse endpoint.
    private static final int SEARCH_SCAN_CAP = 5000

    /**
     * Locate the original order by its node name (order_{id}.json) under
     * /content/commerce/orders/raw. Returns the Resource, or null when the id is
     * blank or no matching order exists. `session` is the script's
     * repositorySession. Query/lookup errors propagate to the caller, which is
     * expected to treat order resolution as best-effort.
     */
    static Object findResource(session, orderId) {
        if (!orderId) {
            return null
        }
        def fileName = "order_${orderId}.json"
        def stmt = "/jcr:root/content/commerce/orders/raw//${fileName}"
        def query = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
        query.limit(1)
        def resources = query.execute().getResources()
        return (resources != null && resources.length > 0) ? resources[0] : null
    }

    /**
     * Read total_price from an already-parsed order map as a BigDecimal, or null
     * when it is absent / unparseable.
     */
    static BigDecimal totalPrice(order) {
        return Money.toNumber(order?.total_price)
    }

    // --- Lookup / queries (endpoint) -----------------------------------------------

    /**
     * Order id from a store key / node name (order_{id}[.json]), a bare numeric
     * id, or the wire GID (gid://shopify/Order/{id} — clients pass ids back in
     * the GID form; the numeric form is peeled HERE, never client-side). Null
     * when blank.
     */
    static String idFor(key) {
        if (key == null) return null
        def s = key.toString().trim()
        if (s.isEmpty()) return null
        s = Api.legacyId(s)
        if (s.endsWith(".json")) s = s.substring(0, s.length() - 5)
        if (s.startsWith("order_")) s = s.substring("order_".length())
        return s.isEmpty() ? null : s
    }

    /**
     * One order's record (props + raw mirror body) by order id or store key. Empty
     * map when absent. The node is located by id via {@link #findResource} (the
     * nested store has no fixed path) and the body is read via {@link Jcr#readMap}.
     */
    static Map read(session, key) {
        def id = idFor(key)
        if (id == null) return [:]
        def res = findResource(session, id)
        if (res == null || !res.exists()) return [:]
        def out = row(session, res)
        out.body = Jcr.readMap(session, res.getPath())
        return out
    }

    /**
     * The endpoint row shape for one order node (identity + money + lifecycle
     * props), WIRE-SHAPED per the commerce.Api contract:
     *   - id is the Shopify GID (the numeric commerce:order_id never leaves
     *     the orchestration layer),
     *   - money rides as { currency, amount } objects with NUMBER amounts,
     *   - counts are numbers, timestamps are ms-precision ISO-8601.
     */
    static Map row(session, res) {
        def currency = propStr(res, "commerce:currency")
        def baseCurrency = propStr(res, "commerce:base_currency")
        return [
            id             : Api.gid("Order", propStr(res, "commerce:order_id")),
            path           : res.getPath(),
            orderNumber    : Api.count(propVal(res, "commerce:order_number")),
            customerEmail  : propStr(res, "commerce:customer_email"),
            customerId     : Api.gid("Customer", propStr(res, "commerce:customer_id")),
            orderedAt      : Api.instant(propVal(res, "commerce:ordered_at")),
            totalPrice     : Api.money(currency, propVal(res, "commerce:total_price")),
            totalPriceBase : Api.money(baseCurrency, propVal(res, "commerce:total_price_base")),
            refundedAmount : Api.money(currency, propVal(res, "commerce:refunded_amount")),
            refundCount    : Api.count(propVal(res, "commerce:refund_count")) ?: 0L,
            status         : propStr(res, "commerce:status"),
            sourceStatus   : propStr(res, "commerce:source_status"),
            cancelledAt    : Api.instant(propVal(res, "commerce:cancelled_at")),
            fulfilledAt    : Api.instant(propVal(res, "commerce:fulfilled_at")),
            trackingNumber : propStr(res, "commerce:tracking_number"),
            trackingCompany: propStr(res, "commerce:tracking_company"),
        ]
    }

    /**
     * Partial-match order search over order number / customer email / order id /
     * store node name (case-insensitive contains), newest order number first. The
     * store is nested, so this walks raw//element(*, nt:file) via a query (capped)
     * and filters in memory — it backs an admin UI.
     */
    static List search(session, String query, int limit) {
        def q = query == null ? "" : query.trim().toLowerCase()
        if (q.isEmpty()) return []
        def rows = []
        eachOrder(session) { res ->
            try {
                def hay = [propStr(res, "commerce:order_number"), propStr(res, "commerce:customer_email"),
                           propStr(res, "commerce:order_id"), res.getName()]
                if (hay.any { it != null && it.toLowerCase().contains(q) }) {
                    rows << row(session, res)
                }
            } catch (Exception ignore) {}
        }
        // order_number is the typed Long chronological key; sort numerically descending
        // (newest first), the same ordering the browse view applies server-side.
        rows.sort { a, b -> longOf(b.orderNumber) <=> longOf(a.orderNumber) }
        return limit > 0 && rows.size() > limit ? rows.subList(0, limit) : rows
    }

    // --- Internals -------------------------------------------------------------------

    /** Walk the member order files (order_{id}.json) under the nested store, capped. */
    private static void eachOrder(session, Closure cb) {
        try {
            def stmt = "/jcr:root${STORE_DIR}//element(*, nt:file)"
            def q = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
            q.limit((long) SEARCH_SCAN_CAP)
            def resources = q.execute().getResources()
            if (resources == null) return
            resources.each { res ->
                try { if (res.getName() ==~ /order_\d+\.json/) cb(res) } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
    }

    // Property readers tolerant of typed values (Date/Calendar/Number) AND legacy
    // String values.
    private static Object propVal(res, String name) {
        try { if (res.hasProperty(name)) return res.getProperty(name).getValue() } catch (Exception ignore) {}
        return null
    }

    private static String propStr(res, String name) {
        def v = propVal(res, name)
        return v == null ? null : v.toString()
    }

    private static long longOf(v) {
        if (v == null) return 0L
        try { return v.toString().trim() as long } catch (Exception ignore) { return 0L }
    }
}
