package commerce

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Planning layer — per-variant explicit planning value. After velocity was fully
 * removed, the ONLY planning value is a fixed
 * {@code threshold} (unit count), held statically per variant. Replaces the retired
 * InventoryRules rule engine and the retired velocity/ROP proposal.
 *
 * <h2>Model</h2>
 * The value is EXPLICIT per variant, stored in the product's PIM overlay under
 * {@code pim.planning} (variantId → { threshold: value }), the same
 * single-String-JSON-property convention as {@code inventory_level_config}.
 * Resolution:
 *
 *   per-variant set value  →  global default (planning.yml `defaults:`)  →  none
 *
 * (A legacy per-variant manual override written by the Set Inventory Threshold form —
 * {@code inventory_level_config} — slots in between, so existing onboarding keeps working.)
 *
 * <h2>Semantics</h2>
 * {@code threshold} is a FIXED count. When the materialized stock total drops below it,
 * the event-driven sweep raises ONE "stock check + reorder" task (stock &lt; fixed threshold). The
 * system never derives or writes it (no velocity, no ROP formula, no proposal) — the
 * operator sets it (per-variant, or via the bulk-set screen). Only parameter: threshold.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.Planning}.
 */
class Planning {

    static final String CONFIG_PATH = "/etc/commerce/config/planning.yml"

    static final List PARAMS = ["threshold"]

    private static final ObjectMapper MAPPER = new ObjectMapper()

    // --- Config -----------------------------------------------------------------

    /** Parsed planning.yml (empty map when absent). Classes cannot use the YAML binding. */
    static Map config(session) {
        try {
            def res = session.getResource(CONFIG_PATH)
            if (res == null || !res.exists()) return [:]
            return SimpleYaml.parse(res.content?.toString()) ?: [:]
        } catch (Exception e) {
            return [:]
        }
    }

    /** The global default for a parameter (planning.yml `defaults:`), or null. */
    static Integer defaultFor(Map cfg, String param) {
        def d = cfg?.defaults
        return (d instanceof Map) ? intOrNull(d[param]) : null
    }

    // --- Per-variant set values ---------------------------------------------------

    /** The pim.planning map (variantId → { param: value }) of a product node. Defensive. */
    static Map planningByVariant(resource) {
        try {
            if (resource == null || !resource.hasProperty(Pim.PIM_PROPERTY)) return [:]
            def pim = MAPPER.readValue(resource.getProperty(Pim.PIM_PROPERTY).getValue().toString(), Map.class)
            def planning = pim?.planning
            return (planning instanceof Map) ? planning : [:]
        } catch (Exception e) {
            return [:]
        }
    }

    /**
     * Resolve every parameter for one variant: param → [value: Integer|null,
     * source: "variant"|"manual"|"default"|"none"]. {@code resource} is the
     * product node; {@code cfg} the parsed planning.yml.
     */
    static Map resolve(resource, String variantId, Map cfg) {
        def out = [:]
        def perVariant = planningByVariant(resource)
        def mine = (variantId != null && perVariant[variantId] instanceof Map) ? perVariant[variantId] : [:]
        def manualThresholds = [:]
        try { manualThresholds = Inventory.thresholdsByVariantId(resource) } catch (Exception ignore) {}

        PARAMS.each { param ->
            def v = intOrNull(mine[param])
            if (v != null) {
                out[param] = [value: v, source: "variant"]
                return
            }
            if (param == "threshold" && manualThresholds.containsKey(variantId)) {
                out[param] = [value: manualThresholds[variantId] as int, source: "manual"]
                return
            }
            def dflt = defaultFor(cfg, param)
            out[param] = (dflt != null) ? [value: dflt, source: "default"] : [value: null, source: "none"]
        }
        return out
    }

    /** Convenience: the resolved integer value of one parameter (null = unset). */
    static Integer value(Map resolved, String param) {
        return (Integer) resolved?.get(param)?.value
    }

    /**
     * True when at least one variant of the product resolves to a usable
     * threshold — the onboarding gate (replaces
     * InventoryRules.hasEffectiveThreshold).
     */
    static boolean hasEffectiveThreshold(resource, List variantIds, Map cfg) {
        if (defaultFor(cfg, "threshold") != null) return true
        def manual = [:]
        try { manual = Inventory.thresholdsByVariantId(resource) } catch (Exception ignore) {}
        if (!manual.isEmpty()) return true
        def perVariant = planningByVariant(resource)
        return (variantIds ?: []).any { vid ->
            def p = perVariant[vid?.toString()]
            p instanceof Map && intOrNull(p.threshold) != null
        }
    }

    /**
     * Adopt planning values for a product's variants: deep-merge
     * { planning: { variantId: { param: value } } } into the PIM overlay (so
     * unrelated overlay fields and other variants are untouched). Operator
     * action only — the system never calls this on its own.
     */
    static Map setValues(session, log, productId, Map byVariant, String editor) {
        return Pim.write(session, log, productId, [planning: byVariant ?: [:]], true, editor)
    }

    // --- Helpers -------------------------------------------------------------------

    static Integer intOrNull(v) {
        if (v == null) return null
        if (v instanceof Number) return ((Number) v).intValue()
        try { return Integer.valueOf(v.toString().trim()) } catch (Exception e) { return null }
    }
}
