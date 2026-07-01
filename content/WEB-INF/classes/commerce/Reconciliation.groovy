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
 * Shopify is the single source of truth for every reconciled field, so the only heal
 * direction is Shopify→CMS: reconciliation detects + reports + alerts on drift and refreshes
 * the CMS follower mirror from Shopify. There is no per-field source-of-truth and no
 * CMS→Shopify push. {@link #diffProduct} is pure (testable); {@link #applyRefresh}
 * (Shopify→CMS mirror patch for status / price) is defensive; inventory is refreshed from its
 * per-location data by the reconcile batch (commerce.Locations.replaceLevels). Lives under
 * /content/WEB-INF/classes; use via {@code import commerce.Reconciliation}.
 */
class Reconciliation {

    private static final ObjectMapper MAPPER = new ObjectMapper()

    /**
     * Compute the field-level diffs between a CMS product (parsed product JSON), its
     * CMS inventory aggregates (inventory_item_id → available), and the Shopify
     * product fetched from the Admin API. PURE. Shopify is the source of truth, so the
     * heal direction is always Shopify→CMS.
     *
     * @return list of diffs, each:
     *   { field, variantId, inventoryItemId, cms, shopify, sourceOfTruth: "shopify", heal }
     *   heal = "refresh" (Shopify→CMS mirror patch for status / price) | "report"
     *   (inventory — refreshed from per-location data by the reconcile batch).
     */
    static List diffProduct(Map cmsProduct, Map cmsInvByItem, Map shopifyProduct) {
        def diffs = []
        def inv = cmsInvByItem ?: [:]

        // --- status ---
        def cmsStatus = lower(cmsProduct?.status)
        def shopStatus = lower(shopifyProduct?.status)
        if (cmsStatus && shopStatus && cmsStatus != shopStatus) {
            diffs << diff("status", null, null, cmsStatus, shopStatus, "refresh")
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
                    diffs << diff("price", vid, null, cP.toString(), sP.toString(), "refresh")
                }

                def itemId = v?.inventory_item_id?.toString()
                def sInv = sv?.inventoryQuantity
                if (itemId != null && inv.containsKey(itemId) && sInv != null) {
                    int cI = toInt(inv[itemId])
                    int sI = toInt(sInv)
                    if (cI != sI) {
                        // Inventory is reported here; the actual mirror refresh is done from
                        // per-location data by the reconcile batch (Locations.replaceLevels).
                        diffs << diff("inventory", vid, itemId, cI.toString(), sI.toString(), "report")
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

    private static Map diff(String field, String variantId, String inventoryItemId, String cms, String shopify, String heal) {
        return [field: field, variantId: variantId, inventoryItemId: inventoryItemId,
                cms: cms, shopify: shopify, sourceOfTruth: "shopify", heal: heal]
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
