package commerce

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Content-commerce landing pages (category F, #22).
 *
 * Editorial landing pages are authored in the CMS as block documents under
 * /content/commerce/pages/{slug}.json (versioned + ACL-governed like any content),
 * mixing prose with product showcases. The publisher resolves the product blocks
 * against the public catalog cards (#20) and writes a sanitized public projection
 * under /content/public/commerce/pages/{slug}.json that the ichigo.js landing
 * renderer consumes — so articles and products live on one page, and the embedded
 * product cards reuse the storefront's catalog + real-time inventory (#21).
 *
 * Source block types (authored): heading | markdown | html | hero | products.
 * A `products` block references products by `productIds` (explicit, ordered) or by
 * `tag`; {@link #publicPage} resolves them to catalog cards.
 *
 * Pure resolution (given the catalog cards the caller supplies); the publish script
 * does the JCR IO. Lives under /content/WEB-INF/classes; use via
 * {@code import commerce.Pages}.
 */
class Pages {

    static final String SOURCE_DIR = "/content/commerce/pages"
    static final String PUBLIC_DIR = "/content/public/commerce/pages"

    static final int DEFAULT_PRODUCT_LIMIT = 12

    private static final ObjectMapper MAPPER = new ObjectMapper()

    /**
     * Resolve a source page into its public projection. PURE — {@code allCards} is
     * the published catalog card list (catalog/index.json `products`). Product blocks
     * are replaced with their resolved cards; prose blocks pass through. Returns null
     * for a page that is not publishable (no slug, or status draft).
     */
    static Map publicPage(Map source, List allCards) {
        if (source == null) return null
        def slug = str(source.slug)
        if (!slug) return null
        def status = str(source.status)
        if (status != null && status.toLowerCase() == "draft") return null

        def cards = allCards ?: []
        def byId = [:]
        cards.each { c -> if (c?.id != null) byId[c.id.toString()] = c }

        def blocks = (source.blocks instanceof List) ? source.blocks : []
        def outBlocks = []
        blocks.each { b ->
            if (!(b instanceof Map)) return
            def type = str(b.type)
            if (type == "products") {
                def resolved = resolveProducts(b, cards, byId)
                outBlocks << [type: "products", title: b.title, layout: (b.layout ?: "grid"), products: resolved]
            } else {
                // Prose / hero blocks pass through (authored by trusted CMS editors).
                outBlocks << b
            }
        }

        return [
            slug     : slug,
            title    : source.title,
            subtitle : source.subtitle,
            updatedAt: java.time.Instant.now().toString(),
            blocks   : outBlocks,
        ]
    }

    /** Index entry for the published-pages list. PURE. */
    static Map indexEntry(Map publicPage) {
        return [slug: publicPage.slug, title: publicPage.title, subtitle: publicPage.subtitle, updatedAt: publicPage.updatedAt]
    }

    static String toJson(value) { MAPPER.writeValueAsString(value) }

    // --- Helpers ---------------------------------------------------------------

    private static List resolveProducts(Map block, List allCards, Map byId) {
        int limit = intOr(block.limit, DEFAULT_PRODUCT_LIMIT)
        def out = []
        if (block.productIds instanceof List) {
            block.productIds.each { id -> def c = byId[id?.toString()]; if (c != null) out << c }
        } else if (block.tag != null) {
            def tag = block.tag.toString()
            out = allCards.findAll { (it?.tags instanceof List) && it.tags.contains(tag) }
        }
        return limit > 0 && out.size() > limit ? out.subList(0, limit) : out
    }

    private static String str(v) { v == null ? null : v.toString() }

    private static int intOr(v, int dflt) {
        if (v == null) return dflt
        try { return v.toString().trim() as int } catch (Exception e) { return dflt }
    }
}
