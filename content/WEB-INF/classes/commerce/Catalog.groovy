package commerce

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Public storefront catalog projection (category F, #20 / #21).
 *
 * The product catalog lives in the admin area (/content/commerce/products) which an
 * anonymous storefront visitor cannot read. Rather than expose that, the publisher
 * builds a <em>sanitized public projection</em> under /content/public/commerce/catalog/
 * (anonymous-readable) that the ichigo.js storefront consumes directly:
 *
 *   catalog/index.json          { meta, products:[ card … ] }   — list/search
 *   catalog/products/{id}.json  full public product detail
 *   catalog/inventory.json      { updatedAt, items:{ itemId: available } } — realtime (#21)
 *   catalog/store.json          { name, shopDomain, currency, lowStock } — store + checkout
 *
 * This class builds the projection objects (pure given the inputs the caller
 * gathers: the product JSON, its PIM overlay and the per-item availability); the
 * publish script does the JCR IO. Only customer-safe fields are included — no
 * commerce:* admin metadata, costs, or internal PIM attributes (only the localized
 * marketing overlay). Lives under /content/WEB-INF/classes; use via
 * {@code import commerce.Catalog}.
 */
class Catalog {

    static final String PUBLIC_DIR = "/content/public/commerce/catalog"

    private static final ObjectMapper MAPPER = new ObjectMapper()

    /**
     * Full public product detail. {@code availByItem} maps inventory_item_id →
     * available for STOCK-TRACKED items only (a missing key = untracked = treated as
     * purchasable / availability unknown). PURE.
     */
    static Map detail(Map product, Map pimOverlay, Map availByItem) {
        def avail = availByItem ?: [:]
        def variants = (product?.variants instanceof List) ? product.variants.collect { v ->
            def itemId = str(v?.inventory_item_id)
            [
                id            : str(v?.id),
                title         : v?.title,
                sku           : v?.sku,
                price         : str(v?.price),
                compareAtPrice: str(v?.compare_at_price),
                inventoryItemId: itemId,
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
            id         : str(product?.id),
            handle     : str(product?.handle),
            title      : product?.title,
            bodyHtml   : product?.body_html,
            vendor     : product?.vendor,
            productType: product?.product_type,
            tags       : tagList(product?.tags),
            images     : images,
            options    : options,
            variants   : variants,
            updatedAt  : java.time.Instant.now().toString(),
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
            priceMin      : prices.isEmpty() ? null : prices.min().toString(),
            priceMax      : prices.isEmpty() ? null : prices.max().toString(),
            inventoryItemIds: itemIds,
            available     : agg,
        ]
    }

    /** Serialize a value to JSON (for the publish script). */
    static String toJson(value) { MAPPER.writeValueAsString(value) }

    // --- Helpers ---------------------------------------------------------------

    static List tagList(tags) {
        if (tags instanceof List) return tags.collect { str(it) }.findAll { it }
        if (tags == null) return []
        return tags.toString().split(",").collect { it?.trim() }.findAll { it }
    }

    private static String str(v) { v == null ? null : v.toString() }

    private static Integer numOrNull(v) {
        if (v == null) return null
        try { return (int) Math.round(Double.parseDouble(v.toString())) } catch (Exception e) { return null }
    }

    private static int toInt(v) {
        if (v instanceof Number) return ((Number) v).intValue()
        try { return Integer.parseInt(v.toString().trim()) } catch (Exception e) { return 0 }
    }
}
