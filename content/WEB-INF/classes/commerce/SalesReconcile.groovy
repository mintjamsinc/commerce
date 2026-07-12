package commerce

import java.math.BigDecimal

/**
 * PURE reconciliation guards for the sales facts — the request side (A) and the refund side (A′). No
 * repository / script bindings; pass parsed order / refund bodies (REST maps). This is the reviewable,
 * testable piece: the drainer WARNS on a ring (it must never drop a fact), the report SURFACES the
 * residual as a diagnostic (a report must not 500), and the self-tests THROW.
 *
 * A  (request side): the parsed components must reconcile to the amount actually charged —
 *     |gross − discounts + tax + shipping + tips + duties − total_price| within tolerance. This is the
 *     recon_delta the {@link commerce.Sales} decomposition already computes; a ring means Shopify charged
 *     something no component models (a wiring gap that would understate revenue).
 * A′ (refund side): the returned value the ladder subtracts (goods + tax + shipping, order cohort) must
 *     match the CASH actually refunded (Σ successful refund transactions). A ring means money moved that
 *     the returns decomposition does not account for — e.g. a restocking fee the store kept.
 *
 * The cash anchor is native only (refund transactions carry no shop_money); for a single-currency shop
 * native == base, so the caller must guard {@code currency == baseCurrency} rather than silently trust it
 * cross-currency. The three-way classification stops a restock-only refund (no cash, nothing returned)
 * from being mistaken for a wiring gap.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.SalesReconcile}.
 */
class SalesReconcile {

    static final BigDecimal TOLERANCE = Sales.RECON_TOLERANCE

    /** A refund backed by a cash transaction — A′ applies (delta = returned value − cash). */
    static final String CASH_BACKED = "cash_backed"
    /** No cash transaction and nothing of value returned — a pure restock; A′ is skipped. */
    static final String RESTOCK_ONLY = "restock_only"
    /** No cash transaction but value WAS returned — money owed but not moved; must not pass silently. */
    static final String TRANSACTIONLESS_WITH_VALUE = "transactionless_with_value"

    /** Marker: the refund has been reconciled by this layer (distinguishes "verified" from "not yet"). */
    static final String P_RECONCILED = "commerce:refund_reconciled"
    /** The A′ residual (base) persisted per refund — Σ over a window is the unclassified refund adjustment. */
    static final String P_RECON_DELTA = "commerce:refund_recon_delta_base"
    /** Flag: no cash transaction but value returned — the count the report surfaces. */
    static final String P_TRANSACTIONLESS = "commerce:refund_transactionless_value"

    /** Refund-cohort breakdown persisted per refund for the cash-out (refunds) block: goods / shipping /
     *  restocking-fee-income (SIGNED, income). tax rides the existing commerce:refund_tax_base; cash rides
     *  the existing commerce:refund_amount (native). */
    static final String P_RETURNS = "commerce:refund_returns_base"
    static final String P_RETURNS_SHIPPING = "commerce:refund_returns_shipping_base"
    static final String P_RESTOCKING_FEE_INCOME = "commerce:refund_restocking_fee_income_base"

    /** A: whether the request-side recon delta (base) is within tolerance. Null/absent → NOT ok (unknown). */
    static boolean orderReconOk(reconDeltaBase) {
        if (reconDeltaBase == null) return false
        return num(reconDeltaBase).abs().compareTo(TOLERANCE) <= 0
    }

    /**
     * A′ + three-way classification for one refund body. Returns a Map:
     *   classification : cash_backed | restock_only | transactionless_with_value
     *   cash           : Σ successful refund transactions (native), or null when there are none
     *   refundExpected : goods + tax + shipping the ladder subtracts (base, order cohort)
     *   delta          : refundExpected − cash (null when no cash) — the A′ residual (signed: + = store kept)
     *   rings          : true when |delta| exceeds tolerance
     *   currency       : the refund transaction currency (for the caller's base-currency guard)
     */
    static Map refundReconcile(Map refund) {
        def st = [complete: true]
        def fr = Sales.foldReturns([refund], st)
        BigDecimal returnedValue = num(fr.returnsB).add(num(fr.taxB)).add(num(fr.shipB))
        // The restocking fee (refund_discrepancy) is a KNOWN adjustment: the store kept it, so it reduces
        // the cash refunded. Fold it in, so the expected cash accounts for it and A′ stays silent on it —
        // A′ then rings ONLY on an adjustment kind nothing models (a genuine wiring gap).
        BigDecimal restockingFee = num(fr.restockingFeeB)
        BigDecimal expectedCash = returnedValue.subtract(restockingFee)
        BigDecimal cash = Refunds.amount(refund)   // native; null when no successful refund transaction

        String classification
        BigDecimal delta = null
        boolean rings = false
        if (cash != null) {
            classification = CASH_BACKED
            delta = expectedCash.subtract(cash)
            rings = delta.abs().compareTo(TOLERANCE) > 0
        } else if (returnedValue.abs().compareTo(TOLERANCE) <= 0) {
            classification = RESTOCK_ONLY
        } else {
            classification = TRANSACTIONLESS_WITH_VALUE
        }
        return [
            classification: classification,
            cash          : cash,
            returnedValue : returnedValue,   // goods + tax + shipping (the line-item refund)
            restockingFee : restockingFee,   // refund_discrepancy the store kept (signed)
            refundExpected: expectedCash,     // returnedValue − restockingFee = the expected cash
            delta         : delta,
            rings         : rings,
            currency      : Refunds.currency(refund),
        ]
    }

    /**
     * The persisted reconcile props for a refund + the reconcile result. The ONE place the refund
     * reconcile prop names/values are formed — the drainer writers and the migration apply {@code props}
     * to the node and log from {@code reconcile}. cash_backed carries its residual; the other classes
     * carry ZERO (so Σ over a window sums only real residuals), plus the transactionless flag.
     */
    static Map reconProps(Map refund) {
        def rc = refundReconcile(refund)
        def fr = Sales.foldReturns([refund], [complete: true])
        def props = new LinkedHashMap()
        props[P_RECONCILED] = Boolean.TRUE
        props[P_RECON_DELTA] = (rc.classification == CASH_BACKED && rc.delta != null) ? rc.delta : BigDecimal.ZERO
        props[P_TRANSACTIONLESS] = (rc.classification == TRANSACTIONLESS_WITH_VALUE)
        // Cash-out breakdown (refund cohort) for the refunds block.
        props[P_RETURNS] = num(fr.returnsB)
        props[P_RETURNS_SHIPPING] = num(fr.shipB)
        props[P_RESTOCKING_FEE_INCOME] = num(fr.restockingFeeB)
        return [props: props, reconcile: rc]
    }

    /**
     * The shop (base) currency the refund's shop_money is denominated in, or null — for the caller's
     * cross-currency guard. The cash anchor is native, so native != base means the base ladder has no
     * cash anchor for this refund and the drainer must warn rather than trust it silently.
     */
    static String baseCurrencyOf(Map refund) {
        for (rli in (refund?.refund_line_items ?: [])) {
            def c = rli?.subtotal_set?.shop_money?.currency_code
            if (c != null && !c.toString().trim().isEmpty()) return c.toString()
        }
        for (adj in (refund?.order_adjustments ?: [])) {
            def c = adj?.amount_set?.shop_money?.currency_code
            if (c != null && !c.toString().trim().isEmpty()) return c.toString()
        }
        return null
    }

    private static BigDecimal num(v) {
        if (v == null) return BigDecimal.ZERO
        if (v instanceof BigDecimal) return (BigDecimal) v
        return new BigDecimal(v.toString())
    }

    /** yyyy-MM-dd of an epoch-ms instant in the server zone (matches the order-grain ordered_day format). */
    static String dayOf(Long ms) {
        if (ms == null) return null
        return java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    }
}
