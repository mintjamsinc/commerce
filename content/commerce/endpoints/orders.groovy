// Order endpoint (admin, READ-ONLY): browse / search / order detail over the Shopify order mirror.
//
//   GET ?view=browse[&q=&status=&financial=&currency=&customerId=&productId=&from=&to=&page=&limit=]
//                                             — paginated order list + facets
//   GET ?view=search&q=partial[&limit]        — partial match on order number / email / id
//   GET ?view=order&id=123                    — one order (Shopify body + props)
//   GET ?view=order&key=order_123[.json]        (id / key are interchangeable)
//
// Browse drill-down axes:
//   customerId — GID or numeric Shopify customer id → @commerce:customer_id (index-backed).
//   productId  — GID or numeric Shopify product id. The raw order node has no product axis, so
//                the order-id set is resolved from the line-grain sales facts
//                (/content/commerce/sales/lines/index, @commerce:product_id) via one facet COUNT
//                over @commerce:order_id — uncapped, then intersected during the scan.
//   from/to    — ISO-8601 instants → @commerce:ordered_at range (the business date).
//
// Backed by the nested order mirror (/content/commerce/orders/raw/{yyyy}/{MM}) —
// body = raw Shopify order JSON, identity / money / lifecycle fields promoted to
// typed, auto-indexed properties. The safe metadata edits (note / tags /
// customAttributes) go through the sync endpoint (POST
// {"action":"order",...}); this endpoint only reads.
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.

import commerce.Api
import commerce.Orders
import commerce.SalesQuery
import com.fasterxml.jackson.databind.ObjectMapper
import javax.jcr.query.Query

def mapper = new ObjectMapper()

try {
    if (request.getMethod() != "GET") {
        response.setStatus(405)
        return
    }

    def view = (request.getParameter("view") ?: "browse").trim().toLowerCase()
    switch (view) {
        case "browse":
            respond(200, browseOrders())
            break
        case "search":
            def sq = blankToNull(request.getParameter("q"))
            if (sq == null) { respond(400, [error: "q is required"]); break }
            int searchLimit = paramInt("limit", 100, 1, 1000)
            respond(200, [query: sq, orders: Orders.search(repositorySession, sq, searchLimit)])
            break
        case "order":
            // id=<orderId> and key=order_{id}[.json] are interchangeable; Orders.read
            // normalizes either form to the order id and locates the (nested) node.
            def key = blankToNull(request.getParameter("key"))
            if (key == null) key = blankToNull(request.getParameter("id"))
            if (key == null) { respond(400, [error: "id or key is required"]); break }
            def rec = Orders.read(repositorySession, key)
            if (rec.isEmpty()) { respond(404, [error: "Order not found: ${key}".toString()]); break }
            // Wire contract (commerce.Api): id is the Shopify GID, money rides as
            // {currency, amount} number objects, timestamps are ms-precision ISO.
            // body stays the SOURCE-FAITHFUL raw Shopify mirror (documented raw view).
            respond(200, [
                id   : rec.id,
                path : rec.path,
                body : rec.body,
                props: [
                    orderNumber    : rec.orderNumber,
                    customerEmail  : rec.customerEmail,
                    totalPrice     : rec.totalPrice,
                    totalPriceBase : rec.totalPriceBase,
                    status         : rec.status,
                    sourceStatus   : rec.sourceStatus,
                    refundedAmount : rec.refundedAmount,
                    refundCount    : rec.refundCount,
                    cancelledAt    : rec.cancelledAt,
                    fulfilledAt    : rec.fulfilledAt,
                    trackingNumber : rec.trackingNumber,
                    trackingCompany: rec.trackingCompany,
                ],
            ])
            break
        default:
            respond(400, [error: "unknown view (browse|search|order)"])
    }
} catch (Exception e) {
    log.error("orders endpoint error: ${e.message}", e)
    respond(500, [error: "Internal error"])
}

// --- Views -------------------------------------------------------------------

// Faceted order browse (the Commerce Orders browser). One XPath query over the
// auto-indexed commerce:* properties applies the string filters; a single pass
// over the matches collects the requested page AND the facet counts, so counts
// reflect the current drill-down. Options come from the request: q, status,
// financial, currency, customerId, productId, from, to, page, limit.
Map browseOrders() {
    final int SCAN_CAP = 5000
    int pageSize = paramInt("limit", 50, 1, 200)
    int page = paramInt("page", 1, 1, 1_000_000)
    int offset = (page - 1) * pageSize

    def preds = []
    def q = xpathSafe(request.getParameter("q"))
    if (!q.isEmpty()) preds << "jcr:contains(., '${q}')".toString()
    def status = xpathSafe(request.getParameter("status"))
    if (!status.isEmpty()) preds << "@commerce:status = '${status}'".toString()
    // financial → the Shopify financial_status mirrored on commerce:source_status.
    def financial = xpathSafe(request.getParameter("financial"))
    if (!financial.isEmpty()) preds << "@commerce:source_status = '${financial}'".toString()
    def currency = xpathSafe(request.getParameter("currency"))
    if (!currency.isEmpty()) preds << "@commerce:currency = '${currency}'".toString()
    // customerId: GID or numeric (Api.legacyId peels the GID server-side).
    def customerId = xpathSafe(Api.legacyId(blankToNull(request.getParameter("customerId"))))
    if (!customerId.isEmpty()) preds << "@commerce:customer_id = '${customerId}'".toString()
    // from/to: ISO instants → index-backed range on the ordered_at business date.
    String fromIso = paramInstant("from")
    String toIso = paramInstant("to")
    if (fromIso != null) preds << "@commerce:ordered_at >= xs:dateTime('${fromIso}')".toString()
    if (toIso != null) preds << "@commerce:ordered_at <= xs:dateTime('${toIso}')".toString()

    // productId: resolve the order-id set from the line-grain sales facts (one facet COUNT over
    // @commerce:order_id — the whole match set, no scan cap). A typical product's set is small,
    // so it is pushed INTO the XPath predicate (index-backed, exact — orders older than the scan
    // cap still match); only a very large set (bestseller) falls back to the in-memory intersect,
    // whose semantics are then the same capped-window ones as the rest of the browse.
    final int PRODUCT_ID_PREDICATE_MAX = 1000
    def productId = xpathSafe(Api.legacyId(blankToNull(request.getParameter("productId"))))
    Set productOrderIds = null
    if (!productId.isEmpty()) {
        def ids = SalesQuery.labelsOf(
            SalesQuery.facets(repositorySession,
                "/jcr:root${SalesQuery.LINES_FACT_DIR}//element(*, nt:file)" +
                "[@commerce:product_id = '${productId}']" +
                " facet accumulate ${SalesQuery.countExpr('commerce:order_id')}".toString()),
            SalesQuery.countDim("commerce:order_id"))
            .collect { xpathSafe(it?.toString()) }.findAll { it && !it.isEmpty() }
        if (ids.isEmpty()) {
            return [items: [], facets: [status: [:], financial: [:], currency: [:]],
                    total: 0, page: page, pageSize: pageSize, capped: false]
        }
        if (ids.size() <= PRODUCT_ID_PREDICATE_MAX) {
            preds << "(${ids.collect { "@commerce:order_id = '${it}'" }.join(' or ')})".toString()
        } else {
            productOrderIds = new HashSet(ids)
        }
    }

    def where = preds.isEmpty() ? "" : "[${preds.join(' and ')}]"
    // Sort by commerce:order_number descending — the typed Long human order number,
    // the always-present chronological key for orders (they carry NO date property).
    // MUST cast with xs:long(): a BARE @commerce:order_number order-by makes Lucene pick
    // the String (SORTED) comparator, which throws against this field's numeric
    // (SORTED_NUMERIC) docvalues — the Long analogue of the xs:dateTime() cast the
    // customer/product browse needs for their Date sort keys. Newest orders first.
    def stmt = "/jcr:root${Orders.STORE_DIR}//element(*, nt:file)${where}" +
               " order by xs:long(@commerce:order_number) descending"
    def jq = repositorySession.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
    jq.limit((long) SCAN_CAP)
    def resources = jq.execute().getResources()

    def items = []
    int matched = 0
    def statuses = [:], financials = [:], currencies = [:]
    if (resources != null) {
        resources.each { res ->
            try {
                if (!res.getName().endsWith(".json")) return
                // Only member order files (order_{id}.json) are listed — the ones the
                // editor can open (it derives the id from the filename).
                if (!(res.getName() ==~ /order_\d+\.json/)) return
                if (productOrderIds != null && !productOrderIds.contains(propStr(res, "commerce:order_id"))) return
                matched++
                countInc(statuses, propStr(res, "commerce:status"))
                countInc(financials, propStr(res, "commerce:source_status"))
                countInc(currencies, propStr(res, "commerce:currency"))
                if (matched > offset && items.size() < pageSize) {
                    // ONE row builder — the same wire shape the search/detail views
                    // return (commerce.Api contract), never an ad-hoc projection.
                    items << Orders.row(repositorySession, res)
                }
            } catch (Exception ignore) {}
        }
    }
    return [
        items   : items,
        facets  : [status: statuses, financial: financials, currency: currencies],
        total   : matched,
        page    : page,
        pageSize: pageSize,
        capped  : resources != null && resources.length >= SCAN_CAP,
    ]
}

// --- Helpers -----------------------------------------------------------------

void respond(int status, Object body) {
    response.setStatus(status)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(body))
}

String blankToNull(String s) { (s == null || s.trim().isEmpty()) ? null : s.trim() }

int paramInt(String name, int dflt, int lo, int hi) {
    try {
        def v = request.getParameter(name)
        if (v != null && !v.trim().isEmpty()) return Math.max(lo, Math.min(hi, v.trim() as int))
    } catch (Exception ignore) {}
    return dflt
}

// Keep a user value safe inside an XPath string literal: drop the characters that
// would break out of the quoted term or the expression.
String xpathSafe(String s) {
    if (s == null) return ""
    return s.replaceAll("['\"\\[\\]\\(\\)\\\\]", " ").replaceAll("\\s+", " ").trim()
}

// ISO-8601 instant parameter → a normalized xs:dateTime literal for the XPath range
// predicate, or null when absent/invalid. Parsed via OffsetDateTime (never concatenating
// the raw value) so the query stays injection-safe.
String paramInstant(String name) {
    def v = request.getParameter(name)
    if (v == null || v.trim().isEmpty()) return null
    try {
        return java.time.OffsetDateTime.parse(v.trim())
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    } catch (Exception ignore) { return null }
}

void countInc(Map counter, String value) {
    if (value == null) return
    def v = value.trim()
    if (v.isEmpty()) return
    counter[v] = ((counter[v] ?: 0) as int) + 1
}

String propStr(res, String name) {
    try { if (res.hasProperty(name)) return res.getProperty(name).getValue()?.toString() } catch (Exception ignore) {}
    return null
}

