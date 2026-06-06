package commerce

import javax.jcr.query.Query
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Product Information Management (category G, #23).
 *
 * The platform mirrors each Shopify product as raw JSON at
 * /content/commerce/products/product_{id}.json (with commerce:* metadata and, when
 * the Admin API is on, a metafields mirror). PIM adds a CMS-authoritative
 * <em>overlay</em> of extended attributes that live beyond Shopify — multi-language
 * titles/descriptions, rich descriptions, and custom attributes / metafields — kept
 * co-located on the product node so they inherit the platform's JCR strengths:
 * version history (the node is versioned like any other content change), full-text
 * search, and ACLs (who may read/edit a product governs its PIM data too).
 *
 * The overlay is stored as one JSON property ({@code pim}) on the product node
 * (the same pattern as the forms' {@code inventory_level_config} / {@code fulfillment}
 * properties), so it is versioned and searchable with the node. {@link #view}
 * composes the Shopify base + metafields mirror + PIM overlay into one unified
 * product view (for storefronts / downstream consumers); CMS-authored metafields in
 * the overlay are pushed to Shopify through the outbound sync (#2,
 * {@link ShopifyWrite#setMetafields}).
 *
 * Reads are defensive (missing/garbled overlay → empty); {@link #write} raises so
 * the editing endpoint can report the outcome. Lives under /content/WEB-INF/classes;
 * use via {@code import commerce.Pim}.
 */
class Pim {

    static final String PRODUCTS_DIR = "/content/commerce/products"
    static final String PIM_PROPERTY = "pim"

    private static final ObjectMapper MAPPER = new ObjectMapper()

    /** Resolve a product node by Shopify product id, or null when absent. */
    static Object productResource(session, productId) {
        if (!productId) return null
        try {
            def res = session.getResource("${PRODUCTS_DIR}/product_${productId}.json")
            return (res != null && res.exists()) ? res : null
        } catch (Exception e) {
            return null
        }
    }

    /** The CMS-authored PIM overlay for a product (empty map when none). Defensive. */
    static Map read(session, productId) {
        return readFrom(productResource(session, productId))
    }

    /**
     * Write the PIM overlay. When {@code merge} is true the given attributes are
     * deep-merged onto the existing overlay (so a partial edit keeps other fields);
     * otherwise the overlay is replaced. Stamps updatedAt/updatedBy. Raises when the
     * product does not exist or the write fails.
     */
    static Map write(session, log, productId, Map overlay, boolean merge, String editor) {
        def res = productResource(session, productId)
        if (res == null) {
            throw new RuntimeException("Product not found: ${productId}")
        }
        Map current = merge ? readFrom(res) : [:]
        Map next = deepMerge(current, overlay ?: [:])
        next.updatedAt = java.time.Instant.now().toString()
        if (editor) next.updatedBy = editor
        try {
            res.setProperty(PIM_PROPERTY, MAPPER.writeValueAsString(next))
            res.setProperty("pim:updated_at", next.updatedAt.toString())
            session.commit()
            try { log.info("Pim.write: updated overlay for product ${productId}") } catch (Exception ignore) {}
            return next
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            throw e
        }
    }

    /**
     * Unified product view: Shopify base fields + metafields mirror + PIM overlay,
     * the single source downstream consumers (storefront, exports) read. Null when
     * the product is unknown.
     */
    static Map view(session, productId) {
        def res = productResource(session, productId)
        if (res == null) return null
        def product = [:]
        try { product = MAPPER.readValue(res.content.toString(), Map.class) } catch (Exception ignore) {}
        def variants = (product.variants instanceof List) ? product.variants.collect {
            [id: str(it?.id), title: it?.title, sku: it?.sku, price: it?.price,
             inventory_item_id: str(it?.inventory_item_id)]
        } : []
        return [
            productId : productId.toString(),
            base      : [
                title      : product.title,
                handle     : product.handle,
                status     : product.status,
                bodyHtml   : product.body_html,
                vendor     : product.vendor,
                productType : product.product_type,
                tags       : product.tags,
                variants   : variants,
            ],
            metafields: metafieldsMirror(res),
            pim       : readFrom(res),
        ]
    }

    /**
     * Full-text product search over the mirrored products (JCR jcr:contains, which
     * indexes the stored JSON + the PIM overlay). Each row: { productId, title,
     * handle, status, path }. Query/lookup errors propagate.
     */
    static List search(session, String query, int limit) {
        def out = []
        def q = sanitize(query)
        if (q.isEmpty()) return out
        def stmt = "/jcr:root/content/commerce/products//element(*, nt:file)[jcr:contains(., '${q}')]"
        def jq = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
        if (limit > 0) jq.limit((long) limit)
        def resources = jq.execute().getResources()
        if (resources != null) {
            resources.each { res ->
                try {
                    def name = res.getName()
                    if (!name.endsWith(".json")) return
                    def row = [
                        productId: prop(res, "commerce:product_id"),
                        title    : prop(res, "commerce:title"),
                        handle   : prop(res, "commerce:handle"),
                        status   : prop(res, "commerce:source_status"),
                        path     : res.getPath(),
                    ]
                    // Fall back to the always-present sources when the denormalized
                    // commerce:* metadata is absent (products imported outside the
                    // webhook route, or mirrored before these properties existed).
                    // The node name encodes the id (product_{id}.json) and the stored
                    // JSON body carries title/handle/status — so a result row is always
                    // populated and consistent with view(), never "#null".
                    if (row.productId == null) row.productId = productIdFromName(name)
                    if (row.title == null || row.handle == null
                            || row.status == null || row.productId == null) {
                        def body = parseContent(res)
                        if (row.productId == null) row.productId = str(body.id)
                        if (row.title == null)     row.title     = str(body.title)
                        if (row.handle == null)    row.handle    = str(body.handle)
                        if (row.status == null)    row.status    = str(body.status)
                    }
                    out << row
                } catch (Exception ignore) {}
            }
        }
        return out
    }

    /** Extract the Shopify product id from a mirror node name (product_{id}.json). */
    private static String productIdFromName(String name) {
        def m = (name =~ /^product_(.+)\.json$/)
        return m ? m.group(1) : null
    }

    /** Parse a product mirror's JSON body; empty map when absent/garbled. Defensive. */
    private static Map parseContent(res) {
        try { return MAPPER.readValue(res.content.toString(), Map.class) } catch (Exception ignore) {}
        return [:]
    }

    /**
     * The CMS-authored metafields to push to Shopify, from the overlay's
     * {@code metafields} list. Each: { namespace, key, type, value }. Pure.
     */
    static List metafieldsToPush(Map overlay) {
        def mf = overlay?.metafields
        if (!(mf instanceof List)) return []
        return mf.findAll { it instanceof Map && it.namespace && it.key }.collect {
            [namespace: it.namespace.toString(), key: it.key.toString(),
             type: (it.type ?: "single_line_text_field").toString(), value: str(it.value)]
        }
    }

    // --- Helpers ---------------------------------------------------------------

    private static Map readFrom(res) {
        try {
            if (res != null && res.hasProperty(PIM_PROPERTY)) {
                def raw = res.getProperty(PIM_PROPERTY).getValue()?.toString()
                if (raw != null && !raw.trim().isEmpty()) {
                    return MAPPER.readValue(raw, Map.class)
                }
            }
        } catch (Exception ignore) {}
        return [:]
    }

    // Best-effort read of the Shopify metafields mirror (stored by getMetafields via
    // the product route as the `metafields` property). Empty when absent/unreadable.
    private static Map metafieldsMirror(res) {
        try {
            if (res != null && res.hasProperty("metafields")) {
                def raw = res.getProperty("metafields").getValue()?.toString()
                if (raw != null && !raw.trim().isEmpty()) {
                    if (raw.trim().startsWith("{")) return MAPPER.readValue(raw, Map.class)
                    return [value: raw]
                }
            }
        } catch (Exception ignore) {}
        return [:]
    }

    @SuppressWarnings("unchecked")
    private static Map deepMerge(Map base, Map overlay) {
        def out = [:]
        if (base) out.putAll(base)
        overlay.each { k, v ->
            def existing = out[k]
            if (existing instanceof Map && v instanceof Map) {
                out[k] = deepMerge((Map) existing, (Map) v)
            } else {
                out[k] = v
            }
        }
        return out
    }

    private static String prop(res, String name) {
        try { if (res.hasProperty(name)) return res.getProperty(name).getValue()?.toString() } catch (Exception ignore) {}
        return null
    }

    // Keep a user query safe inside the XPath jcr:contains string literal: drop the
    // characters that would break out of the quoted term or the XPath expression.
    private static String sanitize(String s) {
        if (s == null) return ""
        return s.replaceAll("['\"\\[\\]\\(\\)\\\\]", " ").replaceAll("\\s+", " ").trim()
    }

    private static String str(v) { return v == null ? null : v.toString() }
}
