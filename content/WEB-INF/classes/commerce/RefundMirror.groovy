package commerce

import java.net.http.HttpClient
import javax.jcr.query.Query

/**
 * Historical refund MIRROR writer — the per-order refunds fetch, the GraphQL→REST refund mapper
 * and the idempotent store, used by the orders backfill import (importBulkResult.groovy).
 *
 * TWO-STEP FETCH. The orders Bulk export can only FLAG refund-bearing orders (refunds { id } — a
 * Bulk query rejects a connection field inside a list field and caps a query at 5 connections,
 * live-verified 2026-07-11), so the refund DETAIL is fetched per refund-bearing order via the
 * foreground Admin GraphQL query below ({@link #refundsQuery}/{@link #fetchRefundNodes}), and only
 * for orders whose bulk-flagged refund ids are not all mirrored yet — a re-run therefore makes
 * ZERO foreground calls once the store is complete.
 *
 * WHY. The refund WEBHOOK route (refund-created.xml) only mirrors refunds that arrive AFTER the
 * webhook was wired up. Refunds that predate it belong to the bulk-imported historical orders, and
 * the orders backfill mirrors any that are missing into the SAME refund raw store, in the SAME
 * shape, so {@link commerce.Sales#compute} / {@link commerce.Refunds} read them exactly as they
 * read a webhook-delivered refund.
 *
 * REST-SHAPE REQUIREMENT. The sales drainer reads refund BODIES (not the props): {@link commerce.Sales
 * #foldReturns} reads {@code refund_line_items[].{subtotal,subtotal_set.shop_money/presentment_money
 * .amount,total_tax,total_tax_set...,quantity,line_item_id}} and {@code order_adjustments[].{kind,amount,
 * amount_set...}}; {@link commerce.Refunds#amount} reads {@code transactions[].{kind:'refund',status:
 * 'success',amount}}; {@link commerce.Refunds#amountBase} reads the {@code *_set.shop_money} on the line
 * items / adjustments. So the body we store MUST be in Shopify REST (snake_case) shape — the SAME shape a
 * refund webhook delivers. The per-order Admin GraphQL response is camelCase / edges-node / UPPERCASE
 * enums, so {@link #toRestRefund} maps each GraphQL refund NODE back to that REST body (this PURE mapper
 * is the one testable, reviewable piece; the rest is DEFENSIVE JCR/HTTP I/O).
 *
 * NOT NEW WORK. Historical refunds are mirror data, not new events, so — unlike refund-created.xml — the
 * backfill does NOT: start refund-review-flow, cancel backorders, or write a webhook idempotency marker.
 * It also does NOT mutate the parent order's cumulative refund SUMMARY (commerce:refunded_amount /
 * refund_count / source_status) — that is recordRefund's order-summary bookkeeping, which is non-
 * idempotent (it accumulates) and belongs to the live review-flow, not a re-runnable backfill. Returns/
 * net are recovered purely from the stored refund BODIES via the drainer, which is the only thing the
 * sales view needs.
 *
 * ORDER-COHORT vs REFUND-PERIOD. refund-created.xml folds the raw node into the year/MM folder of
 * {@code date:now} (the webhook arrival month). For a BACKFILL that would scatter historical refunds into
 * the CURRENT month, so here we fold by the refund's OWN created_at (business month) — matching
 * commerce:refunded_at, which is the Date axis of the refund-period sales view (returnsBasis=refund). The
 * folder is placement only (the drainer reads refunds by the typed commerce:order_id prop, recursing the
 * whole store), so this deviation is safe and is the correct business-month placement.
 *
 * IDEMPOTENT. Before storing refund_{id}.json the caller checks whether a node of that name already exists
 * ANYWHERE under the refund store (an XPath existence query by node name — the store is month-nested, so a
 * computed path could miss a node folded under a different month; see {@link #findRefundResource},
 * mirroring {@link commerce.Orders#findResource}). Already-present refunds are SKIPPED — a webhook-
 * delivered refund's lifecycle (owned by the review flow) is never reset by a re-import.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.RefundMirror}.
 */
class RefundMirror {

    /** The SAME refund raw store the webhook route writes (SalesFacts.REFUNDS_RAW_DIR, referenced to avoid drift). */
    static final String REFUNDS_RAW_DIR = SalesFacts.REFUNDS_RAW_DIR

    /**
     * Throttle between per-order refund fetches to respect the Admin API rate limit (foreground
     * GraphQL cost bucket). Small constant; 300ms keeps the fetch loop comfortably under the
     * leaky-bucket refill even on a burst of refund-bearing orders.
     */
    static final long THROTTLE_MS = 300L

    // =========================================================================
    // GraphQL query (per-order refunds) — FOREGROUND, non-bulk
    // =========================================================================

    /**
     * The per-order refunds query. {@code order(id:)} takes the order GID; the returned {@code refunds} is
     * a bounded LIST on Order (NOT a connection) so it takes NO first:/after: pagination args — its nested
     * refundLineItems / orderAdjustments / refundShippingLines / transactions DO take {@code first:}.
     * Money is a MoneyBag
     * ({@code shopMoney}=base, {@code presentmentMoney}=native); enums are UPPERCASE (lower-cased in
     * {@link #toRestRefund} so foldReturns/Refunds compare correctly).
     *
     * LIVE-VERIFIED (confirmed against the configured Admin API version):
     *   • LineItem does NOT expose {@code legacyResourceId} — we query {@code lineItem { id }} (the GID);
     *     the mapper peels either form via Api.legacyId, and Sales.foldReturns keys refund lines through
     *     Api.legacyId too, so the numeric id still matches;
     *   • OrderAdjustment does NOT expose {@code kind} (that is REST-only): orderAdjustments carry ONLY
     *     refund discrepancies ({@code reason} explains them) and refunded shipping is the separate
     *     {@code refundShippingLines} connection — so we fetch BOTH and the mapper synthesizes the REST
     *     kinds ({@code refund_discrepancy} / {@code shipping_refund}) that Sales.foldReturns /
     *     Refunds.amountBase read.
     *
     * LIVE-VERIFY (remaining, check against the pinned Admin API version):
     *   • {@code refunds} with NO pagination args (bounded list) — some versions cap the list length;
     *   • {@code restockType} enum values (NO_RESTOCK / CANCEL / RETURN / LEGACY_RESTOCK);
     *   • transactions {@code amountSet} (older versions exposed a scalar {@code amount}); we read the
     *     presentment amount as the native cash-returned scalar.
     */
    static String refundsQuery(String orderId) {
        String gid = Api.gid('Order', orderId)
        return """
query {
  order(id: "${gid}") {
    refunds {
      id
      createdAt
      note
      refundLineItems(first: 250) {
        edges {
          node {
            quantity
            restockType
            lineItem { id }
            subtotalSet {
              shopMoney { amount currencyCode }
              presentmentMoney { amount currencyCode }
            }
            totalTaxSet {
              shopMoney { amount currencyCode }
              presentmentMoney { amount currencyCode }
            }
          }
        }
      }
      orderAdjustments(first: 100) {
        edges {
          node {
            reason
            amountSet {
              shopMoney { amount currencyCode }
              presentmentMoney { amount currencyCode }
            }
            taxAmountSet {
              shopMoney { amount currencyCode }
              presentmentMoney { amount currencyCode }
            }
          }
        }
      }
      refundShippingLines(first: 100) {
        edges {
          node {
            subtotalAmountSet {
              shopMoney { amount currencyCode }
              presentmentMoney { amount currencyCode }
            }
            taxAmountSet {
              shopMoney { amount currencyCode }
              presentmentMoney { amount currencyCode }
            }
          }
        }
      }
      transactions(first: 100) {
        edges {
          node {
            kind
            status
            amountSet {
              shopMoney { amount currencyCode }
              presentmentMoney { amount currencyCode }
            }
          }
        }
      }
      totalRefundedSet {
        shopMoney { amount currencyCode }
        presentmentMoney { amount currencyCode }
      }
    }
  }
}
""".trim()
    }

    /** Fetch and return the raw GraphQL refund NODES for one order ({@code data.order.refunds}, a List). */
    static List fetchRefundNodes(HttpClient client, String endpoint, String token, String orderId) {
        // Direct GraphQL (NOT wrapped in Health.timeApi): the backfill loops over potentially many
        // orders, and feeding each call into the health monitor would swamp its per-name datapoints.
        def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: refundsQuery(orderId)])
        def order = (resp instanceof Map) ? mapGet(mapGet(resp, "data"), "order") : null
        def refunds = mapGet(order, "refunds")
        return (refunds instanceof List) ? (List) refunds : []
    }

    // =========================================================================
    // PURE mapper: GraphQL refund NODE -> REST (webhook) refund body
    // =========================================================================

    /**
     * Map ONE Admin GraphQL refund node to the Shopify REST refund body shape (snake_case, lower-cased
     * enums, MoneyBag→*_set + presentment scalar) that {@link commerce.Sales}/{@link commerce.Refunds}
     * consume. PURE and null-tolerant: no session/log/JSON bindings, never throws on a partial node.
     *
     * Emits, for each money field, BOTH {@code *_set.shop_money} (base) and {@code *_set.presentment_money}
     * (native) PLUS the plain scalar taken from the presentment amount — mirroring how the orders bulk
     * normalizer builds its MoneyBags — so {@code Sales.nativeAmt}/{@code baseOrNative} find every field.
     * REST {@code order_adjustments[]} is rebuilt from TWO GraphQL sources: orderAdjustments (refund
     * discrepancies → kind {@code refund_discrepancy}) and refundShippingLines (→ kind
     * {@code shipping_refund}, amounts negated to the REST sign convention). The field-shape
     * rationale (lineItem gid peel, kind synthesis) is documented on {@link #refundsQuery}.
     *
     * @param gqlRefundNode a Refund node (its connections as {edges:[{node:{...}}]}).
     * @param orderId       the numeric order id this refund belongs to (stored as the String order_id).
     */
    static Map toRestRefund(Map gqlRefundNode, String orderId) {
        def node = (gqlRefundNode == null) ? [:] : gqlRefundNode

        def rest = new LinkedHashMap()
        rest.id = Api.legacyId(node.id)          // "gid://shopify/Refund/123" -> "123"
        rest.order_id = orderId
        if (node.createdAt != null) rest.created_at = node.createdAt.toString()
        if (node.note != null) rest.note = node.note.toString()

        // refund_line_items[]: goods subtotal + tax (base+native), quantity, line id, restock type.
        def rlis = []
        for (n in nodesOf(node.refundLineItems)) {
            def li = new LinkedHashMap()
            if (n.quantity != null) li.quantity = n.quantity
            def liId = Api.legacyId(mapGet(n.lineItem, "legacyResourceId") ?: mapGet(n.lineItem, "id"))
            if (liId != null) li.line_item_id = liId
            def restock = lower(n.restockType)
            if (restock != null) li.restock_type = restock
            putSet(li, "subtotal", n.subtotalSet)
            putSet(li, "total_tax", n.totalTaxSet)
            rlis << li
        }
        rest.refund_line_items = rlis

        // order_adjustments[]: REST folds shipping refunds AND discrepancies into one list keyed by
        // `kind`, but GraphQL has no OrderAdjustment.kind — orderAdjustments carry ONLY refund
        // discrepancies (`reason` explains them) and refunded shipping is the separate
        // refundShippingLines connection. Synthesize the REST kinds from the two sources so
        // Sales.foldReturns (kind=shipping_refund) and Refunds.amountBase (all adjustments) read
        // the body exactly as a webhook-delivered one.
        def adjs = []
        for (n in nodesOf(node.orderAdjustments)) {
            def a = new LinkedHashMap()
            a.kind = "refund_discrepancy"
            def reason = lower(n.reason)
            if (reason != null) a.reason = reason
            putSet(a, "amount", n.amountSet)
            putSet(a, "tax_amount", n.taxAmountSet)
            adjs << a
        }
        for (n in nodesOf(node.refundShippingLines)) {
            def a = new LinkedHashMap()
            a.kind = "shipping_refund"
            // REST stores shipping_refund adjustment amounts NEGATIVE; GraphQL RefundShippingLine
            // exposes positive magnitudes — negate for shape fidelity (consumers read via .abs(),
            // so either sign folds the same).
            putSetNegated(a, "amount", n.subtotalAmountSet)
            putSetNegated(a, "tax_amount", n.taxAmountSet)
            adjs << a
        }
        rest.order_adjustments = adjs

        // transactions[]: cash actually returned. Refunds.amount sums kind:'refund'/status:'success' amount;
        // Refunds.currency reads the transaction currency. Both taken from the presentment (native) money.
        def txns = []
        for (n in nodesOf(node.transactions)) {
            def t = new LinkedHashMap()
            def kind = lower(n.kind)
            if (kind != null) t.kind = kind
            def status = lower(n.status)
            if (status != null) t.status = status
            def amt = presentmentAmount(n.amountSet)
            if (amt != null) t.amount = amt
            def cur = presentmentCurrency(n.amountSet)
            if (cur != null) t.currency = cur
            txns << t
        }
        rest.transactions = txns

        // Source-fidelity only (nothing reads it) — the refund's own reported total.
        def totalSet = moneyBag(node.totalRefundedSet)
        if (totalSet != null) rest.total_refunded_set = totalSet

        return rest
    }

    // --- Mapper helpers (pure) -------------------------------------------------

    /** Put {field}_set (MoneyBag) + a scalar {field} (presentment amount) onto a REST child map, when present. */
    private static void putSet(Map target, String field, Object gqlSet) {
        def bag = moneyBag(gqlSet)
        if (bag != null) target.put("${field}_set".toString(), bag)
        def scalar = presentmentAmount(gqlSet)
        if (scalar != null) target.put(field, scalar)
    }

    /** Like {@link #putSet} but with every amount NEGATED (REST shipping_refund adjustments are negative). */
    private static void putSetNegated(Map target, String field, Object gqlSet) {
        def bag = moneyBag(gqlSet)
        if (bag != null) target.put("${field}_set".toString(), negateBag(bag))
        def scalar = presentmentAmount(gqlSet)
        if (scalar != null) target.put(field, negateAmount(scalar))
    }

    /** A NEW REST MoneyBag map with each money's amount negated (input map untouched). */
    private static Map negateBag(Map bag) {
        def out = new LinkedHashMap()
        for (e in bag.entrySet()) {
            def m = e.value
            if (m instanceof Map && ((Map) m).amount != null) {
                def nm = new LinkedHashMap((Map) m)
                nm.amount = negateAmount(((Map) m).amount.toString())
                out.put(e.key, nm)
            } else {
                out.put(e.key, m)
            }
        }
        return out
    }

    /** "12.34" -> "-12.34" (string in/out, BigDecimal exact); an unparseable amount is returned as-is. */
    private static String negateAmount(String amt) {
        try { return new BigDecimal(amt).negate().toPlainString() }
        catch (Exception ignore) { return amt }
    }

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
        // Shopify returns Money amounts as strings (like the REST webhook body); keep them as strings so
        // Money.toNumber parses them identically.
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

    /** The node list of a GraphQL connection ({edges:[{node:{...}}]}) — [] for anything malformed. */
    private static List nodesOf(Object conn) {
        if (!(conn instanceof Map)) return []
        def edges = ((Map) conn).edges
        if (!(edges instanceof List)) return []
        def out = []
        for (e in edges) {
            def n = (e instanceof Map) ? ((Map) e).node : null
            if (n instanceof Map) out << n
        }
        return out
    }

    private static Object mapGet(Object m, String k) {
        return (m instanceof Map) ? ((Map) m).get(k) : null
    }

    private static String lower(Object v) {
        if (v == null) return null
        def s = v.toString().trim()
        return s.isEmpty() ? null : s.toLowerCase()
    }

    // =========================================================================
    // Idempotent store (same node shape as refund-created.xml + recordRefund.groovy)
    // =========================================================================

    /**
     * Existence check by node NAME across the whole (month-nested) refund store — mirrors {@link
     * commerce.Orders#findResource}. A computed path could sit in the wrong month, so we query by name.
     * Returns the existing resource or null. Defensive.
     */
    static Object findRefundResource(session, String refundId) {
        if (refundId == null) return null
        try {
            def stmt = "/jcr:root${REFUNDS_RAW_DIR}//refund_${refundId}.json".toString()
            def q = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
            q.limit(1)
            def rs = q.execute().getResources()
            return (rs != null && rs.length > 0) ? rs[0] : null
        } catch (Exception e) {
            return null
        }
    }

    /**
     * Write ONE refund's REST body + the SAME typed props the webhook path ends up with (refund-created.xml
     * core props + recordRefund.groovy derived money/flags/counts). Stages the node only — the caller
     * commits it. When {@code existing} is non-null we reuse its path so a rewrite can never create a
     * duplicate node in a different month; otherwise the folder is the refund's OWN created_at year/MM.
     */
    static void storeRefund(session, Map rest, existing) {
        String rid = rest.id.toString()
        Long ms = Api.epochMs(rest.created_at)

        String path
        if (existing != null) {
            path = existing.getPath()                              // rewrite in place (no dup)
        } else {
            def ym = yearMonth(ms)                                 // business month (deviation from date:now)
            path = "${REFUNDS_RAW_DIR}/${ym[0]}/${ym[1]}/refund_${rid}.json".toString()
        }

        def res = Jcr.getOrCreateFile(session, path)
        res.write(Jcr.toJson(rest))
        // MIME as the webhook route stores it (cms:store?mimeType=application/json).
        res.setProperty("jcr:mimeType", "application/json")

        // Core props (refund-created.xml): String ids, Date refunded_at, processing status.
        res.setProperty("commerce:refund_id", rid)
        res.setProperty("commerce:order_id", rest.order_id.toString())
        res.setProperty("commerce:status", "received")
        if (ms != null) res.setProperty("commerce:refunded_at", new java.util.Date(ms))

        // Derived facts (recordRefund.groovy) — via commerce.Refunds, from the mapped REST body, so the
        // stored props match the webhook path exactly. Money omitted when absent (null distinguishes
        // "unavailable" from zero, same as recordRefund).
        def amount = Refunds.amount(rest)
        def amountBase = Refunds.amountBase(rest)
        def currency = Refunds.currency(rest)
        def lineItems = (rest.refund_line_items instanceof List) ? rest.refund_line_items : []
        boolean restocked = lineItems.any { Refunds.isRestocked(it) }
        if (amount != null) res.setProperty("commerce:refund_amount", (BigDecimal) amount)
        if (amountBase != null) res.setProperty("commerce:refund_amount_base", (BigDecimal) amountBase)
        def taxBase = Refunds.taxBase(rest)
        if (taxBase != null) res.setProperty("commerce:refund_tax_base", (BigDecimal) taxBase)
        if (currency != null) res.setProperty("commerce:currency", currency)
        res.setProperty("commerce:restocked", (boolean) restocked)
        res.setProperty("commerce:line_item_count", (long) lineItems.size())

        // A' (refund-side recon) props, same as the webhook path, so the report reads the residual /
        // classification from a facet. Backfill is bulk, so this path stays silent — the migration and the
        // live webhook emit the warns.
        SalesReconcile.reconProps(rest).props.each { k, v ->
            if (v instanceof Boolean) res.setProperty(k.toString(), (boolean) v)
            else if (v != null) res.setProperty(k.toString(), (BigDecimal) v)
        }
        // Cash-out (refunds block) dimensions: the refund day (facet axis) and the parent order's
        // ordered_at (so the report can flag a refund whose order fell outside the window — crossPeriod).
        if (ms != null) res.setProperty("commerce:refunded_day", SalesReconcile.dayOf(ms))
        def orderedAt = orderedAtOf(session, rest.order_id)
        if (orderedAt != null) res.setProperty("commerce:refund_ordered_at", new java.util.Date(orderedAt))
        def note = rest.note?.toString()
        if (note != null && !note.trim().isEmpty()) {
            res.setProperty("commerce:refund_note", note.length() > 2048 ? note.substring(0, 2048) : note)
        }
    }

    // --- Helpers (defensive) -----------------------------------------------------

    // [yyyy, MM] of an epoch-ms instant in the server zone (matches SalesFacts/Sales month bucketing);
    // falls back to now when the timestamp is absent. Folder placement only — reads recurse by order_id.
    private static List yearMonth(Object ms) {
        def zdt
        if (ms != null) {
            try { zdt = java.time.Instant.ofEpochMilli(((Number) ms).longValue()).atZone(java.time.ZoneId.systemDefault()) }
            catch (Exception ignore) {}
        }
        if (zdt == null) zdt = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
        return [String.format("%04d", zdt.getYear()), String.format("%02d", zdt.getMonthValue())]
    }

    /** The parent order's ordered_at (created_at) epoch-ms, or null — for the refund's crossPeriod flag. */
    static Long orderedAtOf(session, orderId) {
        try {
            def oid = Api.legacyId(orderId)?.toString()
            if (oid == null) return null
            return Api.epochMs(SalesFacts.resolveOrderBody(session, oid)?.created_at)
        } catch (Exception e) { return null }
    }
}
