package commerce

/**
 * Helpers for interpreting Shopify order transaction (payment) payloads.
 *
 * Every method takes an already-parsed REST transaction body (Map, as produced by the
 * script's JSON binding or by {@link commerce.PaymentMirror#toRestTransaction}) and is
 * PURE — no repository or script bindings — so it is safe to call from any context.
 * The cash-in judgment here is the ONE definition both the webhook route and the bulk
 * import gate on, so the two paths can never disagree on what counts as a payment.
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.Payments}.
 */
class Payments {

    /**
     * Transaction kinds that represent money actually RECEIVED (cash in): an immediate
     * charge (sale) or the capture of a prior authorization. authorization/void move no
     * money; refund cash-out is anchored by the refund store (commerce.Refunds), so a
     * kind=refund transaction must NEVER become a payment node (double count).
     */
    static final List<String> CASH_IN_KINDS = ["sale", "capture"]

    /**
     * Whether a transaction is a successful cash-in event (kind sale/capture and status
     * success). A missing status counts as success, mirroring {@link commerce.Refunds#amount}'s
     * transaction reading. Everything else (authorization, void, refund, failures,
     * pending) is NOT a payment.
     */
    static boolean isCashIn(txn) {
        def kind = txn?.kind?.toString()?.toLowerCase()
        if (kind == null || !CASH_IN_KINDS.contains(kind)) return false
        def status = txn?.status?.toString()?.toLowerCase()
        return status == null || status == "success"
    }

    /** The NATIVE cash amount of the transaction, or null when absent. */
    static BigDecimal amount(txn) {
        return Money.toNumber(txn?.amount)
    }

    /**
     * The BASE (shop) currency amount of the transaction. Shopify's own conversion
     * ({@code amount_set.shop_money}, present on GraphQL/bulk-sourced bodies) wins when
     * available. A REST webhook transaction body carries NO shop_money, so we fall back
     * to the NATIVE amount when the transaction currency matches the shop currency or
     * either currency is unknown (single-currency shop assumption). A KNOWN cross-currency
     * transaction without shop_money returns null — "base unavailable", never a fake
     * native-as-base number (the same no-fake-zero principle as the lossy order facts).
     */
    static BigDecimal amountBase(txn, String shopCurrency) {
        def b = Money.toNumber(txn?.amount_set?.shop_money?.amount)
        if (b != null) return b
        def n = amount(txn)
        if (n == null) return null
        def cur = currency(txn)
        if (shopCurrency == null || cur == null || cur.equalsIgnoreCase(shopCurrency)) return n
        return null
    }

    /** Currency of the transaction (upper-cased), or null when absent. */
    static String currency(txn) {
        def c = txn?.currency?.toString()?.trim()
        return (c == null || c.isEmpty()) ? null : c.toUpperCase()
    }

    /**
     * The payment's business timestamp (epoch ms): processed_at (when the gateway moved
     * the money — e.g. a bank transfer marked paid days after the order) falling back to
     * created_at. Null when neither parses.
     */
    static Long paidAtMs(txn) {
        def ms = Api.epochMs(txn?.processed_at)
        return (ms != null) ? ms : Api.epochMs(txn?.created_at)
    }
}
