package commerce

/**
 * Helpers for interpreting Shopify refund webhook payloads.
 *
 * Every method takes an already-parsed refund / line-item (Map/List, as produced
 * by the script's JSON binding) and is pure - no repository or script bindings
 * required - so it is safe to call from any context. Lives under
 * /content/WEB-INF/classes; use via `import commerce.Refunds`.
 */
class Refunds {

    /**
     * Money actually returned to the customer: the sum of the successful refund
     * transactions. Returns null when there is no such transaction, so callers
     * can distinguish "nothing refunded" from "zero".
     */
    static BigDecimal amount(refund) {
        def txns = refund?.transactions ?: []
        BigDecimal total = BigDecimal.ZERO
        boolean seen = false
        for (t in txns) {
            def kind = t?.kind?.toString()?.toLowerCase()
            def status = t?.status?.toString()?.toLowerCase()
            if (kind == "refund" && (status == null || status == "success")) {
                def amt = Money.toNumber(t?.amount)
                if (amt != null) {
                    total = total.add(amt)
                    seen = true
                }
            }
        }
        return seen ? total : null
    }

    /**
     * The refunded total in the SHOP (base) currency — the base counterpart of {@link #amount} for the
     * refund-period sales view (returnsBasis=refund). Reconstructed from the ONLY parts of a refund that
     * carry Shopify's own shop_money conversion: refund_line_items[] (goods subtotal + tax) and
     * order_adjustments[] (shipping etc., stored as a positive magnitude). No external FX. Returns null
     * when no shop_money is present anywhere, so "base unavailable" is distinguishable from zero.
     *
     * NB the native {@link #amount} sums the transactions (cash returned); this sums the line-item
     * breakdown, the only place shop_money exists — for a single-currency shop they coincide.
     */
    static BigDecimal amountBase(refund) {
        BigDecimal total = BigDecimal.ZERO
        boolean seen = false
        for (rli in (refund?.refund_line_items ?: [])) {
            def sub = Money.toNumber(rli?.subtotal_set?.shop_money?.amount)
            if (sub != null) { total = total.add(sub); seen = true }
            def tax = Money.toNumber(rli?.total_tax_set?.shop_money?.amount)
            if (tax != null) { total = total.add(tax); seen = true }
        }
        for (adj in (refund?.order_adjustments ?: [])) {
            // refund_discrepancy is the restocking fee the store KEPT — income, not money refunded — so it
            // must NOT be added to the refunded total (adding its magnitude is what overstated returns).
            if (adj?.kind?.toString()?.toLowerCase() == "refund_discrepancy") continue
            def amt = Money.toNumber(adj?.amount_set?.shop_money?.amount)
            if (amt != null) { total = total.add(amt.abs()); seen = true }
        }
        return seen ? total : null
    }

    /**
     * The consumption-tax portion of the refund in the BASE currency: Σ refund_line_items[].total_tax
     * (shop_money, falling back to the native scalar). Null when no line carries a tax amount, so
     * "unavailable" stays distinguishable from a genuine zero-tax refund.
     */
    static BigDecimal taxBase(refund) {
        BigDecimal total = BigDecimal.ZERO
        boolean seen = false
        for (rli in (refund?.refund_line_items ?: [])) {
            def tax = Money.toNumber(rli?.total_tax_set?.shop_money?.amount)
            if (tax == null) tax = Money.toNumber(rli?.total_tax)
            if (tax != null) { total = total.add(tax); seen = true }
        }
        return seen ? total : null
    }

    /** Currency of the refund transactions (upper-cased), or null if none carry one. */
    static String currency(refund) {
        def txns = refund?.transactions ?: []
        for (t in txns) {
            def c = t?.currency?.toString()?.trim()
            if (c) return c.toUpperCase()
        }
        return null
    }

    /**
     * A refund line item restocks inventory unless its restock_type is
     * "no_restock" (or "none"). A missing restock_type is treated as not restocked.
     */
    static boolean isRestocked(lineItem) {
        def type = lineItem?.restock_type?.toString()?.toLowerCase()
        return type != null && type != "no_restock" && type != "none"
    }
}
