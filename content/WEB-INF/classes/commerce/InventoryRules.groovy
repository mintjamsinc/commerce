package commerce

import java.time.LocalDate
import java.time.ZoneId

/**
 * Inventory threshold rule engine.
 *
 * Resolves an EFFECTIVE alert threshold per variant, moving beyond the original
 * "every product needs a manual threshold" model to dynamic thresholds driven by
 * product attributes (category / tag / vendor), the calendar (season) and sales
 * velocity. Precedence, highest first:
 *
 *   1. manual override  — an explicit per-variant threshold an operator set on the
 *                         product (Inventory.thresholdsByVariantId). Always wins.
 *   2. rule             — the first matching rule in inventory-rules.yml (rules are
 *                         evaluated top-down; order them most-specific first).
 *   3. default          — the config's default threshold, when present.
 *   4. none             — no threshold; the variant is not monitored (the original
 *                         behaviour when nothing is configured).
 *
 * A rule matches when ALL the criteria it specifies hold (unspecified criteria are
 * ignored); a rule with no criteria is a catch-all. Velocity is supplied by the
 * caller (populated by the sales-velocity feature); until then velocity criteria
 * simply never match, so the engine degrades gracefully.
 *
 * Pure logic over plain data (the caller parses inventory-rules.yml with the YAML
 * binding, since it is a list structure) so this class stays testable and needs
 * no parser. Lives under /content/WEB-INF/classes; use via
 * {@code import commerce.InventoryRules}.
 */
class InventoryRules {

    static final String CONFIG_PATH = "/etc/commerce/config/inventory-rules.yml"

    /**
     * Resolve effective thresholds for a product's variants.
     *
     * @param product  [ productType: String, tags: List<String>, vendor: String,
     *                   variants: [ [id: String, quantity: Integer], ... ] ]
     * @param rulesConfig  parsed inventory-rules.yml ({ default, rules: [...] }) or null
     * @param manualByVariantId  variantId(String) -> manual threshold (int)
     * @param velocityByVariantId  variantId(String) -> sales velocity (units/day); may be null
     * @param today  evaluation date for season rules (defaults to today)
     * @return Map: variantId(String) -> [ threshold: Integer|null, source: String, rule: String|null ]
     *         source is one of "manual" / "rule" / "default" / "none".
     */
    static Map resolve(Map product, Map rulesConfig, Map manualByVariantId,
                       Map velocityByVariantId = [:], LocalDate today = null) {
        def result = [:]
        def manual = manualByVariantId ?: [:]
        def velocity = velocityByVariantId ?: [:]
        def date = today ?: LocalDate.now(ZoneId.systemDefault())
        def rules = (rulesConfig?.rules instanceof List) ? rulesConfig.rules : []
        def defaultThreshold = rulesConfig?.containsKey("default") ? intOrNull(rulesConfig.default) : null

        def variants = (product?.variants instanceof List) ? product.variants : []
        variants.each { v ->
            def vid = v?.id?.toString()
            if (vid == null) {
                return
            }
            // 1. Manual override always wins.
            if (manual.containsKey(vid) && manual[vid] != null) {
                result[vid] = [threshold: (manual[vid] as int), source: "manual", rule: null]
                return
            }
            // 2. First matching rule.
            def matched = rules.find { rule -> matches(rule, product, num(velocity[vid], null), date) }
            if (matched != null && intOrNull(matched.threshold) != null) {
                result[vid] = [threshold: intOrNull(matched.threshold), source: "rule", rule: matched.name?.toString()]
                return
            }
            // 3. Default.
            if (defaultThreshold != null) {
                result[vid] = [threshold: defaultThreshold, source: "default", rule: null]
                return
            }
            // 4. Not monitored.
            result[vid] = [threshold: null, source: "none", rule: null]
        }
        return result
    }

    /** True when at least one variant resolved to a usable threshold. */
    static boolean hasEffectiveThreshold(Map resolved) {
        return resolved?.values()?.any { it?.threshold != null }
    }

    // --- Rule matching ---------------------------------------------------------

    /** A rule matches when every criterion it declares holds. */
    private static boolean matches(rule, Map product, Number variantVelocity, LocalDate date) {
        def m = rule?.match
        if (!(m instanceof Map)) {
            // No match block → catch-all rule.
            return true
        }

        if (m.containsKey("productType")) {
            if (!inList(m.productType, product?.productType)) {
                return false
            }
        }
        if (m.containsKey("vendor")) {
            if (!inList(m.vendor, product?.vendor)) {
                return false
            }
        }
        if (m.containsKey("tags")) {
            if (!anyTag(m.tags, product?.tags)) {
                return false
            }
        }
        if (m.containsKey("season")) {
            if (!inSeason(m.season, date)) {
                return false
            }
        }
        if (m.containsKey("minVelocityPerDay")) {
            def min = num(m.minVelocityPerDay, null)
            if (min == null || variantVelocity == null || variantVelocity.doubleValue() < min.doubleValue()) {
                return false
            }
        }
        return true
    }

    /** Case-insensitive membership: is {@code value} one of the configured list? */
    private static boolean inList(allowed, value) {
        if (value == null) {
            return false
        }
        def v = value.toString().trim().toLowerCase()
        return asList(allowed).any { it.toString().trim().toLowerCase() == v }
    }

    /** Does the product carry at least one of the configured tags? */
    private static boolean anyTag(allowedTags, productTags) {
        def wanted = asList(allowedTags).collect { it.toString().trim().toLowerCase() }.findAll { it }
        if (wanted.isEmpty()) {
            return false
        }
        def have = asList(productTags).collect { it.toString().trim().toLowerCase() }.findAll { it }
        return have.any { wanted.contains(it) }
    }

    /**
     * Is {@code date} within the season window? {@code season} is { from: "MM-DD",
     * to: "MM-DD" }. The window may wrap the year end (from > to, e.g. 11-15..01-15).
     */
    private static boolean inSeason(season, LocalDate date) {
        if (!(season instanceof Map)) {
            return false
        }
        Integer from = monthDay(season.from)
        Integer to = monthDay(season.to)
        if (from == null || to == null) {
            return false
        }
        int md = date.getMonthValue() * 100 + date.getDayOfMonth()
        if (from <= to) {
            return md >= from && md <= to
        }
        // Wraps the year boundary.
        return md >= from || md <= to
    }

    private static Integer monthDay(value) {
        if (value == null) {
            return null
        }
        def m = (value.toString().trim() =~ /^(\d{1,2})-(\d{1,2})$/)
        if (!m.find()) {
            return null
        }
        return (m.group(1) as int) * 100 + (m.group(2) as int)
    }

    // --- Coercion helpers ------------------------------------------------------

    private static List asList(v) {
        if (v == null) return []
        if (v instanceof List) return v
        if (v instanceof String) return v.split(",").collect { it.trim() }.findAll { it }
        return [v]
    }

    private static Integer intOrNull(v) {
        if (v == null) return null
        if (v instanceof Number) return ((Number) v).intValue()
        try { return Integer.valueOf(v.toString().trim()) } catch (Exception e) { return null }
    }

    private static Number num(v, Number dflt) {
        if (v == null) return dflt
        if (v instanceof Number) return v
        try { return new BigDecimal(v.toString().trim()) } catch (Exception e) { return dflt }
    }
}
