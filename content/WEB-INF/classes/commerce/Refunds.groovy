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
