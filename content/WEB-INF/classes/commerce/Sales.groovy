package commerce

import java.math.BigDecimal

/**
 * Sales COMPONENT computer — the one place the raw sales components are derived
 * from a stored order (and its refunds), for the sales-fact materialization.
 *
 * Given a parsed REST order body (the source-faithful Shopify mirror) plus the
 * list of that order's parsed refund bodies, {@link #compute} returns every sales
 * COMPONENT — gross, discounts, tax, shipping, tips, duties, returns (+ returns
 * tax/shipping) and the total_price reconciliation key — in BOTH the NATIVE
 * (presentment) currency and the BASE (shop) currency, at ORDER grain and per
 * LINE grain, plus a components_complete flag and a reconciliation delta.
 *
 * It stores NO pre-selected "sales" metric (operator sovereignty, the top-level design principle):
 * gross/net/total are composed FROM these components at read time
 *   net   = gross - discounts - returns
 *   total = net + tax + shipping + tips + duties - returns_tax - returns_shipping
 * so the operator — not the system — picks the metric. The returned prop names
 * ARE the facet aggregation targets (single-valued typed Decimals so
 * `facet accumulate sum(@commerce:gross_base)` reads their SortedNumericDocValues).
 *
 * Design: every method static + PURE — no
 * repositorySession / log / JSON bindings, null-tolerant, NEVER reads or mutates
 * the raw body. All JCR I/O (writing the fact nodes, wrapping dateProps in a
 * Calendar, stamping commerce:computed_at, the single-writer / dedup / null-when-
 * incomplete policy) belongs to the sales-fact drainer, which is the sole caller.
 * All parsing goes through {@link commerce.Money}; ids/epochs through {@link commerce.Api}.
 *
 * Lives under /content/WEB-INF/classes; use via `import commerce.Sales`.
 */
class Sales {

    /** Reconciliation slack: |gross - discounts + tax + shipping + tips + duties - total_price| within
     *  this is treated as reconciled. NB zero-decimal currencies (JPY) want a whole-unit tolerance —
     *  the drainer/read layer applies currency-aware tolerance; this is only the default the computer
     *  exposes alongside the raw signed recon_delta. */
    static final BigDecimal RECON_TOLERANCE = new BigDecimal("0.01")

    /**
     * Compute the sales components for one order (+ its refunds).
     *
     * @param order        the parsed REST order body (source-faithful mirror). Null-tolerant.
     * @param refundBodies the parsed refund bodies for this order (may be null/empty). The order's
     *                     own {@code refunds[]} is NOT used — it is refresh-stale.
     * @return {@code [ order: [props: Map, dateProps: Map], lines: [ [props: Map, dateProps: Map], ... ] ]}.
     *         props: money→BigDecimal, counts→Long, flags→Boolean, ids/currency/day/month/status→String
     *         (JCR types from the Java class on setProperty). dateProps: epoch-ms Long — the drainer
     *         wraps each in a Calendar so commerce:ordered_at persists as a real Date (a Long would
     *         break xs:dateTime range/order-by). computed_at is the drainer's clock, not emitted here.
     */
    static Map compute(Map order, List refundBodies) {
        order = (order == null) ? [:] : order
        def refunds = (refundBodies == null) ? [] : refundBodies.findAll { it != null }

        // A single mutable completeness flag threaded through the base-fallback helper: base amounts
        // come ONLY from Shopify's *_set.shop_money (no external FX); when a component has a native
        // amount but no shop_money we fall back to native AND mark the order incomplete (never silent).
        def st = [complete: hasOrderDecomposition(order)]

        def items = (order.line_items instanceof List) ? order.line_items : []

        // Returns, folded from the refund bodies and indexed by line_item_id so each line gets its
        // share (order-cohort basis: attributed to the order's date, not the refund date).
        def ret = foldReturns(refunds, st)

        // ---- order-grain money components (native + base) -----------------------
        BigDecimal grossN = BigDecimal.ZERO, grossB = BigDecimal.ZERO
        BigDecimal discN = BigDecimal.ZERO, discB = BigDecimal.ZERO
        BigDecimal taxN = BigDecimal.ZERO, taxB = BigDecimal.ZERO
        items.each { li ->
            BigDecimal qty = Money.toNumber(li?.quantity) ?: BigDecimal.ZERO
            grossN = grossN.add((nativeAmt(li?.price_set, li?.price) ?: BigDecimal.ZERO).multiply(qty))
            grossB = grossB.add(baseOrNative(li?.price_set, li?.price, st).multiply(qty))
            // Discounts come ONLY from per-line discount_allocations (Shopify allocates order-level
            // discounts down to the lines). Summing order.total_discounts too would double-count; any
            // unallocated remainder surfaces in recon_delta instead.
            ((li?.discount_allocations instanceof List) ? li.discount_allocations : []).each { a ->
                discN = discN.add(nativeAmt(a?.amount_set, a?.amount) ?: BigDecimal.ZERO)
                discB = discB.add(baseOrNative(a?.amount_set, a?.amount, st))
            }
        }
        // Order-level tax: prefer the order total_tax(_set); fall back to Σ line tax_lines.
        if (order.total_tax != null || order.total_tax_set != null) {
            taxN = nativeAmt(order.total_tax_set, order.total_tax) ?: BigDecimal.ZERO
            taxB = baseOrNative(order.total_tax_set, order.total_tax, st)
        } else {
            items.each { li ->
                ((li?.tax_lines instanceof List) ? li.tax_lines : []).each { t ->
                    taxN = taxN.add(nativeAmt(t?.price_set, t?.price) ?: BigDecimal.ZERO)
                    taxB = taxB.add(baseOrNative(t?.price_set, t?.price, st))
                }
            }
        }

        // Shipping: prefer the order total_shipping_price_set; fall back to Σ shipping_lines[].price.
        BigDecimal shipN, shipB
        if (order.total_shipping_price_set != null) {
            shipN = nativeAmt(order.total_shipping_price_set, null) ?: BigDecimal.ZERO
            shipB = baseOrNative(order.total_shipping_price_set, null, st)
        } else {
            shipN = BigDecimal.ZERO; shipB = BigDecimal.ZERO
            ((order.shipping_lines instanceof List) ? order.shipping_lines : []).each { s ->
                shipN = shipN.add(nativeAmt(s?.price_set, s?.price) ?: BigDecimal.ZERO)
                shipB = shipB.add(baseOrNative(s?.price_set, s?.price, st))
            }
        }

        // Tips: Shopify has no *_set for tips, so base == native (documented, NOT a completeness fail).
        BigDecimal tipsN = Money.toNumber(order.total_tip_received) ?: BigDecimal.ZERO
        BigDecimal tipsB = tipsN

        // Duties: present only on cross-border orders (nullable) → 0/absent otherwise, no flag.
        BigDecimal dutiesN = nativeAmt(order.current_total_duties_set, null) ?: BigDecimal.ZERO
        BigDecimal dutiesB = (order.current_total_duties_set != null)
            ? baseOrNative(order.current_total_duties_set, null, st) : BigDecimal.ZERO

        // total_price — the reconciliation key, NOT a metric.
        BigDecimal totalN = nativeAmt(order.total_price_set, order.total_price) ?: BigDecimal.ZERO
        BigDecimal totalB = baseOrNative(order.total_price_set, order.total_price, st)

        // Reconciliation: the pre-refund charge should tie to total_price within rounding; the signed
        // delta captures unallocated order-level discounts / rounding for the drainer to log.
        BigDecimal reconExpectedN = grossN.subtract(discN).add(taxN).add(shipN).add(tipsN).add(dutiesN)
        BigDecimal reconExpectedB = grossB.subtract(discB).add(taxB).add(shipB).add(tipsB).add(dutiesB)
        BigDecimal reconDeltaN = reconExpectedN.subtract(totalN)
        BigDecimal reconDeltaB = reconExpectedB.subtract(totalB)

        // ---- dimensions (shared by order + line grain) --------------------------
        def orderId = Api.legacyId(order.id)
        Long orderedAtMs = Api.epochMs(order.created_at)
        def customerId = Api.legacyId(order.customer?.id)
        def currency = str(order.currency)
        def baseCurrency = str(order.total_price_set?.shop_money?.currency_code)
        def sourceStatus = str(order.financial_status)
        // str() treats blank as null, so a "" cancelled_at is not mis-read as cancelled.
        Boolean cancelled = (str(order.cancelled_at) != null)
        // Cancellation OCCURRENCE-date instant (order-level, full-cancel only — Shopify sets
        // cancelled_at only on a full cancel). The occurrence-date sales report buckets
        // cancellations on this Date prop at query time (its own date axis, distinct from
        // ordered_at — an order placed one month and cancelled the next counts in the cancel
        // month), and the order browser drills into them via the node's commerce:cancelled_at.
        Long cancelledAtMs = cancelled ? Api.epochMs(order.cancelled_at) : null

        // ---- order-grain props --------------------------------------------------
        def op = new LinkedHashMap()
        putIf(op, 'commerce:order_id', orderId)
        putIf(op, 'commerce:order_number', Api.count(order.order_number))
        putIf(op, 'commerce:customer_id', customerId)
        putIf(op, 'commerce:currency', currency)
        putIf(op, 'commerce:base_currency', baseCurrency)
        putIf(op, 'commerce:source_status', sourceStatus)
        op['commerce:cancelled'] = cancelled
        // Money components (always present, ZERO when absent, so facet SUM stays additive; the drainer
        // nulls the decomposed components when components_complete=false — "not decomposable").
        op['commerce:gross'] = grossN;             op['commerce:gross_base'] = grossB
        op['commerce:discounts'] = discN;          op['commerce:discounts_base'] = discB
        op['commerce:tax'] = taxN;                 op['commerce:tax_base'] = taxB
        op['commerce:shipping'] = shipN;           op['commerce:shipping_base'] = shipB
        op['commerce:tips'] = tipsN;               op['commerce:tips_base'] = tipsB
        op['commerce:duties'] = dutiesN;           op['commerce:duties_base'] = dutiesB
        op['commerce:returns'] = ret.returnsN;     op['commerce:returns_base'] = ret.returnsB
        op['commerce:returns_tax'] = ret.taxN;     op['commerce:returns_tax_base'] = ret.taxB
        op['commerce:returns_shipping'] = ret.shipN; op['commerce:returns_shipping_base'] = ret.shipB
        op['commerce:restocking_fee_income'] = ret.restockingFeeN; op['commerce:restocking_fee_income_base'] = ret.restockingFeeB
        op['commerce:total_price'] = totalN;       op['commerce:total_price_base'] = totalB
        op['commerce:refund_count'] = (long) refunds.size()
        op['commerce:components_complete'] = (Boolean) st.complete
        op['commerce:recon_delta'] = reconDeltaN;  op['commerce:recon_delta_base'] = reconDeltaB

        def odp = new LinkedHashMap()
        if (orderedAtMs != null) odp['commerce:ordered_at'] = orderedAtMs
        if (cancelledAtMs != null) odp['commerce:cancelled_at'] = cancelledAtMs

        // ---- line-grain facts ---------------------------------------------------
        def lines = []
        items.each { li ->
            def lineId = Api.legacyId(li?.id)
            BigDecimal qty = Money.toNumber(li?.quantity) ?: BigDecimal.ZERO
            BigDecimal lgN = (nativeAmt(li?.price_set, li?.price) ?: BigDecimal.ZERO).multiply(qty)
            BigDecimal lgB = baseOrNative(li?.price_set, li?.price, st).multiply(qty)
            BigDecimal ldN = BigDecimal.ZERO, ldB = BigDecimal.ZERO
            ((li?.discount_allocations instanceof List) ? li.discount_allocations : []).each { a ->
                ldN = ldN.add(nativeAmt(a?.amount_set, a?.amount) ?: BigDecimal.ZERO)
                ldB = ldB.add(baseOrNative(a?.amount_set, a?.amount, st))
            }
            BigDecimal ltN = BigDecimal.ZERO, ltB = BigDecimal.ZERO
            ((li?.tax_lines instanceof List) ? li.tax_lines : []).each { t ->
                ltN = ltN.add(nativeAmt(t?.price_set, t?.price) ?: BigDecimal.ZERO)
                ltB = ltB.add(baseOrNative(t?.price_set, t?.price, st))
            }
            def rl = ret.byLine.get(lineId?.toString()) ?: [qty: 0L, retN: BigDecimal.ZERO, retB: BigDecimal.ZERO]

            def lp = new LinkedHashMap()
            putIf(lp, 'commerce:order_id', orderId)
            putIf(lp, 'commerce:line_id', lineId)
            putIf(lp, 'commerce:product_id', Api.legacyId(li?.product_id))
            putIf(lp, 'commerce:variant_id', Api.legacyId(li?.variant_id))
            putIf(lp, 'commerce:sku', str(li?.sku))
            // denormalized order dimensions so line-grain facet queries need no join
            putIf(lp, 'commerce:customer_id', customerId)
            putIf(lp, 'commerce:currency', currency)
            putIf(lp, 'commerce:source_status', sourceStatus)
            lp['commerce:cancelled'] = cancelled
            lp['commerce:quantity'] = (Money.toNumber(li?.quantity) ?: BigDecimal.ZERO).toBigInteger().longValue()
            lp['commerce:gross'] = lgN;        lp['commerce:gross_base'] = lgB
            lp['commerce:discounts'] = ldN;    lp['commerce:discounts_base'] = ldB
            lp['commerce:tax'] = ltN;          lp['commerce:tax_base'] = ltB
            lp['commerce:returned_quantity'] = (long) rl.qty
            lp['commerce:returns'] = rl.retN;  lp['commerce:returns_base'] = rl.retB

            def ldp = new LinkedHashMap()
            if (orderedAtMs != null) ldp['commerce:ordered_at'] = orderedAtMs

            lines << [props: lp, dateProps: ldp]
        }

        return [order: [props: op, dateProps: odp], lines: lines]
    }

    // --- Component helpers -----------------------------------------------------

    /** NATIVE (presentment) amount: the set's presentment_money, else the plain scalar field. Null when both absent. */
    static BigDecimal nativeAmt(setObj, scalar) {
        def n = Money.toNumber(setObj?.presentment_money?.amount)
        return (n != null) ? n : Money.toNumber(scalar)
    }

    /** BASE (shop) amount from *_set.shop_money ONLY (no external FX). Null when absent. */
    static BigDecimal baseAmt(setObj) {
        return Money.toNumber(setObj?.shop_money?.amount)
    }

    /**
     * The BASE amount, falling back to NATIVE when shop_money is absent — and, when it falls back on a
     * non-zero native amount, marking the order incomplete (never silently substitute).
     * Always returns non-null (ZERO at worst) so BigDecimal sums stay safe.
     */
    static BigDecimal baseOrNative(setObj, scalar, Map st) {
        def b = baseAmt(setObj)
        if (b != null) return b
        def n = nativeAmt(setObj, scalar)
        if (n != null && n.signum() != 0) {
            st.complete = false
            return n
        }
        return (n != null) ? n : BigDecimal.ZERO
    }

    /**
     * Fold returns from all refund bodies: goods (refund_line_items[].subtotal), returns tax
     * (refund_line_items[].total_tax), and returns shipping (order_adjustments kind=shipping_refund,
     * stored as a positive magnitude so read-time subtraction is uniform). Also indexes per line_item_id
     * for line-grain attribution. Zero-money / restock-only refund lines still count returned_quantity.
     */
    static Map foldReturns(List refunds, Map st) {
        BigDecimal returnsN = BigDecimal.ZERO, returnsB = BigDecimal.ZERO
        BigDecimal rtaxN = BigDecimal.ZERO, rtaxB = BigDecimal.ZERO
        BigDecimal rshipN = BigDecimal.ZERO, rshipB = BigDecimal.ZERO
        BigDecimal feeN = BigDecimal.ZERO, feeB = BigDecimal.ZERO   // restocking fee (refund_discrepancy), SIGNED
        def byLine = [:]   // line_id(String) -> [qty: Long, retN: BigDecimal, retB: BigDecimal]

        refunds.each { r ->
            ((r?.refund_line_items instanceof List) ? r.refund_line_items : []).each { rli ->
                BigDecimal subN = nativeAmt(rli?.subtotal_set, rli?.subtotal) ?: BigDecimal.ZERO
                BigDecimal subB = baseOrNative(rli?.subtotal_set, rli?.subtotal, st)
                BigDecimal txN = nativeAmt(rli?.total_tax_set, rli?.total_tax) ?: BigDecimal.ZERO
                BigDecimal txB = baseOrNative(rli?.total_tax_set, rli?.total_tax, st)
                long q = (Money.toNumber(rli?.quantity) ?: BigDecimal.ZERO).toBigInteger().longValue()
                returnsN = returnsN.add(subN); returnsB = returnsB.add(subB)
                rtaxN = rtaxN.add(txN);        rtaxB = rtaxB.add(txB)

                // Normalize the refund's line_item_id the SAME way the order line id is keyed
                // (Api.legacyId) so a GID-form id (a GraphQL/Bulk-sourced refund in a later phase)
                // still matches the numeric-keyed order line instead of silently attributing 0.
                def key = Api.legacyId(rli?.line_item_id)
                if (key != null) {
                    def agg = byLine.get(key)
                    if (agg == null) { agg = [qty: 0L, retN: BigDecimal.ZERO, retB: BigDecimal.ZERO]; byLine.put(key, agg) }
                    agg.qty = ((long) agg.qty) + q
                    agg.retN = ((BigDecimal) agg.retN).add(subN)
                    agg.retB = ((BigDecimal) agg.retB).add(subB)
                }
            }
            ((r?.order_adjustments instanceof List) ? r.order_adjustments : []).each { adj ->
                def kind = adj?.kind?.toString()?.toLowerCase()
                if (kind == 'shipping_refund') {
                    def n = nativeAmt(adj?.amount_set, adj?.amount)
                    def b = baseOrNative(adj?.amount_set, adj?.amount, st)
                    if (n != null) rshipN = rshipN.add(n.abs())
                    rshipB = rshipB.add(b.abs())
                } else if (kind == 'refund_discrepancy') {
                    // The restocking fee the store kept — income, NOT a refunded magnitude, so it is summed
                    // SIGNED (positive = kept, negative = over-refunded). abs() here is what mis-classified
                    // it as a return; the sign carries the meaning.
                    def n = nativeAmt(adj?.amount_set, adj?.amount)
                    def b = baseOrNative(adj?.amount_set, adj?.amount, st)
                    if (n != null) feeN = feeN.add(n)
                    feeB = feeB.add(b)
                }
            }
        }
        return [returnsN: returnsN, returnsB: returnsB, taxN: rtaxN, taxB: rtaxB,
                shipN: rshipN, shipB: rshipB, restockingFeeN: feeN, restockingFeeB: feeB, byLine: byLine]
    }

    /**
     * Whether the order body carries its component DECOMPOSITION. A source-faithful webhook body always
     * emits total_tax / total_shipping_price_set / total_discounts and per-line tax_lines/
     * discount_allocations (even when zero); the current lossy bulk-normalized body emits NONE of them.
     * So this cleanly distinguishes a complete webhook body from a lossy bulk one (and would also
     * recognize a future bulk body enriched to carry the same decomposition). Gating on FIELD PRESENCE,
     * never on value==0, keeps a legit zero-tax order from being mis-flagged.
     */
    static boolean hasOrderDecomposition(order) {
        if (order == null) return false
        if (order.total_tax != null) return true
        if (order.total_tax_set != null) return true
        if (order.total_shipping_price_set != null) return true
        if (order.total_discounts != null) return true
        def items = (order.line_items instanceof List) ? order.line_items : []
        return items.any { it?.tax_lines != null || it?.discount_allocations != null }
    }

    // --- Small helpers ---------------------------------------------------------

    private static String str(v) {
        if (v == null) return null
        def s = v.toString()
        return s.trim().isEmpty() ? null : s
    }

    /** Put only when the value is non-null (ids/dims are omitted, never written blank). */
    private static void putIf(Map m, String k, v) {
        if (v != null) m.put(k, v)
    }

}
