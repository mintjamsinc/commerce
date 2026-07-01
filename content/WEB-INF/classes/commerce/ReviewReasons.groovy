package commerce

/**
 * Structured "review reason" message descriptors shared across the order/refund
 * screening workflow.
 *
 * Why this exists
 * ---------------
 * The screening scripts (screenOrder / screenRefund) decide WHY an order or
 * refund needs a human review. That decision is consumed in two very different
 * contexts:
 *
 *   - the review FORMS (order-review.html / refund-review.html), rendered in the
 *     reviewer's own locale and time zone with the Tasks Form SDK's ICU engine;
 *   - the operational NOTIFICATIONS (Slack, Discord, Teams, LINE, webhook,
 *     email), rendered server-side with no per-user locale.
 *
 * If the reason were pre-rendered to a fixed string in Groovy (the old
 * approach), the form could never localize it and money/numbers could never
 * follow the reviewer's locale. So instead the producer emits a *descriptor* —
 * a stable {@code code} plus the raw {@code params} (numbers, currency code,
 * status, etc.) — and each consumer renders it itself:
 *
 *   { "code": "highValue", "params": { "total": 133000, "currency": "JPY", "threshold": 100000 } }
 *
 * The forms map {@code code} to an i18n key and format money/numbers via their
 * locale-aware helpers; the server renders the operational English string via
 * {@link #render(Object)} here. This same descriptor shape is the intended
 * contract for any future server-produced, user-facing message (e.g. form
 * validation errors), so the codes/params live in exactly one place.
 *
 * Every method is static and pure (see the design rules in
 * docs/commerce-shared-classes.md): it takes plain values / already-parsed maps
 * and uses none of the script bindings.
 */
class ReviewReasons {

    // --- Order screening codes ---------------------------------------------
    static final String HIGH_VALUE = "highValue"
    static final String FLAGGED_FINANCIAL_STATUS = "flaggedFinancialStatus"
    static final String LARGE_QUANTITY = "largeQuantity"
    static final String NEW_CUSTOMER = "newCustomer"
    static final String ADDRESS_MISMATCH = "addressMismatch"

    // --- Refund screening codes --------------------------------------------
    static final String HIGH_REFUND_VALUE = "highRefundValue"
    static final String FULL_REFUND = "fullRefund"
    static final String NO_RESTOCK = "noRestock"

    // =======================================================================
    // Builders — produce a descriptor map. The screening scripts call these so
    // the code strings and parameter names exist in exactly one place; the list
    // of descriptors is then serialized with JSON.stringify onto the
    // `reviewReasons` process variable.
    // =======================================================================

    /** Generic descriptor: {@code [code: ..., params: [...]]}. */
    static Map descriptor(String code, Map params) {
        return [code: code, params: (params != null ? params : [:])]
    }

    static Map highValue(Number total, String currency, Number threshold) {
        descriptor(HIGH_VALUE, [total: total, currency: currency, threshold: threshold])
    }

    static Map flaggedFinancialStatus(String status) {
        descriptor(FLAGGED_FINANCIAL_STATUS, [status: status])
    }

    static Map largeQuantity(String title, Number quantity, Number max) {
        descriptor(LARGE_QUANTITY, [title: title, quantity: quantity, max: max])
    }

    static Map newCustomer(Number count, Number max) {
        descriptor(NEW_CUSTOMER, [count: count, max: max])
    }

    static Map addressMismatch(String billing, String shipping) {
        descriptor(ADDRESS_MISMATCH, [billing: billing, shipping: shipping])
    }

    static Map highRefundValue(Number amount, String currency, Number threshold) {
        descriptor(HIGH_REFUND_VALUE, [amount: amount, currency: currency, threshold: threshold])
    }

    static Map fullRefund(Number amount, Number orderTotal, String currency) {
        descriptor(FULL_REFUND, [amount: amount, orderTotal: orderTotal, currency: currency])
    }

    static Map noRestock(Number count) {
        descriptor(NO_RESTOCK, [count: count])
    }

    // =======================================================================
    // Server-side rendering — turn descriptors into operational English text
    // for the notifications (Slack, Discord, Teams, LINE, webhook, email),
    // which have no per-user locale. The forms do NOT use this; they
    // render each descriptor in the reviewer's
    // locale via their i18n bundle.
    // =======================================================================

    /**
     * Render a list of reasons to English strings, dropping anything that
     * renders empty. Accepts descriptors (maps) and, for in-flight tasks that
     * predate the descriptor format, legacy plain strings (passed through).
     */
    static List<String> renderAll(List reasons) {
        if (reasons == null) {
            return []
        }
        def out = []
        for (def reason : reasons) {
            def text = render(reason)
            if (text) {
                out << text
            }
        }
        return out
    }

    /** Render a single reason (descriptor map or legacy string) to English. */
    static String render(Object reason) {
        if (reason == null) {
            return null
        }
        // Legacy: a reason produced before the descriptor format was a string.
        if (reason instanceof CharSequence) {
            return reason.toString()
        }
        if (!(reason instanceof Map)) {
            return reason.toString()
        }

        Map m = (Map) reason
        def code = m.code?.toString()
        Map p = (m.params instanceof Map) ? (Map) m.params : [:]

        switch (code) {
            case HIGH_VALUE:
                return "High-value order: ${money(p.total, p.currency)} >= ${Money.format(Money.toNumber(p.threshold))}".toString()
            case FLAGGED_FINANCIAL_STATUS:
                return "Financial status needs review: ${p.status}".toString()
            case LARGE_QUANTITY:
                return "Large quantity: '${p.title ?: "item"}' x${intText(p.quantity)} (>= ${intText(p.max)})".toString()
            case NEW_CUSTOMER:
                return "New customer: orders_count=${intText(p.count)} (<= ${intText(p.max)})".toString()
            case ADDRESS_MISMATCH:
                return "Billing/shipping country mismatch: ${p.billing} -> ${p.shipping}".toString()
            case HIGH_REFUND_VALUE:
                return "High-value refund: ${money(p.amount, p.currency)} >= ${Money.format(Money.toNumber(p.threshold))}".toString()
            case FULL_REFUND:
                return "Full refund: ${money(p.amount, p.currency)} of ${money(p.orderTotal, p.currency)}".toString()
            case NO_RESTOCK:
                return "Items refunded without restocking: ${intText(p.count)} line item(s)".toString()
            default:
                // Unknown code: fall back to a free-text param if present, else
                // drop it rather than surface a raw code to operators.
                return p.text ? p.text.toString() : null
        }
    }

    // --- Internal rendering helpers ----------------------------------------

    /** "133,000 JPY" (currency suffixed when present), or just the number. */
    private static String money(Object amount, Object currency) {
        def s = Money.format(Money.toNumber(amount))
        def c = currency?.toString()?.trim()
        return (c ? (s + " " + c) : s)
    }

    /** Whole-number text without decimals, e.g. a quantity / count. */
    private static String intText(Object value) {
        def n = Money.toNumber(value)
        return (n != null ? Money.format(n.toBigInteger()) : "")
    }
}
