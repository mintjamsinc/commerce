package commerce

import com.fasterxml.jackson.databind.ObjectMapper
import javax.jcr.query.Query

/**
 * Sales-fact materialization — the pending queue and the SINGLE cluster-guarded writer of the two
 * derived sales-fact grains, modeled on the inventory-total materialize precedent (commerce.Locations
 * + sweepInventoryAlerts): every source path drops a pending marker; one timer/direct-kicked drainer,
 * serialized cluster-wide by a cluster.tryLock lease, RECOMPUTES each order's facts from source.
 *
 * Grains, one node per fact, typed commerce:* props = the facet aggregation axes:
 *   order-grain  /content/commerce/sales/orders/index/{yyyy}/{MM}/order_{id}.json
 *   line-grain   /content/commerce/sales/lines/index/{yyyy}/{MM}/order_{oid}_line_{lid}.json
 *   pending      /content/commerce/sales/_pending/{order_id}.json   { order_id, at }
 *
 * {@link #recompute} is idempotent recompute-from-source: it resolves the order's authoritative body
 * (preferring a components-complete webhook body over a lossy bulk body — {@link #pickBody}), folds
 * its refund bodies, calls {@link commerce.Sales#compute}, then UPSERTS the order fact + its line
 * facts and PRUNES any stale facts (month drift / removed lines) for the same order id. A lossy body
 * NEVER downgrades an already-complete fact. Aggregation itself is delegated to read-time
 * `facet accumulate`, not this class.
 *
 * The JCR methods are DEFENSIVE (a bookkeeping failure must never break a source route or the drain);
 * {@link #pickBody} is PURE and unit-testable. Single-writer discipline: ONLY the drainer
 * (sweepSalesFacts.groovy) may call {@link #recompute}; all other paths only enqueue. Lives under
 * /content/WEB-INF/classes; use via {@code import commerce.SalesFacts}.
 */
class SalesFacts {

    static final String PENDING_DIR     = "/content/commerce/sales/_pending"
    static final String ORDERS_FACT_DIR = "/content/commerce/sales/orders/index"
    static final String LINES_FACT_DIR  = "/content/commerce/sales/lines/index"
    static final String ORDERS_RAW_DIR  = "/content/commerce/orders/raw"
    static final String REFUNDS_RAW_DIR = "/content/commerce/refunds/raw"

    /** Cluster lease name for the single-writer drainer, so only one cluster node drains at a time. */
    static final String LOCK_NAME = "commerce-sales-materialize"

    private static final ObjectMapper MAPPER = new ObjectMapper()
    private static final java.util.regex.Pattern NUMERIC = ~/^\d+$/

    // --- Pending queue (mirrors commerce.InventoryAlert) -----------------------

    /**
     * Mark an order as needing a fact recompute (upsert a marker + commit). Webhook bursts for the
     * same order race on this one marker path (concurrent same-path creates, rewrites overlapping
     * the drainer's clearPending), so the write goes through the retrying Jcr.commitJson. Defensive.
     */
    static void markPending(session, log, orderId) {
        def id = Api.legacyId(orderId)
        if (!id) return
        try {
            Jcr.commitJson(session, "${PENDING_DIR}/${id}.json".toString(), [order_id: id, at: Api.now()])
        } catch (Exception e) {
            try { log.warn("SalesFacts.markPending ${id}: ${e.message}") } catch (Exception ignore) {}
        }
    }

    /** Stage a pending marker WITHOUT committing — the caller batches the commit (bulk import). */
    static boolean writePending(session, log, orderId) {
        def id = Api.legacyId(orderId)
        if (!id) return false
        try {
            def res = Jcr.getOrCreateFile(session, "${PENDING_DIR}/${id}.json".toString())
            res.write(Jcr.toJson([order_id: id, at: Api.now()]))
            return true
        } catch (Exception e) {
            try { log.warn("SalesFacts.writePending ${id}: ${e.message}") } catch (Exception ignore) {}
            return false
        }
    }

    /**
     * Re-stamp a pending marker carrying a no-body retry counter (upsert + commit). Used by the
     * drainer when {@link #recompute} could not resolve the raw order body — typically because the
     * async search index has not surfaced a just-imported order yet — so the order is retried on a
     * later tick instead of silently losing its fact. The counter bounds the retries (a marker for
     * a genuinely body-less ghost id must not loop forever); a fresh webhook/import writePending
     * resets it, which is correct (a new source event means a new chance to resolve). Defensive.
     */
    static void markPendingRetry(session, log, orderId, int retries) {
        def id = Api.legacyId(orderId)
        if (!id) return
        try {
            Jcr.commitJson(session, "${PENDING_DIR}/${id}.json".toString(),
                [order_id: id, at: Api.now(), no_body_retries: retries])
        } catch (Exception e) {
            try { log.warn("SalesFacts.markPendingRetry ${id}: ${e.message}") } catch (Exception ignore) {}
        }
    }

    /** The order ids currently pending a fact recompute (pending marker basenames). */
    static List pendingOrderIds(session) {
        def out = []
        def base = Jcr.safeGet(session, PENDING_DIR)
        if (base == null || !base.exists()) return out
        try {
            def it = base.list()
            while (it.hasNext()) {
                def name = it.next().getName()
                if (name != null && name.endsWith(".json")) out << name.substring(0, name.length() - 5)
            }
        } catch (Exception ignore) {}
        return out
    }

    /** Remove a pending marker. Call BEFORE recomputing (delete-before-evaluate). Defensive. */
    static void clearPending(session, log, orderId) {
        def id = Api.legacyId(orderId)
        if (!id) return
        try {
            def res = Jcr.safeGet(session, "${PENDING_DIR}/${id}.json".toString())
            if (res != null && res.exists()) {
                res.remove()
                session.commit()
            }
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("SalesFacts.clearPending ${id}: ${e.message}") } catch (Exception ignore) {}
        }
    }

    // --- Single-writer recompute-from-source -----------------------------------

    /**
     * Recompute BOTH fact grains for one order from source (the sole fact writer). Idempotent:
     * UPSERT the order fact + line facts IN PLACE at their current-month target paths (an existing
     * same-path node is overwritten, its stale commerce:* props explicitly cleared — never removed
     * and recreated, so a same-path create can never collide with a node the async search index has
     * not surfaced yet), prune stale facts at OTHER paths (month drift / removed lines) for the same
     * id, and NEVER downgrade an already-components_complete fact with a lossy body. Prune + upserts
     * ride ONE commit, so a crash mid-recompute never leaves the order without its fact. Defensive —
     * a failure rolls back and is logged, never thrown. ONLY the drainer may call this.
     *
     * Returns FALSE only when the raw order body could not be resolved (the async search index may
     * not have surfaced a just-imported order yet) — the drainer re-stamps the pending marker for a
     * bounded retry. Every other outcome (including a logged compute/write failure) returns true.
     */
    static boolean recompute(session, log, orderId) {
        def oid = Api.legacyId(orderId)
        if (oid == null || !NUMERIC.matcher(oid).matches()) {
            try { log.warn("SalesFacts.recompute: non-numeric order id '${orderId}' - skipping") } catch (Exception ignore) {}
            return true
        }
        def orderBody = resolveOrderBody(session, oid)
        if (orderBody == null) {
            try { log.info("SalesFacts.recompute: no raw order body for ${oid} - will retry") } catch (Exception ignore) {}
            return false
        }
        def refunds = loadRefundBodies(session, oid)
        def result
        try {
            result = Sales.compute(orderBody, refunds)
        } catch (Exception e) {
            try { log.warn("SalesFacts.recompute: compute failed for ${oid}: ${e.message}") } catch (Exception ignore) {}
            return true
        }
        boolean newComplete = (result.order.props['commerce:components_complete'] == Boolean.TRUE)

        // A (request-side recon): a complete order whose parsed components do not tie to the charged
        // total means Shopify charged something no component models. WARN — never drop the fact (a lossy
        // order that vanished would be worse); the report surfaces the delta and the fact is still written.
        if (newComplete && !SalesReconcile.orderReconOk(result.order.props['commerce:recon_delta_base'])) {
            try { log.warn("SalesFacts.recompute: A recon delta ${result.order.props['commerce:recon_delta_base']} out of tolerance for order ${oid}") } catch (Exception ignore) {}
        }

        // Target paths for THIS recompute (both grains share the ordered_at month).
        def ym = yearMonth(result.order.dateProps['commerce:ordered_at'])
        String orderFactPath = "${ORDERS_FACT_DIR}/${ym[0]}/${ym[1]}/order_${oid}.json".toString()
        def lineTargets = new LinkedHashMap()   // path -> line result
        result.lines.eachWithIndex { ln, i ->
            def lid = ln.props['commerce:line_id'] ?: ("idx" + i)
            lineTargets["${LINES_FACT_DIR}/${ym[0]}/${ym[1]}/order_${oid}_line_${lid}.json".toString()] = ln
        }

        def existingOrderFacts = queryResources(session,
            "/jcr:root${ORDERS_FACT_DIR}//element(*, nt:file)[@commerce:order_id = '${oid}']")
        // No-downgrade guard: never replace a complete fact with a lossy (bulk-derived) one. The
        // search-index query covers any month; the DIRECT target-path probe additionally covers a
        // fact the async index has not surfaced yet (a fresh write from a recent drain).
        boolean existingComplete = existingOrderFacts.any { factComplete(it) }
        if (!existingComplete) {
            def direct = Jcr.safeGet(session, orderFactPath)
            if (direct != null && direct.exists()) existingComplete = factComplete(direct)
        }
        if (existingComplete && !newComplete) {
            try { log.info("SalesFacts.recompute: ${oid} keeping complete fact (incoming body is lossy) - skipping") } catch (Exception ignore) {}
            return true
        }

        try {
            // Prune stale facts at OTHER paths only (month drift / removed lines) — the target-path
            // nodes are upserted in place below, so no path is ever removed and recreated in the
            // same recompute. Best-effort: a node the async index has not surfaced simply survives
            // until a later recompute prunes it.
            existingOrderFacts.each { if (safePathOf(it) != orderFactPath) safeRemove(it) }
            queryResources(session,
                "/jcr:root${LINES_FACT_DIR}//element(*, nt:file)[@commerce:order_id = '${oid}']")
                .each { if (!lineTargets.containsKey(safePathOf(it))) safeRemove(it) }

            writeFact(session, orderFactPath, effectiveProps(result.order.props, newComplete), result.order.dateProps)
            lineTargets.each { path, ln ->
                writeFact(session, (String) path, effectiveProps(ln.props, newComplete), ln.dateProps)
            }
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("SalesFacts.recompute: write failed for ${oid}: ${e.message}") } catch (Exception ignore) {}
        }
        return true
    }

    // The money COMPONENT props are trustworthy only when the order body carries its full decomposition
    // (components_complete). When it does NOT (a lossy bulk-imported historical order missing that
    // decomposition), we OMIT them so `facet accumulate sum(@commerce:gross_base)` never counts a fake 0 (which would
    // silently understate gross/net) — the node exposes only total_price(_base) + dimensions + counts +
    // the flag ("not decomposable"). They return once the order is re-fetched with components.
    private static final List MONEY_DECOMP = [
        'commerce:gross', 'commerce:gross_base', 'commerce:discounts', 'commerce:discounts_base',
        'commerce:tax', 'commerce:tax_base', 'commerce:shipping', 'commerce:shipping_base',
        'commerce:tips', 'commerce:tips_base', 'commerce:duties', 'commerce:duties_base',
        'commerce:returns', 'commerce:returns_base', 'commerce:returns_tax', 'commerce:returns_tax_base',
        'commerce:returns_shipping', 'commerce:returns_shipping_base',
        'commerce:restocking_fee_income', 'commerce:restocking_fee_income_base',
        'commerce:recon_delta', 'commerce:recon_delta_base',
    ]

    /** Props to actually persist: the full set when the order is components_complete, else with the
     *  money-decomposition components stripped (kept: total_price(_base), dimensions, counts, flag). */
    static Map effectiveProps(Map props, boolean complete) {
        if (complete) return props
        def out = new LinkedHashMap(props)
        MONEY_DECOMP.each { out.remove(it) }
        return out
    }

    /**
     * The authoritative raw order body for an id — there may be TWO nodes (paid-month vs created_at
     * month fold), so gather all and {@link #pickBody} the components-complete one (else newest).
     */
    static Map resolveOrderBody(session, String oid) {
        def bodies = []
        queryResources(session, "/jcr:root${ORDERS_RAW_DIR}//order_${oid}.json").each { res ->
            def b = readBody(res)
            if (b != null) bodies << b
        }
        return pickBody(bodies)
    }

    /** All refund bodies for an order (indexed by the String commerce:order_id prop). */
    static List loadRefundBodies(session, String oid) {
        def out = []
        queryResources(session,
            "/jcr:root${REFUNDS_RAW_DIR}//element(*, nt:file)[@commerce:order_id = '${oid}']").each { res ->
            def b = readBody(res)
            if (b != null) out << b
        }
        return out
    }

    /**
     * PURE body selection: prefer a components-complete (webhook) body over a lossy (bulk) one, and
     * among the candidates take the most recently updated (updated_at). Null for an empty list.
     */
    static Map pickBody(List bodies) {
        if (bodies == null || bodies.isEmpty()) return null
        def complete = bodies.findAll { Sales.hasOrderDecomposition(it) }
        def pool = complete.isEmpty() ? bodies : complete
        return pool.max { (Api.epochMs(it?.updated_at) ?: 0L) }
    }

    /** Whether an order body carries its component decomposition (delegates to the one definition). */
    static boolean isComponentsComplete(Map body) {
        return Sales.hasOrderDecomposition(body)
    }

    // --- Fact-node write helpers -----------------------------------------------

    // Upsert a fact node IN PLACE: a readable JSON body (for debugging) + the typed commerce:* props
    // that the facet aggregation reads, + the drainer's computed_at clock. An existing node keeps its
    // identity; any commerce:* prop it carries that the fresh recompute no longer emits (a dimension
    // that went present→absent, or money components stripped for a lossy body) is explicitly removed
    // so the facet axes never see a stale value.
    private static void writeFact(session, String path, Map props, Map dateProps) {
        def res = Jcr.getOrCreateFile(session, path)
        res.write(Jcr.toJson(readableBody(props, dateProps)))
        clearStaleFactProps(res, props, dateProps)
        setTypedProps(res, props)
        (dateProps ?: [:]).each { k, ms ->
            if (ms != null) res.setProperty(k.toString(), new java.util.Date(((Number) ms).longValue()))
        }
        res.setProperty("commerce:computed_at", new java.util.Date())
    }

    // Remove the commerce:* props present on an (upserted-in-place) node but absent from the fresh
    // prop set. setProperty(name, null) removes a property; computed_at is re-stamped by the caller.
    // Defensive — an enumeration failure must not sink the write (worst case a stale prop lingers
    // until the next recompute).
    private static void clearStaleFactProps(res, Map props, Map dateProps) {
        try {
            def keep = new HashSet()
            (props ?: [:]).keySet().each { keep << it.toString() }
            (dateProps ?: [:]).keySet().each { keep << it.toString() }
            keep << "commerce:computed_at"
            def stale = []
            def pit = res.getProperties("commerce:*")
            while (pit.hasNext()) {
                def name = pit.next().getName()
                if (name != null && !keep.contains(name)) stale << name
            }
            stale.each { res.setProperty((String) it, (String) null) }
        } catch (Exception ignore) {}
    }

    // setProperty picks the JCR property type from the Java class (BigDecimal→Decimal, long→Long,
    // boolean→Boolean, String→String) — the same idiom importBulkResult's setDecimal/setLong use.
    private static void setTypedProps(res, Map props) {
        (props ?: [:]).each { k, v ->
            if (v == null) return
            String name = k.toString()
            if (v instanceof BigDecimal) res.setProperty(name, (BigDecimal) v)
            else if (v instanceof Boolean) res.setProperty(name, ((Boolean) v).booleanValue())
            else if (v instanceof Long) res.setProperty(name, ((Long) v).longValue())
            else if (v instanceof Integer) res.setProperty(name, ((Integer) v).longValue())
            else if (v instanceof Number) res.setProperty(name, new BigDecimal(v.toString()))
            else res.setProperty(name, v.toString())
        }
    }

    // A human-readable JSON body: props minus the commerce: prefix, dates as ms-ISO. Debug/audit only;
    // the typed props are the query surface.
    private static Map readableBody(Map props, Map dateProps) {
        def doc = new LinkedHashMap()
        (props ?: [:]).each { k, v -> if (v != null) doc[stripNs(k)] = v }
        (dateProps ?: [:]).each { k, ms -> if (ms != null) doc[stripNs(k)] = Api.instant(ms) }
        doc['computedAt'] = Api.now()
        return doc
    }

    private static String stripNs(k) {
        def s = k?.toString() ?: ""
        return s.startsWith("commerce:") ? s.substring("commerce:".length()) : s
    }

    private static boolean factComplete(res) {
        try {
            if (res.hasProperty("commerce:components_complete")) {
                return res.getProperty("commerce:components_complete").getValue()?.toString() == "true"
            }
        } catch (Exception ignore) {}
        return false
    }

    // --- JCR query / small helpers ---------------------------------------------

    private static List queryResources(session, String stmt) {
        def out = []
        try {
            def q = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
            def rs = q.execute().getResources()
            if (rs != null) rs.each { out << it }
        } catch (Exception ignore) {}
        return out
    }

    private static Map readBody(res) {
        try {
            def c = res?.content?.toString()
            return (c == null || c.trim().isEmpty()) ? null : MAPPER.readValue(c, Map.class)
        } catch (Exception e) { return null }
    }

    private static void safeRemove(res) {
        try { if (res != null && res.exists()) res.remove() } catch (Exception ignore) {}
    }

    /** A resource's path, or null when unreadable (a null never equals a target path, so it is pruned). */
    private static String safePathOf(res) {
        try { return res?.getPath() } catch (Exception ignore) { return null }
    }

    // [yyyy, MM] of an epoch-ms instant in UTC (the shared fold rule, Api.utcYearMonth). Fact folder
    // placement only — reads recurse, and prune is by @commerce:order_id, so a month drift is
    // harmless; the report's day rows are query-time range() buckets on the Date props, never
    // derived from the path. Falls back to now when the timestamp is absent.
    private static List yearMonth(Object ms) {
        return Api.utcYearMonth(ms)
    }
}
