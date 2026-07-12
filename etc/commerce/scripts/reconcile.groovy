// CMS ← Shopify reconciliation (diff scope: status / price).
//
// Invoked on demand from the reconcile endpoint and by the `diff` schedule
// (the wall-clock reconcile scheduler → direct:commerce-reconcile). For the products Shopify reports as
// changed since the last diff watermark it fetches the current Shopify state (status / variant
// price), compares it to the CMS mirror, refreshes the CMS mirror FROM Shopify (status / price
// patched in place), and writes a run-history report. A report is written for EVERY run that
// gets past the enable/Admin-API gates — including no-change and failed runs — so the operator
// UI (commerce-reconcile) can show a complete execution history.
//
// A product's `updatedAt` does NOT change on inventory-only edits, so diff is a status/price
// mechanism. Inventory is NOT reconciled here at all (no inventory fetch, no mirror refresh) —
// the FULL inventory audit is the Bulk job broker, scheduled via the `inventory` scope.
//
// Robustness:
//   • Truncation-safe watermark — if more than (maxPerRun × maxPages) products changed in one
//     window, the run processes the oldest page-cap and advances the watermark only to the
//     last fetched product's updatedAt, so the rest are picked up next run (never skipped).
//   • Cost-budget backoff — per-product fetches pace themselves on Shopify's throttleStatus to
//     leave reserveBudgetPercent of the cost bucket for foreground ops (fulfillment / PIM).
//
// The hourly round-robin baseline (cursor over the whole catalog) was RETIRED on 2026-06-30.
//
// Best-effort throughout: a failure is logged, never thrown; a failed run is still recorded in
// the run history with result "error" and the counters it reached (mirror patches commit one by
// one, so work done before the failure is real and must not be reported as zero).

import java.net.http.HttpClient
import commerce.Api
import commerce.ShopifyAdmin
import commerce.Reconciliation
import commerce.Health
import commerce.Jcr

final int MAX_PAGES = 20

// Cluster guard: only the node that wins this lease runs the task; the others skip this tick.
def __clusterLease = cluster.tryLock("commerce-reconcile", 1800000)
if (__clusterLease == null) {
    log.info("reconcile: another cluster node is running this task - skipping")
    return
}
try {
    def PRODUCTS_DIR = "/content/commerce/products"
    def RECON_DIR = "/content/commerce/reconciliation"
    def STATE_PATH = "${RECON_DIR}/state.json"

    // Run-history bookkeeping — hoisted so the failed-run report in the catch below records
    // the run's ACTUAL progress, not fabricated zeros. runStartedAt doubles as the "did the
    // run really start?" flag: null means a skip (disabled / Admin API unset), not a run.
    def runStartedAt = null
    int checked = 0
    int refreshed = 0                     // field-level refreshes (status / price patches)
    int errors = 0
    int batchSize = 0
    boolean truncated = false
    def allDiffs = []
    def refreshedProductIds = [] as Set   // product-level count for the run history

    try {
        def cfg = readYaml("/etc/commerce/config/reconcile.yml")
        if (cfg == null || cfg.enabled?.toString()?.toLowerCase() == "false") {
            return
        }

        // Reconciliation needs Shopify Admin API reads.
        def shopCfg = readYaml("/etc/commerce/config/shopify.yml")
        def adminApi = shopCfg?.adminApi ?: shopCfg
        if (!ShopifyAdmin.adminApiEnabled(shopCfg)) {
            log.info("reconcile: Admin API not configured - skipping (set adminApi in shopify.yml)")
            return
        }

        // Past the skip gates: from here on the run is real, and every outcome — success,
        // no-change, or failure (even in token/endpoint resolution) — lands in the history.
        runStartedAt = Api.now()

        int maxPerRun = intOr(cfg.maxPerRun, 50)
        // "Accelerator" — leave reserveBudgetPercent of the cost bucket for foreground ops, and
        // an optional fixed floor between per-product calls.
        int reservePct = intOr(cfg.reserveBudgetPercent, 50)
        long minDelayMs = (long) intOr(cfg.minDelayMsPerCall, 0)

        def endpoint = ShopifyAdmin.endpoint(adminApi)
        def token = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
        def httpClient = HttpClient.newHttpClient()

        // --- Pick the batch: products changed in Shopify since the diff watermark ----------
        def state = Jcr.readMap(repositorySession, STATE_PATH)
        def since = state.diffSince?.toString() ?: java.time.Instant.now().minusSeconds(24L * 3600L).toString()
        def sinceWithMargin
        try { sinceWithMargin = java.time.Instant.parse(since).minusSeconds(300L).toString() }
        catch (Exception ignore) { sinceWithMargin = since }

        def changed = fetchChangedProducts(httpClient, endpoint, token, sinceWithMargin, maxPerRun, MAX_PAGES)
        def batch = changed.ids.collect { "product_${it}.json".toString() }
        batchSize = batch.size()
        truncated = (changed.truncated == true)
        String lastUpdatedAt = changed.lastUpdatedAt
        log.info("reconcile: ${batchSize} changed product(s) since ${sinceWithMargin}" +
                 (truncated ? " (TRUNCATED at ${maxPerRun * MAX_PAGES}; resuming from ${lastUpdatedAt} next run)" : ""))

        def lastCost = null   // last Shopify cost extension, drives the throttle gate
        batch.each { name ->
            def productId = name.replace("product_", "").replace(".json", "")
            try {
                def res = repositorySession.getResource("${PRODUCTS_DIR}/${name}")
                if (res == null || !res.exists()) return
                def cmsProduct = JSON.parse(res.content.toString())

                // Pace this call so the batch leaves API budget for foreground operations.
                throttleGate(lastCost, reservePct, minDelayMs)
                def fetched = fetchShopifyProduct(httpClient, endpoint, token, productId)
                lastCost = fetched?.cost
                def shopifyProduct = fetched?.product
                if (shopifyProduct == null) return
                checked++

                def diffs = Reconciliation.diffProduct(cmsProduct, shopifyProduct)
                diffs.each { d ->
                    // Report rows ride to the operator UI — ids in the wire GID form.
                    d.productId = Api.gid("Product", productId)
                    d.title = cmsProduct?.title?.toString()
                    // Shopify is the single source of truth: refresh the CMS mirror for every
                    // drifted field (status / price).
                    d.refreshed = Reconciliation.applyRefresh(repositorySession, log, res, d) ? "ok" : "skipped"
                    if (d.refreshed == "ok") {
                        refreshed++
                        refreshedProductIds << productId
                    }
                    allDiffs << d
                }
            } catch (Exception e) {
                errors++
                log.warn("reconcile: product ${productId} failed: ${e.message}")
            }
        }

        // --- Persist the report + diff watermark ----------------------------------
        // The report is written even for an empty batch (0 checked, result "success"), so the
        // run history stays complete.
        def report = buildReport(runStartedAt, checked, batchSize, truncated, allDiffs,
                                 refreshed, refreshedProductIds.size(), errors,
                                 errors > 0 ? "error" : "success", null)
        writeReport(report)

        // Truncation-safe: if the changed set exceeded the page cap, resume next run from the
        // last fetched product's updatedAt (do NOT skip the un-fetched newer changes). Else
        // advance to the run start time.
        state.diffSince = (truncated && lastUpdatedAt) ? lastUpdatedAt : runStartedAt
        state.lastRunAt = report.finishedAt
        def stateRes = Jcr.getOrCreateFile(repositorySession, STATE_PATH)
        stateRes.write(Jcr.toJson(state))
        repositorySession.commit()

        log.info("reconcile: checked ${checked}, drift on ${report.productsWithDrift} product(s), ${allDiffs.size()} diff(s), " +
                 "refreshed ${refreshedProductIds.size()} product(s) / ${refreshed} field(s), ${errors} error(s)")
    } catch (Exception e) {
        try { log.warn("reconcile: ${e.message}") } catch (Exception ignore) {}
        // Record the failed run in the history (best-effort). The watermark is deliberately
        // NOT advanced, so the failed window is retried next run. Counters reflect the work
        // that really happened before the failure (applyRefresh commits patch by patch).
        if (runStartedAt != null) {
            try {
                try { repositorySession.rollback() } catch (Exception ignore) {}
                writeReport(buildReport(runStartedAt, checked, batchSize, truncated, allDiffs,
                                        refreshed, refreshedProductIds.size(), errors + 1,
                                        "error", e.message?.toString()))
                repositorySession.commit()
            } catch (Exception ignore) {}
        }
    }
} finally {
    __clusterLease.close()
}


// --- Helpers -----------------------------------------------------------------

// The ONE schema definition for the run-history report — success, no-change and failed runs
// all build through here, so the shape cannot drift between paths.
Map buildReport(String startedAt, int checked, int batchSize, boolean truncated, List allDiffs,
                int refreshed, int refreshedProducts, int errors, String result, String error) {
    def now = Api.now()
    def report = [
        generatedAt       : now,
        scope             : "diff",
        startedAt         : startedAt,
        finishedAt        : now,
        checked           : checked,
        batchSize         : batchSize,
        truncated         : truncated,
        productsWithDrift : allDiffs.collect { it.productId }.unique().size(),
        totalDiffs        : allDiffs.size(),
        refreshed         : refreshed,
        refreshedProducts : refreshedProducts,
        errors            : errors,
        result            : result,
    ]
    if (error != null) report.error = error
    report.diffs = allDiffs
    return report
}

// Persist one run-history report. The shared writer (Reconciliation.writeRunReport) owns
// the storage layout and the typed queryable properties, so the diff batch and the
// inventory audit can never drift apart; it does not commit — this run's commit covers
// the report together with the watermark.
def writeReport(Map report) {
    Reconciliation.writeRunReport(repositorySession, report)
}

// Fetch one product's current Shopify state (status / variant prices — reconcile's diff scope
// deliberately fetches NO inventory, so its GraphQL cost stays minimal). Returns [product, cost]
// where cost is the response's extensions.cost (used to pace the next call); product is null
// when not found.
def fetchShopifyProduct(httpClient, endpoint, token, productId) {
    def query = """
query {
  product(id: "${Api.gid('Product', productId)}") {
    id
    status
    variants(first: 100) {
      edges { node { id price } }
    }
  }
}
""".trim()
    def resp = Health.timeApi(repositorySession, log, "reconcile:product") {
        ShopifyAdmin.graphql(httpClient, endpoint, token, [query: query])
    }
    def p = resp?.data?.product
    // Shopify status is upper-case (ACTIVE/DRAFT/ARCHIVED); the mirror is lower-case.
    if (p != null && p.status != null) p.status = p.status.toString().toLowerCase()
    return [product: p, cost: resp?.extensions?.cost]
}

// Products Shopify reports as updated since `sinceIso`, paginated and sorted oldest-first.
// Returns [ids, truncated, lastUpdatedAt]: `truncated` = more pages existed past maxPages;
// `lastUpdatedAt` = the updatedAt of the last fetched product (the resume point when truncated).
Map fetchChangedProducts(httpClient, endpoint, token, String sinceIso, int pageSize, int maxPages) {
    def ids = []
    String after = null
    int pages = 0
    String lastUpdatedAt = null
    while (pages < maxPages) {
        def afterArg = (after == null) ? "" : ", after: \"${after}\""
        def query = """
query {
  products(first: ${pageSize}${afterArg}, query: "updated_at:>'${sinceIso}'", sortKey: UPDATED_AT) {
    edges { node { id updatedAt } }
    pageInfo { hasNextPage endCursor }
  }
}
""".trim()
        def resp = Health.timeApi(repositorySession, log, "reconcile:diff") {
            ShopifyAdmin.graphql(httpClient, endpoint, token, [query: query])
        }
        def conn = resp?.data?.products
        def edges = conn?.edges
        if (edges instanceof List) {
            edges.each { e ->
                def nid = Reconciliation.numericId(e?.node?.id)
                if (nid) ids << nid
                def ua = e?.node?.updatedAt
                if (ua != null) lastUpdatedAt = ua.toString()
            }
        }
        pages++
        def pi = conn?.pageInfo
        if (pi?.hasNextPage && pi?.endCursor) { after = pi.endCursor.toString() } else { after = null; break }
    }
    return [ids: ids, truncated: (after != null), lastUpdatedAt: lastUpdatedAt]
}

// Pace Shopify calls on the previous response's throttleStatus: keep at least
// reserveBudgetPercent of the cost bucket free for foreground ops, plus an optional floor.
void throttleGate(cost, int reservePct, long minDelayMs) {
    if (minDelayMs > 0L) { try { Thread.sleep(minDelayMs) } catch (Exception ignore) {} }
    def ts = cost?.throttleStatus
    if (ts == null) return
    Double avail = toDouble(ts.currentlyAvailable)
    Double maxAvail = toDouble(ts.maximumAvailable)
    Double restore = toDouble(ts.restoreRate)
    if (avail == null || maxAvail == null || restore == null || restore <= 0) return
    int pct = Math.max(0, Math.min(100, reservePct))
    double reserve = maxAvail * (pct / 100.0d)
    if (avail < reserve) {
        long waitMs = (long) (((reserve - avail) / restore) * 1000.0d)
        waitMs = Math.min(Math.max(waitMs, 0L), 30_000L)   // cap a single wait at 30s
        if (waitMs > 0L) { try { Thread.sleep(waitMs) } catch (Exception ignore) {} }
    }
}

Double toDouble(v) {
    if (v == null) return null
    if (v instanceof Number) return ((Number) v).doubleValue()
    try { return Double.parseDouble(v.toString()) } catch (Exception e) { return null }
}

def readYaml(String path) {
    try {
        def res = repositorySession.getResource(path)
        if (res != null && res.exists()) return YAML.parse(res)
    } catch (Exception e) {
        log.warn("reconcile: could not read ${path}: ${e.message}")
    }
    return null
}

int intOr(v, int dflt) {
    if (v == null) return dflt
    try { return v.toString().trim() as int } catch (Exception e) { return dflt }
}
