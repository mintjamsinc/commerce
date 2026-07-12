package commerce

import javax.jcr.query.Query
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Product Information Management.
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
 * the overlay are pushed to Shopify through the outbound sync
 * ({@link ShopifyWrite#setMetafields}).
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
     * productId → title for a set of Shopify product ids (numeric strings), from the mirrored
     * product nodes' typed {@code commerce:title}. Used to label sales-fact aggregations (top
     * products and sales-sorted browses carry only the product_id dimension key). Defensive:
     * a missing product simply has no entry (the caller falls back to the raw id).
     */
    static Map titles(session, Collection productIds) {
        def out = [:]
        (productIds ?: []).each { pid ->
            if (pid == null || pid.toString().trim().isEmpty()) return
            try {
                def res = productResource(session, pid.toString().trim())
                if (res == null) return
                if (res.hasProperty("commerce:title")) {
                    def t = res.getProperty("commerce:title").getValue()?.toString()
                    if (t != null && !t.isEmpty()) out[pid.toString()] = t
                }
            } catch (Exception ignore) {}
        }
        return out
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
        Map next = planningKeysToStorage(deepMerge(current, overlay ?: [:]))
        next.updatedAt = Api.now()
        if (editor) next.updatedBy = editor
        try {
            res.setProperty(PIM_PROPERTY, MAPPER.writeValueAsString(next))
            res.setProperty("pim:updated_at", next.updatedAt.toString())
            session.commit()
            try { log.info("Pim.write: updated overlay for product ${productId}") } catch (Exception ignore) {}
            // Echo the saved overlay back in the WIRE form (GID planning keys).
            return planningKeysToWire(next)
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
        // Wire rows (commerce.Api): GID ids, camelCase keys, numeric price.
        // Variant prices are in the SHOP currency (Shopify REST semantics) — a bare
        // number here; money WITH a currency axis rides as {currency, amount}.
        def variants = (product.variants instanceof List) ? product.variants.collect {
            [id: Api.gid("ProductVariant", it?.id), title: it?.title, sku: it?.sku,
             price: Api.num(it?.price),
             inventoryItemId: Api.gid("InventoryItem", it?.inventory_item_id)]
        } : []
        // Overview primary thumbnail: the mirror's images[] mapped to { src, alt } (same
        // extraction as Catalog.detail), falling back to the single product.image. Note:
        // MediaImage gids are NOT here — the editable Media section reads those live from
        // the Admin API (image {src,alt} in the mirror carries no MediaImage gid).
        def images = (product.images instanceof List) ? product.images.collect {
            [src: str(it?.src), alt: str(it?.alt)]
        }.findAll { it.src } : []
        if (images.isEmpty() && product?.image?.src) {
            images << [src: str(product.image.src), alt: str(product.image.alt)]
        }
        return [
            id        : Api.gid("Product", productId),
            base      : [
                title      : product.title,
                handle     : product.handle,
                status     : product.status,
                bodyHtml   : product.body_html,
                vendor     : product.vendor,
                productType : product.product_type,
                tags       : product.tags,
                images     : images,
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
                        productId: prop(res, "commerce:product_id"),   // numeric; GID-shaped below
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
                    // Wire ids are Shopify GIDs (commerce.Api).
                    row.id = Api.gid("Product", row.remove("productId"))
                    out << row
                } catch (Exception ignore) {}
            }
        }
        return out
    }

    /**
     * Faceted product browse (the Commerce Products browser). One XPath query
     * over the auto-indexed commerce:* properties applies the active filters;
     * a single pass over the matches collects the requested page AND the facet
     * counts, so counts always reflect the current drill-down. Options:
     * { q, vendor, productType, tag, sourceStatus, status, limit, offset,
     *   sort: updated (default) | sales (base gross) | quantity (units sold),
     *   salesFrom / salesTo: epoch-ms Longs bounding the sales window (absent = all time) }.
     *
     * The sales sorts rank the SAME filtered match set by the per-product figures from ONE
     * grouped facet pass over the line-grain sales facts ({@link SalesQuery#salesByProduct}
     * — uncapped, exact; real product_id axis). Ranked rows carry a {@code sales} object
     * ({ quantity, gross, discounts, returns, net, baseCurrency }); products without sales
     * in the window rank last (zero), they are never dropped. Soft-deleted mirrors
     * (commerce:deletedAt) are excluded.
     */
    static final int BROWSE_SCAN_CAP = 5000

    static Map browse(session, Map opts) {
        int limit = clampInt(opts.limit, 50, 1, 200)
        int offset = clampInt(opts.offset, 0, 0, 1000000)

        // Sales ranking axis (sort=sales|quantity): per-product figures from the line facts.
        def sort = (str(opts.sort) ?: "updated").trim().toLowerCase()
        if (!["updated", "sales", "quantity"].contains(sort)) sort = "updated"
        boolean salesAxis = (sort != "updated")
        Map salesMap = [:]
        String baseCurrency = null
        if (salesAxis) {
            long sf = (opts.salesFrom instanceof Number) ? ((Number) opts.salesFrom).longValue() : 0L
            long st = (opts.salesTo instanceof Number) ? ((Number) opts.salesTo).longValue() : System.currentTimeMillis()
            def sq = SalesQuery.defaults(SalesQuery.config(session))
            salesMap = SalesQuery.salesByProduct(session, sf, st, sq)
            baseCurrency = SalesQuery.baseCurrencyOf(session, sf, st, sq)
        }

        def preds = ["not(@commerce:deletedAt)"]
        def q = sanitize(str(opts.q) ?: "")
        if (!q.isEmpty()) preds << "jcr:contains(., '${q}')".toString()
        addEquals(preds, "commerce:vendor", opts.vendor)
        addEquals(preds, "commerce:product_type", opts.productType)
        addEquals(preds, "commerce:source_status", opts.sourceStatus)
        addEquals(preds, "commerce:status", opts.status)
        def tag = sanitize(str(opts.tag) ?: "")
        if (!tag.isEmpty()) preds << "jcr:like(@commerce:tags, '%${tag}%')".toString()

        // Cast the sort key to xs:dateTime so the query uses the date comparator
        // that matches the typed (Date) docvalues — a bare @commerce:updated_at
        // picks the String (SORTED) comparator and throws on the numeric docvalues
        // left by the property-type migration. Same idiom as the EIP Console.
        // The sales sorts keep this scan order and re-rank the match set in memory
        // by the facet-derived per-product figure.
        def stmt = "/jcr:root${PRODUCTS_DIR}//element(*, nt:file)[${preds.join(' and ')}]" +
                   " order by xs:dateTime(@commerce:updated_at) descending"
        def jq = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
        jq.limit((long) BROWSE_SCAN_CAP)
        def resources = jq.execute().getResources()

        def rows = []
        def ranked = []   // filtered sales sorts: the scanned match set, paged after ranking
        int matched = 0
        // No filter beyond the soft-delete guard → the ranking can come straight from the fact
        // aggregation (exact and UNCAPPED — a top seller whose mirror was not recently updated is
        // never lost to the scan window); the scan below then only feeds the facet counts.
        boolean exactRank = salesAxis && preds.size() == 1
        def vendors = [:], types = [:], tags = [:], sourceStatuses = [:], statuses = [:]
        if (resources != null) {
            resources.each { res ->
                try {
                    def name = res.getName()
                    if (!name.endsWith(".json")) return
                    def vendor = prop(res, "commerce:vendor")
                    def type = prop(res, "commerce:product_type")
                    def tagsRaw = prop(res, "commerce:tags")
                    def sourceStatus = prop(res, "commerce:source_status")
                    def status = prop(res, "commerce:status")
                    matched++
                    count(vendors, vendor)
                    count(types, type)
                    count(sourceStatuses, sourceStatus)
                    count(statuses, status)
                    splitTags(tagsRaw).each { count(tags, it) }
                    if (salesAxis) {
                        if (exactRank) return   // rows come from the fact ranking below
                        def pid = prop(res, "commerce:product_id") ?: productIdFromName(name)
                        def rec = (pid == null) ? null : salesMap[pid]
                        def measure = (sort == "quantity") ? (rec?.quantity ?: 0L)
                                                           : ((rec?.gross ?: BigDecimal.ZERO))
                        ranked << [res: res, sales: rec, measure: measure]
                    } else if (matched > offset && rows.size() < limit) {
                        rows << browseRow(res)
                    }
                } catch (Exception ignore) {}
            }
        }

        if (exactRank) {
            // Products with sales in the window, ranked by the chosen measure (largest first),
            // hydrated by direct id lookup; soft-deleted / unmirrored ids are skipped.
            def rankedIds = salesMap.entrySet().sort { a, b ->
                def ma = (sort == "quantity") ? (a.value?.quantity ?: 0L) : (a.value?.gross ?: BigDecimal.ZERO)
                def mb = (sort == "quantity") ? (b.value?.quantity ?: 0L) : (b.value?.gross ?: BigDecimal.ZERO)
                mb <=> ma
            }
            matched = rankedIds.size()
            int skipped = 0
            for (e in rankedIds) {
                if (rows.size() >= limit) break
                def res = productResource(session, e.key)
                if (res == null || prop(res, "commerce:deletedAt") != null) { matched--; continue }
                if (skipped < offset) { skipped++; continue }
                def row = browseRow(res)
                row.sales = SalesQuery.salesRowWire(e.value, baseCurrency)
                rows << row
            }
        } else if (salesAxis) {
            ranked.sort { a, b -> (b.measure ?: 0) <=> (a.measure ?: 0) }
            ranked.drop(offset).take(limit).each { e ->
                def row = browseRow(e.res)
                row.sales = SalesQuery.salesRowWire(e.sales, baseCurrency)
                rows << row
            }
        }

        return [
            total  : matched,
            capped : !exactRank && matched >= BROWSE_SCAN_CAP,
            limit  : limit,
            offset : offset,
            sort   : sort,
            results: rows,
            facets : [
                vendors       : facetList(vendors),
                productTypes  : facetList(types),
                tags          : facetList(tags),
                sourceStatuses: facetList(sourceStatuses),
                statuses      : facetList(statuses),
            ],
        ]
    }

    /** One browse row (typed props with body fallback) — the single projection browse emits. */
    private static Map browseRow(res) {
        def name = res.getName()
        def row = [
            productId   : prop(res, "commerce:product_id"),
            title       : prop(res, "commerce:title"),
            handle      : prop(res, "commerce:handle"),
            status      : prop(res, "commerce:source_status"),
            procStatus  : prop(res, "commerce:status"),
            vendor      : prop(res, "commerce:vendor"),
            productType : prop(res, "commerce:product_type"),
            tags        : prop(res, "commerce:tags"),
            updatedAt   : propIso(res, "commerce:updated_at"),
            path        : res.getPath(),
        ]
        if (row.productId == null) row.productId = productIdFromName(name)
        if (row.title == null || row.handle == null || row.productId == null) {
            def body = parseContent(res)
            if (row.productId == null) row.productId = str(body.id)
            if (row.title == null)     row.title     = str(body.title)
            if (row.handle == null)    row.handle    = str(body.handle)
            if (row.status == null)    row.status    = str(body.status)
        }
        // Wire ids are Shopify GIDs (commerce.Api).
        row.id = Api.gid("Product", row.remove("productId"))
        return row
    }

    // --- browse helpers ---------------------------------------------------------

    private static void addEquals(List preds, String property, value) {
        def v = sanitize(str(value) ?: "")
        if (!v.isEmpty()) preds << "@${property} = '${v}'".toString()
    }

    private static void count(Map counter, value) {
        def v = str(value)?.trim()
        if (v == null || v.isEmpty()) return
        counter[v] = ((counter[v] ?: 0) as int) + 1
    }

    // Shopify tags are one comma-separated string ("winter, sale").
    private static List splitTags(tagsRaw) {
        def s = str(tagsRaw)
        if (s == null || s.trim().isEmpty()) return []
        return s.split(",").collect { it.trim() }.findAll { !it.isEmpty() }
    }

    /** Facet counter → sorted [{value, count}] (count desc, then value), top 50. */
    private static List facetList(Map counter) {
        def entries = counter.collect { k, v -> [value: k, count: v] }
        entries.sort { a, b -> (b.count <=> a.count) ?: (a.value <=> b.value) }
        return entries.take(50)
    }

    private static int clampInt(value, int dflt, int lo, int hi) {
        try {
            if (value != null && !value.toString().trim().isEmpty()) {
                return Math.max(lo, Math.min(hi, value.toString().trim() as int))
            }
        } catch (Exception ignore) {}
        return dflt
    }

    // Date-typed property → ISO-8601 string (frontends parse it); other types
    // fall back to toString. Null when absent.
    // Date-typed property → the wire timestamp (ms-precision ISO-8601, commerce.Api).
    private static String propIso(res, String name) {
        try {
            if (!res.hasProperty(name)) return null
            return Api.instant(res.getProperty(name).getValue())
        } catch (Exception ignore) {}
        return null
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
                    return planningKeysToWire(MAPPER.readValue(raw, Map.class))
                }
            }
        } catch (Exception ignore) {}
        return [:]
    }

    // pim.planning is keyed by variant id. STORAGE keeps the numeric key (the
    // threshold consumers — Planning / checkThresholdConfig / detectBackorders —
    // key by it); the WIRE uses the GID form (commerce.Api). These two remaps are
    // the only place the key crosses the boundary.
    private static Map planningKeysToWire(Map overlay) {
        if (!(overlay?.planning instanceof Map)) return overlay
        def out = new LinkedHashMap()
        overlay.planning.each { k, v -> out[Api.gid("ProductVariant", k)] = v }
        overlay.planning = out
        return overlay
    }

    private static Map planningKeysToStorage(Map overlay) {
        if (!(overlay?.planning instanceof Map)) return overlay
        def out = new LinkedHashMap()
        overlay.planning.each { k, v -> out[Api.legacyId(k)] = v }
        overlay.planning = out
        return overlay
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
