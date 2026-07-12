package commerce

import java.net.http.HttpClient
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
    static final String INDEX_DIR = "/content/commerce/inventory/index"

    /** Materialized per-variant total available, stored on the index node so screens
     *  can read it 1:1 without re-aggregating the per-location levels. */
    static final String TOTAL_PROP    = "commerce:available_total"
    static final String TOTAL_AT_PROP = "commerce:available_total_at"

    private static final int WRITE_RETRIES = 6

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

    // --- Materialized total (fast 1:1 read) ------------------------------------

    /**
     * Read the pre-computed total available for an inventory item from its index node
     * property ({@link #TOTAL_PROP}), or {@code null} when not materialized yet (item not
     * indexed, or the property has never been written). Callers that need a value regardless
     * should fall back to {@link #aggregate}. Defensive — never throws.
     */
    static Integer readTotal(session, inventoryItemId) {
        def id = inventoryItemId?.toString()
        if (!id) {
            return null
        }
        try {
            def res = session.getResource("${INDEX_DIR}/${id}.json".toString())
            if (res == null || !res.exists() || !res.hasProperty(TOTAL_PROP)) {
                return null
            }
            def v = res.getProperty(TOTAL_PROP).getValue()
            return (v == null) ? null : toInt(v)
        } catch (Exception e) {
            return null
        }
    }

    /**
     * Write the materialized total onto the item's index node as a JCR property. The index node
     * is created by {@code indexInventoryItems.groovy} on products/* ingestion; when it does not
     * exist yet (inventory event arrived before the product), the write is SKIPPED (the reader
     * falls back to {@link #aggregate}). Only the single-threaded, cluster-guarded alert sweep
     * writes this, so a small retry covers a concurrent product re-index touching the same node.
     * Defensive — returns whether the property was written.
     */
    static boolean writeTotal(session, log, inventoryItemId, int total) {
        def id = inventoryItemId?.toString()
        if (!id) {
            return false
        }
        def path = "${INDEX_DIR}/${id}.json".toString()
        for (int attempt = 0; attempt < WRITE_RETRIES; attempt++) {
            try {
                def res = session.getResource(path)
                if (res == null || !res.exists()) {
                    return false  // not indexed yet — reader falls back to aggregate()
                }
                res.setProperty(TOTAL_PROP, total)
                res.setProperty(TOTAL_AT_PROP, Api.now())
                session.commit()
                return true
            } catch (Exception e) {
                try { session.rollback() } catch (Exception ignore) {}
                if (attempt == WRITE_RETRIES - 1) {
                    try { log.warn("Locations.writeTotal ${id}: ${e.message}") } catch (Exception ignore) {}
                } else {
                    try { Thread.sleep(20L * (attempt + 1)) } catch (Exception ignore) {}
                }
            }
        }
        return false
    }

    /**
     * Recompute the total from the per-location levels (recompute-from-source, so it converges
     * regardless of update order / coalescing) and persist it to the index node. Returns the
     * computed total. Called by the alert sweep, the sole writer of the materialized total.
     */
    static int materializeTotal(session, log, inventoryItemId) {
        int total = aggregate(session, inventoryItemId)
        writeTotal(session, log, inventoryItemId, total)
        return total
    }

    /**
     * Replace the per-location levels for an item with an authoritative full snapshot
     * (e.g. a reconciliation pull from Shopify). Unlike the webhook recorder this is a
     * full OVERWRITE, not a per-location merge, since the caller holds the complete set
     * of locations. Defensive — returns whether the file was written.
     */
    static boolean replaceLevels(session, log, inventoryItemId, Map byLocation) {
        def id = inventoryItemId?.toString()
        if (id == null || id.isEmpty()) {
            return false
        }
        try {
            def now = Api.now()
            def locs = [:]
            (byLocation ?: [:]).each { locId, avail ->
                if (locId != null) {
                    locs[locId.toString()] = [available: toInt(avail), updatedAt: now]
                }
            }
            def doc = [inventory_item_id: id, locations: locs]
            def res = Jcr.getOrCreateFile(session, "${LEVELS_DIR}/${id}.json".toString())
            res.write(Jcr.toJson(doc))
            session.commit()
            return true
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Locations.replaceLevels ${id}: ${e.message}") } catch (Exception ignore) {}
            return false
        }
    }

    /**
     * Stage a full level overwrite WITHOUT committing — the caller batches the commit (e.g. a
     * large bulk reconcile). Same shape as {@link #replaceLevels} but leaves the commit (and
     * rollback) to the caller. Defensive — returns whether the node was written.
     */
    static boolean writeLevels(session, log, inventoryItemId, Map byLocation) {
        def id = inventoryItemId?.toString()
        if (id == null || id.isEmpty()) {
            return false
        }
        try {
            def now = Api.now()
            def locs = [:]
            (byLocation ?: [:]).each { locId, avail ->
                if (locId != null) {
                    locs[locId.toString()] = [available: toInt(avail), updatedAt: now]
                }
            }
            def res = Jcr.getOrCreateFile(session, "${LEVELS_DIR}/${id}.json".toString())
            res.write(Jcr.toJson([inventory_item_id: id, locations: locs]))
            return true
        } catch (Exception e) {
            try { log.warn("Locations.writeLevels ${id}: ${e.message}") } catch (Exception ignore) {}
            return false
        }
    }

    /** True when two locationId→available maps hold the same available for the same locations. */
    static boolean sameLevels(Map a, Map b) {
        def x = a ?: [:]
        def y = b ?: [:]
        if (x.size() != y.size()) {
            return false
        }
        for (e in x) {
            if (!y.containsKey(e.key) || toInt(e.value) != toInt(y[e.key])) {
                return false
            }
        }
        return true
    }

    /**
     * Reverse-index lookup: resolve an {@code inventory_item_id} to its product/variant,
     * or {@code null} when it has not been indexed yet. The index is built from the
     * Shopify product payload by {@code indexInventoryItems.groovy} on products/* ingestion
     * (an inventory_levels/update webhook carries only the inventory_item_id).
     *
     *   /content/commerce/inventory/index/{inventory_item_id}.json
     *
     * @return Map: [ inventory_item_id, product_id, product_path, variant_id, variant_title ]
     *         or null when absent/invalid.
     */
    static Map resolveItem(session, inventoryItemId) {
        if (inventoryItemId == null) {
            return null
        }
        def doc = readJson(session, "${INDEX_DIR}/${inventoryItemId}.json")
        return (doc instanceof Map && doc.inventory_item_id != null) ? doc : null
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

    // --- Location metadata backfill (Admin API) --------------------------------

    /**
     * Backfill the location-metadata mirror ({@link #LOCATIONS_DIR}) from the Shopify Admin API —
     * all locations, paginated. This is the INITIAL-IMPORT / self-heal counterpart to the
     * {@code locations/create}|{@code locations/update} webhooks: a shop's locations are almost
     * always created BEFORE the app is installed (and are rarely edited after), so no webhook ever
     * fires and the mirror stays EMPTY — which leaves the reorder destination picker with nothing to
     * pick and per-location names falling back to raw ids. The inventory reconcile / backfill calls
     * this FIRST (before pulling the per-location stock), so a full inventory pull always leaves the
     * location names + destinations populated.
     *
     * Writes the same files as {@code recordLocation.groovy}, normalized to the source-faithful REST
     * shape (snake_case, numeric id) so the webhook path and this path stay interchangeable — whichever
     * writes last, {@link #locationName} and the reorder form read it identically. Commits once at the
     * end (locations are few — a shop has at most a handful). Best-effort: returns the number of
     * locations written; a failure is logged and rolls back the staged writes, leaving the mirror as it
     * was (callers degrade exactly as with an empty mirror).
     */
    static int backfillFromAdmin(session, log, HttpClient client, String endpoint, String token) {
        int written = 0
        String after = null
        int pages = 0
        try {
            // includeInactive/includeLegacy so name resolution is COMPLETE for every location an
            // inventory level might reference (a deactivated/legacy location still needs a name).
            while (pages < 50) {
                def afterArg = (after == null) ? "" : ", after: \"${after}\""
                def query = """
{
  locations(first: 250${afterArg}, includeInactive: true, includeLegacy: true) {
    edges {
      node {
        id
        name
        isActive
        address { address1 address2 city zip province provinceCode country countryCode phone }
      }
    }
    pageInfo { hasNextPage endCursor }
  }
}
""".trim()
                def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: query])
                def conn = resp?.data?.locations
                def edges = conn?.edges
                if (edges instanceof List) {
                    edges.each { e ->
                        if (writeLocationDoc(session, log, e?.node)) {
                            written++
                        }
                    }
                }
                pages++
                def pi = conn?.pageInfo
                if (pi?.hasNextPage && pi?.endCursor) {
                    after = pi.endCursor.toString()
                } else {
                    break
                }
            }
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Locations.backfillFromAdmin: ${e.message}") } catch (Exception ignore) {}
            return 0
        }
        return written
    }

    /**
     * Stage one Admin-GraphQL location node into the mirror in the REST webhook shape (numeric id +
     * snake_case address fields), WITHOUT committing — {@link #backfillFromAdmin} batch-commits.
     * Defensive — returns whether the node was staged.
     */
    private static boolean writeLocationDoc(session, log, node) {
        if (!(node instanceof Map)) {
            return false
        }
        // Wire ids arrive as GIDs; the mirror is keyed (and bodied) by the numeric REST id.
        def numericId = Api.legacyId(node.id)
        if (numericId == null || numericId.toString().trim().isEmpty()) {
            return false
        }
        try {
            def addr = (node.address instanceof Map) ? node.address : [:]
            def body = [
                id           : Api.count(numericId),
                name         : node.name?.toString(),
                active       : (node.isActive == null) ? null : (node.isActive == true),
                address1     : addr.address1,
                address2     : addr.address2,
                city         : addr.city,
                zip          : addr.zip,
                province     : addr.province,
                province_code: addr.provinceCode,
                country      : addr.country,
                country_code : addr.countryCode,
                phone        : addr.phone,
            ]
            def res = Jcr.getOrCreateFile(session, "${LOCATIONS_DIR}/${numericId}.json".toString())
            res.write(Jcr.toJson(body))
            return true
        } catch (Exception e) {
            try { log.warn("Locations.writeLocationDoc ${numericId}: ${e.message}") } catch (Exception ignore) {}
            return false
        }
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
            // Wire ids are Shopify GIDs (commerce.Api) — the numeric storage keys
            // never leave the orchestration layer.
            def byLocation = lv.collect { locId, avail ->
                [locationId: Api.gid("Location", locId), name: locationName(session, locId), available: avail]
            }.sort { a, b -> b.available <=> a.available }
            out << [
                variantId      : Api.gid("ProductVariant", v?.id),
                inventoryItemId: Api.gid("InventoryItem", itemId),
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
