// Customer endpoint (admin, READ-ONLY): browse / search / customer detail over the Shopify customer mirror.
//
//   GET ?view=browse[&q=&tag=&marketing=&sourceStatus=&page=&limit=]
//                   [&sort=updated|spend&spendFrom=&spendTo=&minSpend=&spendMetric=totalPrice|gross|net]
//                                             — paginated customer list + facets
//   GET ?view=search&q=partial[&limit]        — partial match on name / email / id
//   GET ?view=customer&id=123                 — one customer (Shopify body + props)
//   GET ?view=customer&key=customer_123[.json]  (id / key are interchangeable)
//
// Spend axis (operator sovereignty — the metric/window/threshold are all request-chosen):
//   sort=spend        — rank by per-customer purchase amount, largest first. With no other
//                       filter active the ranking comes straight from the fact aggregation
//                       (exact, uncapped — purchasers only); with filters it re-ranks the
//                       filtered scan window (capped like the rest of the browse).
//   spendFrom/spendTo — ISO-8601 instants bounding the purchase window (absent = all time).
//   minSpend          — keep only customers whose chosen metric ≥ this base-currency amount
//                       over the window (the "purchased at least N over the specified window" filter).
//   spendMetric       — totalPrice (default) | gross | net — which figure ranks/filters.
// The per-customer figures come from ONE grouped facet pass over the order-grain sales
// facts (commerce.SalesQuery.spendByCustomer — uncapped, exact); the customer mirror
// itself stores NO derived spend (facts stay the single source of the numbers).
//
// Backed by the first-class customer store (/content/commerce/customers) — body
// = raw Shopify customer JSON, profile fields promoted to typed, auto-indexed
// properties. Edits go through the sync endpoint (POST
// {"action":"customer",...}); this endpoint only reads.
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.

import commerce.Api
import commerce.Customers
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
            respond(200, browseCustomers())
            break
        case "search":
            def sq = blankToNull(request.getParameter("q"))
            if (sq == null) { respond(400, [error: "q is required"]); break }
            int searchLimit = paramInt("limit", 100, 1, 1000)
            respond(200, [query: sq, customers: Customers.search(repositorySession, sq, searchLimit)])
            break
        case "customer":
            // id=<customerId> and key=customer_{id}[.json] are interchangeable.
            def key = blankToNull(request.getParameter("key"))
            if (key != null && key.endsWith(".json")) key = key.substring(0, key.length() - 5)
            if (key == null) {
                // The wire id form is the Shopify GID — peel to the numeric
                // storage key HERE (commerce.Api), never in the client.
                def id = Api.legacyId(blankToNull(request.getParameter("id")))
                if (id != null) key = "customer_${id}".toString()
            }
            if (key == null) { respond(400, [error: "id or key is required"]); break }
            def rec = Customers.read(repositorySession, key)
            if (rec.isEmpty()) { respond(404, [error: "Customer not found: ${key}".toString()]); break }
            def path = Customers.pathFor(key)
            def res = repositorySession.getResource(path)
            // Wire contract (commerce.Api): id is the Shopify GID; timestamps are
            // ms-precision ISO. body stays the SOURCE-FAITHFUL raw Shopify mirror.
            respond(200, [
                id   : rec.id,
                path : path,
                body : rec.customer,
                props: [
                    email           : propStr(res, "commerce:email"),
                    name            : propStr(res, "commerce:name"),
                    tags            : propStr(res, "commerce:tags"),
                    taxExempt       : propBool(res, "commerce:tax_exempt"),
                    marketingConsent: propStr(res, "commerce:marketing_consent"),
                    marketingEnabled: propBool(res, "commerce:marketing_enabled"),
                    sourceStatus    : propStr(res, "commerce:source_status"),
                    status          : propStr(res, "commerce:status"),
                    createdAt       : propIso(res, "commerce:created_at"),
                    updatedAt       : propIso(res, "commerce:updated_at"),
                ],
            ])
            break
        default:
            respond(400, [error: "unknown view (browse|search|customer)"])
    }
} catch (Exception e) {
    log.error("crm endpoint error: ${e.message}", e)
    respond(500, [error: "Internal error"])
}

// --- Views -------------------------------------------------------------------

// Faceted customer browse (the Commerce Customers browser). One XPath query over
// the auto-indexed commerce:* profile properties applies the string filters; a
// single pass over the matches applies the marketing filter (a Boolean prop),
// collects the requested page AND the facet counts, so counts reflect the current
// drill-down. Options come from the request: q, tag, marketing, sourceStatus,
// page, limit — plus the spend axis (sort=spend / spendFrom / spendTo / minSpend /
// spendMetric), resolved from the order-grain sales facts in one grouped facet pass.
Map browseCustomers() {
    final int SCAN_CAP = 5000
    int pageSize = paramInt("limit", 50, 1, 200)
    int page = paramInt("page", 1, 1, 1_000_000)
    int offset = (page - 1) * pageSize
    def marketing = blankToNull(request.getParameter("marketing"))?.toLowerCase()

    // Spend axis (sort by purchase amount / min-spend-in-period filter).
    def sort = (blankToNull(request.getParameter("sort")) ?: "updated").toLowerCase()
    BigDecimal minSpend = paramDecimal("minSpend")
    def metric = (blankToNull(request.getParameter("spendMetric")) ?: "totalPrice")
    if (!["totalPrice", "gross", "net"].contains(metric)) metric = "totalPrice"
    boolean spendAxis = (sort == "spend" || minSpend != null)
    Map spendMap = [:]
    String baseCurrency = null
    Long spendFrom = null, spendTo = null
    if (spendAxis) {
        spendFrom = instantMs("spendFrom")
        spendTo = instantMs("spendTo")
        long sf = spendFrom != null ? spendFrom.longValue() : 0L
        long st = spendTo != null ? spendTo.longValue() : System.currentTimeMillis()
        def opts = SalesQuery.defaults(SalesQuery.config(repositorySession))
        spendMap = SalesQuery.spendByCustomer(repositorySession, sf, st, opts)
        baseCurrency = SalesQuery.baseCurrencyOf(repositorySession, sf, st, opts)
    }

    def preds = []
    def q = xpathSafe(request.getParameter("q"))
    if (!q.isEmpty()) preds << "jcr:contains(., '${q}')".toString()
    def tag = xpathSafe(request.getParameter("tag"))
    if (!tag.isEmpty()) preds << "jcr:like(@commerce:tags, '%${tag}%')".toString()
    def sourceStatus = xpathSafe(request.getParameter("sourceStatus"))
    if (!sourceStatus.isEmpty()) preds << "@commerce:source_status = '${sourceStatus}'".toString()
    boolean hasFilters = !preds.isEmpty() || marketing != null

    def where = preds.isEmpty() ? "" : "[${preds.join(' and ')}]"
    // Cast the sort key to xs:dateTime so the date comparator matches the typed
    // (Date) docvalues; a bare @commerce:updated_at picks the String (SORTED)
    // comparator and throws on the numeric docvalues left by the property-type
    // migration. Same idiom as the EIP Console search. The spend sort keeps this
    // scan order (it is only the tie-break) and re-ranks the match set in memory
    // by the facet-derived per-customer figure.
    def stmt = "/jcr:root${Customers.STORE_DIR}//element(*, nt:file)${where}" +
               " order by xs:dateTime(@commerce:updated_at) descending"
    def jq = repositorySession.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
    jq.limit((long) SCAN_CAP)
    def resources = jq.execute().getResources()

    def items = []
    def ranked = []   // spend sort: [res, spendRec, metricVal] for the whole match set, paged after ranking
    int matched = 0
    boolean exactRank = (sort == "spend" && !hasFilters)
    def tags = [:], marketingFacet = [:], sourceStatuses = [:]
    if (resources != null) {
        resources.each { res ->
            try {
                if (!res.getName().endsWith(".json")) return
                // Only member customers (customer_{id}.json) are listed — they are the
                // ones the editor can open (it derives the id from the filename). Guests
                // (customer_email_{hash}.json, no Shopify id) are excluded by design.
                if (!(res.getName() ==~ /customer_\d+\.json/)) return
                boolean me = propBool(res, "commerce:marketing_enabled")
                if (marketing != null && me.toString() != marketing) return

                def spendRec = null
                BigDecimal metricVal = null
                if (spendAxis) {
                    def cid = res.getName().replaceAll(/^customer_/, "").replaceAll(/\.json$/, "")
                    spendRec = spendMap[cid]
                    metricVal = (spendRec?.get(metric) ?: BigDecimal.ZERO) as BigDecimal
                    // The operator threshold: keep only customers at/above minSpend over the
                    // window. Applied BEFORE the counters so facets reflect the drill-down.
                    if (minSpend != null && metricVal.compareTo(minSpend) < 0) return
                }

                matched++
                splitTags(propStr(res, "commerce:tags")).each { countInc(tags, it) }
                countInc(marketingFacet, me.toString())
                countInc(sourceStatuses, propStr(res, "commerce:source_status"))
                if (exactRank) {
                    // Rows come from the fact ranking below (uncapped) — this scan only feeds the facets.
                } else if (sort == "spend") {
                    ranked << [res: res, spend: spendRec, metric: metricVal]
                } else if (matched > offset && items.size() < pageSize) {
                    // ONE row builder — the same wire shape the search/detail views
                    // return (commerce.Api contract), never an ad-hoc projection.
                    def row = Customers.row(repositorySession, res)
                    if (spendAxis) row.spend = SalesQuery.spendRowWire(spendRec, baseCurrency)
                    items << row
                }
            } catch (Exception ignore) {}
        }
    }

    if (exactRank) {
        // No store-side filters → rank straight from the fact aggregation (exact and UNCAPPED — a top
        // spender whose mirror node was not recently updated is never lost to the 5000-node scan
        // window). Only customers with orders in the window rank; the page rows are resolved by
        // direct id lookup on the flat store.
        def rankedIds = spendMap.entrySet()
            .findAll { minSpend == null || (((it.value?.get(metric) ?: BigDecimal.ZERO) as BigDecimal).compareTo(minSpend) >= 0) }
            .sort { a, b -> ((b.value?.get(metric) ?: BigDecimal.ZERO) as BigDecimal) <=> ((a.value?.get(metric) ?: BigDecimal.ZERO) as BigDecimal) }
        matched = rankedIds.size()
        int skipped = 0
        for (e in rankedIds) {
            if (items.size() >= pageSize) break
            def res = null
            try { res = repositorySession.getResource(Customers.pathFor("customer_${e.key}")) } catch (Exception ignore) {}
            if (res == null || !res.exists()) { matched--; continue }   // fact without a mirror (not yet imported)
            if (skipped < offset) { skipped++; continue }
            def row = Customers.row(repositorySession, res)
            row.spend = SalesQuery.spendRowWire(e.value, baseCurrency)
            items << row
        }
    } else if (sort == "spend") {
        ranked.sort { a, b -> (b.metric ?: BigDecimal.ZERO) <=> (a.metric ?: BigDecimal.ZERO) }
        ranked.drop(offset).take(pageSize).each { e ->
            def row = Customers.row(repositorySession, e.res)
            row.spend = SalesQuery.spendRowWire(e.spend, baseCurrency)
            items << row
        }
    }

    def out = [
        items   : items,
        facets  : [tags: tags, marketing: marketingFacet, sourceStatus: sourceStatuses],
        total   : matched,
        page    : page,
        pageSize: pageSize,
        capped  : !exactRank && resources != null && resources.length >= SCAN_CAP,
    ]
    if (spendAxis) {
        out.spendWindow = [
            from        : spendFrom != null ? Api.instant(spendFrom) : null,
            to          : spendTo != null ? Api.instant(spendTo) : null,
            metric      : metric,
            minSpend    : minSpend != null ? Api.num(minSpend, 0) : null,
            baseCurrency: baseCurrency,
        ]
    }
    return out
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

// Decimal query param (the minSpend threshold) → BigDecimal, or null when absent/invalid.
BigDecimal paramDecimal(String name) {
    def v = request.getParameter(name)
    if (v == null || v.trim().isEmpty()) return null
    try { return new BigDecimal(v.trim()) } catch (Exception ignore) { return null }
}

// ISO-8601 instant parameter → epoch ms, or null when absent/invalid (the client sends
// new Date(...).toISOString(), per the platform wire convention).
Long instantMs(String name) {
    def v = request.getParameter(name)
    if (v == null || v.trim().isEmpty()) return null
    try { return java.time.OffsetDateTime.parse(v.trim()).toInstant().toEpochMilli() } catch (Exception ignore) { return null }
}

// Keep a user value safe inside an XPath string literal: drop the characters that
// would break out of the quoted term or the expression.
String xpathSafe(String s) {
    if (s == null) return ""
    return s.replaceAll("['\"\\[\\]\\(\\)\\\\]", " ").replaceAll("\\s+", " ").trim()
}

// Shopify tags are one comma-separated string ("winter, sale").
List splitTags(String tagsRaw) {
    if (tagsRaw == null || tagsRaw.trim().isEmpty()) return []
    return tagsRaw.split(",").collect { it.trim() }.findAll { !it.isEmpty() }
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

boolean propBool(res, String name) {
    try {
        if (res.hasProperty(name)) {
            def v = res.getProperty(name).getValue()
            if (v instanceof Boolean) return v
            return v != null && v.toString().equalsIgnoreCase("true")
        }
    } catch (Exception ignore) {}
    return false
}

// Date-typed property → the wire timestamp (ms-precision ISO-8601, commerce.Api).
String propIso(res, String name) {
    try {
        if (!res.hasProperty(name)) return null
        return Api.instant(res.getProperty(name).getValue())
    } catch (Exception ignore) {}
    return null
}
