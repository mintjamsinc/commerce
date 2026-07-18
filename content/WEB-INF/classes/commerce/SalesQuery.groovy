package commerce

import java.math.BigDecimal
import java.math.RoundingMode
import javax.jcr.query.Query

/**
 * Sales READER — the index-backed aggregation layer, and the ONLY sales read path. Composes every sales
 * metric at READ TIME from the sales facts written by the drainer ({@link commerce.SalesFacts}),
 * delegating the SUM itself to the platform's server-side {@code facet accumulate}:
 * one XPath round-trip aggregates the WHOLE match set in a
 * single pass, with NO in-memory 5000-row cap (the FacetResult re-runs the query at limit 0), so the
 * totals are exact regardless of order volume.
 *
 * Reads served:
 *   occurrenceSummary — the occurrence-date report (Commerce Reports app + the dashboard trend hero).
 *   topProducts       — line-grain top-N by base gross (dashboard trend).
 *   salesByProduct    — per-product components (product browse sales sorts).
 *   spendByCustomer   — per-customer components (CRM spend sort / min-spend filter).
 *
 * The population (financial_status / cancelled) of the browse-sort axes is chosen per-request over
 * BUILT-IN defaults (financial_status = ALL statuses, cancelled EXCLUDED), compiled to an XPath
 * predicate — never hard-coded into the metric. An optional {@link #CONFIG_PATH} file, absent by
 * default (sales.yml was retired), can still re-point those defaults if dropped in. The occurrence
 * report takes NO population — it counts every event on its own date.
 *
 * Grains queried:
 *   order-grain   {@link #ORDERS_FACT_DIR}   — occurrence new-order axis, per-customer spend.
 *   line-grain    {@link #LINES_FACT_DIR}    — product-attributed metrics (topProducts, salesByProduct).
 *   refund store  {@link #REFUNDS_RAW_DIR}   — occurrence refund axis by refunded_at.
 *   payment store {@link #PAYMENTS_RAW_DIR}  — occurrence payment (cash-in) axis by paid_at.
 *
 * Prop name == facet dimension text is a CONTRACT: a single set of dimension helpers
 * ({@link #sumDim}/{@link #countDim}) build the normalized "@"-stripped text that the
 * writer's prop names and {@code Facet.getFacet(dim)} must agree on — a space/arg-order drift would read
 * a silent zero. Money-decomposition components are OMITTED on components_complete=false facts (the
 * drainer strips them), so a lossy historical order contributes to the order count and total_price but
 * NOT to gross/net — "not decomposable", never a fake 0.
 *
 * Defensive: a query/facet failure degrades to zeros/empty, never throws (a report must not 500 on a bad
 * fact). Design principle: every method static, no script bindings held — pass
 * {@code session}; parse config with {@link commerce.SimpleYaml}. Lives under /content/WEB-INF/classes;
 * use via {@code import commerce.SalesQuery}.
 */
class SalesQuery {

    static final String ORDERS_FACT_DIR  = "/content/commerce/sales/orders/index"
    static final String LINES_FACT_DIR   = "/content/commerce/sales/lines/index"
    static final String REFUNDS_RAW_DIR  = "/content/commerce/refunds/raw"
    static final String PAYMENTS_RAW_DIR = PaymentMirror.PAYMENTS_RAW_DIR
    static final String CONFIG_PATH      = "/etc/commerce/config/sales.yml"

    // -------------------------------------------------------------------------
    // Config — built-in defaults, with an OPTIONAL override file (sales.yml retired)
    // -------------------------------------------------------------------------

    /**
     * Parsed OPTIONAL override file at {@link #CONFIG_PATH} (empty map when absent — the default, since
     * sales.yml was retired). Classes cannot use the YAML binding — SimpleYaml here. Empty map ⇒ the
     * built-in defaults in {@link #defaults} apply.
     */
    static Map config(session) {
        def res = Jcr.safeGet(session, CONFIG_PATH)
        if (res == null || !res.exists()) return [:]
        try { return SimpleYaml.parse(res.content?.toString()) ?: [:] }
        catch (Exception e) { return [:] }
    }

    /**
     * The population options over the BUILT-IN defaults, optionally re-pointed by an override map (see
     * {@link #config}). With an empty cfg the defaults are:
     *   financialStatus = null (ALL statuses), includeCancelled = false (cancelled excluded).
     * Keys: financialStatus (List<String> | null=all), includeCancelled (boolean).
     */
    static Map defaults(Map cfg) {
        def d = (cfg?.defaults instanceof Map) ? cfg.defaults : [:]
        return [
            financialStatus : statusList(d?.financialStatus),
            includeCancelled: (d?.includeCancelled == true || d?.includeCancelled?.toString() == "true"),
        ]
    }

    // -------------------------------------------------------------------------
    // Population compiler — operator filter → XPath predicate
    // -------------------------------------------------------------------------

    /**
     * The population predicate fragment for the given opts, over the denormalized dimension props
     * (source_status / cancelled) shared by both grains. financialStatus null/empty ⇒ ALL statuses;
     * includeCancelled true ⇒ no cancelled filter. Values are xpath-sanitized (financial_status is an
     * enum-ish token, so only word chars survive), so there is no injection surface.
     */
    static String populationPredicate(Map opts) {
        def preds = []
        def statuses = (opts?.financialStatus instanceof List) ? opts.financialStatus : null
        if (statuses != null && !statuses.isEmpty()) {
            def ors = statuses.collect { xpathSafe(it?.toString()) }.findAll { it && !it.isEmpty() }
                               .collect { "@commerce:source_status = '${it}'".toString() }
            if (!ors.isEmpty()) preds << "(${ors.join(' or ')})".toString()
        }
        boolean includeCancelled = (opts?.includeCancelled == true)
        if (!includeCancelled) preds << "@commerce:cancelled = false"
        return preds.join(" and ")
    }

    // -------------------------------------------------------------------------
    // topProducts (line grain) — top-N products by base gross, with net components
    // -------------------------------------------------------------------------

    /**
     * The top {@code n} products over the range/population, by base gross, with the same-axis returns /
     * discounts / quantity so per-product net = gross − discounts − returns is composable. Keyed by REAL
     * product_id (GID on the wire) — resolves the legacy sku|title bucket collision. The
     * top() and the other grouped sums share the product_id axis; only gross uses top() to bound to N.
     */
    static List topProducts(session, long fromMs, long toMs, int n, Map opts = [:]) {
        opts = (opts == null) ? [:] : opts
        int limit = (n <= 0) ? 20 : n
        def preds = [rangePredicate("commerce:ordered_at", fromMs, toMs)]
        def pop = populationPredicate(opts)
        if (pop && !pop.isEmpty()) preds << pop

        def exprs = [
            topExpr("commerce:gross_base", "commerce:product_id", limit),   // dim: sum(commerce:gross_base,commerce:product_id)
            sumExpr("commerce:returns_base", "commerce:product_id"),
            sumExpr("commerce:discounts_base", "commerce:product_id"),
            sumExpr("commerce:quantity", "commerce:product_id"),
            sumExpr("commerce:gross", "commerce:product_id"),               // native gross by product
        ]
        def stmt = "/jcr:root${LINES_FACT_DIR}//element(*, nt:file)[${preds.join(' and ')}] facet accumulate ${exprs.join(', ')}".toString()
        def fr = facets(session, stmt)
        if (fr == null) return []

        // The top() dimension carries the top-N product_id labels in gross-descending order.
        def grossDim = sumDim("commerce:gross_base", "commerce:product_id")
        def labels = labelsOf(fr, grossDim)
        def returnsG = groupNumbers(fr, sumDim("commerce:returns_base", "commerce:product_id"))
        def discG    = groupNumbers(fr, sumDim("commerce:discounts_base", "commerce:product_id"))
        def qtyG     = groupNumbers(fr, sumDim("commerce:quantity", "commerce:product_id"))
        def grossNatG= groupNumbers(fr, sumDim("commerce:gross", "commerce:product_id"))

        def out = []
        labels.take(limit).each { pid ->
            if (pid == null || pid.toString().isEmpty()) return
            BigDecimal g  = money(number(fr, grossDim, pid)) ?: BigDecimal.ZERO
            BigDecimal dd = money(discG[pid]) ?: BigDecimal.ZERO
            BigDecimal rr = money(returnsG[pid]) ?: BigDecimal.ZERO
            out << [
                productId  : Api.gid("Product", pid),   // the ONLY id on the wire (rule 2.2 — no numeric form)
                quantity   : Api.count(qtyG[pid]) ?: 0L,
                gross      : Api.num(g, 0),
                discounts  : Api.num(dd, 0),
                returns    : Api.num(rr, 0),
                net        : Api.num(g.subtract(dd).subtract(rr), 0),
                revenue    : Api.num(money(grossNatG[pid]), 0),   // native gross (currency mix — informational)
                baseRevenue: Api.num(g, 0),
            ]
        }
        return out
    }

    // -------------------------------------------------------------------------
    // salesByProduct / spendByCustomer — the browse-sort axes
    // -------------------------------------------------------------------------

    /**
     * Per-product sales components over the range/population — ONE grouped facet pass over the
     * line-grain facts (uncapped, exact): product_id → { quantity, gross, discounts, returns, net }
     * (money in base currency, BigDecimal; quantity Long). Backs the product browse's
     * best-selling / top-gross sort. The ranking measure is the caller's choice.
     */
    static Map salesByProduct(session, long fromMs, long toMs, Map opts = [:]) {
        opts = (opts == null) ? [:] : opts
        def preds = [rangePredicate("commerce:ordered_at", fromMs, toMs)]
        def pop = populationPredicate(opts)
        if (pop && !pop.isEmpty()) preds << pop
        def exprs = [
            sumExpr("commerce:quantity", "commerce:product_id"),
            sumExpr("commerce:gross_base", "commerce:product_id"),
            sumExpr("commerce:discounts_base", "commerce:product_id"),
            sumExpr("commerce:returns_base", "commerce:product_id"),
        ]
        def stmt = "/jcr:root${LINES_FACT_DIR}//element(*, nt:file)[${preds.join(' and ')}] facet accumulate ${exprs.join(', ')}".toString()
        def fr = facets(session, stmt)
        if (fr == null) return [:]
        def qtyG   = groupNumbers(fr, sumDim("commerce:quantity", "commerce:product_id"))
        def grossG = groupNumbers(fr, sumDim("commerce:gross_base", "commerce:product_id"))
        def discG  = groupNumbers(fr, sumDim("commerce:discounts_base", "commerce:product_id"))
        def retG   = groupNumbers(fr, sumDim("commerce:returns_base", "commerce:product_id"))
        def out = [:]
        def pids = new HashSet()
        pids.addAll(qtyG.keySet()); pids.addAll(grossG.keySet())
        pids.each { pid ->
            if (pid == null || pid.toString().isEmpty()) return
            BigDecimal g  = money(grossG[pid]) ?: BigDecimal.ZERO
            BigDecimal dd = money(discG[pid]) ?: BigDecimal.ZERO
            BigDecimal rr = money(retG[pid]) ?: BigDecimal.ZERO
            out[pid.toString()] = [
                quantity : (Api.count(qtyG[pid]) ?: 0L),
                gross    : g,
                discounts: dd,
                returns  : rr,
                net      : g.subtract(dd).subtract(rr),
            ]
        }
        return out
    }

    /**
     * The wire shape of one customer's spend figures (base currency, JSON numbers) — the ONE place
     * this row fragment is formed (row-builder rule). All raw components
     * ride along; nothing is pre-selected server-side.
     */
    static Map spendRowWire(Map rec, String baseCurrency) {
        def s = rec ?: [:]
        return [
            baseCurrency: baseCurrency,
            orders      : (s.orders ?: 0L),
            totalPrice  : Api.num(s.totalPrice, 0),
            gross       : Api.num(s.gross, 0),
            discounts   : Api.num(s.discounts, 0),
            returns     : Api.num(s.returns, 0),
            net         : Api.num(s.net, 0),
        ]
    }

    /** The wire shape of one product's sales figures (base currency) — ONE place (row-builder rule). */
    static Map salesRowWire(Map rec, String baseCurrency) {
        def s = rec ?: [:]
        return [
            baseCurrency: baseCurrency,
            quantity    : (s.quantity ?: 0L),
            gross       : Api.num(s.gross, 0),
            discounts   : Api.num(s.discounts, 0),
            returns     : Api.num(s.returns, 0),
            net         : Api.num(s.net, 0),
        ]
    }

    /**
     * Per-customer spend components over the range/population — ONE grouped facet pass over the
     * order-grain facts (uncapped, exact): customer_id → { orders, totalPrice, gross, discounts,
     * returns, net } (all base currency, BigDecimal; orders Long). Backs the CRM browse's spend
     * sort and the "spent ≥ X in period" operator filter. The metric the operator ranks/filters
     * by is chosen at read time (totalPrice / gross / net) — nothing is pre-selected.
     */
    static Map spendByCustomer(session, long fromMs, long toMs, Map opts = [:]) {
        opts = (opts == null) ? [:] : opts
        def preds = [rangePredicate("commerce:ordered_at", fromMs, toMs)]
        def pop = populationPredicate(opts)
        if (pop && !pop.isEmpty()) preds << pop
        def exprs = [
            countExpr("commerce:customer_id"),                            // orders per customer
            sumExpr("commerce:total_price_base", "commerce:customer_id"),
            sumExpr("commerce:gross_base", "commerce:customer_id"),
            sumExpr("commerce:discounts_base", "commerce:customer_id"),
            sumExpr("commerce:returns_base", "commerce:customer_id"),
        ]
        def stmt = "/jcr:root${ORDERS_FACT_DIR}//element(*, nt:file)[${preds.join(' and ')}] facet accumulate ${exprs.join(', ')}".toString()
        def fr = facets(session, stmt)
        if (fr == null) return [:]
        def ordersG = groupNumbers(fr, countDim("commerce:customer_id"))
        def totalG  = groupNumbers(fr, sumDim("commerce:total_price_base", "commerce:customer_id"))
        def grossG  = groupNumbers(fr, sumDim("commerce:gross_base", "commerce:customer_id"))
        def discG   = groupNumbers(fr, sumDim("commerce:discounts_base", "commerce:customer_id"))
        def retG    = groupNumbers(fr, sumDim("commerce:returns_base", "commerce:customer_id"))
        def out = [:]
        def cids = new HashSet()
        cids.addAll(ordersG.keySet()); cids.addAll(totalG.keySet())
        cids.each { cid ->
            if (cid == null || cid.toString().isEmpty()) return
            BigDecimal g  = money(grossG[cid]) ?: BigDecimal.ZERO
            BigDecimal dd = money(discG[cid]) ?: BigDecimal.ZERO
            BigDecimal rr = money(retG[cid]) ?: BigDecimal.ZERO
            out[cid.toString()] = [
                orders    : (Api.count(ordersG[cid]) ?: 0L),
                totalPrice: money(totalG[cid]) ?: BigDecimal.ZERO,
                gross     : g,
                discounts : dd,
                returns   : rr,
                net       : g.subtract(dd).subtract(rr),
            ]
        }
        return out
    }

    // -------------------------------------------------------------------------
    // occurrenceSummary — the OCCURRENCE-DATE report: every metric counted on the date
    // its OWN event happened (new order / cancel / refund), distinct from the order-cohort
    // browse axes above. A closed month never changes because each event's date is fixed.
    // -------------------------------------------------------------------------

    /**
     * The occurrence-date summary over [from, to). FOUR facet passes over FOUR date axes, merged per day.
     * Day rows are formed AT QUERY TIME: each pass declares one {@code range()} bucket per LOCAL day of
     * {@code tz} (IANA id; UTC when absent/invalid — never the server default) directly on the absolute
     * Date prop, so the day boundaries follow the CALLER's timezone and no baked day-string props exist:
     *   • new orders    — count + base amount, bucketed on ordered_at. NO population filter: an occurrence-date
     *                     report counts EVERY created order on its creation day, whatever its financial_status
     *                     and even if it is cancelled later (the cancellation is its OWN column on its OWN day).
     *   • cancellations — count, bucketed on cancelled_at (only full-cancel facts carry it —
     *                     Shopify sets cancelled_at only on a full cancel, so partial cancels never appear).
     *   • payments      — count + base cash IN, bucketed on paid_at, over the payment store (successful
     *                     sale/capture transactions — the initial charge AND later surcharge captures / bank
     *                     transfers marked paid, each on the day the money moved). Reported POSITIVE.
     *   • refunds       — count + base cash, bucketed on refunded_at (all refunds, incl. partial-cancel reductions).
     * confirmedSales = paymentAmount + refundAmount (the PAYMENT basis: only money that actually moved —
     * cash in minus cash out — so unpaid orders, pre-payment cancellations and partial payments all
     * reconcile with zero special-casing); refundAmount is reported NEGATIVE (cash out), paymentAmount
     * POSITIVE. newOrderAmount stays a column of its own (the order-intake reference axis; receivables =
     * newOrderAmount − paymentAmount is composable by the reader). The payment store must be backfilled
     * (orders backfill re-run) before this basis reads true for historical windows. All money is
     * base-currency (the window's baseCurrency rides along). Daily rows; the client rolls up to month.
     * Windows longer than {@link #MAX_DAY_BUCKETS} days truncate to the first MAX_DAY_BUCKETS days and
     * flag {@code truncated: true} — never silently. Defensive like the rest of the reader: a bad facet
     * degrades to zeros, never throws.
     */
    static Map occurrenceSummary(session, long fromMs, long toMs, String tz = null) {
        def zone = zoneOf(tz)
        def buckets = dayBuckets(fromMs, toMs, zone)
        if (buckets.isEmpty()) {
            return emptySummary(fromMs, toMs, zone)
        }
        boolean truncated = (((List) buckets[-1])[2] as long) < toMs

        // 1. New orders bucketed on ordered_at — no population predicate (count every created order).
        def ostmt = ("/jcr:root${ORDERS_FACT_DIR}//element(*, nt:file)[${rangePredicate("commerce:ordered_at", fromMs, toMs)}]"
            + " facet accumulate ${rangeExprs("commerce:ordered_at", buckets)}"
            + ", ${sumExprByDate("commerce:total_price_base", "commerce:ordered_at")}").toString()
        def ofr = facets(session, ostmt)
        def dayNewCount = groupNumbers(ofr, "commerce:ordered_at")
        def dayNewAmt   = groupNumbers(ofr, sumDim("commerce:total_price_base", "commerce:ordered_at"))

        // 2. Cancellations bucketed on cancelled_at (present iff full-cancel).
        def cstmt = ("/jcr:root${ORDERS_FACT_DIR}//element(*, nt:file)[${rangePredicate("commerce:cancelled_at", fromMs, toMs)}]"
            + " facet accumulate ${rangeExprs("commerce:cancelled_at", buckets)}").toString()
        def cfr = facets(session, cstmt)
        def dayCancel = groupNumbers(cfr, "commerce:cancelled_at")

        // 3. Payments bucketed on paid_at — count + the CASH received (base), over the payment store. Only
        //    successful cash-in transactions are stored there (commerce.Payments.isCashIn), so no
        //    kind/status predicate is needed here. payment_amount_base is OMITTED on a known
        //    cross-currency transaction without shop_money (never a fake base), so such a payment
        //    contributes to the count but not the sum — same "not decomposable" stance as lossy facts.
        def pstmt = ("/jcr:root${PAYMENTS_RAW_DIR}//element(*, nt:file)[${rangePredicate("commerce:paid_at", fromMs, toMs)}]"
            + " facet accumulate ${rangeExprs("commerce:paid_at", buckets)}"
            + ", ${sumExprByDate("commerce:payment_amount_base", "commerce:paid_at")}").toString()
        def pfr = facets(session, pstmt)
        def dayPayCount = groupNumbers(pfr, "commerce:paid_at")
        def dayPayAmt   = groupNumbers(pfr, sumDim("commerce:payment_amount_base", "commerce:paid_at"))

        // 4. Refunds bucketed on refunded_at — count + the CASH refunded (base), over the refund store.
        //    CASH refunded = refund_amount_base (goods+tax+shipping returned) − refund_restocking_fee_income_base
        //    (the fee the store KEPT — income, not money paid out). refund_amount_base ALONE is the returned
        //    VALUE, which overstates the cash by any kept fee (that was the -¥34,000-for-a-¥29,000-refund bug);
        //    this matches the refunds block's cashOut reconciliation (cashOut = goods+tax+shipping − fee).
        def rstmt = ("/jcr:root${REFUNDS_RAW_DIR}//element(*, nt:file)[${rangePredicate("commerce:refunded_at", fromMs, toMs)}]"
            + " facet accumulate ${rangeExprs("commerce:refunded_at", buckets)}"
            + ", ${sumExprByDate("commerce:refund_amount_base", "commerce:refunded_at")}"
            + ", ${sumExprByDate("commerce:refund_restocking_fee_income_base", "commerce:refunded_at")}").toString()
        def rfr = facets(session, rstmt)
        def dayRefCount = groupNumbers(rfr, "commerce:refunded_at")
        def dayRefGross = groupNumbers(rfr, sumDim("commerce:refund_amount_base", "commerce:refunded_at"))
        def dayRefFee   = groupNumbers(rfr, sumDim("commerce:refund_restocking_fee_income_base", "commerce:refunded_at"))

        def daily = []
        long tNewC = 0L, tCancel = 0L, tPayC = 0L, tRefC = 0L
        BigDecimal tNewA = BigDecimal.ZERO, tPayA = BigDecimal.ZERO, tRefA = BigDecimal.ZERO
        // Iterate the buckets in declaration order (= chronological); skip all-zero days so the
        // wire shape stays sparse, exactly as the baked-prop grouping only returned days with data.
        buckets.each { b ->
            def d = ((List) b)[0]
            long nc = (Api.count(dayNewCount[d]) ?: 0L) as long
            BigDecimal na = money(dayNewAmt[d]) ?: BigDecimal.ZERO
            long cc = (Api.count(dayCancel[d]) ?: 0L) as long
            long pc = (Api.count(dayPayCount[d]) ?: 0L) as long
            BigDecimal pa = money(dayPayAmt[d]) ?: BigDecimal.ZERO
            long rc = (Api.count(dayRefCount[d]) ?: 0L) as long
            // CASH refunded (base) = returned value − the kept restocking fee (see the query comment).
            BigDecimal ra = (money(dayRefGross[d]) ?: BigDecimal.ZERO).subtract(money(dayRefFee[d]) ?: BigDecimal.ZERO)
            if (nc == 0L && cc == 0L && pc == 0L && rc == 0L) {
                return
            }
            daily << [
                date          : d,
                newOrderCount : nc,
                newOrderAmount: Api.num(na, 0),
                cancelledCount: cc,
                paymentCount  : pc,
                paymentAmount : Api.num(pa, 0),                   // POSITIVE — cash in
                refundCount   : rc,
                refundAmount  : Api.num(ra.negate(), 0),          // NEGATIVE — cash out
                confirmedSales: Api.num(pa.subtract(ra), 0),      // payment cash in − refund cash out
            ]
            tNewC += nc; tCancel += cc; tPayC += pc; tRefC += rc
            tNewA = tNewA.add(na); tPayA = tPayA.add(pa); tRefA = tRefA.add(ra)
        }

        int spanDays = (int) Math.max(1L, (toMs - fromMs + 86_399_999L).intdiv(86_400_000L))
        return [
            from        : Api.instant(fromMs),
            to          : Api.instant(toMs),
            days        : spanDays,
            tz          : zone.getId(),
            truncated   : truncated,
            baseCurrency: baseCurrencyOf(session, fromMs, toMs, [:]),
            daily       : daily,
            totals      : [
                newOrderCount : tNewC,
                newOrderAmount: Api.num(tNewA, 0),
                cancelledCount: tCancel,
                paymentCount  : tPayC,
                paymentAmount : Api.num(tPayA, 0),
                refundCount   : tRefC,
                refundAmount  : Api.num(tRefA.negate(), 0),
                confirmedSales: Api.num(tPayA.subtract(tRefA), 0),
            ],
        ]
    }

    /** The zero-row summary shape for an empty window (toMs <= fromMs). */
    private static Map emptySummary(long fromMs, long toMs, zone) {
        return [
            from: Api.instant(fromMs), to: Api.instant(toMs), days: 0,
            tz: zone.getId(), truncated: false,
            baseCurrency: null, daily: [],
            totals: [
                newOrderCount: 0L, newOrderAmount: 0, cancelledCount: 0L,
                paymentCount: 0L, paymentAmount: 0, refundCount: 0L,
                refundAmount: 0, confirmedSales: 0,
            ],
        ]
    }

    // -------------------------------------------------------------------------
    // Facet plumbing
    // -------------------------------------------------------------------------

    /**
     * Run an XPath statement that carries a {@code facet accumulate} clause and return its FacetResult, or
     * null on any error. The script Query re-runs the statement at limit 0 for the facet pass (ALL matches,
     * SCAN_CAP not applied) — so we never iterate getResources() here. Defensive: a malformed facet
     * expression / query throws IllegalStateException, which we swallow to a null (report degrades to zero).
     */
    static Object facets(session, String stmt) {
        try {
            def q = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
            return q.execute().getFacetResult()
        } catch (Exception e) {
            return null
        }
    }

    /** The number for one label of a facet dimension, or null. NaN/Infinity → null (empty-set stats). */
    static Number number(fr, String dim, Object label) {
        if (fr == null) return null
        try {
            def facet = fr.getFacet(dim)
            if (facet == null) return null
            def n = facet.getNumber(label?.toString())
            if (n == null) return null
            double d = n.doubleValue()
            return (Double.isNaN(d) || Double.isInfinite(d)) ? null : n
        } catch (Exception e) { return null }
    }

    /** All label→Number pairs of a grouped facet dimension (empty map when absent). */
    static Map groupNumbers(fr, String dim) {
        def out = [:]
        if (fr == null) return out
        try {
            def facet = fr.getFacet(dim)
            if (facet == null) return out
            (facet.getLabels() ?: []).each { l ->
                def n = facet.getNumber(l)
                if (n != null) { double d = n.doubleValue(); if (!Double.isNaN(d) && !Double.isInfinite(d)) out[l] = n }
            }
        } catch (Exception e) {}
        return out
    }

    /** The labels of a grouped facet dimension in facet order (top()/sum descending), or []. */
    static List labelsOf(fr, String dim) {
        if (fr == null) return []
        try {
            def facet = fr.getFacet(dim)
            return (facet == null) ? [] : ((facet.getLabels() ?: []) as List)
        } catch (Exception e) { return [] }
    }

    // --- dimension text helpers (the ONE place the facet dimension strings are formed) ---

    /** {@code sum(@a)} or {@code sum(@a, @b)} facet EXPRESSION (with the '@'). */
    static String sumExpr(String a)            { "sum(@${a})".toString() }
    static String sumExpr(String a, String by) { "sum(@${a}, @${by})".toString() }
    /**
     * {@code sum(@a, xs:dateTime(@by))} — a sum GROUPED BY a DATE prop's {@code range()} buckets. The
     * xs:dateTime cast tells the facet engine the grouping prop is a Date, so it reads the raw
     * epoch-millisecond doc values the day-bucket bounds live in; a bare {@code @by} would be decoded as
     * a double and fall in NO bucket, silently summing to zero. The result DIMENSION drops the cast
     * ({@link #sumDim} stays {@code sum(a,by)}), so retrieval is unchanged. */
    static String sumExprByDate(String a, String by) { "sum(@${a}, xs:dateTime(@${by}))".toString() }
    static String topExpr(String a, String by, int n) { "top(sum(@${a}, @${by}), ${n})".toString() }
    static String countExpr(String a)          { "@${a}".toString() }   // bare property = count facet

    /** The DIMENSION text (normalized, '@' stripped) that Facet.getFacet expects — must mirror the exprs. */
    static String sumDim(String a)             { "sum(${a})".toString() }
    static String sumDim(String a, String by)  { "sum(${a},${by})".toString() }
    static String countDim(String a)           { a }                    // count facet dimension = the property name

    // --- misc helpers ---

    /** Range predicate on a Date prop: {@code @p >= xs:dateTime('..') and @p < xs:dateTime('..')} (UTC literals). */
    static String rangePredicate(String prop, long fromMs, long toMs) {
        return "@${prop} >= xs:dateTime('${iso(fromMs)}') and @${prop} < xs:dateTime('${iso(toMs)}')".toString()
    }

    // --- query-time day axis (range() buckets on the absolute Date props) ---

    /** Max day buckets per occurrence query — range() declarations grow the statement linearly. */
    static final int MAX_DAY_BUCKETS = 400

    /** IANA zone id → ZoneId, defaulting to UTC when absent/invalid — never the server default. */
    static java.time.ZoneId zoneOf(String tz) {
        def s = tz?.toString()?.trim()
        if (s == null || s.isEmpty()) return java.time.ZoneOffset.UTC
        try { return java.time.ZoneId.of(s) } catch (Exception e) { return java.time.ZoneOffset.UTC }
    }

    /**
     * One [label, loMs, hiMs] per LOCAL day of {@code zone} covering [fromMs, toMs), chronological,
     * capped at {@link #MAX_DAY_BUCKETS}. Bounds are the local midnights (DST-safe via atStartOfDay),
     * NOT clamped to the window — the statement's base range predicate already restricts matches, so
     * the edge buckets simply cover the intersection.
     */
    static List dayBuckets(long fromMs, long toMs, zone) {
        def out = []
        if (toMs <= fromMs) return out
        def d = java.time.Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
        def last = java.time.Instant.ofEpochMilli(toMs - 1).atZone(zone).toLocalDate()
        while (!d.isAfter(last) && out.size() < MAX_DAY_BUCKETS) {
            long lo = d.atStartOfDay(zone).toInstant().toEpochMilli()
            long hi = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            out << [d.toString(), lo, hi]
            d = d.plusDays(1)
        }
        return out
    }

    /**
     * The {@code range()} bucket declarations (one per day) for a Date prop — the query-time day axis.
     * The engine counts each bucket (dimension = the prop name) and buckets any {@code sum(@x, @prop)}
     * in the same clause by these same ranges, labels in declaration order.
     */
    static String rangeExprs(String prop, List buckets) {
        return buckets.collect { b ->
            def l = (List) b
            "range('${l[0]}', xs:dateTime('${iso(l[1] as long)}') <= @${prop} < xs:dateTime('${iso(l[2] as long)}'))".toString()
        }.join(", ")
    }

    private static String iso(long ms) {
        return java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))
    }

    /** The base currency seen in the window (from the first order fact), or null. Best-effort. */
    static String baseCurrencyOf(session, long fromMs, long toMs, Map opts) {
        try {
            def preds = [rangePredicate("commerce:ordered_at", fromMs, toMs)]
            def pop = populationPredicate(opts)
            if (pop && !pop.isEmpty()) preds << pop
            def stmt = "/jcr:root${ORDERS_FACT_DIR}//element(*, nt:file)[${preds.join(' and ')}]".toString()
            def q = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
            q.limit(1)
            def rs = q.execute().getResources()
            if (rs != null && rs.length > 0 && rs[0].hasProperty("commerce:base_currency")) {
                return rs[0].getProperty("commerce:base_currency").getValue()?.toString()
            }
        } catch (Exception e) {}
        return null
    }

    /** Round a facet double to money scale (HALF_UP), or null for null/NaN/Infinity. */
    static BigDecimal money(Number n) {
        if (n == null) return null
        double d = n.doubleValue()
        if (Double.isNaN(d) || Double.isInfinite(d)) return null
        return new BigDecimal(n.toString()).setScale(2, RoundingMode.HALF_UP)
    }

    /** financialStatus config/param → a clean List<String> (null for absent/blank = ALL statuses). */
    private static List statusList(v) {
        if (v == null) return null
        if (v instanceof List) return v.collect { it?.toString()?.trim() }.findAll { it && !it.isEmpty() }
        def s = v.toString().trim()
        if (s.isEmpty()) return null
        def list = s.split(",").collect { it.trim() }.findAll { !it.isEmpty() }
        return list.isEmpty() ? null : list
    }

    // Keep a user value safe inside an XPath string literal (mirror Reports.xpathSafe).
    private static String xpathSafe(String s) {
        if (s == null) return ""
        return s.replaceAll("['\"\\[\\]\\(\\)\\\\]", " ").replaceAll("\\s+", " ").trim()
    }
}
