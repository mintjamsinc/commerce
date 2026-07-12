package commerce

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The wire mapper — the single normalization gate every admin/public endpoint
 * passes its JSON through on the way OUT (and applies to ids on the way IN).
 *
 * Storage keeps its own conventions (raw Shopify mirror bodies stay
 * source-faithful snake_case; commerce:* props stay snake_case query axes) —
 * this class owns the CONTRACT BETWEEN THE ORCHESTRATION LAYER AND THE
 * FRONTEND:
 *
 *   1. Money / quantities are JSON numbers, never strings. Money always rides
 *      as a {currency, amount} object ({@link #money}) or a
 *      [{currency, amount}] array ({@link #moneyList}) — multi-currency-ready,
 *      zero-decimal-currency-safe (no ".0" drift; {@link #num}).
 *   2. Ids are Shopify GIDs (gid://shopify/{Type}/{id}) — {@link #gid}. The
 *      numeric REST form never leaves the orchestration layer; peeling a GID
 *      back to numeric for storage lookups / REST calls happens ONLY here
 *      ({@link #legacyId}), never in the frontend.
 *   3. Timestamps are millisecond-precision UTC ISO-8601
 *      (yyyy-MM-dd'T'HH:mm:ss.SSS'Z') — {@link #instant}/{@link #now}. Never
 *      emit Instant.now().toString() on the wire: its fraction digits vary
 *      (0/3/6/9) with the clock value and break naive frontend parsing.
 *   4. Keys are camelCase — {@link #camel}/{@link #camelize} convert the
 *      snake_case storage rows at the exit. Enum-ish string values on the wire
 *      are camelCase too ({@link #camelValue}).
 *
 * Design: every method is static and pure — no script bindings, no state,
 * null-tolerant (null in → null out, so "absent" survives the mapping instead
 * of exploding).
 */
class Api {

    // -------------------------------------------------------------------------
    // 3. Timestamps — millisecond-precision UTC ISO-8601
    // -------------------------------------------------------------------------

    /** The one wire timestamp format: yyyy-MM-dd'T'HH:mm:ss.SSS'Z' (UTC, always 3 fraction digits). */
    private static final DateTimeFormatter WIRE_INSTANT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Now, in the wire format. Use instead of Instant.now().toString(). */
    static String now() {
        return WIRE_INSTANT.format(Instant.now())
    }

    /**
     * Any timestamp-ish value → the wire format (UTC, ms precision), or null.
     * Accepts Instant / Date / Calendar / Number (epoch ms) / ISO-8601 strings
     * of any precision or zone (nanosecond Shopify strings, second-precision
     * REST strings, offset forms — all collapse to one shape).
     */
    static String instant(Object v) {
        Long ms = epochMs(v)
        return ms == null ? null : WIRE_INSTANT.format(Instant.ofEpochMilli(ms))
    }

    /** Any timestamp-ish value → java.util.Date (typed JCR Date properties), or null. */
    static java.util.Date date(Object v) {
        Long ms = epochMs(v)
        return ms == null ? null : new java.util.Date(ms)
    }

    /** Any timestamp-ish value → epoch ms, or null when absent/unparseable. */
    static Long epochMs(Object v) {
        if (v == null) return null
        if (v instanceof Instant) return v.toEpochMilli()
        if (v instanceof java.util.Calendar) return v.getTimeInMillis()
        if (v instanceof java.util.Date) return v.getTime()
        if (v instanceof Number) return ((Number) v).longValue()
        def s = v.toString().trim()
        if (s.isEmpty()) return null
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (Exception ignore) {}
        try { return Instant.parse(s).toEpochMilli() } catch (Exception ignore) {}
        return null
    }

    // -------------------------------------------------------------------------
    // 1. Numbers & money
    // -------------------------------------------------------------------------

    /**
     * Any numeric-ish value → a clean JSON number, or null. A whole value
     * serializes as an integer (1385, never "1385.0" — zero-decimal currencies
     * like JPY render clean); a fractional value keeps its trimmed decimals.
     * Strings parse ("1385.0" → 1385); unparseable → null.
     */
    static Number num(Object v) {
        if (v == null) return null
        BigDecimal d
        try { d = (v instanceof BigDecimal) ? (BigDecimal) v : new BigDecimal(v.toString().trim()) }
        catch (Exception ignore) { return null }
        d = d.stripTrailingZeros()
        return d.scale() <= 0 ? (Number) d.toBigInteger() : (Number) d
    }

    /** Like {@link #num} but with a default for absent/unparseable values. */
    static Number num(Object v, Number dflt) {
        def n = num(v)
        return n == null ? dflt : n
    }

    /** Any integer-ish value → Long, or null (counts, quantities, attempts). */
    static Long count(Object v) {
        def n = num(v)
        return n == null ? null : n.longValue()
    }

    /**
     * The wire money shape: { currency: "JPY", amount: 1385 } (amount always a
     * clean number). Null when the amount is absent/unparseable — a money field
     * is whole or absent, never half-filled.
     */
    static Map money(Object currency, Object amount) {
        def n = num(amount)
        if (n == null) return null
        return [currency: currency == null ? null : currency.toString(), amount: n]
    }

    /**
     * A {currency → amount} map → the stable wire array
     * [ { currency, amount } ] (multi-currency loops stay trivial client-side).
     */
    static List moneyList(Map byCurrency) {
        def out = []
        if (byCurrency != null) {
            byCurrency.each { k, v ->
                def m = money(k, v)
                if (m != null) out << m
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // 2. Ids — Shopify GID on the wire, numeric only inside this layer
    // -------------------------------------------------------------------------

    private static final java.util.regex.Pattern NUMERIC = ~/^\d+$/
    private static final java.util.regex.Pattern GID = ~/^gid:\/\/[^\/]+\/[^\/]+\/.+$/

    /**
     * Canonical wire id: gid://shopify/{type}/{id}.
     *   - already a GID → returned as-is (idempotent),
     *   - numeric (REST form) → prefixed,
     *   - anything else (cart/checkout tokens, emails) → returned unchanged —
     *     only real Shopify numeric ids are GID-shaped,
     *   - null/blank → null.
     * Never build "gid://shopify/..." by string concatenation anywhere else.
     */
    static String gid(String type, Object id) {
        if (id == null) return null
        def s = id.toString().trim()
        if (s.isEmpty()) return null
        if (GID.matcher(s).matches()) return s
        if (type != null && NUMERIC.matcher(s).matches()) return "gid://shopify/${type}/${s}".toString()
        return s
    }

    /**
     * The numeric tail of a GID ("gid://shopify/Order/123" → "123"); non-GID
     * values pass through. INTERNAL USE ONLY (storage keys, REST paths, XPath
     * predicates) — this value must never appear on the wire.
     */
    static String legacyId(Object id) {
        if (id == null) return null
        def s = id.toString().trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("gid://")) return s
        int q = s.indexOf('?')                        // gid may carry a query part
        if (q >= 0) s = s.substring(0, q)
        int i = s.lastIndexOf('/')
        return i >= 0 ? s.substring(i + 1) : s
    }

    /** The {Type} segment of a GID ("gid://shopify/Order/123" → "Order"), else null. */
    static String gidType(Object id) {
        if (id == null) return null
        def s = id.toString().trim()
        if (!s.startsWith("gid://")) return null
        def parts = s.split("/")
        return parts.length >= 4 ? parts[3] : null
    }

    /**
     * GID type for an entity/collection word ("orders" → "Order",
     * "inventory_levels" → "InventoryLevel"). For the event/entity stores whose
     * type arrives as a plural snake_case collection name.
     */
    static String gidTypeFor(String collection) {
        if (collection == null) return null
        def s = collection.trim()
        if (s.isEmpty()) return null
        if (s.endsWith("ies")) s = s.substring(0, s.length() - 3) + "y"
        else if (s.endsWith("s") && !s.endsWith("ss")) s = s.substring(0, s.length() - 1)
        return s.split("[_\\-]").collect { it.isEmpty() ? it : it[0].toUpperCase() + it.substring(1) }.join("")
    }

    // -------------------------------------------------------------------------
    // 4. Keys — camelCase on the wire
    // -------------------------------------------------------------------------

    /** snake_case / kebab-case → camelCase ("entity_id" → "entityId", "customers-backfill" → "customersBackfill"). */
    static String camel(String key) {
        if (key == null) return null
        if (!(key.contains('_') || key.contains('-'))) return key
        def parts = key.split("[_\\-]") as List
        def head = parts.remove(0)
        return head + parts.collect { it.isEmpty() ? it : it[0].toUpperCase() + it.substring(1) }.join("")
    }

    /** Alias of {@link #camel} for VALUES that are enum-ish identifiers (job types, lanes). */
    static String camelValue(Object v) {
        return v == null ? null : camel(v.toString())
    }

    /**
     * Deep-convert every Map key in a Map/List tree to camelCase (values are
     * untouched). For rows assembled from storage where authoring each key by
     * hand is impractical. Do NOT run raw Shopify payload passthroughs through
     * this — raw bodies are source-faithful by design and documented as such.
     */
    static Object camelize(Object v) {
        if (v instanceof Map) {
            def out = new LinkedHashMap()
            v.each { k, val -> out[camel(k?.toString())] = camelize(val) }
            return out
        }
        if (v instanceof List) return v.collect { camelize(it) }
        return v
    }
}
