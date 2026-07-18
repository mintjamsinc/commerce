package commerce

import javax.jcr.query.Query

/**
 * Payment (order transaction) MIRROR writer — the GraphQL→REST transaction mapper, the shared
 * typed-prop writer and the idempotent store for the payment raw store, used by BOTH ingest
 * paths: the order_transactions/create webhook route (via recordPayment.groovy) and the orders
 * backfill import (importBulkResult.groovy). Modeled on {@link commerce.RefundMirror}.
 *
 * WHY A STORE OF ITS OWN. orders/paid fires only on the FIRST full payment of an order — an
 * Order-Editing surcharge capture or a bank transfer marked paid later never re-fires it — so
 * the payment (cash-in) axis of the occurrence-date sales report cannot be derived from the
 * order mirror. Each successful cash-in transaction ({@link commerce.Payments#isCashIn}) becomes
 * its own node under {@link #PAYMENTS_RAW_DIR}, carrying commerce:paid_at as its
 * occurrence-date axis — the exact symmetric of the refund store's refunded_at.
 *
 * REST-SHAPE REQUIREMENT. The stored body is the Shopify REST Transaction shape (snake_case,
 * lower-cased enums) — the SAME shape the order_transactions/create webhook delivers — so
 * {@link commerce.Payments} reads a bulk-mirrored body exactly as a webhook-delivered one.
 * {@link #toRestTransaction} maps an Admin GraphQL OrderTransaction node (camelCase / UPPERCASE
 * enums / MoneyBag) back to that shape; it is the PURE, testable piece.
 *
 * BASE CURRENCY. A GraphQL-sourced body carries amount_set.shop_money (Shopify's own base
 * conversion); a REST webhook body does not. {@link #applyProps} resolves the base amount via
 * {@link commerce.Payments#amountBase} against the parent order's shop currency
 * ({@link #shopCurrencyOf}) — single-currency fallback to native, and NO base prop on a known
 * cross-currency transaction (the report's facet SUM must never read a fake base).
 *
 * FOLDER PLACEMENT. The webhook route folds by webhook-arrival month (date:now, matching
 * refund-created.xml); {@link #storeTransaction} folds a backfilled node by the transaction's
 * OWN business month (paid_at, matching RefundMirror's deviation). Placement is cosmetic —
 * every read recurses the store and filters on the typed props.
 *
 * IDEMPOTENT. Node name transaction_{id}.json is the identity; {@link #findTransactionResource}
 * locates an existing node ANYWHERE under the month-nested store (name query, mirroring
 * RefundMirror.findRefundResource). The bulk import SKIPS an already-present transaction so a
 * webhook-delivered node is never reset by a re-import.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.PaymentMirror}.
 */
class PaymentMirror {

    static final String PAYMENTS_RAW_DIR = "/content/commerce/payments/raw"

    // =========================================================================
    // PURE mapper: GraphQL OrderTransaction NODE -> REST (webhook) transaction body
    // =========================================================================

    /**
     * Map ONE Admin GraphQL OrderTransaction node (as inlined on the orders bulk export — a
     * plain LIST field on Order, not a connection) to the Shopify REST transaction body shape
     * that {@link commerce.Payments} consumes. PURE and null-tolerant: no session/log/JSON
     * bindings, never throws on a partial node.
     *
     * Emits amount_set (shop_money + presentment_money) PLUS the plain scalar amount/currency
     * from the presentment money — the same MoneyBag treatment as RefundMirror.toRestRefund —
     * so both the native and the base reading find every field.
     *
     * @param gqlTxnNode an OrderTransaction node ({id, kind, status, gateway, createdAt,
     *                   processedAt, amountSet}).
     * @param orderId    the numeric order id this transaction belongs to (stored as the
     *                   String order_id).
     */
    static Map toRestTransaction(Map gqlTxnNode, String orderId) {
        def node = (gqlTxnNode == null) ? [:] : gqlTxnNode

        def rest = new LinkedHashMap()
        rest.id = Api.legacyId(node.id)          // "gid://shopify/OrderTransaction/123" -> "123"
        rest.order_id = orderId
        def kind = lower(node.kind)
        if (kind != null) rest.kind = kind
        def status = lower(node.status)
        if (status != null) rest.status = status
        def gateway = str(node.gateway)
        if (gateway != null) rest.gateway = gateway
        if (node.createdAt != null) rest.created_at = node.createdAt.toString()
        if (node.processedAt != null) rest.processed_at = node.processedAt.toString()

        def bag = moneyBag(node.amountSet)
        if (bag != null) rest.amount_set = bag
        def amt = presentmentAmount(node.amountSet)
        if (amt != null) rest.amount = amt
        def cur = presentmentCurrency(node.amountSet)
        if (cur != null) rest.currency = cur

        return rest
    }

    // =========================================================================
    // Idempotent store + the shared typed-prop writer
    // =========================================================================

    /**
     * Existence check by node NAME across the whole (month-nested) payment store — mirrors
     * {@link commerce.RefundMirror#findRefundResource}. Returns the existing resource or null.
     * Defensive.
     */
    static Object findTransactionResource(session, String transactionId) {
        if (transactionId == null) return null
        try {
            def stmt = "/jcr:root${PAYMENTS_RAW_DIR}//transaction_${transactionId}.json".toString()
            def q = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
            q.limit(1)
            def rs = q.execute().getResources()
            return (rs != null && rs.length > 0) ? rs[0] : null
        } catch (Exception e) {
            return null
        }
    }

    /**
     * Write ONE transaction's REST body + typed props (via {@link #applyProps}) into the payment
     * store. Stages the node only — the caller commits it. When {@code existing} is non-null its
     * path is reused (a rewrite can never create a duplicate node in a different month);
     * otherwise the folder is the transaction's OWN paid_at year/MM (business month). Sets the
     * integration lifecycle to "received" — callers must SKIP an already-present node instead of
     * re-storing it, so a webhook-delivered node's lifecycle is never reset.
     */
    static void storeTransaction(session, Map rest, existing, String shopCurrency) {
        String tid = rest.id.toString()
        Long ms = Payments.paidAtMs(rest)

        String path
        if (existing != null) {
            path = existing.getPath()
        } else {
            def ym = yearMonth(ms)
            path = "${PAYMENTS_RAW_DIR}/${ym[0]}/${ym[1]}/transaction_${tid}.json".toString()
        }

        def res = Jcr.getOrCreateFile(session, path)
        res.write(Jcr.toJson(rest))
        // MIME as the webhook route stores it (cms:store?mimeType=application/json).
        res.setProperty("jcr:mimeType", "application/json")
        res.setProperty("commerce:status", "received")
        applyProps(res, rest, shopCurrency)
    }

    /**
     * Stamp the typed commerce:* payment props on a payment node from its REST body — the ONE
     * prop writer both ingest paths share (the webhook route's recordPayment.groovy applies it
     * to the route-stored node; the bulk import applies it via {@link #storeTransaction}), so
     * the facet axes can never drift between the two. Money is omitted when absent — null
     * distinguishes "unavailable" from zero, and the occurrence report's facet SUM must never
     * count a fake 0. Returns TRUE when the base amount was written (false = base unavailable,
     * e.g. a known cross-currency transaction without shop_money — the caller logs it).
     */
    static boolean applyProps(res, Map rest, String shopCurrency) {
        if (rest.id != null) res.setProperty("commerce:transaction_id", rest.id.toString())
        if (rest.order_id != null) res.setProperty("commerce:order_id", rest.order_id.toString())
        def kind = rest.kind?.toString()
        if (kind != null) res.setProperty("commerce:kind", kind)
        def gateway = rest.gateway?.toString()
        if (gateway != null && !gateway.trim().isEmpty()) res.setProperty("commerce:gateway", gateway)
        def currency = Payments.currency(rest)
        if (currency != null) res.setProperty("commerce:currency", currency)

        def amount = Payments.amount(rest)
        if (amount != null) res.setProperty("commerce:payment_amount", (BigDecimal) amount)
        def amountBase = Payments.amountBase(rest, shopCurrency)
        if (amountBase != null) res.setProperty("commerce:payment_amount_base", (BigDecimal) amountBase)

        // The occurrence-date axis: one absolute instant. Range predicates AND the report's
        // day rows both work off it (the day grouping is query-time range() buckets in the
        // caller's timezone — no baked day string).
        Long ms = Payments.paidAtMs(rest)
        if (ms != null) {
            res.setProperty("commerce:paid_at", new java.util.Date(ms))
        }
        return amountBase != null
    }

    /**
     * The SHOP (base) currency of the transaction's parent order, resolved from the order
     * mirror's body (total_price_set.shop_money.currency_code — the same reading commerce.Sales
     * uses), or null when the order is not mirrored yet (the payment webhook can outrun
     * orders/paid). Defensive.
     */
    static String shopCurrencyOf(session, orderId) {
        try {
            def oid = Api.legacyId(orderId)?.toString()
            if (oid == null) return null
            def body = SalesFacts.resolveOrderBody(session, oid)
            def c = body?.total_price_set?.shop_money?.currency_code?.toString()?.trim()
            return (c == null || c.isEmpty()) ? null : c.toUpperCase()
        } catch (Exception e) { return null }
    }

    // --- Helpers (pure) ----------------------------------------------------------

    /** GraphQL MoneyBag {shopMoney,presentmentMoney} -> REST {shop_money,presentment_money}; null when empty. */
    private static Map moneyBag(Object gqlSet) {
        if (!(gqlSet instanceof Map)) return null
        def out = new LinkedHashMap()
        def shop = money(((Map) gqlSet).shopMoney)
        def pres = money(((Map) gqlSet).presentmentMoney)
        if (shop != null) out.shop_money = shop
        if (pres != null) out.presentment_money = pres
        return out.isEmpty() ? null : out
    }

    /** GraphQL MoneyV2 {amount,currencyCode} -> REST {amount(String),currency_code}; null when empty. */
    private static Map money(Object gqlMoney) {
        if (!(gqlMoney instanceof Map)) return null
        def m = (Map) gqlMoney
        def out = new LinkedHashMap()
        // Shopify returns Money amounts as strings (like the REST webhook body); keep them as
        // strings so Money.toNumber parses them identically.
        if (m.amount != null) out.amount = m.amount.toString()
        if (m.currencyCode != null) out.currency_code = m.currencyCode.toString()
        return out.isEmpty() ? null : out
    }

    private static String presentmentAmount(Object gqlSet) {
        def p = (gqlSet instanceof Map) ? ((Map) gqlSet).presentmentMoney : null
        def amt = (p instanceof Map) ? ((Map) p).amount : null
        return amt == null ? null : amt.toString()
    }

    private static String presentmentCurrency(Object gqlSet) {
        def p = (gqlSet instanceof Map) ? ((Map) gqlSet).presentmentMoney : null
        def c = (p instanceof Map) ? ((Map) p).currencyCode : null
        return c == null ? null : c.toString()
    }

    private static String lower(Object v) {
        if (v == null) return null
        def s = v.toString().trim()
        return s.isEmpty() ? null : s.toLowerCase()
    }

    private static String str(Object v) {
        if (v == null) return null
        def s = v.toString()
        return s.trim().isEmpty() ? null : s
    }

    // [yyyy, MM] of an epoch-ms instant in UTC (the shared fold rule, Api.utcYearMonth —
    // matches SalesFacts/RefundMirror month bucketing); falls back to now when the
    // timestamp is absent. Folder placement only.
    private static List yearMonth(Object ms) {
        return Api.utcYearMonth(ms)
    }
}
