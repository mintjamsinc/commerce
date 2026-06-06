package commerce

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Sales velocity and stockout prediction.
 *
 * Computes, from the stored order history, how fast each variant is selling
 * (units/day over a window) and — combined with current stock — how many days
 * until it runs out. Velocity is computed by a periodic batch and CACHED to
 * {@code /content/commerce/analytics/velocity.json}, so the per-webhook inventory
 * scripts can read it cheaply and feed it to the threshold rule engine
 * (commerce.InventoryRules.minVelocityPerDay) without scanning order history.
 *
 * Velocity uses each order's {@code created_at} (falling back to the resource's
 * ingestion time) so the window reflects real sales dates. JSON is parsed with
 * jackson (like the other commerce classes). Defensive: a bad order/product is
 * skipped, never fails the whole computation.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.SalesVelocity}.
 */
class SalesVelocity {

    static final String ANALYTICS_DIR = "/content/commerce/analytics"
    static final String VELOCITY_PATH = ANALYTICS_DIR + "/velocity.json"
    static final String ORDERS_RAW = "/content/commerce/orders/raw"
    static final String PRODUCTS_DIR = "/content/commerce/products"

    private static final ObjectMapper MAPPER = new ObjectMapper()

    /**
     * Units sold per variant over the last {@code windowDays} days, from order
     * line items. Returns variantId(String) -> [ units: long, perDay: double ].
     */
    static Map computeByVariant(session, log, int windowDays) {
        int window = Math.max(windowDays, 1)
        def today = LocalDate.now(ZoneId.systemDefault())
        long cutoff = today.minusDays(window - 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        def units = [:]   // variantId -> long
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
                        def order = MAPPER.readValue(child.content.toString(), Map.class)
                        long whenMs = orderTimeMs(order, child)
                        if (whenMs < cutoff) {
                            return
                        }
                        def lineItems = order.line_items
                        if (lineItems instanceof List) {
                            lineItems.each { li ->
                                def vid = li?.variant_id
                                def qty = li?.quantity
                                if (vid != null && qty != null) {
                                    def key = vid.toString()
                                    units[key] = ((units[key] ?: 0L) as long) + (toLong(qty))
                                }
                            }
                        }
                    } catch (Exception e) {
                        try { log?.warn("SalesVelocity: skipped order ${child?.getName()}: ${e.message}") } catch (Exception ignore) {}
                    }
                }
            }
            month = month.plusMonths(1)
        }

        def out = [:]
        units.each { vid, u ->
            out[vid] = [units: u, perDay: round((u as double) / window)]
        }
        return out
    }

    /** Persist the velocity map to the cache file. */
    static void writeCache(session, int windowDays, Map byVariant) {
        def doc = [
            generatedAt: java.time.Instant.now().toString(),
            windowDays : windowDays,
            variants   : byVariant,
        ]
        def res = Jcr.getOrCreateFile(session, VELOCITY_PATH)
        res.write(Jcr.toJson(doc))
        session.commit()
    }

    /**
     * Load the cached velocity as variantId(String) -> perDay(Double) for cheap
     * use by the inventory scripts. Empty map when the cache is absent.
     */
    static Map loadPerDay(session) {
        def doc = Jcr.readMap(session, VELOCITY_PATH)
        def variants = doc?.variants
        def out = [:]
        if (variants instanceof Map) {
            variants.each { vid, v ->
                if (v instanceof Map && v.perDay != null) {
                    out[vid.toString()] = toDouble(v.perDay)
                }
            }
        }
        return out
    }

    /** Days until stockout, or null when velocity is zero/unknown (no risk). */
    static Double daysToStockout(Integer qty, Double perDay) {
        if (qty == null || perDay == null || perDay <= 0) {
            return null
        }
        return round((qty as double) / perDay)
    }

    /**
     * Scan products and return every (non-deleted) variant with its current stock
     * and velocity. Each entry:
     *   [ productId, productPath, title, variantId, variantTitle, quantity, perDay ]
     * Shared by the stockout forecast and the reorder proposer.
     */
    static List variants(session, Map perDayByVariant) {
        def perDay = perDayByVariant ?: [:]
        def out = []
        def dir = safeGet(session, PRODUCTS_DIR)
        if (dir == null || !dir.exists()) {
            return out
        }
        children(dir).each { child ->
            try {
                if (!child.getName().endsWith(".json")) {
                    return
                }
                if (prop(child, "commerce:status") == "deleted") {
                    return
                }
                def product = MAPPER.readValue(child.content.toString(), Map.class)
                def title = product?.title?.toString()
                def pid = product?.id?.toString()
                def variants = product?.variants
                if (!(variants instanceof List)) {
                    return
                }
                variants.each { v ->
                    def vid = v?.id?.toString()
                    if (vid == null) {
                        return
                    }
                    out << [
                        productId   : pid,
                        productPath : child.getPath(),
                        title       : title,
                        variantId   : vid,
                        variantTitle: v?.title?.toString(),
                        quantity    : (v?.inventory_quantity == null ? null : toInt(v.inventory_quantity)),
                        perDay      : (perDay[vid] == null ? null : toDouble(perDay[vid])),
                    ]
                }
            } catch (Exception ignore) {}
        }
        return out
    }

    /**
     * Variants predicted to run out within {@code warnDays}, soonest first. Each
     * entry adds {@code days} (days to stockout) to the {@link #variants} shape.
     */
    static List forecast(session, Map perDayByVariant, int warnDays) {
        def out = []
        variants(session, perDayByVariant).each { v ->
            Double days = daysToStockout(v.quantity, v.perDay)
            if (days != null && days <= warnDays) {
                def e = new LinkedHashMap(v)
                e.days = days
                out << e
            }
        }
        out.sort { a, b -> (a.days <=> b.days) }
        return out
    }

    // --- Helpers ---------------------------------------------------------------

    private static long orderTimeMs(Map order, resource) {
        def created = order?.created_at
        if (created != null) {
            try {
                return OffsetDateTime.parse(created.toString()).toInstant().toEpochMilli()
            } catch (Exception ignore) {}
        }
        try {
            return resource.getCreated().getTime()
        } catch (Exception ignore) {}
        return System.currentTimeMillis()
    }

    private static safeGet(session, String path) {
        try { return session.getResource(path) } catch (Exception e) { return null }
    }

    private static List children(resource) {
        def out = []
        try {
            def it = resource.list()
            while (it.hasNext()) { out << it.next() }
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

    private static long toLong(v) {
        if (v instanceof Number) return ((Number) v).longValue()
        try { return Long.parseLong(v.toString().trim()) } catch (Exception e) { return 0L }
    }

    private static Integer toInt(v) {
        if (v instanceof Number) return ((Number) v).intValue()
        try { return Integer.valueOf(v.toString().trim()) } catch (Exception e) { return null }
    }

    private static Double toDouble(v) {
        if (v instanceof Number) return ((Number) v).doubleValue()
        try { return Double.valueOf(v.toString().trim()) } catch (Exception e) { return null }
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0d
    }
}
