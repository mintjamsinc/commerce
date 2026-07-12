// Download a completed orders-backfill Bulk Operation's JSONL and IMPORT it into the order
// mirror, STREAMING line-by-line so memory stays constant (a few MB) regardless of how many
// historical orders the range covers. This is the ORDER counterpart of reconcileBulkResult
// (which reconciles the INVENTORY mirror); it shares the same broker scaffolding.
//
// The bulk query (BulkQueries.ORDERS_BACKFILL_TEMPLATE) is rooted at orders with a nested
// lineItems connection AND a refund-id FLAG list (refunds { id } — Bulk rejects a connection
// field inside a list field and caps a query at 5 connections, so the refund DETAIL cannot ride
// the export), so the JSONL is a 2-level stream in GRAPHQL shape:
//   {"id":"gid://shopify/Order/111","legacyResourceId":"111", ... }              // order (no __parentId)
//   {"id":"gid://shopify/LineItem/9","quantity":2, ... ,"__parentId":".../Order/111"}  // line item
//   {"id":"gid://shopify/Refund/5","__parentId":".../Order/111"}                       // refund id flag
// We accumulate one order's subtree, then on the next order line flush it: NORMALIZE the GraphQL
// order node (+ its line items) to the REST (webhook) body shape every mirror consumer expects,
// UPSERT the order file committing in batches, and RECORD the order as a refund CANDIDATE when it
// flagged refund ids. Refund flags are ALSO accepted inline on the order line (o.refunds as a
// list) in case the export inlines the list field.
//
// REFUND DETAIL PHASE (after the stream): for each candidate order whose flagged refund ids are
// NOT all mirrored yet, fetch that order's refunds via the foreground Admin GraphQL API
// (RefundMirror.fetchRefundNodes, throttled per order) and mirror the missing ones into the
// refund raw store — same body + typed props as the webhook path. A fully-mirrored candidate is
// skipped WITHOUT an API call, so a re-run makes zero foreground calls once the store is complete.
//
// BACKFILL CHAIN: an orders backfill is the WHOLE historical sales import. Refund details are
// fetched for the refund-bearing orders (above), every imported order is enqueued for a
// sales-fact recompute, and on COMPLETED this script kicks the sales-fact SEED
// (direct:commerce-sales-backfill-seed) once — the seed re-walks the whole order mirror, enqueues
// every distinct order and kicks the single-writer drainer, so the operator triggers ONE action
// and the facts follow.
//
// BACKFILL SEMANTICS (operator-sovereignty; historical data is NOT "new work"):
//   - Mirror-only: this NEVER starts order-review-flow / refund-review-flow / detectBackorders /
//     any BPMN, and never touches the parent order's cumulative refund summary (that is
//     recordRefund's live-flow bookkeeping, which accumulates and must not re-run).
//   - Idempotent + lifecycle-preserving: the node path yyyy/MM is derived from the ORDER's
//     created_at, but an ALREADY-mirrored order is located first (Orders.findResource by id) and
//     overwritten in place regardless of month; commerce:status is set to "received" ONLY for a
//     genuinely NEW node — an existing node's integration lifecycle (owned by the review/fulfil/
//     cancel flow) is left untouched. An ALREADY-mirrored refund is SKIPPED entirely (a webhook-
//     delivered refund's lifecycle is never reset). Re-running is therefore safe.
//
// Defensive: a MISSING/null GraphQL field maps to null/omit and NEVER throws mid-stream; only an
// I/O error is transient-retryable (retry via READY), while a data/parse error fails the job
// terminally (isTransient decides) — a genuinely corrupt export must not loop forever.
//
// PRECONDITION: the CMS consumer lane (runBulkCmsLane) has ALREADY marked this job PROCESSING
// before dispatching here (that is how it claims a domain-safe ingest slot). This script only
// downloads + imports and sets the terminal state; it must NOT re-mark PROCESSING.

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.GZIPInputStream
import commerce.BulkJobs
import commerce.Jcr
import commerce.Money
import commerce.Orders
import commerce.Reconciliation
import commerce.RefundMirror
import commerce.SalesFacts
import commerce.ShopifyAdmin

final int BATCH = 200

def jobId = (binding.hasVariable("bulkJobId") ? binding.getVariable("bulkJobId")?.toString() : null)
if (!jobId) {
    log.warn("importBulkResult: no bulkJobId")
    return
}
def job = BulkJobs.list(repositorySession).find { it.jobId?.toString() == jobId }
if (job == null) {
    log.warn("importBulkResult: job ${jobId} not found")
    return
}
def gid = job.bulkOperationGid?.toString()
if (!gid) {
    log.warn("importBulkResult: job ${jobId} has no bulk gid")
    BulkJobs.markFailed(repositorySession, log, jobId, "no bulk gid")
    return
}

// A TRANSIENT failure (a completed bulk whose result URL is momentarily null, or a network /
// stream / 5xx blip mid-download) must not cost a whole schedule cycle. Bump the persisted
// reconcile attempt counter and, while under the cap, put the job back to READY so runBulkCmsLane
// re-dispatches it on the next tick — READY keeps the domain blocked in the Shopify producer lane,
// so no duplicate bulk is started. Once attempts are exhausted, fail it (terminal). The import is
// idempotent (locate-then-overwrite, lifecycle-preserving), so a retry re-runs safely.
final int MAX_RECONCILE_ATTEMPTS = 3
def retryOrFail = { String reason ->
    try {
        int attempts = (BulkJobs.incrementReconcileAttempts(repositorySession, log, jobId) ?: 0) as int
        if (attempts < MAX_RECONCILE_ATTEMPTS) {
            log.warn("importBulkResult: job ${jobId} transient (${reason}) - attempt ${attempts}/${MAX_RECONCILE_ATTEMPTS}, marking READY to retry")
            // The job is PROCESSING at this point (the CMS lane claimed it before dispatch), so use
            // markReadyForRetry (RUNNING|PROCESSING -> READY); a plain markReady (RUNNING-only) would
            // be a no-op and freeze the job PROCESSING until the watchdog fails it.
            BulkJobs.markReadyForRetry(repositorySession, log, jobId)
        } else {
            log.warn("importBulkResult: job ${jobId} transient (${reason}) - ${attempts} attempts exhausted, failing")
            BulkJobs.markFailed(repositorySession, log, jobId, "import retries exhausted: ${reason}")
        }
    } catch (Exception ex) {
        log.warn("importBulkResult: retryOrFail for job ${jobId}: ${ex.message}")
    }
}

try {
    def shopCfg = readYaml("/etc/commerce/config/shopify.yml")
    def adminApi = shopCfg?.adminApi ?: shopCfg
    def endpoint = ShopifyAdmin.endpoint(adminApi)
    def token = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
    def httpClient = HttpClient.newHttpClient()

    def bulk = ShopifyAdmin.bulkByGid(httpClient, endpoint, token, gid)
    def bulkStatus = bulk?.status
    def url = bulk?.url
    if (bulkStatus == "FAILED" || bulkStatus == "CANCELED" || bulkStatus == "EXPIRED") {
        // Shopify says this bulk is TERMINALLY un-downloadable - fail the job (terminal).
        log.warn("importBulkResult: bulk ${gid} terminal status=${bulkStatus} - failing job ${jobId}")
        BulkJobs.markFailed(repositorySession, log, jobId, "bulk terminal status ${bulkStatus}")
        return
    }
    if (bulkStatus != "COMPLETED" || !url) {
        // COMPLETED-but-url-not-yet-populated, or a momentary status/read blip: TRANSIENT - retry.
        retryOrFail("not yet downloadable (status=${bulkStatus}, url=${url != null})")
        return
    }

    // (The CMS lane already marked this job PROCESSING before dispatch; do not re-claim here.)

    // Streaming import state (one order's subtree at a time = constant memory). curRefundGids
    // collects the current order's flagged refund gids; refundCandidates maps an order id to its
    // flagged (numeric) refund ids for the post-stream detail phase — ids only, so even a very
    // large export stays small.
    def state = [orders: 0, created: 0, skipped: 0,
                 refundsStored: 0, refundsSkipped: 0, refundsFailed: 0, ordersWithRefunds: 0,
                 sinceCommit: 0, curOrder: null, curOrderGid: null, curLineItems: [],
                 curRefundGids: new LinkedHashSet(), refundCandidates: new LinkedHashMap()]

    def flush = {
        def node = state.curOrder
        if (node != null) {
            // One malformed / unwritable order must not sink a months-wide historical import: a
            // per-order failure (a JCR write / findResource glitch, or a surprise in one node) is
            // logged and SKIPPED so the rest of the range still imports. Stream-level IO errors are
            // raised by eachJsonlLine (outside this closure) and still reach the transient-retry path.
            try {
                def body = normalizeOrder(node, state.curLineItems)
                def oid = body.id?.toString()
                if (oid != null && !oid.trim().isEmpty()) {
                    // Locate an already-mirrored order first: overwrite the ORIGINAL node in place
                    // (idempotent) regardless of the month its created_at would derive, and preserve
                    // its integration lifecycle. Only a genuinely NEW node gets a created_at-derived
                    // path and commerce:status="received".
                    def existing = Orders.findResource(repositorySession, oid)
                    boolean isNew = (existing == null || !existing.exists())
                    def path = isNew ? derivePath(oid, body.created_at) : existing.getPath()

                    def res = Jcr.getOrCreateFile(repositorySession, path)
                    res.write(Jcr.toJson(body))
                    res.setProperty("jcr:mimeType", Orders.ORDER_MIME)

                    // Typed, auto-indexed props — EXACTLY the set order-paid stamps (identity + money +
                    // source status). Money is the native (presentment) total; *_base is Shopify's own
                    // shop-currency conversion (total_price_set.shop_money). Missing values are omitted.
                    def sm = body.total_price_set?.shop_money
                    res.setProperty("commerce:order_id", oid)
                    setStr(res, "commerce:customer_email", body.contact_email)
                    setDecimal(res, "commerce:total_price", body.total_price)
                    setDecimal(res, "commerce:total_price_base", sm?.amount)
                    setStr(res, "commerce:currency", body.currency)
                    setStr(res, "commerce:base_currency", sm?.currency_code)
                    setLong(res, "commerce:order_number", body.order_number)
                    setStr(res, "commerce:source_status", body.financial_status)
                    // Sales dimension axes — parity with the webhook path (order-paid.xml): the order
                    // date (created_at) as a real Date for period aggregation, and the customer id for
                    // customer grouping. A guest order (no customer) simply omits customer_id.
                    setDate(res, "commerce:ordered_at", body.created_at)
                    setStr(res, "commerce:customer_id", body.customer?.id)
                    // Lifecycle: claim "received" ONLY for a new node; NEVER reset an existing order's
                    // integration status (it is owned by the review/fulfil/cancel flow).
                    if (isNew) {
                        res.setProperty("commerce:status", "received")
                        state.created++
                    }

                    state.orders++
                    // Enqueue the order for a sales-fact recompute, STAGED so the marker rides
                    // the existing batch commit below (no per-order commit storm). Bulk is
                    // latency-insensitive so we do NOT async-kick — the drainer's timer picks it up; and
                    // its components_complete guard prevents a lossy bulk fact from downgrading a richer
                    // webhook fact.
                    SalesFacts.writePending(repositorySession, log, oid)

                    // Record the order as a refund candidate (numeric refund ids) for the
                    // post-stream detail phase — the bulk only FLAGS refunds, it cannot carry
                    // their detail.
                    if (!state.curRefundGids.isEmpty()) {
                        def rids = state.curRefundGids.collect { Reconciliation.numericId(it) }
                                        .findAll { it != null && !it.toString().trim().isEmpty() }
                        if (!rids.isEmpty()) state.refundCandidates[oid] = rids
                    }

                    if (++state.sinceCommit >= BATCH) {
                        repositorySession.commit()
                        state.sinceCommit = 0
                    }
                }
            } catch (Exception ex) {
                state.skipped++
                try { log.warn("importBulkResult: skipping order ${state.curOrderGid}: ${ex.message}") } catch (Exception ignore) {}
            }
        }
        state.curOrder = null
        state.curOrderGid = null
        state.curLineItems = []
        state.curRefundGids = new LinkedHashSet()
    }

    eachJsonlLine(httpClient, url) { line ->
        def o = JSON.parse(line)
        if (o["__parentId"] == null) {
            // order root line
            flush()
            state.curOrder = o
            state.curOrderGid = o.id?.toString()
            state.curLineItems = []
            state.curRefundGids = new LinkedHashSet()
            // Tolerate an export that INLINES the refund flags on the order line (refunds is a
            // plain list field; whether Bulk explodes it into its own lines is version-sensitive).
            def inline = o.refunds
            if (inline instanceof List) {
                inline.each { r ->
                    def rgid = (r instanceof Map) ? r.id?.toString() : null
                    if (rgid != null && !rgid.isEmpty()) state.curRefundGids << rgid
                }
            }
        } else if (state.curOrderGid != null && o["__parentId"]?.toString() == state.curOrderGid) {
            def lineGid = o.id?.toString()
            if (lineGid != null && lineGid.startsWith("gid://shopify/Refund/")) {
                // refund id flag of the current order (detail is fetched post-stream)
                state.curRefundGids << lineGid
            } else {
                // line-item child of the current order (deeper nesting keys off a different
                // __parentId and is ignored)
                state.curLineItems << o
            }
        }
    }
    flush()
    repositorySession.commit()

    // Refund DETAIL phase: the stream above only flagged which orders have refunds. Fetch the
    // detail per candidate order via the foreground Admin API — but ONLY when at least one flagged
    // refund id is not mirrored yet (a complete candidate costs no API call, so re-runs are free).
    // Idempotent and defensive: an already-mirrored refund is skipped (a webhook-delivered
    // refund's lifecycle is never reset), each stored refund commits atomically, and a per-order
    // fetch/store failure is counted and never sinks the rest. Throttled per fetched order to
    // respect the Admin API cost bucket.
    if (!state.refundCandidates.isEmpty()) {
        log.info("importBulkResult: job ${jobId} - resolving refund details for ${state.refundCandidates.size()} refund-bearing order(s)")
        state.refundCandidates.each { oid, rids ->
            try {
                def missing = rids.findAll { RefundMirror.findRefundResource(repositorySession, it.toString()) == null }
                if (missing.isEmpty()) {
                    state.refundsSkipped += rids.size()
                    return
                }
                def refundNodes = RefundMirror.fetchRefundNodes(httpClient, endpoint, token, oid.toString())
                boolean anyStored = false
                refundNodes.each { rn ->
                    if (!(rn instanceof Map)) return
                    try {
                        def rest = RefundMirror.toRestRefund((Map) rn, oid.toString())
                        def rid = rest?.id?.toString()
                        if (rid == null || rid.trim().isEmpty()) return
                        if (RefundMirror.findRefundResource(repositorySession, rid) != null) {
                            state.refundsSkipped++
                            return
                        }
                        // Commit each refund atomically: a partial write on one refund rolls back
                        // only that one and never rides another refund's commit.
                        RefundMirror.storeRefund(repositorySession, rest, null)
                        repositorySession.commit()
                        state.refundsStored++
                        anyStored = true
                    } catch (Exception se) {
                        try { repositorySession.rollback() } catch (Exception ignore) {}
                        state.refundsFailed++
                        try { log.warn("importBulkResult: refund store failed (order ${oid}): ${se.message}") } catch (Exception ignore) {}
                    }
                }
                if (anyStored) {
                    state.ordersWithRefunds++
                    // Re-enqueue the order: the drainer may have already recomputed it from the
                    // import-phase marker before its refunds landed.
                    SalesFacts.writePending(repositorySession, log, oid.toString())
                    try { repositorySession.commit() } catch (Exception ce) {
                        try { repositorySession.rollback() } catch (Exception ignore) {}
                    }
                }
                throttle()
            } catch (Exception fe) {
                // A fetch failure leaves the ids unmirrored — the next orders backfill retries them.
                state.refundsFailed++
                try { log.warn("importBulkResult: refund fetch failed (order ${oid}): ${fe.message}") } catch (Exception ignore) {}
                throttle()
            }
        }
    }

    // Terminal bookkeeping + the backfill CHAIN: markCompleted is a guarded CAS (true iff THIS call
    // applied the transition), so the sales-fact seed kick below fires exactly once per job even if
    // a watchdog or duplicate dispatch races this script. The seed re-walks the order mirror,
    // enqueues every distinct order and kicks the single-writer drainer when it finishes.
    boolean completed = BulkJobs.markCompleted(repositorySession, log, jobId, [
        orders           : state.orders,
        created          : state.created,
        skipped          : state.skipped,
        refundsStored    : state.refundsStored,
        refundsSkipped   : state.refundsSkipped,
        refundsFailed    : state.refundsFailed,
        ordersWithRefunds: state.ordersWithRefunds,
    ])
    if (completed && job.type?.toString() == "orders-backfill") {
        try {
            IntegrationAPI.createMessageSender()
                .setEndpointURI("direct:commerce-sales-backfill-seed")
                .setBody("")
                .setHeader("runAs", "commerce-service-user")
                .sendAsync()
        } catch (Exception ke) {
            // The drainer's 30s timer still materializes the per-order markers staged above; only
            // orders that predate this backfill would miss a re-enqueue until the next backfill.
            log.warn("importBulkResult: sales-fact seed kick failed: ${ke.message}")
        }
    }
    log.info("importBulkResult: job ${jobId} - ${state.orders} order(s) imported, ${state.created} new, ${state.skipped} skipped, ${state.refundsStored} refund(s) stored, ${state.refundsSkipped} already mirrored, ${state.refundsFailed} failed")
} catch (Exception e) {
    try { repositorySession.rollback() } catch (Exception ignore) {}
    if (isTransient(e)) {
        // A network drop / stream reset / server 5xx mid-download is transient: retry via READY
        // rather than burning the whole schedule cycle. The staged (uncommitted) writes were just
        // rolled back and the import is idempotent, so the retry re-imports cleanly.
        log.warn("importBulkResult: job ${jobId} transient download error: ${e.message}")
        retryOrFail("download error: ${e.class?.simpleName}: ${e.message}")
    } else {
        log.warn("importBulkResult: job ${jobId}: ${e.message}")
        try { BulkJobs.markFailed(repositorySession, log, jobId, e.message) } catch (Exception ignore) {}
    }
}

// --- Normalization: GraphQL order node -> REST (webhook) body shape ------------

// Map one Shopify Bulk order GraphQL node (+ its accumulated lineItem child nodes) to the REST
// order body every mirror consumer reads (order editor / Reports / Reconciliation / Gdpr). PURE +
// null-safe: a missing field becomes null / [] / omit, it NEVER throws (only JSON.parse or a JCR
// write can throw, and those are handled by the caller's transient/terminal split).
Map normalizeOrder(Map node, List lineItemNodes) {
    def body = [:]

    // Identity. Numeric id from legacyResourceId (preferred) or the gid tail; REST body.id is a
    // number, but commerce:order_id is the String form (set by the caller).
    def oidStr = firstNonBlank(str(node.legacyResourceId), Reconciliation.numericId(node.id))
    body.id = toNumericIdValue(oidStr)
    def name = str(node.name)                           // "#1001"
    body.name = name
    body.order_number = orderNumberFrom(name)           // digits -> Long (chronological key)

    // Contact. GraphQL Order exposes a single `email`; the REST body carries both email and
    // contact_email (consumers read email || contact_email, commerce:customer_email <- contact_email).
    body.email = str(node.email)
    body.contact_email = str(node.email)

    body.note = str(node.note)
    body.tags = tagsToString(node.tags)                 // [String] -> "a, b, c"

    // Timestamps (kept as the source ISO strings; created_at also drives the yyyy/MM placement).
    body.created_at = str(node.createdAt)
    body.processed_at = str(node.processedAt)
    body.updated_at = str(node.updatedAt)
    body.cancelled_at = str(node.cancelledAt)

    // Status enums are UPPERCASE in GraphQL; the mirror stores lowercase (financial_status /
    // fulfillment_status), matching the REST webhook + commerce:source_status.
    body.financial_status = lower(node.displayFinancialStatus)
    body.fulfillment_status = fulfillmentStatus(node.displayFulfillmentStatus)

    // Money. total_price / currency are the order's NATIVE (presentment) total — that is what the
    // order editor shows as the order currency and what commerce:total_price mirrors. shop_money is
    // Shopify's own base-currency conversion (commerce:total_price_base). Fall back across the two
    // single-money sets so an export that carries only one still populates both views.
    def shopMoney = node?.totalPriceSet?.shopMoney
    def presentmentMoney = node?.totalPriceSet?.presentmentMoney
    def nativeMoney = presentmentMoney ?: shopMoney
    body.total_price = str(nativeMoney?.amount)
    body.currency = str(nativeMoney?.currencyCode)
    body.total_price_set = [
        shop_money       : moneyMap(shopMoney ?: presentmentMoney),
        presentment_money: moneyMap(presentmentMoney ?: shopMoney),
    ]

    // Sales components: order-grain tax / shipping / tips / duties in the SAME native (scalar)
    // + *_set (shop_money/presentment_money) REST shape the webhook body carries, so a backfilled order
    // decomposes to components_complete at the sales-fact layer (commerce.Sales). Nullable sets (duties
    // on non-cross-border orders) → null (omitted), never zero-filled.
    body.total_tax = moneyNative(node.totalTaxSet)
    body.total_tax_set = moneyBag(node.totalTaxSet)
    body.total_shipping_price_set = moneyBag(node.totalShippingPriceSet)
    body.total_tip_received = moneyNative(node.totalTipReceivedSet)
    body.current_total_duties_set = moneyBag(node.currentTotalDutiesSet)

    // customAttributes {key,value} -> note_attributes [{name,value}].
    body.note_attributes = attrsToList(node.customAttributes)

    def cust = node.customer
    if (cust != null) {
        def cidStr = firstNonBlank(str(cust.legacyResourceId), Reconciliation.numericId(cust.id))
        body.customer = [id: toNumericIdValue(cidStr), email: str(cust.email)]
    }

    body.shipping_address = address(node.shippingAddress)
    body.billing_address = address(node.billingAddress)

    body.line_items = (lineItemNodes ?: []).findAll { it != null }.collect { li -> lineItem(li) }
    return body
}

// One REST line_item from a GraphQL lineItem node. price is the NATIVE (presentment) unit price —
// the same axis as the order-level total_price and what the mirror / editor / Reports read as
// li.price (REST line_items[].price is in the order's presentment currency). price_set carries both
// money views when present.
Map lineItem(li) {
    def sm = li?.originalUnitPriceSet?.shopMoney
    def pm = li?.originalUnitPriceSet?.presentmentMoney
    return [
        id           : toNumericIdValue(Reconciliation.numericId(li?.id)),
        name         : str(li?.name),
        title        : str(firstNonBlank(str(li?.title), str(li?.name))),
        variant_title: str(li?.variantTitle),
        sku          : str(li?.sku),
        quantity     : asLong(li?.quantity),
        // Product/variant ids for the line-grain product facet; legacyResourceId is the numeric
        // id, matching the webhook body's line_items[].product_id / variant_id.
        product_id   : toNumericIdValue(str(li?.product?.legacyResourceId)),
        variant_id   : toNumericIdValue(str(li?.variant?.legacyResourceId)),
        price        : str((pm ?: sm)?.amount),
        price_set    : [
            shop_money       : moneyMap(sm ?: pm),
            presentment_money: moneyMap(pm ?: sm),
        ],
        // Per-line discount / tax breakdown in the REST webhook shape commerce.Sales reads
        // (amount_set/price_set for base, amount/price for native).
        discount_allocations: discountAllocations(li?.discountAllocations),
        tax_lines           : taxLines(li?.taxLines),
    ]
}

// GraphQL displayFulfillmentStatus (UPPERCASE) -> the REST fulfillment_status vocabulary
// (null / partial / fulfilled / restocked) so a backfilled order's status token matches the
// webhook mirror. Unknown values fall back to lowercase rather than being dropped.
String fulfillmentStatus(v) {
    if (v == null) return null
    switch (v.toString().trim().toUpperCase()) {
        case "UNFULFILLED":          return null
        case "PARTIALLY_FULFILLED":  return "partial"
        case "FULFILLED":            return "fulfilled"
        case "RESTOCKED":            return "restocked"
        default:                     return v.toString().trim().toLowerCase()
    }
}

// GraphQL MailingAddress (camelCase) -> REST address (snake_case). Null address -> null.
Map address(a) {
    if (a == null) return null
    return [
        first_name  : str(a.firstName),
        last_name   : str(a.lastName),
        name        : str(a.name),
        company     : str(a.company),
        address1    : str(a.address1),
        address2    : str(a.address2),
        city        : str(a.city),
        province    : str(a.province),
        province_code: str(a.provinceCode),
        zip         : str(a.zip),
        country     : str(a.country),
        country_code: str(a.countryCodeV2),
        phone       : str(a.phone),
    ]
}

// A REST money map ({amount, currency_code}) from a GraphQL MoneyV2 ({amount, currencyCode}); null
// stays null so an absent money set is omitted rather than faked.
Map moneyMap(m) {
    if (m == null) return null
    return [amount: str(m.amount), currency_code: str(m.currencyCode)]
}

// A REST money SET ({shop_money, presentment_money}) from a GraphQL MoneyBag ({shopMoney, presentment
// Money}); null -> null so an absent set is omitted (never faked). Cross-fills the two views so an
// export carrying only one still populates both (matches the total_price_set fallback above).
Map moneyBag(set) {
    if (set == null) return null
    return [
        shop_money       : moneyMap(set.shopMoney ?: set.presentmentMoney),
        presentment_money: moneyMap(set.presentmentMoney ?: set.shopMoney),
    ]
}

// The NATIVE (presentment) scalar amount of a MoneyBag as the REST body carries it (e.g. total_tax,
// total_tip_received), falling back to shopMoney; null when absent.
String moneyNative(set) {
    return str((set?.presentmentMoney ?: set?.shopMoney)?.amount)
}

// GraphQL lineItem.discountAllocations [{allocatedAmountSet}] -> REST line_items[].discount_allocations
// [{amount, amount_set}] (commerce.Sales sums amount_set for base, amount for native). Null-safe -> [].
List discountAllocations(list) {
    if (!(list instanceof List)) return []
    return list.findAll { it != null }.collect { a ->
        [amount: moneyNative(a.allocatedAmountSet), amount_set: moneyBag(a.allocatedAmountSet)]
    }
}

// GraphQL taxLines [{title, rate, priceSet}] -> REST tax_lines [{title, rate, price, price_set}]. Use
// TaxLine.rate (the fraction), NOT ratePercentage. Null-safe -> [].
List taxLines(list) {
    if (!(list instanceof List)) return []
    return list.findAll { it != null }.collect { t ->
        [title: str(t.title), rate: t.rate, price: moneyNative(t.priceSet), price_set: moneyBag(t.priceSet)]
    }
}

// Pause between per-order refund fetches (Admin API cost-bucket courtesy).
void throttle() {
    try { Thread.sleep(RefundMirror.THROTTLE_MS) }
    catch (InterruptedException ie) { Thread.currentThread().interrupt() }
}

// customAttributes [{key,value}] -> note_attributes [{name,value}]; anything else -> [].
List attrsToList(attrs) {
    if (!(attrs instanceof List)) return []
    return attrs.findAll { it != null }.collect { a -> [name: str(a.key), value: str(a.value)] }
}

// tags: a GraphQL [String] joined into the REST comma+space string; a bare string passes through.
String tagsToString(tags) {
    if (tags == null) return null
    if (tags instanceof List) return tags.findAll { it != null }.collect { it.toString() }.join(", ")
    return tags.toString()
}

// The order number from an order name ("#1001" -> 1001) as a Long, or null. Takes the LAST digit
// run so a numeric/store prefix ("2024#1001", "JP2024-1001" -> 1001) still yields the real number
// (the webhook path stamps Shopify's true $.order_number; default "#NNNN" names match exactly).
// A trailing-suffix scheme ("1001-A2") is the residual exotic case and would take the suffix.
Long orderNumberFrom(name) {
    if (name == null) return null
    def m = (name.toString() =~ /(\d+)/)
    String last = null
    while (m.find()) last = m.group(1)
    try { if (last != null) return Long.parseLong(last) } catch (Exception ignore) {}
    return null
}

// --- Mirror path / typed props ------------------------------------------------

// The NEW-node path for an order: /content/commerce/orders/raw/{yyyy}/{MM}/order_{id}.json, nested
// by the ORDER's created_at (historical placement). Falls back to server-now only when created_at
// is missing/unparseable.
String derivePath(String oid, createdAt) {
    def ym = yearMonth(createdAt)
    return "${Orders.STORE_DIR}/${ym[0]}/${ym[1]}/order_${oid}.json".toString()
}

// [yyyy, MM] from an ISO timestamp, honouring the embedded offset (Shopify emits shop-local
// timestamps), falling back to server-now when absent/unparseable.
List yearMonth(v) {
    def s = v?.toString()?.trim()
    if (s) {
        try {
            def odt = java.time.OffsetDateTime.parse(s)
            return [String.format("%04d", odt.getYear()), String.format("%02d", odt.getMonthValue())]
        } catch (Exception ignore) {}
        try {
            def odt = java.time.Instant.parse(s).atOffset(java.time.ZoneOffset.UTC)
            return [String.format("%04d", odt.getYear()), String.format("%02d", odt.getMonthValue())]
        } catch (Exception ignore) {}
    }
    def now = java.time.OffsetDateTime.now()
    return [String.format("%04d", now.getYear()), String.format("%02d", now.getMonthValue())]
}

// Typed setters — set ONLY when a value is present (missing -> omit), mirroring order-paid's
// jsonpath-then-setProperties behaviour and the typed-prop idioms of the commerce classes
// (money as BigDecimal, counters as long).
void setStr(res, String name, v) {
    def s = (v == null) ? null : v.toString()
    if (s != null && !s.trim().isEmpty()) res.setProperty(name, s)
}

void setDecimal(res, String name, v) {
    def bd = Money.toNumber(v)
    if (bd != null) res.setProperty(name, (BigDecimal) bd)
}

void setLong(res, String name, v) {
    def l = asLong(v)
    if (l != null) res.setProperty(name, (long) l)
}

// Set a Date prop from an ISO string / epoch value — omit when unparseable. Matches the toDate the
// order routes apply to commerce:ordered_at (a real Date so xs:dateTime range/order-by works).
void setDate(res, String name, value) {
    long ms = parseMs(value)
    if (ms > 0) res.setProperty(name, new java.util.Date(ms))
}

long parseMs(v) {
    if (v == null) return 0L
    if (v instanceof java.util.Calendar) return ((java.util.Calendar) v).getTimeInMillis()
    if (v instanceof java.util.Date) return ((java.util.Date) v).getTime()
    if (v instanceof Number) return ((Number) v).longValue()
    def s = v.toString().trim()
    if (s.isEmpty()) return 0L
    try { return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (Exception ignore) {}
    try { return java.time.Instant.parse(s).toEpochMilli() } catch (Exception ignore) {}
    return 0L
}

// --- Small value helpers ------------------------------------------------------

def str(v) { v == null ? null : v.toString() }

def lower(v) { v == null ? null : v.toString().trim().toLowerCase() }

def firstNonBlank(a, b) {
    if (a != null && !a.toString().trim().isEmpty()) return a
    return (b != null && !b.toString().trim().isEmpty()) ? b : null
}

Long asLong(v) {
    if (v == null) return null
    if (v instanceof Number) return ((Number) v).longValue()
    def s = v.toString().trim()
    if (s.isEmpty()) return null
    try { return Long.parseLong(s) } catch (Exception ignore) { return null }
}

// A Shopify numeric id string -> a Long when it parses (REST ids are numbers), else the String
// form. Guarded so a "0"-valued id does not trip Groovy's falsy-on-zero elvis.
def toNumericIdValue(s) {
    if (s == null || s.toString().trim().isEmpty()) return null
    def l = asLong(s)
    return (l != null) ? l : s.toString()
}

// --- Streaming / IO helpers (shared with reconcileBulkResult) -----------------

// Stream a (possibly gzipped) JSONL URL line by line, never holding the whole file.
void eachJsonlLine(HttpClient httpClient, String url, Closure handle) {
    def req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build()
    def resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream())
    // A 5xx while fetching the (pre-signed) result URL is a transient server-side blip: surface it
    // as an IOException so the caller's isTransient(...) path retries instead of failing terminally.
    if (resp.statusCode() >= 500) {
        try { resp.body()?.close() } catch (Exception ignore) {}
        throw new java.io.IOException("download HTTP ${resp.statusCode()}")
    }
    def raw = resp.body()
    // Auto-detect gzip (magic 0x1f 0x8b) so a plain or gzipped JSONL both stream.
    def pin = new java.io.PushbackInputStream(raw, 2)
    byte[] head = new byte[2]
    int n = 0
    while (n < 2) {
        int r = pin.read(head, n, 2 - n)
        if (r == -1) break
        n += r
    }
    boolean gz = (n == 2 && (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b)
    if (n > 0) pin.unread(head, 0, n)
    def ins = gz ? new GZIPInputStream(pin) : pin
    new java.io.BufferedReader(new java.io.InputStreamReader(ins, "UTF-8")).withCloseable { reader ->
        String line
        while ((line = reader.readLine()) != null) {
            line = line.trim()
            if (!line.isEmpty()) handle(line)
        }
    }
}

def readYaml(String path) {
    try {
        def res = repositorySession.getResource(path)
        if (res != null && res.exists()) return YAML.parse(res)
    } catch (Exception e) {
        log.warn("importBulkResult: could not read ${path}: ${e.message}")
    }
    return null
}

// A download-time failure is TRANSIENT (worth retrying) when it is an I/O / network error
// (connection or stream reset, read timeout) or a server 5xx surfaced by eachJsonlLine. A data
// error (JSON parse/mapping, from jackson) is NOT transient - it falls through to a terminal
// FAILED so a genuinely corrupt export does not loop forever.
boolean isTransient(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
        if (t.getClass().getName().startsWith("com.fasterxml.jackson")) return false
        if (t instanceof java.io.IOException) return true
    }
    return false
}
