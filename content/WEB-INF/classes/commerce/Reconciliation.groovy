package commerce

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * CMS ↔ Shopify consistency reconciliation (category G, #24).
 *
 * Detects where the CMS mirror has drifted from Shopify's current truth — product
 * status, variant price, and per-variant inventory — and (when explicitly enabled
 * per field) heals it. Drift normally means a missed/failed webhook (now also
 * mitigated by ingest replay #4) or a CMS-authoritative value that was never
 * pushed.
 *
 * Each field has a configured <b>source of truth</b> that decides the heal
 * direction:
 *   sourceOfTruth = "cms"     → CMS value wins → push to Shopify (ShopifyWrite)
 *   sourceOfTruth = "shopify" → Shopify value wins → refresh the CMS mirror
 *
 * By default reconciliation only detects + reports + alerts; healing is opt-in per
 * field (reconcile.yml). {@link #diffProduct} is pure (testable); {@link #applyRefresh}
 * (Shopify→CMS mirror patch) is defensive. CMS→Shopify healing is done by the
 * caller via {@link ShopifyWrite}. Lives under /content/WEB-INF/classes; use via
 * {@code import commerce.Reconciliation}.
 */
class Reconciliation {

    private static final ObjectMapper MAPPER = new ObjectMapper()

    /**
     * Compute the field-level diffs between a CMS product (parsed product JSON), its
     * CMS inventory aggregates (inventory_item_id → available), and the Shopify
     * product fetched from the Admin API. PURE.
     *
     * @param sourceOfTruth { status, price, inventory } → "cms" | "shopify"
     * @return list of diffs, each:
     *   { field, variantId, inventoryItemId, cms, shopify, sourceOfTruth, heal }
     *   heal = "push" (CMS→Shopify) | "refresh" (Shopify→CMS) | "report" (no clean
     *   auto-heal, e.g. inventory which is per-location in the mirror).
     */
    static List diffProduct(Map cmsProduct, Map cmsInvByItem, Map shopifyProduct, Map sourceOfTruth) {
        def diffs = []
        def sot = sourceOfTruth ?: [:]
        def inv = cmsInvByItem ?: [:]

        // --- status ---
        def cmsStatus = lower(cmsProduct?.status)
        def shopStatus = lower(shopifyProduct?.status)
        if (cmsStatus && shopStatus && cmsStatus != shopStatus) {
            diffs << diff("status", null, null, cmsStatus, shopStatus, sotOf(sot, "status"), healFor(sotOf(sot, "status"), true))
        }

        // --- per-variant price + inventory ---
        def shopByVariant = [:]
        def edges = shopifyProduct?.variants?.edges
        if (edges instanceof List) {
            edges.each { e ->
                def n = e?.node
                def vid = numericId(n?.id)
                if (vid) shopByVariant[vid] = n
            }
        }
        def cmsVariants = cmsProduct?.variants
        if (cmsVariants instanceof List) {
            cmsVariants.each { v ->
                def vid = v?.id?.toString()
                def sv = vid == null ? null : shopByVariant[vid]
                if (sv == null) return

                def cP = Money.toNumber(v?.price)
                def sP = Money.toNumber(sv?.price)
                if (cP != null && sP != null && cP.compareTo(sP) != 0) {
                    diffs << diff("price", vid, null, cP.toString(), sP.toString(), sotOf(sot, "price"), healFor(sotOf(sot, "price"), true))
                }

                def itemId = v?.inventory_item_id?.toString()
                def sInv = sv?.inventoryQuantity
                if (itemId != null && inv.containsKey(itemId) && sInv != null) {
                    int cI = toInt(inv[itemId])
                    int sI = toInt(sInv)
                    if (cI != sI) {
                        // Inventory in the mirror is per-location while Shopify exposes
                        // an aggregate, so neither direction can be written back
                        // losslessly: inventory drift is always reported (heal by
                        // replaying the missed inventory webhook (#4) or manually).
                        diffs << diff("inventory", vid, itemId, cI.toString(), sI.toString(), sotOf(sot, "inventory"), "report")
                    }
                }
            }
        }
        return diffs
    }

    /**
     * Heal a Shopify→CMS diff by patching the stored product mirror (status / price).
     * Inventory is not refreshed here (per-location data cannot be derived from the
     * aggregate). Defensive — returns whether the mirror was changed.
     */
    static boolean applyRefresh(session, log, productResource, Map diff) {
        try {
            if (productResource == null || !productResource.exists()) return false
            def field = diff?.field
            def product = MAPPER.readValue(productResource.content.toString(), Map.class)
            boolean changed = false

            if (field == "status") {
                product.status = diff.shopify
                productResource.setProperty("commerce:source_status", diff.shopify?.toString())
                changed = true
            } else if (field == "price" && diff.variantId && product.variants instanceof List) {
                product.variants.each { v ->
                    if (v?.id?.toString() == diff.variantId?.toString()) {
                        v.price = diff.shopify
                        changed = true
                    }
                }
            } else {
                return false  // inventory / unknown: not auto-refreshable here
            }

            if (changed) {
                productResource.write(MAPPER.writeValueAsString(product))
                session.commit()
                try { log.info("Reconciliation.applyRefresh: ${field} mirror updated for ${productResource.getPath()}") } catch (Exception ignore) {}
            }
            return changed
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Reconciliation.applyRefresh: ${e.message}") } catch (Exception ignore) {}
            return false
        }
    }

    // --- Helpers ---------------------------------------------------------------

    private static Map diff(String field, String variantId, String inventoryItemId, String cms, String shopify, String sot, String heal) {
        return [field: field, variantId: variantId, inventoryItemId: inventoryItemId,
                cms: cms, shopify: shopify, sourceOfTruth: sot, heal: heal]
    }

    private static String sotOf(Map sot, String field) {
        def v = sot?.get(field)
        return v == null ? "shopify" : v.toString().trim().toLowerCase()
    }

    // Heal direction from the source of truth. supported=false marks a field we only
    // report (currently unused; inventory is decided inline).
    private static String healFor(String sot, boolean supported) {
        if (!supported) return "report"
        return sot == "cms" ? "push" : "refresh"
    }

    /** Numeric id from a Shopify gid ("gid://shopify/ProductVariant/123" → "123"). */
    static String numericId(id) {
        if (id == null) return null
        def s = id.toString()
        int i = s.lastIndexOf('/')
        return i >= 0 ? s.substring(i + 1) : s
    }

    private static String lower(v) { v == null ? null : v.toString().trim().toLowerCase() }

    private static int toInt(v) {
        if (v instanceof Number) return ((Number) v).intValue()
        try { return Integer.parseInt(v.toString().trim()) } catch (Exception e) { return 0 }
    }
}
