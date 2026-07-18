// Commerce dashboard snapshot endpoint (admin).
//
// One aggregated JSON view for the Commerce Dashboard Webtop app: the sales trend
// (occurrence-date summary), fulfillment and inventory KPIs (commerce.Dashboard),
// integration health (commerce.Health) and open-task / SLA counts (BPMN engine +
// commerce.TaskSla). Read-only.
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.
//
//   GET /bin/cms.cgi/{workspace}/content/commerce/endpoints/dashboard.groovy?days=7&salesDays=30

import commerce.Api
import commerce.Dashboard
import commerce.Health
import commerce.TaskSla
import commerce.SimpleYaml
import commerce.Backorders
import commerce.Events
import commerce.Reports
import commerce.Reconciliation
import commerce.Jcr
import com.fasterxml.jackson.databind.ObjectMapper

if (request.getMethod() != "GET") {
    response.setStatus(405)
    return
}

def PROCESS_KEYS = ["order-review-flow", "refund-review-flow", "product-update-flow", "backorder-release-flow"]

int days = paramInt("days", 7, 1, 90)
int salesDays = paramInt("salesDays", 30, 1, 365)
// The viewer's IANA timezone (sent by the dashboard app from the user's Preferences).
// Drives the day-window arithmetic; absent/invalid falls back to UTC, never the
// server default, so the dashboard is server-timezone independent.
String tz = request.getParameter("tz")?.trim() ?: null
def tzZone = commerce.SalesQuery.zoneOf(tz)

try {
    def out = [generatedAt: Api.now()]

    // Fulfillment + inventory (defensive: each section degrades independently).
    try { out.fulfillment = Dashboard.fulfillmentSummary(repositorySession) }
    catch (Exception e) { log.warn("dashboard: fulfillment failed: ${e.message}"); out.fulfillment = [error: true] }

    try { out.inventory = Dashboard.inventorySummary(repositorySession) }
    catch (Exception e) { log.warn("dashboard: inventory failed: ${e.message}"); out.inventory = [error: true] }

    try { out.health = Health.snapshot(repositorySession, days) }
    catch (Exception e) { log.warn("dashboard: health failed: ${e.message}"); out.health = [error: true] }

    try { out.tasks = taskSummary(PROCESS_KEYS) }
    catch (Exception e) { log.warn("dashboard: tasks failed: ${e.message}"); out.tasks = [error: true] }

    try { out.reorders = reorderSummary() }
    catch (Exception e) { log.warn("dashboard: reorders failed: ${e.message}"); out.reorders = [error: true] }

    try { out.locations = locationsSummary() }
    catch (Exception e) { log.warn("dashboard: locations failed: ${e.message}"); out.locations = [error: true] }

    try { out.backorders = backorderSummary() }
    catch (Exception e) { log.warn("dashboard: backorders failed: ${e.message}"); out.backorders = [error: true] }

    try { out.events = eventSummary() }
    catch (Exception e) { log.warn("dashboard: events failed: ${e.message}"); out.events = [error: true] }

    try { out.crm = crmSummary() }
    catch (Exception e) { log.warn("dashboard: crm failed: ${e.message}"); out.crm = [error: true] }

    try { out.salesTrend = salesTrendSummary(salesDays, tz, tzZone) }
    catch (Exception e) { log.warn("dashboard: salesTrend failed: ${e.message}"); out.salesTrend = [error: true] }

    try { out.reconciliation = reconciliationSummary() }
    catch (Exception e) { log.warn("dashboard: reconciliation failed: ${e.message}"); out.reconciliation = [error: true] }

    try { out.outboundSync = syncSummary(salesDays, tzZone) }
    catch (Exception e) { log.warn("dashboard: outboundSync failed: ${e.message}"); out.outboundSync = [error: true] }

    response.setStatus(200)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(out))
} catch (Exception e) {
    log.error("dashboard endpoint error: ${e.message}", e)
    response.setStatus(500)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"Internal error"}')
}

// --- Helpers -----------------------------------------------------------------

int paramInt(String name, int dflt, int lo, int hi) {
    try {
        def raw = request.getParameter(name)
        if (raw != null && !raw.trim().isEmpty()) {
            return Math.max(lo, Math.min(hi, raw.trim() as int))
        }
    } catch (Exception ignore) {}
    return dflt
}

// Open human tasks of the commerce flows, counted by SLA status.
Map taskSummary(List processKeys) {
    def cfg = [:]
    def cfgRes = repositorySession.getResource("/etc/commerce/config/sla.yml")
    if (cfgRes != null && cfgRes.exists()) {
        cfg = SimpleYaml.parse(cfgRes.content?.toString())
    }

    def engine = ProcessAPI.getEngine()
    def taskService = engine.getTaskService()
    long now = System.currentTimeMillis()

    long total = 0, unassigned = 0
    def byStatus = [ok: 0L, unclaimed: 0L, open: 0L, overdue: 0L]
    processKeys.each { key ->
        def found
        try {
            found = taskService.createTaskQuery().processDefinitionKey(key).active().list()
        } catch (Exception e) {
            log.warn("dashboard: task query failed for ${key}: ${e.message}")
            return
        }
        found.each { t ->
            total++
            def assignee = t.getAssignee()
            if (assignee == null || assignee.trim().isEmpty()) {
                unassigned++
            }
            def createTime = t.getCreateTime()
            def dueDate = t.getDueDate()
            def taskMap = [
                assignee     : assignee,
                createTimeMs : createTime == null ? now : createTime.getTime(),
                dueDateMs    : dueDate == null ? null : dueDate.getTime(),
            ]
            def status = TaskSla.status(cfg, taskMap, now) ?: "ok"
            byStatus[status] = ((byStatus[status] ?: 0) as long) + 1
        }
    }
    return [total: total, unassigned: unassigned, byStatus: byStatus]
}

// Open "stock check + reorder" tasks (stock < fixed threshold)
// + incoming transfers recorded to Shopify recently (from the outbound audit).
Map reorderSummary() {
    long pendingReview = 0
    try {
        pendingReview = ProcessAPI.getEngine().getTaskService().createTaskQuery()
            .processDefinitionKey("inventory-alert-flow").active().count()
    } catch (Exception e) {
        log.warn("dashboard: review task query failed: ${e.message}")
    }

    // Incoming transfers recorded (outbound audit, this + previous month).
    long ordered = 0
    def ym = java.time.format.DateTimeFormatter.ofPattern("yyyy/MM")
    def today = java.time.LocalDate.now()
    for (int i = 0; i <= 1; i++) {
        def folder
        try { folder = repositorySession.getResource("/content/commerce/sync/${today.minusMonths(i).format(ym)}") } catch (Exception e) { folder = null }
        if (folder == null || !folder.exists()) continue
        def it = folder.list()
        while (it.hasNext()) {
            def child = it.next()
            try {
                if (child.getName().endsWith(".json")
                        && child.hasProperty("commerce:action")
                        && child.getProperty("commerce:action").getValue()?.toString() == "incoming_transfer"
                        && child.hasProperty("commerce:status")
                        && child.getProperty("commerce:status").getValue()?.toString() == "ok") {
                    ordered++
                }
            } catch (Exception ignore) {}
        }
    }
    return [pendingApproval: pendingReview, ordered: ordered]
}

// Multi-location inventory: location count, tracked items, and (item,location)
// pairs at or below the safety stock (out-at-location).
Map locationsSummary() {
    int safety = 0
    def cfgRes = repositorySession.getResource("/etc/commerce/config/locations.yml")
    if (cfgRes != null && cfgRes.exists()) {
        def cfg = commerce.SimpleYaml.parse(cfgRes.content?.toString())
        try { safety = (cfg?.defaultSafetyStock ?: 0).toString().trim() as int } catch (Exception ignore) {}
    }

    long locations = countJson("/content/commerce/inventory/locations")
    long trackedItems = 0
    long lowLocations = 0
    def dir = repositorySession.getResource("/content/commerce/inventory/levels")
    if (dir != null && dir.exists()) {
        def it = dir.list()
        def mapper = new ObjectMapper()
        while (it.hasNext()) {
            def child = it.next()
            try {
                if (!child.getName().endsWith(".json")) continue
                trackedItems++
                def doc = mapper.readValue(child.content.toString(), Map.class)
                if (doc?.locations instanceof Map) {
                    doc.locations.each { loc, v ->
                        if (v instanceof Map && v.available != null) {
                            int avail = (v.available as int)
                            if (avail <= safety) lowLocations++
                        }
                    }
                }
            } catch (Exception ignore) {}
        }
    }
    return [locations: locations, trackedItems: trackedItems, lowLocations: lowLocations]
}

// Backorders: counts by status (book health) + units still awaited.
Map backorderSummary() {
    def s = Backorders.summary(repositorySession)
    def by = s.byStatus ?: [:]
    return [
        backordered: (by.backordered ?: 0) as long,
        ready      : (by.ready ?: 0) as long,
        total      : (s.total ?: 0) as long,
        openUnits  : (s.openUnits ?: 0) as long,
    ]
}

// Event ingestion: total events + by-status (failed events need replay).
Map eventSummary() {
    def s = Events.summary(repositorySession)
    def by = s.byStatus ?: [:]
    return [
        total    : (s.total ?: 0) as long,
        received : (by.received ?: 0) as long,
        processed: (by.processed ?: 0) as long,
        error    : (by.error ?: 0) as long,
    ]
}

// CRM: total customers for the dashboard card — an inline count of the
// received customer records.
Map crmSummary() {
    long customers = 0
    def stmt = "/jcr:root/content/commerce/customers//element(*, nt:file)[@commerce:status='received']"
    def jq = repositorySession.getWorkspace().getQueryManager().createQuery(stmt, javax.jcr.query.Query.XPATH)
    def resources = jq.execute().getResources()
    if (resources != null) {
        resources.each { res ->
            try { if (res.getName().startsWith("customer_") && res.getName().endsWith(".json")) customers++ } catch (Exception ignore) {}
        }
    }
    return [customers: customers]
}

// Sales trend: the occurrence-date daily series + top products for the headline chart.
// The KPIs mirror the Commerce Reports app's occurrence-date summary
// (commerce.SalesQuery.occurrenceSummary — uncapped, exact) so the dashboard headline
// and the report can never disagree: confirmed sales (paymentAmount + refundAmount —
// the payment basis: cash in minus cash out, refundAmount NEGATIVE), the new-order
// count, and AOV = newOrderAmount / newOrderCount
// (the per-order value of the window's NEW orders — refunds against older orders must
// not bend it). All money is base-currency. The top products stay on the line-grain
// sales facts (commerce.SalesQuery.topProducts), labelled with the mirrored product
// titles (the facts carry only the real product_id dimension key).
Map salesTrendSummary(int salesDays, String tz, tzZone) {
    long hi = System.currentTimeMillis()
    long lo = Dashboard.windowStartMs(salesDays, tzZone)
    def report = commerce.SalesQuery.occurrenceSummary(repositorySession, lo, hi, tz)
    def totals = report.totals ?: [:]
    String baseCurrency = report.baseCurrency

    long orders = (totals.newOrderCount ?: 0L) as long
    double newOrderAmount = parseD(totals.newOrderAmount)
    double aov = orders > 0 ? newOrderAmount / orders : 0d

    // Per-day series: the confirmed-sales figure — the same headline the KPI shows.
    def points = (report.daily ?: []).collect { d ->
        [date: d.date, orders: d.newOrderCount, revenue: parseD(d.confirmedSales)]
    }

    // TOP5 products by base gross over the same window (population defaults), titled from the
    // product mirror. The rows carry only the GID — peel to the numeric storage key HERE.
    def opts = commerce.SalesQuery.defaults(commerce.SalesQuery.config(repositorySession))
    def top = commerce.SalesQuery.topProducts(repositorySession, lo, hi, 5, opts)
    def titles = commerce.Pim.titles(repositorySession, top.collect { Api.legacyId(it.productId) })
    top.each { row ->
        row.title = titles[Api.legacyId(row.productId)]
        row.baseCurrency = baseCurrency
    }

    return [
        days          : salesDays,
        baseCurrency  : baseCurrency,
        totalRevenue  : parseD(totals.confirmedSales),
        totalOrders   : orders,
        aov           : aov,
        points        : points,
        topProducts   : top,
    ]
}

// Reconciliation: latest drift report + last-run state.
Map reconciliationSummary() {
    def state = Jcr.readMap(repositorySession, "/content/commerce/reconciliation/state.json")
    def latest = latestReconReport()
    return [
        lastRunAt        : Api.instant(state.lastRunAt),
        productsWithDrift: (latest.productsWithDrift ?: 0) as long,
        totalDiffs       : (latest.totalDiffs ?: 0) as long,
        refreshed        : (latest.refreshed ?: 0) as long,
        checked          : (latest.checked ?: 0) as long,
    ]
}

// Newest DIFF run report whose run did not fail. The batch records EVERY run — including
// failed ones, whose counters reflect only partial work — so the card summarizes the latest
// completed inspection rather than letting an error run's numbers mask it. Index-backed
// (Reconciliation.listRuns over the typed report properties); only this card needs the
// report BODY, so it reads exactly one document.
Map latestReconReport() {
    def rows = Reconciliation.listRuns(repositorySession,
        [scope: Reconciliation.SCOPE_DIFF, result: "success", limit: 1L])
    return rows.isEmpty() ? [:] : Jcr.readMap(repositorySession, rows[0].path)
}

// Outbound CMS → Shopify writes, tallied by outcome over the window.
Map syncSummary(int windowDays, zone) {
    // Reports.operations now filters by an XPath date range (not a day count), so
    // translate the window into a from-bound (start of day, windowDays ago, in the
    // viewer's timezone — UTC when the client sent none).
    def fromIso = java.time.LocalDate.now(zone).minusDays(windowDays)
        .atStartOfDay(zone).toOffsetDateTime()
        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    def ops = Reports.operations(repositorySession, null, fromIso, null, null, 5000)
    long ok = 0, failed = 0, dryrun = 0
    ops.each { o ->
        switch (o.status?.toString()) {
            case "ok": ok++; break
            case "failed": failed++; break
            case "dryrun": dryrun++; break
        }
    }
    return [window: windowDays, total: ops.size(), ok: ok, failed: failed, dryrun: dryrun]
}

double parseD(v) {
    if (v == null) return 0d
    try { return Double.parseDouble(v.toString()) } catch (Exception e) { return 0d }
}

long countJson(String dirPath) {
    long n = 0
    def dir = repositorySession.getResource(dirPath)
    if (dir != null && dir.exists()) {
        def it = dir.list()
        while (it.hasNext()) {
            try { if (it.next().getName().endsWith(".json")) n++ } catch (Exception ignore) {}
        }
    }
    return n
}
