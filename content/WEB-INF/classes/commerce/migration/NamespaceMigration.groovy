package commerce.migration

import javax.jcr.query.Query

/**
 * One-time data migration: rename the legacy, non-namespaced commerce metadata
 * properties on the mirrored product / order / refund nodes to the canonical
 * {@code commerce:} namespace.
 *
 * <h2>Background</h2>
 * The Shopify ingest routes historically wrote these properties via
 * {@code cms:setProperties?includes=commerce_~}. The trailing {@code ~} in that
 * filter <em>strips</em> the matched {@code commerce_} prefix, so a Camel header
 * {@code commerce_product_id} landed as the bare JCR property {@code product_id}
 * (no namespace). Meanwhile every consumer — {@link Pim}, {@link Orders},
 * {@link Backorders}, {@link Reconciliation}, the workflow status scripts and the
 * order/refund forms — and the documentation use the namespaced names
 * ({@code commerce:product_id}, {@code commerce:status}, ...). The routes now
 * write the namespaced names ({@code includes=commerce:*}); this migration brings
 * already-mirrored nodes in line so property/full-text queries and the operator
 * UIs see consistent data.
 *
 * <h2>Behaviour</h2>
 * Idempotent and type-preserving. For each legacy name on a node:
 * <ul>
 *   <li>when the namespaced property is absent, the value is copied across
 *       (keeping its JCR type) and the legacy property removed;</li>
 *   <li>when both already exist, the namespaced property is authoritative (it may
 *       have been written by a workflow / groovy) and only the legacy duplicate
 *       is dropped.</li>
 * </ul>
 * Re-running is a no-op. Only the allow-listed names are touched, so unrelated
 * properties ({@code pim}, {@code metafields}, {@code jcr:*}, {@code mi:*} and the
 * already-namespaced {@code commerce:*}) are never modified. Lives under
 * /content/WEB-INF/classes; use via {@code import commerce.migration.NamespaceMigration}.
 */
class NamespaceMigration {

    static final String PREFIX = "commerce:"

    // The legacy (prefix-stripped) local names each ingest route wrote, by mirror
    // root. Derived from the setHeader/commerce_* mappings in the product-update,
    // product-delete, order-paid and refund-created routes.
    static final Map<String, List<String>> AREAS = [
        "/content/commerce/products": [
            "product_id", "title", "handle", "source_status", "status", "vendor",
            "product_type", "tags", "updated_at", "deletedAt", "errorMessage", "stackTrace",
        ],
        "/content/commerce/orders": [
            "order_id", "customer_email", "total_price", "currency", "order_number",
            "source_status", "status", "errorMessage", "stackTrace",
        ],
        "/content/commerce/refunds": [
            "refund_id", "order_id", "status", "errorMessage", "stackTrace",
        ],
    ]

    /**
     * Run the migration over every area. When {@code dryRun} is true nothing is
     * written, but the report still reflects what would change. Each area is
     * committed independently to bound transaction size. Returns a summary:
     * {@code { dryRun, areas:[ {path, scanned, nodesChanged, renamed,
     * droppedDuplicates, errors} ], totals:{...} }}.
     */
    static Map run(session, log, boolean dryRun) {
        def areaReports = []
        int tNodes = 0, tRenamed = 0, tDropped = 0, tErrors = 0
        AREAS.each { root, names ->
            def rep = migrateArea(session, log, root, names, dryRun)
            areaReports << rep
            tNodes += rep.nodesChanged
            tRenamed += rep.renamed
            tDropped += rep.droppedDuplicates
            tErrors += rep.errors
        }
        return [
            dryRun: dryRun,
            areas : areaReports,
            totals: [nodesChanged: tNodes, renamed: tRenamed, droppedDuplicates: tDropped, errors: tErrors],
        ]
    }

    private static Map migrateArea(session, log, String root, List<String> names, boolean dryRun) {
        int scanned = 0, renamed = 0, dropped = 0, nodesChanged = 0, errors = 0
        def resources = []
        try {
            def rootRes = session.getResource(root)
            if (rootRes != null && rootRes.exists()) {
                def stmt = "/jcr:root${root}//element(*, nt:file)"
                def jq = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
                resources = jq.execute().getResources()
            }
        } catch (Exception e) {
            try { log.warn("NamespaceMigration: query failed for ${root}: ${e.message}") } catch (Exception ignore) {}
        }

        if (resources != null) {
            resources.each { res ->
                scanned++
                boolean nodeTouched = false
                names.each { name ->
                    try {
                        if (!res.hasProperty(name)) return
                        def target = PREFIX + name
                        if (res.hasProperty(target)) {
                            // Namespaced value is authoritative; drop the legacy duplicate.
                            if (!dryRun) res.getProperty(name).remove()
                            dropped++
                        } else {
                            if (!dryRun) {
                                def val = res.getProperty(name).getValue()
                                setTyped(res, target, val)
                                res.getProperty(name).remove()
                            }
                            renamed++
                        }
                        nodeTouched = true
                    } catch (Exception e) {
                        errors++
                        try { log.warn("NamespaceMigration: ${res.getPath()} property '${name}': ${e.message}") } catch (Exception ignore) {}
                    }
                }
                if (nodeTouched) nodesChanged++
            }
        }

        if (!dryRun && (renamed > 0 || dropped > 0)) {
            try {
                session.commit()
            } catch (Exception e) {
                try { session.rollback() } catch (Exception ignore) {}
                throw e
            }
        }

        return [path: root, scanned: scanned, nodesChanged: nodesChanged,
                renamed: renamed, droppedDuplicates: dropped, errors: errors]
    }

    // Set a property preserving the source JCR value type (Date / Decimal / Long /
    // Double / Boolean / String). The ingest routes only ever wrote single-valued
    // properties, so multi-value handling is intentionally omitted.
    private static void setTyped(res, String name, Object val) {
        if (val == null) return
        if (val instanceof java.util.Date) { res.setProperty(name, (java.util.Date) val); return }
        if (val instanceof java.math.BigDecimal) { res.setProperty(name, (java.math.BigDecimal) val); return }
        if (val instanceof Boolean) { res.setProperty(name, (boolean) val); return }
        if (val instanceof Long) { res.setProperty(name, (long) val); return }
        if (val instanceof Integer) { res.setProperty(name, (int) val); return }
        if (val instanceof Double) { res.setProperty(name, (double) val); return }
        res.setProperty(name, val.toString())
    }
}
