// Commerce dashboard snapshot endpoint (admin).
//
// One aggregated JSON view for the Commerce Dashboard Webtop app: sales and
// inventory KPIs (commerce.Dashboard), integration health (commerce.Health) and
// open-task / SLA counts (BPMN engine + commerce.TaskSla). Read-only.
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.
//
//   GET /bin/cms.cgi/{workspace}/content/commerce/endpoints/dashboard.groovy?days=7&salesDays=30

import commerce.Dashboard
import commerce.Health
import commerce.TaskSla
import commerce.SalesVelocity
import commerce.SimpleYaml
import commerce.Backorders
import commerce.Events
import commerce.Customers
import commerce.Checkouts
import commerce.Reports
import commerce.Jcr
import com.fasterxml.jackson.databind.ObjectMapper

if (request.getMethod() != "GET") {
    response.setStatus(405)
    return
}

def PROCESS_KEYS = ["order-review-flow", "refund-review-flow", "product-update-flow", "backorder-release-flow"]

int days = paramInt("days", 7, 1, 90)
int salesDays = paramInt("salesDays", 30, 1, 365)

try {
    def out = [generatedAt: java.time.Instant.now().toString()]

    // Sales + inventory (defensive: each section degrades independently).
    try { out.sales = Dashboard.salesSummary(repositorySession, salesDays) }
    catch (Exception e) { log.warn("dashboard: sales failed: ${e.message}"); out.sales = [error: true] }

    try { out.inventory = Dashboard.inventorySummary(repositorySession) }
    catch (Exception e) { log.warn("dashboard: inventory failed: ${e.message}"); out.inventory = [error: true] }

    try { out.health = Health.snapshot(repositorySession, days) }
    catch (Exception e) { log.warn("dashboard: health failed: ${e.message}"); out.health = [error: true] }

    try { out.tasks = taskSummary(PROCESS_KEYS) }
    catch (Exception e) { log.warn("dashboard: tasks failed: ${e.message}"); out.tasks = [error: true] }

    try { out.forecast = forecastSummary() }
    catch (Exception e) { log.warn("dashboard: forecast failed: ${e.message}"); out.forecast = [error: true] }

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

    try { out.salesTrend = salesTrendSummary(salesDays) }
    catch (Exception e) { log.warn("dashboard: salesTrend failed: ${e.message}"); out.salesTrend = [error: true] }

    try { out.reconciliation = reconciliationSummary() }
    catch (Exception e) { log.warn("dashboard: reconciliation failed: ${e.message}"); out.reconciliation = [error: true] }

    try { out.outboundSync = syncSummary(salesDays) }
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

// Stockout forecast: count + the most-urgent at-risk variants, from cached velocity.
Map forecastSummary() {
    int warnDays = 7
    def cfgRes = repositorySession.getResource("/etc/commerce/config/velocity.yml")
    if (cfgRes != null && cfgRes.exists()) {
        def cfg = SimpleYaml.parse(cfgRes.content?.toString())
        if (cfg?.stockout?.warnDays != null) {
            try { warnDays = cfg.stockout.warnDays.toString().trim() as int } catch (Exception ignore) {}
        }
    }
    def perDay = SalesVelocity.loadPerDay(repositorySession)
    def atRisk = SalesVelocity.forecast(repositorySession, perDay, warnDays)
    def top = atRisk.take(5).collect {
        [title: it.title, variantTitle: it.variantTitle, quantity: it.quantity, perDay: it.perDay, days: it.days]
    }
    return [warnDays: warnDays, atRisk: atRisk.size(), top: top]
}

// Replenishment: reorders awaiting approval (open workflow tasks) + recently ordered.
Map reorderSummary() {
    long pendingApproval = 0
    try {
        pendingApproval = ProcessAPI.getEngine().getTaskService().createTaskQuery()
            .processDefinitionKey("replenishment-flow").active().count()
    } catch (Exception e) {
        log.warn("dashboard: reorder task query failed: ${e.message}")
    }

    // Recently ordered POs (status "ordered"), this + previous month.
    long ordered = 0
    def ym = java.time.format.DateTimeFormatter.ofPattern("yyyy/MM")
    def today = java.time.LocalDate.now()
    for (int i = 0; i <= 1; i++) {
        def folder
        try { folder = repositorySession.getResource("/content/commerce/purchase-orders/${today.minusMonths(i).format(ym)}") } catch (Exception e) { folder = null }
        if (folder == null || !folder.exists()) continue
        def it = folder.list()
        while (it.hasNext()) {
            def child = it.next()
            try {
                if (child.getName().endsWith(".json") && child.hasProperty("commerce:status")
                        && child.getProperty("commerce:status").getValue()?.toString() == "ordered") {
                    ordered++
                }
            } catch (Exception ignore) {}
        }
    }
    return [pendingApproval: pendingApproval, ordered: ordered]
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

// Backorders: counts by status (book health) + units still awaited (#12).
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

// Event ingestion: total events + by-status (failed events need replay) (#1/#4).
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

// CRM: customer segments + abandoned carts (#13/#14/#15).
Map crmSummary() {
    def s = Customers.summary(repositorySession)
    def seg = s.bySegment ?: [:]
    def rec = s.byRecency ?: [:]

    long abandonedAfterMs = 60L * 60_000L
    def cfgRes = repositorySession.getResource("/etc/commerce/config/crm.yml")
    if (cfgRes != null && cfgRes.exists()) {
        def cfg = SimpleYaml.parse(cfgRes.content?.toString())
        try { abandonedAfterMs = (cfg?.abandonedCart?.abandonedAfterMinutes ?: 60).toString().trim().toLong() * 60_000L } catch (Exception ignore) {}
    }
    def carts = Checkouts.summary(repositorySession, abandonedAfterMs, System.currentTimeMillis())

    return [
        customers: (s.total ?: 0) as long,
        vip      : (s.vip ?: 0) as long,
        atRisk   : (rec.at_risk ?: 0) as long,
        dormant  : (rec.dormant ?: 0) as long,
        newCount : (seg.new ?: 0) as long,
        abandoned: (carts.abandoned ?: 0) as long,
    ]
}

// Sales trend: the daily series + top products + AOV for the headline chart (#16/#25).
Map salesTrendSummary(int salesDays) {
    def report = Reports.sales(repositorySession, salesDays)

    // Pick the primary currency (largest total) for the single-series sparkline + AOV.
    def revenue = (report.totals?.revenue ?: [:])
    String primary = null
    double best = -1
    revenue.each { cur, amt ->
        double a = parseD(amt)
        if (a > best) { best = a; primary = cur }
    }
    double totalRevenue = primary != null ? parseD(revenue[primary]) : 0d
    long orders = (report.totals?.orders ?: 0L) as long
    double aov = orders > 0 ? totalRevenue / orders : 0d

    // Per-day value in the primary currency, oldest→newest (chart-ready).
    def points = (report.daily ?: []).collect { d ->
        [date: d.date, orders: d.orders, revenue: primary != null ? parseD((d.revenue ?: [:])[primary]) : 0d]
    }

    return [
        days          : salesDays,
        primaryCurrency: primary,
        totalRevenue  : totalRevenue,
        totalOrders   : orders,
        aov           : aov,
        points        : points,
        topProducts   : (report.topProducts ?: []).take(5),
    ]
}

// Reconciliation: latest drift report + last-run state (#24).
Map reconciliationSummary() {
    def state = Jcr.readMap(repositorySession, "/content/commerce/reconciliation/state.json")
    def latest = latestReconReport()
    return [
        lastRunAt        : state.lastRunAt,
        productsWithDrift: (latest.productsWithDrift ?: 0) as long,
        totalDiffs       : (latest.totalDiffs ?: 0) as long,
        healed           : (latest.healed ?: 0) as long,
        checked          : (latest.checked ?: 0) as long,
    ]
}

Map latestReconReport() {
    def ymf = new java.text.SimpleDateFormat("yyyy/MM")
    def cal = java.util.Calendar.getInstance()
    String best = null, bestPath = null
    for (int i = 0; i <= 1; i++) {
        def folder = repositorySession.getResource("/content/commerce/reconciliation/${ymf.format(cal.getTime())}")
        if (folder != null && folder.exists()) {
            def it = folder.list()
            while (it.hasNext()) {
                def c = it.next()
                def n = c.getName()
                if (n.startsWith("recon_") && n.endsWith(".json") && (best == null || n > best)) { best = n; bestPath = c.getPath() }
            }
        }
        cal.add(java.util.Calendar.MONTH, -1)
    }
    return bestPath == null ? [:] : Jcr.readMap(repositorySession, bestPath)
}

// Outbound CMS → Shopify writes, tallied by outcome over the window (#2).
Map syncSummary(int windowDays) {
    def ops = Reports.operations(repositorySession, windowDays, null, 5000)
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
