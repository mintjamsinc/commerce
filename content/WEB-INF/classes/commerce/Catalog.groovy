package commerce

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Sanitizer for the public catalog (self-EC embed toolkit).
 *
 * The product catalog lives in the admin area (/content/commerce/products). Every
 * session — including the anonymous one the public read endpoint
 * (content/public/commerce/endpoints/catalog.groovy) runs as — has repository READ on
 * /content (granted to everyone; only /etc is denied), so the endpoint reads the admin
 * mirror ON DEMAND with the caller's session and runs it through these builders,
 * emitting ONLY customer-safe fields — no commerce:* admin metadata, costs, or internal
 * PIM attributes (only the localized marketing overlay). Sanitizing here is what keeps
 * the raw admin JSON from leaking; there is no pre-built projection, reads are fresh.
 *
 * PURE builders (no JCR IO): {@link #detail} (full product) and {@link #card} (list
 * card). The privileged read script gathers the inputs (the product JSON, its PIM
 * overlay and the per-item availability) and calls these. Lives under
 * /content/WEB-INF/classes; use via {@code import commerce.Catalog}.
 */
class Catalog {

    private static final ObjectMapper MAPPER = new ObjectMapper()

    /**
     * Full public product detail. {@code availByItem} maps inventory_item_id →
     * available for STOCK-TRACKED items only (a missing key = untracked = treated as
     * purchasable / availability unknown). PURE.
     */
    static Map detail(Map product, Map pimOverlay, Map availByItem) {
        def avail = availByItem ?: [:]
        // Wire rows (commerce.Api): GID ids, NUMBER prices (shop currency —
        // Shopify REST variant semantics; the client formats with Intl).
        def variants = (product?.variants instanceof List) ? product.variants.collect { v ->
            def itemId = str(v?.inventory_item_id)   // numeric storage key (internal lookup)
            [
                id            : Api.gid("ProductVariant", v?.id),
                title         : v?.title,
                sku           : v?.sku,
                price         : Api.num(v?.price),
                compareAtPrice: Api.num(v?.compare_at_price),
                inventoryItemId: Api.gid("InventoryItem", itemId),
                available     : (itemId != null && avail.containsKey(itemId)) ? toInt(avail[itemId]) : null,
                options       : [v?.option1, v?.option2, v?.option3].findAll { it != null },
            ]
        } : []

        def images = (product?.images instanceof List) ? product.images.collect {
            [src: str(it?.src), alt: str(it?.alt)]
        }.findAll { it.src } : []
        if (images.isEmpty() && product?.image?.src) {
            images << [src: str(product.image.src), alt: str(product.image.alt)]
        }

        def options = (product?.options instanceof List) ? product.options.collect {
            [name: it?.name, values: (it?.values instanceof List ? it.values : [])]
        } : []

        def out = [
            id         : Api.gid("Product", product?.id),
            handle     : str(product?.handle),
            title      : product?.title,
            bodyHtml   : product?.body_html,
            vendor     : product?.vendor,
            productType: product?.product_type,
            tags       : tagList(product?.tags),
            images     : images,
            options    : options,
            variants   : variants,
            updatedAt  : Api.now(),
        ]
        // Customer-facing PIM overlay only (multi-language). Other PIM attributes are
        // deliberately NOT published.
        def localized = pimOverlay?.localized
        if (localized instanceof Map && !localized.isEmpty()) {
            out.localized = localized
        }
        return out
    }

    /** A lightweight catalog card derived from a detail map. PURE. */
    static Map card(Map detail) {
        def prices = (detail.variants ?: []).collect { numOrNull(it.price) }.findAll { it != null }
        def itemIds = (detail.variants ?: []).collect { it.inventoryItemId }.findAll { it != null }.unique()
        // Aggregate availability across variants: null only when EVERY variant is
        // untracked; otherwise the sum of tracked variants.
        def tracked = (detail.variants ?: []).findAll { it.available != null }
        Integer agg = tracked.isEmpty() ? null : (tracked.sum { (it.available ?: 0) as int } as int)

        return [
            id            : detail.id,
            handle        : detail.handle,
            title         : detail.title,
            image         : detail.images ? detail.images[0].src : null,
            vendor        : detail.vendor,
            productType   : detail.productType,
            tags          : detail.tags,
            priceMin      : prices.isEmpty() ? null : prices.min(),
            priceMax      : prices.isEmpty() ? null : prices.max(),
            inventoryItemIds: itemIds,
            available     : agg,
        ]
    }

    /** Serialize a value to JSON (for the read script). */
    static String toJson(value) { MAPPER.writeValueAsString(value) }

    // --- Helpers ---------------------------------------------------------------

    static List tagList(tags) {
        if (tags instanceof List) return tags.collect { str(it) }.findAll { it }
        if (tags == null) return []
        return tags.toString().split(",").collect { it?.trim() }.findAll { it }
    }

    private static String str(v) { v == null ? null : v.toString() }

    // A clean JSON number (or null) — the one implementation is Api.num, which
    // keeps decimals (no rounding: a 19.99 price must not become 20).
    private static Number numOrNull(v) {
        return Api.num(v)
    }

    private static int toInt(v) {
        if (v instanceof Number) return ((Number) v).intValue()
        try { return Integer.parseInt(v.toString().trim()) } catch (Exception e) { return 0 }
    }
}
