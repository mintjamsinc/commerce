package commerce

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * PURE P/L ladder — composes a set of already-summed sales components (base currency) into the ten
 * canonical P/L figures, ADDITIVELY (never by back-subtraction), and asserts the internal invariants.
 *
 * No repository / script bindings — pass a Map of BigDecimal-ish component sums; this is the one
 * testable/reviewable piece (the reader class does the I/O). Every figure is a LINEAR combination of the
 * inputs, so the ladder distributes over addition: applying it to per-day sums and summing the results
 * equals applying it to the period total (the daily-vs-total snapshot identity holds by construction).
 *
 * netSales is built UP (gross − discounts − returns), never derived by subtracting tax/shipping from a
 * charged total — that back-subtraction is what silently absorbs unmodelled income (tips, duties) into
 * net sales. The returns figures follow the caller's basis; the income side is always order-cohort.
 *
 * LINEAR FIELDS ONLY. Every figure here MUST be a linear combination (sums/differences) of the component
 * sums — that is what makes the ladder distribute over addition (per-day results sum to the period
 * result). Do NOT add a ratio, average, or median (e.g. a return rate returns/grossSales): a non-linear
 * field breaks the daily-equals-total identity silently. Put derived non-linear values outside the
 * ladder (alongside stats).
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.SalesLadder}.
 */
class SalesLadder {

    static final String BASIS_ORDER = "order"
    static final String BASIS_REFUND = "refund"

    /** Money slack tolerated by the invariant asserts (zero-decimal base currency → sub-unit is drift). */
    static final BigDecimal INVARIANT_TOLERANCE = new BigDecimal("0.01")

    /**
     * Compose the P/L ladder from component sums. Keys read from {@code comps} (missing → ZERO):
     *   gross, discounts, returns, returnsTax, returnsShipping, returnsDuties,
     *   tax, shipping, tips, duties
     * The tax / shipping inputs are the GROSS (pre-returns) sums; the ladder nets the returned portion
     * internally. Returns an ordered Map of the ten figures + {@code basis}. Throws IllegalStateException
     * when an internal invariant does not hold (never silently rounds a mismatch away).
     */
    static Map compute(Map comps, String basis) {
        def c = (comps == null) ? [:] : comps
        BigDecimal gross     = money(c.gross)
        BigDecimal discounts = money(c.discounts)
        BigDecimal returns   = money(c.returns)
        BigDecimal retTax    = money(c.returnsTax)
        BigDecimal retShip   = money(c.returnsShipping)
        BigDecimal retDuties = money(c.returnsDuties)
        BigDecimal taxGross  = money(c.tax)
        BigDecimal shipGross = money(c.shipping)
        BigDecimal tips      = money(c.tips)
        BigDecimal restockingFee = money(c.restockingFee)
        BigDecimal dutyGross = money(c.duties)

        // Income side (tax-exclusive P/L): net sales are composed up, not derived by subtraction.
        // otherIncome carries TWO single-cohort parts kept separate on the wire: tips (order cohort) and
        // the restocking fee the store kept on a refund (moves with the returns basis). Folding them into
        // one field would hide a mixed cohort inside a "linear" figure.
        BigDecimal netSales     = gross.subtract(discounts).subtract(returns)
        BigDecimal shippingNet  = shipGross.subtract(retShip)
        BigDecimal otherIncome  = tips.add(restockingFee)
        BigDecimal totalRevenue = netSales.add(shippingNet).add(otherIncome)

        // Held funds (not revenue): net of the returned portion, same basis as returns.
        BigDecimal taxNet    = taxGross.subtract(retTax)
        BigDecimal dutiesNet = dutyGross.subtract(retDuties)
        BigDecimal totalCharged = totalRevenue.add(taxNet).add(dutiesNet)

        def pl = [
            basis        : (basis == BASIS_REFUND) ? BASIS_REFUND : BASIS_ORDER,
            grossSales   : scale(gross),
            discounts    : scale(discounts),
            returns      : scale(returns),
            netSales     : scale(netSales),
            shipping     : scale(shippingNet),
            tips         : scale(tips),
            restockingFees: scale(restockingFee),
            otherIncome  : scale(otherIncome),
            totalRevenue : scale(totalRevenue),
            tax          : scale(taxNet),
            duties       : scale(dutiesNet),
            totalCharged : scale(totalCharged),
        ]
        assertInvariants(pl)
        return pl
    }

    /**
     * The internal invariants the ladder must satisfy horizontally. Recomputes from the OUTPUT figures and
     * throws IllegalStateException on drift beyond the tolerance — so a future edit that breaks the
     * additive composition (e.g. a back-subtracted netSales, or an income term dropped from totalCharged)
     * fails loudly instead of silently mis-stating revenue.
     */
    static void assertInvariants(Map pl) {
        BigDecimal netSales = money(pl?.netSales)
        BigDecimal expectNet = money(pl?.grossSales).subtract(money(pl?.discounts)).subtract(money(pl?.returns))
        if (netSales.subtract(expectNet).abs().compareTo(INVARIANT_TOLERANCE) > 0) {
            throw new IllegalStateException("P/L invariant violated: netSales ${netSales} != grossSales - discounts - returns ${expectNet}")
        }
        BigDecimal otherIncome = money(pl?.otherIncome)
        BigDecimal expectOther = money(pl?.tips).add(money(pl?.restockingFees))
        if (otherIncome.subtract(expectOther).abs().compareTo(INVARIANT_TOLERANCE) > 0) {
            throw new IllegalStateException("P/L invariant violated: otherIncome ${otherIncome} != tips + restockingFees ${expectOther}")
        }
        BigDecimal totalCharged = money(pl?.totalCharged)
        BigDecimal expectTotal = money(pl?.netSales).add(money(pl?.shipping)).add(money(pl?.otherIncome))
                .add(money(pl?.tax)).add(money(pl?.duties))
        if (totalCharged.subtract(expectTotal).abs().compareTo(INVARIANT_TOLERANCE) > 0) {
            throw new IllegalStateException("P/L invariant violated: totalCharged ${totalCharged} != netSales + shipping + otherIncome + tax + duties ${expectTotal}")
        }
    }

    /** A component value → BigDecimal (ZERO for null); accepts BigDecimal, Number, or numeric String. */
    private static BigDecimal money(v) {
        if (v == null) return BigDecimal.ZERO
        if (v instanceof BigDecimal) return (BigDecimal) v
        return new BigDecimal(v.toString())
    }

    private static BigDecimal scale(BigDecimal v) { v.setScale(2, RoundingMode.HALF_UP) }
}
