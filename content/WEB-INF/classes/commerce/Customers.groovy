package commerce

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Customer data normalization + segmentation (category D, #13 / #15).
 *
 * Rolls up every customer's purchase history from the stored order resources
 * (/content/commerce/orders/raw) — the authoritative record of what was actually
 * bought — and classifies each customer into a segment. The rollup is keyed by the
 * order's customer identity (Shopify customer id when present, else the email), so
 * it does not depend on customers/* webhooks having been received (those enrich the
 * entity mirror separately).
 *
 * Results live in a dedicated CRM store, one doc per customer:
 *   /content/commerce/crm/customers/{key}.json   ( id_{id} | email_{hash} )
 * with commerce:* properties (segment, vip, recency, orders, total_spent,
 * last_order_at, email) so the endpoint and alerts can read them cheaply.
 *
 * {@link #segment} is pure (testable); the JCR traversal / persistence is defensive.
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.Customers}.
 */
class Customers {

    static final String ORDERS_DIR = "/content/commerce/orders/raw"
    static final String CRM_DIR = "/content/commerce/crm/customers"

    private static final ObjectMapper MAPPER = new ObjectMapper()

    // --- Aggregation -----------------------------------------------------------

    /**
     * Roll up purchase history per customer from all stored orders. Returns
     * customerKey → stats:
     *   { key, customerId, email, name, orders, totalSpent(BigDecimal), currency,
     *     firstOrderAtMs, lastOrderAtMs, lastOrderAt }
     * Defensive: a bad order is skipped.
     */
    static Map aggregate(session) {
        def out = [:]
        eachOrder(session) { order ->
            def key = customerKey(order)
            if (key == null) return

            def stats = out[key]
            if (stats == null) {
                stats = [key: key, customerId: customerId(order), email: email(order),
                         name: name(order), orders: 0L, totalSpent: BigDecimal.ZERO,
                         currency: null, firstOrderAtMs: Long.MAX_VALUE, lastOrderAtMs: 0L,
                         lastOrderAt: null]
                out[key] = stats
            }
            stats.orders = ((stats.orders ?: 0L) as long) + 1L
            def total = Money.toNumber(order.total_price)
            if (total != null) stats.totalSpent = ((BigDecimal) stats.totalSpent).add(total)
            def cur = order.currency?.toString()
            if (cur) stats.currency = cur
            if (!stats.email) stats.email = email(order)
            if (!stats.name) stats.name = name(order)

            long t = orderTimeMs(order)
            if (t > 0) {
                if (t < (stats.firstOrderAtMs as long)) stats.firstOrderAtMs = t
                if (t > (stats.lastOrderAtMs as long)) {
                    stats.lastOrderAtMs = t
                    stats.lastOrderAt = order.created_at?.toString()
                }
            }
        }
        return out
    }

    // --- Classification (pure) -------------------------------------------------

    /**
     * Classify a customer from their stats + thresholds. Returns:
     *   { segment, vip(boolean), recency }  where
     *   segment  : dormant | at_risk | vip | new | repeat   (primary, by priority)
     *   recency  : active | at_risk | dormant
     *   vip is an orthogonal value flag (so a dormant VIP is still flagged vip).
     *
     * cfg keys (with defaults): vipMinSpend(100000), vipMinOrders(10),
     * newMaxOrders(1), atRiskDays(60), dormantDays(120).
     */
    static Map segment(Map stats, Map cfg, long nowMs) {
        def c = cfg ?: [:]
        def vipMinSpend = num(c.vipMinSpend, 100000)
        int vipMinOrders = intOr(c.vipMinOrders, 10)
        int newMaxOrders = intOr(c.newMaxOrders, 1)
        int atRiskDays = intOr(c.atRiskDays, 60)
        int dormantDays = intOr(c.dormantDays, 120)

        long orders = (stats?.orders ?: 0L) as long
        def spent = (stats?.totalSpent ?: BigDecimal.ZERO) as BigDecimal
        long lastMs = (stats?.lastOrderAtMs ?: 0L) as long
        long ageDays = lastMs > 0 ? (long) ((nowMs - lastMs) / 86_400_000L) : Long.MAX_VALUE

        boolean vip = (vipMinSpend != null && spent.compareTo(vipMinSpend) >= 0) || (orders >= vipMinOrders)
        String recency = ageDays >= dormantDays ? "dormant" : (ageDays >= atRiskDays ? "at_risk" : "active")

        String primary
        if (recency == "dormant") primary = "dormant"
        else if (recency == "at_risk") primary = "at_risk"
        else if (vip) primary = "vip"
        else if (orders <= newMaxOrders) primary = "new"
        else primary = "repeat"

        return [segment: primary, vip: vip, recency: recency]
    }

    // --- Persistence -----------------------------------------------------------

    /** Read the stored CRM record for a key (empty map if none). Defensive. */
    static Map read(session, String key) {
        return Jcr.readMap(session, "${CRM_DIR}/${key}.json")
    }

    /**
     * Write the CRM record (stats + classification). Returns the previous segment
     * snapshot { segment, vip, recency } (empty when new) so the caller can detect
     * transitions for alerting (#15). Defensive — never throws.
     */
    static Map write(session, log, Map stats, Map classification) {
        def prev = [:]
        try {
            def key = stats.key.toString()
            def path = "${CRM_DIR}/${key}.json".toString()
            def existing = Jcr.readMap(session, path)
            prev = [segment: existing.segment, vip: existing.vip, recency: existing.recency]

            def now = java.time.Instant.now().toString()
            def aov = ((stats.orders ?: 0L) as long) > 0 ?
                ((BigDecimal) stats.totalSpent).divide(new BigDecimal((long) stats.orders), 2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO
            def record = [
                key       : key,
                customerId: stats.customerId,
                email     : stats.email,
                name      : stats.name,
                orders    : stats.orders,
                totalSpent: ((BigDecimal) stats.totalSpent).toString(),
                currency  : stats.currency,
                aov       : aov.toString(),
                firstOrderAt: stats.firstOrderAtMs && (stats.firstOrderAtMs as long) != Long.MAX_VALUE ? java.time.Instant.ofEpochMilli(stats.firstOrderAtMs as long).toString() : null,
                lastOrderAt : stats.lastOrderAt,
                segment   : classification.segment,
                vip       : classification.vip,
                recency   : classification.recency,
                updatedAt : now,
            ]
            def res = Jcr.getOrCreateFile(session, path)
            res.write(MAPPER.writeValueAsString(record))
            res.setProperty("commerce:segment", str(classification.segment))
            res.setProperty("commerce:vip", String.valueOf(classification.vip))
            res.setProperty("commerce:recency", str(classification.recency))
            res.setProperty("commerce:orders", String.valueOf(stats.orders))
            res.setProperty("commerce:total_spent", ((BigDecimal) stats.totalSpent).toString())
            if (stats.email) res.setProperty("commerce:email", stats.email.toString())
            if (record.lastOrderAt) res.setProperty("commerce:last_order_at", record.lastOrderAt.toString())
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Customers.write: ${e.message}") } catch (Exception ignore) {}
        }
        return prev
    }

    // --- Queries (for the endpoint) -------------------------------------------

    /** Counts by segment + vip + recency across the CRM store. Defensive. */
    static Map summary(session) {
        def bySegment = [:]
        def byRecency = [:]
        long total = 0, vip = 0
        eachCrm(session) { res ->
            try {
                total++
                def seg = prop(res, "commerce:segment") ?: "unknown"
                bySegment[seg] = ((bySegment[seg] ?: 0L) as long) + 1L
                def rec = prop(res, "commerce:recency") ?: "unknown"
                byRecency[rec] = ((byRecency[rec] ?: 0L) as long) + 1L
                if (prop(res, "commerce:vip") == "true") vip++
            } catch (Exception ignore) {}
        }
        return [total: total, vip: vip, bySegment: bySegment, byRecency: byRecency]
    }

    /** CRM records, optionally filtered by segment, highest spend first. */
    static List list(session, String segment, int limit) {
        def rows = []
        eachCrm(session) { res ->
            try {
                if (segment != null && prop(res, "commerce:segment") != segment) return
                rows << Jcr.readMap(session, res.getPath())
            } catch (Exception ignore) {}
        }
        rows.sort { a, b -> num(b.totalSpent, 0) <=> num(a.totalSpent, 0) }
        return limit > 0 && rows.size() > limit ? rows.subList(0, limit) : rows
    }

    // --- Traversal helpers -----------------------------------------------------

    private static void eachOrder(session, Closure cb) {
        def base = Jcr.safeGet(session, ORDERS_DIR)
        if (base == null || !base.exists()) return
        children(base).each { y ->
            if (!(y.getName() ==~ /\d{4}/)) return
            children(y).each { m ->
                if (!(m.getName() ==~ /\d{1,2}/)) return
                children(m).each { f ->
                    try {
                        if (f.getName().endsWith(".json")) {
                            def order = MAPPER.readValue(f.content.toString(), Map.class)
                            if (order != null) cb(order)
                        }
                    } catch (Exception ignore) {}
                }
            }
        }
    }

    private static void eachCrm(session, Closure cb) {
        def base = Jcr.safeGet(session, CRM_DIR)
        if (base == null || !base.exists()) return
        def it = base.list()
        while (it.hasNext()) {
            def c = it.next()
            try { if (c.getName().endsWith(".json")) cb(c) } catch (Exception ignore) {}
        }
    }

    private static List children(resource) {
        def out = []
        try { def it = resource.list(); while (it.hasNext()) out << it.next() } catch (Exception ignore) {}
        return out
    }

    // --- Order field extraction ------------------------------------------------

    private static String customerKey(order) {
        def id = customerId(order)
        if (id) return "id_${id}".toString()
        def em = email(order)
        if (em) return "email_${sanitize(em.toLowerCase())}".toString()
        return null
    }

    private static String customerId(order) {
        def id = order?.customer?.id
        return id == null ? null : id.toString()
    }

    private static String email(order) {
        def em = order?.contact_email ?: order?.email ?: order?.customer?.email
        return (em == null || em.toString().trim().isEmpty()) ? null : em.toString().trim()
    }

    private static String name(order) {
        def c = order?.customer
        if (c instanceof Map) {
            def n = [c.first_name, c.last_name].findAll { it }.join(" ").trim()
            if (n) return n
        }
        return null
    }

    private static long orderTimeMs(order) {
        def created = order?.created_at
        if (created != null) {
            try { return java.time.OffsetDateTime.parse(created.toString()).toInstant().toEpochMilli() } catch (Exception ignore) {}
        }
        return 0L
    }

    private static String prop(res, String name) {
        try { if (res.hasProperty(name)) return res.getProperty(name).getValue()?.toString() } catch (Exception ignore) {}
        return null
    }

    private static BigDecimal num(v, dflt) {
        def n = Money.toNumber(v)
        return n != null ? n : (dflt == null ? null : new BigDecimal(dflt.toString()))
    }

    private static int intOr(v, int dflt) {
        if (v == null) return dflt
        try { return v.toString().trim() as int } catch (Exception e) { return dflt }
    }

    private static String sanitize(String s) { s == null ? "" : s.replaceAll("[^A-Za-z0-9_.-]", "_") }
    private static String str(v) { v == null ? "" : v.toString() }
}
