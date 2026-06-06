package commerce

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Multi-location inventory access: per-location stock levels (ingested from the
 * Shopify {@code inventory_levels/update} webhook) and location metadata
 * (from {@code locations/*}).
 *
 * Storage:
 *   /content/commerce/inventory/levels/{inventory_item_id}.json
 *       { inventory_item_id, locations: { "<location_id>": { available, updatedAt } } }
 *   /content/commerce/inventory/locations/{location_id}.json
 *       { id, name, ... }   (Shopify location payload)
 *
 * A variant links to its levels via {@code inventory_item_id} (carried on the
 * product JSON's variants). Defensive JSON reads (jackson); a missing/invalid
 * file yields empty data rather than an error.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.Locations}.
 */
class Locations {

    static final String LEVELS_DIR = "/content/commerce/inventory/levels"
    static final String LOCATIONS_DIR = "/content/commerce/inventory/locations"

    private static final ObjectMapper MAPPER = new ObjectMapper()

    /** Available quantity per location for an inventory item: locationId -> available(int). */
    static Map levels(session, inventoryItemId) {
        def out = [:]
        if (inventoryItemId == null) {
            return out
        }
        def doc = readJson(session, "${LEVELS_DIR}/${inventoryItemId}.json")
        def locs = doc?.locations
        if (locs instanceof Map) {
            locs.each { locId, v ->
                if (v instanceof Map && v.available != null) {
                    out[locId.toString()] = toInt(v.available)
                }
            }
        }
        return out
    }

    /** Total available across all locations for an inventory item. */
    static int aggregate(session, inventoryItemId) {
        int total = 0
        levels(session, inventoryItemId).each { loc, avail -> total += (avail ?: 0) }
        return total
    }

    /** Human-readable location name, or the id when no metadata is stored. */
    static String locationName(session, locationId) {
        if (locationId == null) {
            return null
        }
        def doc = readJson(session, "${LOCATIONS_DIR}/${locationId}.json")
        def name = doc?.name
        return (name != null && !name.toString().trim().isEmpty()) ? name.toString() : locationId.toString()
    }

    /**
     * Per-variant, per-location breakdown for a product JSON. Each entry:
     *   [ variantId, inventoryItemId, title, total,
     *     byLocation: [ [ locationId, name, available ], ... ] ]
     */
    static List breakdown(session, productJson) {
        def out = []
        def variants = productJson?.variants
        if (!(variants instanceof List)) {
            return out
        }
        variants.each { v ->
            def itemId = v?.inventory_item_id
            def lv = itemId == null ? [:] : levels(session, itemId)
            def byLocation = lv.collect { locId, avail ->
                [locationId: locId, name: locationName(session, locId), available: avail]
            }.sort { a, b -> b.available <=> a.available }
            out << [
                variantId      : v?.id?.toString(),
                inventoryItemId: itemId?.toString(),
                title          : v?.title?.toString(),
                total          : byLocation.sum { it.available } ?: 0,
                byLocation     : byLocation,
            ]
        }
        return out
    }

    // --- Helpers ---------------------------------------------------------------

    private static Map readJson(session, String path) {
        try {
            def res = session.getResource(path)
            if (res == null || !res.exists()) {
                return null
            }
            def content = res.content?.toString()
            if (content == null || content.trim().isEmpty()) {
                return null
            }
            return MAPPER.readValue(content, Map.class)
        } catch (Exception e) {
            return null
        }
    }

    private static int toInt(v) {
        if (v instanceof Number) return ((Number) v).intValue()
        try { return Integer.parseInt(v.toString().trim()) } catch (Exception e) { return 0 }
    }
}
