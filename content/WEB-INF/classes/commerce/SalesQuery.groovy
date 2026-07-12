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
 * Operator sovereignty (a top-level design principle): this class stores NO pre-selected "sales" number. It returns the
 * raw components — gross, discounts, tax, shipping, tips, duties, returns (+returns tax/shipping) — and
 * SYNTHESIZES the metrics the operator asked for:
 *   net_sales   = gross − discounts − returns
 *   total_sales = net + tax + shipping + tips + duties − returns_tax − returns_shipping
 * The population (financial_status / cancelled) and the returns date basis are the operator's choice too
 * (sales.yml defaults or per-request), compiled to an XPath predicate — never hard-coded.
 *
 * Grains queried:
 *   order-grain  {@link #ORDERS_FACT_DIR}  — period / population / currency / KPI / PoP / distribution.
 *   line-grain   {@link #LINES_FACT_DIR}   — product-attributed metrics (topProducts).
 *   refund store {@link #REFUNDS_RAW_DIR}  — refund-period returns (returnsBasis=refund) by refunded_at.
 *
 * Prop name == facet dimension text is a CONTRACT: a single set of dimension helpers
 * ({@link #sumDim}/{@link #statsDim}/{@link #pctDim}) build the normalized "@"-stripped text that the
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

    static final String ORDERS_FACT_DIR = "/content/commerce/sales/orders/index"
    static final String LINES_FACT_DIR  = "/content/commerce/sales/lines/index"
    static final String REFUNDS_RAW_DIR = "/content/commerce/refunds/raw"
    static final String CONFIG_PATH     = "/etc/commerce/config/sales.yml"

    static final String BASIS_ORDER   = "order"
    static final String BASIS_REFUND  = "refund"

    /** The base-currency money components summed at order grain (typed Decimal props → facet SUM axes). */
    private static final List ORDER_COMPONENTS = [
        "gross_base", "discounts_base", "tax_base", "shipping_base", "tips_base", "duties_base",
        "returns_base", "returns_tax_base", "returns_shipping_base",
        // Restocking fee: read BOTH the current name and the pre-rename name until every historical order
        // has been recomputed under the new name, so pl.restockingFees never dips to a silent 0 (an order
        // carries exactly one of them, so summing both never double-counts). The old name drops to 0 once
        // all orders have been recomputed; remove "restocking_fee_base" here after that is confirmed.
        "restocking_fee_income_base", "restocking_fee_base",
    ]

    // -------------------------------------------------------------------------
    // Config (sales.yml) — the operator's defaults
    // -------------------------------------------------------------------------

    /** Parsed sales.yml (empty map when absent). Classes cannot use the YAML binding — SimpleYaml here. */
    static Map config(session) {
        def res = Jcr.safeGet(session, CONFIG_PATH)
        if (res == null || !res.exists()) return [:]
        try { return SimpleYaml.parse(res.content?.toString()) ?: [:] }
        catch (Exception e) { return [:] }
    }

    /**
     * The report options resolved from sales.yml defaults, overridable per-request. Keys:
     *   financialStatus (List<String> | null=all), includeCancelled (boolean), returnsBasis ("order"|"refund").
     */
    static Map defaults(Map cfg) {
        def d = (cfg?.defaults instanceof Map) ? cfg.defaults : [:]
        return [
            financialStatus : statusList(d?.financialStatus),
            includeCancelled: (d?.includeCancelled == true || d?.includeCancelled?.toString() == "true"),
            returnsBasis    : (d?.returnsBasis?.toString()?.trim()?.toLowerCase() == BASIS_REFUND) ? BASIS_REFUND : BASIS_ORDER,
        ]
    }

    /** Merge per-request overrides onto the sales.yml defaults (null override = keep default). */
    static Map resolveOpts(Map cfg, Map overrides) {
        def base = defaults(cfg)
        if (overrides == null) return base
        if (overrides.containsKey("financialStatus") && overrides.financialStatus != null)
            base.financialStatus = (overrides.financialStatus instanceof List) ? overrides.financialStatus : statusList(overrides.financialStatus)
        if (overrides.containsKey("includeCancelled") && overrides.includeCancelled != null)
            base.includeCancelled = (overrides.includeCancelled == true || overrides.includeCancelled?.toString() == "true")
        if (overrides.containsKey("returnsBasis") && overrides.returnsBasis != null)
            base.returnsBasis = (overrides.returnsBasis.toString().trim().toLowerCase() == BASIS_REFUND) ? BASIS_REFUND : BASIS_ORDER
        return base
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

    /** A human label of the population in effect, echoed on the response so a report is self-describing. */
    static String populationLabel(Map opts) {
        def statuses = (opts?.financialStatus instanceof List && !opts.financialStatus.isEmpty()) ? opts.financialStatus.join(",") : "all"
        return "financialStatus=${statuses}; includeCancelled=${opts?.includeCancelled == true}".toString()
    }

    // -------------------------------------------------------------------------
    // salesRange (order grain) — the full component report over [from, to)
    // -------------------------------------------------------------------------

    /**
     * All sales metrics over an ordered_at range for the given population. opts (see {@link #resolveOpts}):
     * financialStatus / includeCancelled / returnsBasis, plus daily:false to skip the day timeseries.
     * Returns a report Map (days/from/to/totals/daily/topProducts…) with the full component + metric +
     * distribution breakdown — the operator picks which figure to read.
     */
    static Map salesRange(session, long fromMs, long toMs, Map opts = [:]) {
        opts = (opts == null) ? [:] : opts
        boolean wantDaily  = (opts.daily != false)
        String basis = (opts.returnsBasis == BASIS_REFUND) ? BASIS_REFUND : BASIS_ORDER

        def preds = [rangePredicate("commerce:ordered_at", fromMs, toMs)]
        def pop = populationPredicate(opts)
        if (pop && !pop.isEmpty()) preds << pop

        // Build the facet accumulate clause: distribution + completeness + every base component + native
        // by currency (+ per-day groups). One clause = one single-pass aggregation.
        def exprs = []
        exprs << statsExpr("commerce:total_price_base")
        exprs << pctExpr("commerce:total_price_base", [25, 50, 75, 95])
        exprs << sumBoolExpr("commerce:components_complete")       // bool 0/1 → count of complete orders
        exprs << sumExpr("commerce:total_price_base")
        exprs << sumExpr("commerce:recon_delta_base")
        ORDER_COMPONENTS.each { exprs << sumExpr("commerce:${it}") }
        exprs << sumExpr("commerce:total_price", "commerce:currency")   // native revenue by currency
        exprs << sumExpr("commerce:gross", "commerce:currency")         // native gross by currency
        exprs << countExpr("commerce:base_currency")                    // base currency label (no extra query)
        if (wantDaily) {
            exprs << countExpr("commerce:ordered_day")                  // orders per day
            exprs << sumExpr("commerce:total_price_base", "commerce:ordered_day")
            ["gross_base", "discounts_base", "returns_base", "tax_base", "shipping_base", "tips_base", "duties_base",
             "returns_tax_base", "returns_shipping_base", "restocking_fee_income_base", "restocking_fee_base"].each {
                exprs << sumExpr("commerce:${it}", "commerce:ordered_day")
            }
        }

        def stmt = "/jcr:root${ORDERS_FACT_DIR}//element(*, nt:file)[${preds.join(' and ')}] facet accumulate ${exprs.join(', ')}".toString()
        def fr = facets(session, stmt)

        def stats = statsOf(fr, "commerce:total_price_base")
        long orders = (stats.count == null) ? 0L : stats.count.longValue()
        long complete = numOr0(single(fr, sumDim("commerce:components_complete"))).longValue()

        // Base component sums.
        def comp = [:]
        comp.totalPrice = single(fr, sumDim("commerce:total_price_base"))
        ORDER_COMPONENTS.each { comp[camelComp(it)] = single(fr, sumDim("commerce:${it}")) }

        // Synthesize the metrics (all base).
        BigDecimal gross    = money(comp.gross)    ?: BigDecimal.ZERO
        BigDecimal disc     = money(comp.discounts) ?: BigDecimal.ZERO
        BigDecimal retGoods = money(comp.returns)  ?: BigDecimal.ZERO
        BigDecimal tax      = money(comp.tax)      ?: BigDecimal.ZERO
        BigDecimal shipping = money(comp.shipping) ?: BigDecimal.ZERO
        BigDecimal tips     = money(comp.tips)     ?: BigDecimal.ZERO
        BigDecimal duties   = money(comp.duties)   ?: BigDecimal.ZERO
        BigDecimal retTax   = money(comp.returnsTax) ?: BigDecimal.ZERO
        BigDecimal retShip  = money(comp.returnsShipping) ?: BigDecimal.ZERO
        // Dual-read the current + pre-rename restocking-fee component (see ORDER_COMPONENTS).
        BigDecimal restockingFee = (money(comp.restockingFeeIncome) ?: BigDecimal.ZERO)
                .add(money(comp.restockingFee) ?: BigDecimal.ZERO)

        // The P/L reading order: the returns figure the operator sees is ALWAYS the tax-inclusive
        // refunded total, so switching the returns basis only changes WHICH DATE the money is
        // attributed to (order-cohort components vs the refund store by refunded_at) — never the
        // definition of the number itself. The tax portion rides along for the "(incl. tax N)"
        // breakdown next to it.
        BigDecimal returnsTotal, returnsTaxPortion
        if (basis == BASIS_REFUND) {
            def rp = refundPeriodReturns(session, fromMs, toMs)
            returnsTotal = (BigDecimal) rp.total
            returnsTaxPortion = (BigDecimal) rp.tax
        } else {
            returnsTotal = retGoods.add(retTax).add(retShip)
            returnsTaxPortion = retTax
        }

        // salesTotal: the tax-inclusive, pre-discount charge (goods + tax + shipping + tips +
        // duties) — the P/L flow's opening figure. totalSales = salesTotal − discounts −
        // returnsTotal, i.e. what was actually kept, tax-inclusive. netSales stays the STABLE
        // tax-exclusive goods metric (gross − discounts − returned goods, order-cohort — the
        // Shopify Analytics "net sales" axis), independent of the returns basis.
        BigDecimal salesTotal = gross.add(tax).add(shipping).add(tips).add(duties)
        BigDecimal net   = gross.subtract(disc).subtract(retGoods)
        BigDecimal total = salesTotal.subtract(disc).subtract(returnsTotal)

        // Native revenue by currency + the base currency of the window. The base currency rides the SAME
        // facet pass as a count facet on commerce:base_currency (its labels ARE the currency codes,
        // most-frequent first) — no second query.
        def revenueByCur = groupNumbers(fr, sumDim("commerce:total_price", "commerce:currency"))
        def grossByCur   = groupNumbers(fr, sumDim("commerce:gross", "commerce:currency"))
        def baseCurLabels = labelsOf(fr, countDim("commerce:base_currency"))
        String baseCurrency = baseCurLabels.isEmpty() ? null : baseCurLabels[0]?.toString()

        // Daily timeseries.
        def daily = []
        if (wantDaily) {
            def dayOrders = groupNumbers(fr, countDim("commerce:ordered_day"))
            def dayTotal  = groupNumbers(fr, sumDim("commerce:total_price_base", "commerce:ordered_day"))
            def dayGross  = groupNumbers(fr, sumDim("commerce:gross_base", "commerce:ordered_day"))
            def dayDisc   = groupNumbers(fr, sumDim("commerce:discounts_base", "commerce:ordered_day"))
            def dayRet    = groupNumbers(fr, sumDim("commerce:returns_base", "commerce:ordered_day"))
            def dayTax    = groupNumbers(fr, sumDim("commerce:tax_base", "commerce:ordered_day"))
            def dayShip   = groupNumbers(fr, sumDim("commerce:shipping_base", "commerce:ordered_day"))
            def dayTips   = groupNumbers(fr, sumDim("commerce:tips_base", "commerce:ordered_day"))
            def dayDuties = groupNumbers(fr, sumDim("commerce:duties_base", "commerce:ordered_day"))
            def dayRetTax = groupNumbers(fr, sumDim("commerce:returns_tax_base", "commerce:ordered_day"))
            def dayRetShip= groupNumbers(fr, sumDim("commerce:returns_shipping_base", "commerce:ordered_day"))
            def dayFee    = groupNumbers(fr, sumDim("commerce:restocking_fee_income_base", "commerce:ordered_day"))
            def dayFeeOld = groupNumbers(fr, sumDim("commerce:restocking_fee_base", "commerce:ordered_day"))   // dual-read (pre-rename)
            def days = new TreeSet()
            days.addAll(dayOrders.keySet()); days.addAll(dayTotal.keySet())
            days.each { d ->
                BigDecimal g = money(dayGross[d]) ?: BigDecimal.ZERO
                BigDecimal dd = money(dayDisc[d]) ?: BigDecimal.ZERO
                BigDecimal rr = money(dayRet[d]) ?: BigDecimal.ZERO
                BigDecimal tx = money(dayTax[d]) ?: BigDecimal.ZERO
                BigDecimal sh = money(dayShip[d]) ?: BigDecimal.ZERO
                BigDecimal tp = money(dayTips[d]) ?: BigDecimal.ZERO
                BigDecimal du = money(dayDuties[d]) ?: BigDecimal.ZERO
                BigDecimal rtx = money(dayRetTax[d]) ?: BigDecimal.ZERO
                BigDecimal rsh = money(dayRetShip[d]) ?: BigDecimal.ZERO
                BigDecimal rf = (money(dayFee[d]) ?: BigDecimal.ZERO).add(money(dayFeeOld[d]) ?: BigDecimal.ZERO)
                BigDecimal dnet = g.subtract(dd).subtract(rr)
                // P/L flow per row (always order-cohort — a daily row must reconcile horizontally):
                //   salesTotal − discounts − returnsTotal = total; taxNet/shippingNet are the
                //   "(of which)" informational splits of total; net stays the tax-exclusive goods
                //   metric. The raw components ride too (CSV/detail readers).
                BigDecimal dSalesTotal = g.add(tx).add(sh).add(tp).add(du)
                BigDecimal dReturnsTotal = rr.add(rtx).add(rsh)
                daily << [
                    date       : d,
                    orders     : Api.count(dayOrders[d]) ?: 0L,
                    baseRevenue: Api.num(money(dayTotal[d]), 0),
                    salesTotal : Api.num(dSalesTotal, 0),
                    returnsTotal: Api.num(dReturnsTotal, 0),
                    gross      : Api.num(g, 0), discounts: Api.num(dd, 0), returns: Api.num(rr, 0),
                    returnsTax : Api.num(rtx, 0), returnsShipping: Api.num(rsh, 0),
                    tax        : Api.num(tx, 0), shipping: Api.num(sh, 0),
                    taxNet     : Api.num(tx.subtract(rtx), 0), shippingNet: Api.num(sh.subtract(rsh), 0),
                    tips       : Api.num(tp, 0), duties: Api.num(du, 0),
                    net        : Api.num(dnet, 0),
                    total      : Api.num(dSalesTotal.subtract(dd).subtract(dReturnsTotal), 0),
                    // The canonical tax-exclusive P/L reading, composed additively (order cohort).
                    pl         : plWire([gross:g, discounts:dd, returns:rr, returnsTax:rtx, returnsShipping:rsh,
                                         tax:tx, shipping:sh, tips:tp, duties:du, restockingFee:rf], basis),
                ]
            }
        }

        // The totals P/L (order cohort) + the diagnostics. All surfaced, never thrown (a report must not
        // 500): the ladder never throws on valid sums, drift/lossy figures are money the P/L does not yet
        // place, and the report shows them rather than dropping or asserting them.
        def totalsPl = plWire([gross:gross, discounts:disc, returns:retGoods, returnsTax:retTax,
                               returnsShipping:retShip, tax:tax, shipping:shipping, tips:tips, duties:duties,
                               restockingFee:restockingFee], basis)
        // lossyRevenue: the total_price carried by components_complete=false orders that the component
        // ladder omits (reconExpected is complete-only; reconDelta is complete-only, so it corrects it).
        BigDecimal reconExpected = gross.subtract(disc).add(tax).add(shipping).add(tips).add(duties)
        BigDecimal baseRev = money(comp.totalPrice) ?: BigDecimal.ZERO
        BigDecimal reconDeltaB = money(single(fr, sumDim("commerce:recon_delta_base"))) ?: BigDecimal.ZERO
        def diagnostics = [
            lossyOrders : Math.max(0L, orders - complete),
            lossyRevenue: Api.num(baseRev.subtract(reconExpected).add(reconDeltaB), 0),
        ]
        // The day-vs-total drift and the refund-side reconciliation ride the full report only (pop's
        // internal windows pass daily:false and do not need them — saves the extra refund facet pass).
        if (wantDaily) {
            diagnostics.dayTotalDrift = dayTotalDrift(daily, totalsPl)
            diagnostics.putAll(refundDiagnostics(session, fromMs, toMs))
        }
        // The cash-out (refunds) block — refund-date view, separate from pl (pl untouched → dayTotalDrift 0).
        def refundsB = wantDaily ? refundsBlock(session, fromMs, toMs, baseCurrency) : null

        int spanDays = (int) Math.max(1L, (toMs - fromMs + 86_399_999L).intdiv(86_400_000L))
        return [
            days       : spanDays,
            from       : Api.instant(fromMs),
            to         : Api.instant(toMs),
            returnsBasis: basis,
            // The daily rows' returns are ALWAYS order-cohort (the refund store carries no day grouping
            // axis), so under returnsBasis=refund the range-level returns figure and the daily column use
            // different bases — labelled here so the report stays self-describing.
            dailyReturnsBasis: BASIS_ORDER,
            population : populationLabel(opts),
            totals     : [
                orders          : orders,
                completeOrders  : complete,
                incompleteOrders: Math.max(0L, orders - complete),
                baseCurrency    : baseCurrency,
                revenue         : moneyArray(revenueByCur),      // native total_price by currency (legacy-compat)
                nativeGross     : moneyArray(grossByCur),
                baseRevenue     : Api.num(money(comp.totalPrice), 0),   // Σ total_price_base (the cross-currency rollup)
                reconDelta      : Api.num(money(single(fr, sumDim("commerce:recon_delta_base"))), 0),
                components      : [
                    gross          : Api.num(gross, 0), discounts: Api.num(disc, 0), tax: Api.num(tax, 0),
                    shipping       : Api.num(shipping, 0), tips: Api.num(tips, 0), duties: Api.num(duties, 0),
                    returns        : Api.num(retGoods, 0), returnsTax: Api.num(retTax, 0), returnsShipping: Api.num(retShip, 0),
                    restockingFeeIncome: Api.num(restockingFee, 0),
                    totalPrice     : Api.num(money(comp.totalPrice), 0),
                ],
                metrics         : [
                    // P/L flow: salesTotal − discounts − returnsTotal = totalSales; netSales is the
                    // stable tax-exclusive goods metric (Shopify Analytics axis).
                    salesTotal : Api.num(salesTotal, 0),
                    discounts  : Api.num(disc, 0),
                    returnsTotal: Api.num(returnsTotal, 0),
                    returnsTax : Api.num(returnsTaxPortion, 0),
                    grossSales : Api.num(gross, 0),
                    netSales   : Api.num(net, 0),
                    totalSales : Api.num(total, 0),
                    returns    : Api.num(returnsTotal, 0),
                ],
                // The canonical tax-exclusive P/L block (order cohort), composed additively so tips /
                // duties cannot leak into netSales. Additive to metrics — new consumers read pl only.
                pl              : totalsPl,
                // Diagnostic drift (Σ daily.pl − totals.pl), zero when the buckets reconcile. Surfaced,
                // never thrown — same treatment as the lossy / unclassified figures.
                diagnostics     : diagnostics,
                // Cash-out summary (refund-date view). The per-day rows ride the top-level `refunds` block.
                refunds         : refundsB?.summary,
                stats           : [
                    count: orders, sum: Api.num(money(stats.sum), 0),
                    min  : Api.num(money(stats.min), 0), max: Api.num(money(stats.max), 0), avg: Api.num(money(stats.avg), 0),
                ],
                percentiles     : percentilesOf(fr, "commerce:total_price_base", [25, 50, 75, 95]),
            ],
            daily      : daily,
            // The refund-date cash-out day rows (separate from the order-date `daily` above). Null when
            // daily is not requested; the totals summary rides totals.refunds.
            refunds    : (refundsB == null) ? null : [daily: refundsB.daily],
            topProducts: [],   // populated by the endpoint via topProducts() when groupBy=product is requested
        ]
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
    // byCustomer / pop
    // -------------------------------------------------------------------------

    /** The top {@code n} customers over the range/population, by base total_price (customer_id axis). */
    static List byCustomer(session, long fromMs, long toMs, int n, Map opts = [:]) {
        opts = (opts == null) ? [:] : opts
        int limit = (n <= 0) ? 20 : n
        def preds = [rangePredicate("commerce:ordered_at", fromMs, toMs)]
        def pop = populationPredicate(opts)
        if (pop && !pop.isEmpty()) preds << pop
        // top() carries the customer_id axis itself (its dimension IS sum(total_price_base,customer_id)),
        // so a plain grouped sum on the same axis would be a duplicate dimension — declare only the top().
        def stmt = "/jcr:root${ORDERS_FACT_DIR}//element(*, nt:file)[${preds.join(' and ')}] facet accumulate ${topExpr("commerce:total_price_base", "commerce:customer_id", limit)}".toString()
        def fr = facets(session, stmt)
        if (fr == null) return []
        def dim = sumDim("commerce:total_price_base", "commerce:customer_id")
        def out = []
        labelsOf(fr, dim).take(limit).each { cid ->
            if (cid == null || cid.toString().isEmpty()) return
            out << [customerId: Api.gid("Customer", cid),   // GID only on the wire (rule 2.2)
                    baseRevenue: Api.num(money(number(fr, dim, cid)), 0)]
        }
        return out
    }

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

    /**
     * Period-over-period: the same population over the current [from, to) and the immediately-preceding
     * window of equal length, with the component/metric deltas and change ratios. Both windows are
     * index-backed on ordered_at. Pass {@code current} when the caller already aggregated the current
     * window with the same opts (the reports endpoint does) — it saves a full facet pass.
     */
    static Map pop(session, long fromMs, long toMs, Map opts = [:], Map current = null) {
        long span = Math.max(1L, toMs - fromMs)
        def curOpts = new LinkedHashMap(opts ?: [:]); curOpts.daily = false
        def cur  = (current != null) ? current : salesRange(session, fromMs, toMs, curOpts)
        def prev = salesRange(session, fromMs - span, fromMs, curOpts)
        def keys = ["grossSales", "netSales", "totalSales", "returns"]
        def delta = [:], changePct = [:]
        keys.each { k ->
            BigDecimal c = Money.toNumber(cur.totals.metrics[k]) ?: BigDecimal.ZERO
            BigDecimal p = Money.toNumber(prev.totals.metrics[k]) ?: BigDecimal.ZERO
            delta[k] = Api.num(c.subtract(p), 0)
            changePct[k] = (p.signum() == 0) ? null : Api.num(c.subtract(p).multiply(new BigDecimal("100")).divide(p, 4, RoundingMode.HALF_UP), 0)
        }
        def orderDelta = (long) ((cur.totals.orders ?: 0L) - (prev.totals.orders ?: 0L))
        return [current: cur, previous: prev, delta: delta, changePct: changePct, ordersDelta: orderDelta]
    }

    // -------------------------------------------------------------------------
    // refund-period returns (returnsBasis=refund) — the refund store by refunded_at
    // -------------------------------------------------------------------------

    /**
     * The refund-period returns over refunded_at ∈ [from, to): {@code total} = Σ refund_amount_base
     * (the tax-inclusive cash refunded) and {@code tax} = Σ refund_tax_base (its consumption-tax
     * portion, for the "(incl. tax N)" breakdown). One facet pass.
     */
    static Map refundPeriodReturns(session, long fromMs, long toMs) {
        def stmt = ("/jcr:root${REFUNDS_RAW_DIR}//element(*, nt:file)[${rangePredicate("commerce:refunded_at", fromMs, toMs)}]"
            + " facet accumulate ${sumExpr("commerce:refund_amount_base")}, ${sumExpr("commerce:refund_tax_base")}").toString()
        def fr = facets(session, stmt)
        return [
            total: money(single(fr, sumDim("commerce:refund_amount_base"))) ?: BigDecimal.ZERO,
            tax  : money(single(fr, sumDim("commerce:refund_tax_base"))) ?: BigDecimal.ZERO,
        ]
    }

    /**
     * The refund-side reconciliation diagnostics over refunded_at ∈ [from, to) — ONE facet pass over the
     * A′ props the drainer stamps (never re-reading bodies):
     *   unclassifiedRefundAdjustments   Σ refund_recon_delta_base (the cash-anchored residual — money the
     *                                   P/L does not yet place, e.g. a restocking fee the store kept)
     *   transactionlessRefundsWithValue count of refunds with value returned but no cash transaction
     *   unreconciledRefunds             refunds NOT yet stamped by the A′ layer (pre-migration). While this
     *                                   is > 0 the adjustment total is INCOMPLETE — surfaced, not hidden,
     *                                   so "verified clean" is never confused with "not yet checked".
     */
    static Map refundDiagnostics(session, long fromMs, long toMs) {
        def stmt = ("/jcr:root${REFUNDS_RAW_DIR}//element(*, nt:file)[${rangePredicate("commerce:refunded_at", fromMs, toMs)}]"
            + " facet accumulate ${statsExpr("commerce:line_item_count")}"
            + ", ${sumExpr("commerce:refund_recon_delta_base")}"
            + ", ${sumBoolExpr("commerce:refund_reconciled")}"
            + ", ${sumBoolExpr("commerce:refund_transactionless_value")}").toString()
        def fr = facets(session, stmt)
        def stats = statsOf(fr, "commerce:line_item_count")
        long total = (stats.count == null) ? 0L : stats.count.longValue()
        long reconciled = numOr0(single(fr, sumDim("commerce:refund_reconciled"))).longValue()
        return [
            unclassifiedRefundAdjustments  : Api.num(money(single(fr, sumDim("commerce:refund_recon_delta_base"))), 0),
            transactionlessRefundsWithValue: numOr0(single(fr, sumDim("commerce:refund_transactionless_value"))).longValue(),
            unreconciledRefunds            : Math.max(0L, total - reconciled),
        ]
    }

    /**
     * The cash-out (refunds) block — the refund-DATE view, kept SEPARATE from pl (which is order-date only)
     * so pl / dayTotalDrift are never touched. Refunds with refunded_at ∈ [from,to), grouped by refunded_day.
     * cashOut is the ACTUAL cash (native refund_amount); the breakdown reconciles as
     * {@code cashOut == goods + tax + shipping − restockingFeeIncome} (= refundOutflow − restockingFeeIncome).
     * {@code crossPeriod} flags a day whose refunds include an order that fell OUTSIDE the window — derived
     * by comparing the per-day count of ALL refunds vs refunds whose order is also in the window (a query-
     * time relation, not a stored prop). {@code mixedCurrency} (native cash across currencies) and
     * {@code unmigratedRefunds} (refunds not yet carrying refunded_day) surface incompleteness, never hide it.
     */
    static Map refundsBlock(session, long fromMs, long toMs, String baseCurrency) {
        def inWindow = rangePredicate("commerce:refunded_at", fromMs, toMs)
        def stmtA = ("/jcr:root${REFUNDS_RAW_DIR}//element(*, nt:file)[${inWindow}] facet accumulate "
            + "${statsExpr("commerce:line_item_count")}"
            + ", ${countExpr("commerce:refunded_day")}"
            + ", ${sumExpr("commerce:refund_amount", "commerce:refunded_day")}"
            + ", ${sumExpr("commerce:refund_returns_base", "commerce:refunded_day")}"
            + ", ${sumExpr("commerce:refund_tax_base", "commerce:refunded_day")}"
            + ", ${sumExpr("commerce:refund_returns_shipping_base", "commerce:refunded_day")}"
            + ", ${sumExpr("commerce:refund_restocking_fee_income_base", "commerce:refunded_day")}"
            + ", ${countExpr("commerce:currency")}").toString()
        def frA = facets(session, stmtA)
        // Refunds whose ORDER is also in the window — per-day count compared against ALL gives crossPeriod.
        def stmtB = ("/jcr:root${REFUNDS_RAW_DIR}//element(*, nt:file)[${inWindow} and ${rangePredicate("commerce:refund_ordered_at", fromMs, toMs)}]"
            + " facet accumulate ${countExpr("commerce:refunded_day")}").toString()
        def frB = facets(session, stmtB)

        def cStats = statsOf(frA, "commerce:line_item_count")
        long totalRefunds = (cStats.count == null) ? 0L : cStats.count.longValue()
        def dayAll  = groupNumbers(frA, countDim("commerce:refunded_day"))
        def dayCash = groupNumbers(frA, sumDim("commerce:refund_amount", "commerce:refunded_day"))
        def dayGoods= groupNumbers(frA, sumDim("commerce:refund_returns_base", "commerce:refunded_day"))
        def dayTax  = groupNumbers(frA, sumDim("commerce:refund_tax_base", "commerce:refunded_day"))
        def dayShip = groupNumbers(frA, sumDim("commerce:refund_returns_shipping_base", "commerce:refunded_day"))
        def dayFee  = groupNumbers(frA, sumDim("commerce:refund_restocking_fee_income_base", "commerce:refunded_day"))
        def dayInWin= groupNumbers(frB, countDim("commerce:refunded_day"))

        boolean mixed = labelsOf(frA, countDim("commerce:currency")).any {
            it != null && baseCurrency != null && it.toString() != baseCurrency
        }

        def daily = []
        BigDecimal tCash = BigDecimal.ZERO, tGoods = BigDecimal.ZERO, tTax = BigDecimal.ZERO
        BigDecimal tShip = BigDecimal.ZERO, tFee = BigDecimal.ZERO
        long shownCount = 0L
        new TreeSet(dayAll.keySet()).each { d ->
            long cnt = (Api.count(dayAll[d]) ?: 0L) as long
            long inWin = (Api.count(dayInWin[d]) ?: 0L) as long
            BigDecimal cash = money(dayCash[d]) ?: BigDecimal.ZERO
            BigDecimal goods = money(dayGoods[d]) ?: BigDecimal.ZERO
            BigDecimal tax = money(dayTax[d]) ?: BigDecimal.ZERO
            BigDecimal ship = money(dayShip[d]) ?: BigDecimal.ZERO
            BigDecimal fee = money(dayFee[d]) ?: BigDecimal.ZERO
            daily << [
                refundedDay: d, refundCount: cnt,
                cashOut: Api.num(cash, 0), goods: Api.num(goods, 0), tax: Api.num(tax, 0),
                shipping: Api.num(ship, 0), restockingFeeIncome: Api.num(fee, 0),
                crossPeriod: (cnt > inWin),
            ]
            tCash = tCash.add(cash); tGoods = tGoods.add(goods); tTax = tTax.add(tax)
            tShip = tShip.add(ship); tFee = tFee.add(fee); shownCount += cnt
        }
        BigDecimal refundOutflow = tGoods.add(tTax).add(tShip)
        def summary = [
            refundCount        : totalRefunds,
            cashOut            : Api.num(tCash, 0),
            refundOutflow      : Api.num(refundOutflow, 0),
            goods              : Api.num(tGoods, 0),
            tax                : Api.num(tTax, 0),
            shipping           : Api.num(tShip, 0),
            restockingFeeIncome: Api.num(tFee, 0),
            mixedCurrency      : mixed,
            unmigratedRefunds  : Math.max(0L, totalRefunds - shownCount),
        ]
        return [summary: summary, daily: daily]
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

    /** The single-value ("value" label) number of a facet dimension, or null (dimension absent / NaN). */
    static Number single(fr, String dim) {
        return number(fr, dim, "value")
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

    /** The stats(...) labels → Number for a numeric prop (count/missing/sum/min/max/avg/…), null-tolerant. */
    static Map statsOf(fr, String prop) {
        def dim = statsDim(prop)
        return [
            count: number(fr, dim, "count"), sum: number(fr, dim, "sum"),
            min  : number(fr, dim, "min"),   max: number(fr, dim, "max"), avg: number(fr, dim, "avg"),
        ]
    }

    private static Map percentilesOf(fr, String prop, List ps) {
        def dim = pctDim(prop, ps)
        def out = [:]
        ps.each { p -> out["p${p}"] = Api.num(money(number(fr, dim, p.toString())), 0) }
        return out
    }

    // --- dimension text helpers (the ONE place the facet dimension strings are formed) ---

    /** {@code sum(@a)} or {@code sum(@a, @b)} facet EXPRESSION (with the '@'). */
    static String sumExpr(String a)            { "sum(@${a})".toString() }
    static String sumExpr(String a, String by) { "sum(@${a}, @${by})".toString() }
    /**
     * {@code sum(xs:boolean(@a))} facet EXPRESSION for a BOOLEAN property. Boolean doc values are
     * stored raw (0/1) while the aggregator's default decoding assumes the sortable-double encoding
     * of numbers, so the {@code xs:} cast (the same syntax the order-by clause takes) names the
     * encoding IN the query — without it the sum collapses to ~0. The result DIMENSION stays
     * {@code sum(a)} (a cast never changes how the result is addressed), so {@link #sumDim} reads it
     * unchanged. Numbers need no cast; a date aggregate would take {@code xs:dateTime} the same way.
     */
    static String sumBoolExpr(String a)        { "sum(xs:boolean(@${a}))".toString() }
    static String topExpr(String a, String by, int n) { "top(sum(@${a}, @${by}), ${n})".toString() }
    static String statsExpr(String a)          { "stats(@${a})".toString() }
    static String pctExpr(String a, List ps)   { "percentile(@${a}, ${ps.join(', ')})".toString() }
    static String countExpr(String a)          { "@${a}".toString() }   // bare property = count facet

    /** The DIMENSION text (normalized, '@' stripped) that Facet.getFacet expects — must mirror the exprs. */
    static String sumDim(String a)             { "sum(${a})".toString() }
    static String sumDim(String a, String by)  { "sum(${a},${by})".toString() }
    static String statsDim(String a)           { "stats(${a})".toString() }
    static String pctDim(String a, List ps)    { "percentile(${a},${ps.join(',')})".toString() }
    static String countDim(String a)           { a }                    // count facet dimension = the property name

    // --- misc helpers ---

    /** Range predicate on a Date prop: {@code @p >= xs:dateTime('..') and @p < xs:dateTime('..')} (UTC literals). */
    static String rangePredicate(String prop, long fromMs, long toMs) {
        return "@${prop} >= xs:dateTime('${iso(fromMs)}') and @${prop} < xs:dateTime('${iso(toMs)}')".toString()
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

    /**
     * The P/L ladder (order cohort) rendered to the wire from a Map of component BigDecimals
     * (gross/discounts/returns/returnsTax/returnsShipping/tax/shipping/tips/duties). The ten figures are
     * composed additively by {@link commerce.SalesLadder} — the ONE place the P/L reading is formed —
     * so tips/duties can never be back-subtracted into netSales.
     *
     * The ladder is always order cohort for now, so {@code basis} is always "order"; {@code requestedBasis}
     * echoes what the operator asked for, so a refund-basis request that the ladder cannot yet honour is
     * visible on the wire (basis != basisRequested) instead of silently ignored.
     */
    private static Map plWire(Map comps, String requestedBasis) {
        def pl = SalesLadder.compute(comps, SalesLadder.BASIS_ORDER)
        return [
            basis         : pl.basis,
            basisRequested: (requestedBasis == BASIS_REFUND) ? BASIS_REFUND : BASIS_ORDER,
            grossSales    : Api.num(pl.grossSales, 0),
            discounts     : Api.num(pl.discounts, 0),
            returns       : Api.num(pl.returns, 0),
            netSales      : Api.num(pl.netSales, 0),
            shipping      : Api.num(pl.shipping, 0),
            tips          : Api.num(pl.tips, 0),
            restockingFees: Api.num(pl.restockingFees, 0),
            otherIncome   : Api.num(pl.otherIncome, 0),
            totalRevenue  : Api.num(pl.totalRevenue, 0),
            tax           : Api.num(pl.tax, 0),
            duties        : Api.num(pl.duties, 0),
            totalCharged  : Api.num(pl.totalCharged, 0),
        ]
    }

    /** The P/L ladder fields (the linear figures) — the axes {@link #dayTotalDrift} reconciles. */
    private static final List PL_FIELDS = [
        "grossSales", "discounts", "returns", "netSales", "shipping", "tips", "restockingFees",
        "otherIncome", "totalRevenue", "tax", "duties", "totalCharged",
    ]

    /**
     * Per-field Σ(daily.pl) − totals.pl. Zero for every field when the daily buckets reconcile to the
     * total (the ladder is linear, so arithmetic drift is impossible — a non-zero here means the daily
     * and total facets disagree on population / day boundary, which linearity does NOT guard). Diagnostic
     * only: surfaced on the wire, never thrown, so the report cannot 500 on it.
     */
    private static Map dayTotalDrift(List daily, Map totalsPl) {
        def out = [:]
        PL_FIELDS.each { f ->
            BigDecimal sum = BigDecimal.ZERO
            (daily ?: []).each { row ->
                def v = money(Money.toNumber(row?.pl?.get(f)))
                if (v != null) sum = sum.add(v)
            }
            BigDecimal tot = money(Money.toNumber(totalsPl?.get(f))) ?: BigDecimal.ZERO
            out[f] = Api.num(sum.subtract(tot), 0)
        }
        return out
    }

    /** Round a facet double to money scale (HALF_UP), or null for null/NaN/Infinity. */
    static BigDecimal money(Number n) {
        if (n == null) return null
        double d = n.doubleValue()
        if (Double.isNaN(d) || Double.isInfinite(d)) return null
        return new BigDecimal(n.toString()).setScale(2, RoundingMode.HALF_UP)
    }

    private static Number numOr0(Number n) { n == null ? (Number) 0L : n }

    /** A {label→Number} map → the wire money array [{currency, amount}] (native per-currency breakdown). */
    private static List moneyArray(Map byCur) {
        def out = []
        (byCur ?: [:]).each { k, v -> def m = Api.money(k, money(v)); if (m != null) out << m }
        return out
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

    private static String camelComp(String base) {
        // "returns_tax_base" → "returnsTax"; "gross_base" → "gross"; strip the _base suffix + camelCase.
        def s = base.endsWith("_base") ? base.substring(0, base.length() - 5) : base
        return Api.camel(s)
    }

    // Keep a user value safe inside an XPath string literal (mirror Reports.xpathSafe).
    private static String xpathSafe(String s) {
        if (s == null) return ""
        return s.replaceAll("['\"\\[\\]\\(\\)\\\\]", " ").replaceAll("\\s+", " ").trim()
    }
}
