// CMS ← Shopify reconciliation (diff scope: status / price). Category G (#24).
//
// Invoked on demand from the reconcile endpoint and by the `diff` schedule
// (scheduleReconcile.groovy → direct:commerce-reconcile). For the products Shopify reports as
// changed since the last diff watermark it fetches the current Shopify state (status / variant
// price / per-location inventory), compares it to the CMS mirror, writes a drift report,
// alerts on drift, and refreshes the CMS mirror FROM Shopify (status / price patched in place;
// the per-location inventory mirror re-mirrored for those changed products).
//
// A product's `updatedAt` does NOT change on inventory-only edits, so diff is a status/price
// mechanism. The FULL inventory audit is the Bulk job broker, scheduled via the `inventory`
// scope — not this script.
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
// Best-effort throughout: a failure is logged, never thrown.

import java.net.http.HttpClient
import commerce.ShopifyAdmin
import commerce.Reconciliation
import commerce.Locations
import commerce.InventoryAlert
import commerce.Health
import commerce.Jcr
import commerce.Alerts
import commerce.NotificationMessage

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

        int maxPerRun = intOr(cfg.maxPerRun, 50)
        boolean alert = !(cfg.alert?.toString()?.toLowerCase() == "false")
        boolean refreshInventoryMirror = !(cfg.refreshInventoryMirror?.toString()?.toLowerCase() == "false")
        // "Accelerator" — leave reserveBudgetPercent of the cost bucket for foreground ops, and
        // an optional fixed floor between per-product calls.
        int reservePct = intOr(cfg.reserveBudgetPercent, 50)
        long minDelayMs = (long) intOr(cfg.minDelayMsPerCall, 0)

        def endpoint = ShopifyAdmin.endpoint(adminApi)
        def token = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
        def httpClient = HttpClient.newHttpClient()

        // --- Pick the batch: products changed in Shopify since the diff watermark ----------
        def state = Jcr.readMap(repositorySession, STATE_PATH)
        def runStartedAt = java.time.Instant.now().toString()
        def since = state.diffSince?.toString() ?: java.time.Instant.now().minusSeconds(24L * 3600L).toString()
        def sinceWithMargin
        try { sinceWithMargin = java.time.Instant.parse(since).minusSeconds(300L).toString() }
        catch (Exception ignore) { sinceWithMargin = since }

        def changed = fetchChangedProducts(httpClient, endpoint, token, sinceWithMargin, maxPerRun, MAX_PAGES)
        def batch = changed.ids.collect { "product_${it}.json".toString() }
        boolean truncated = (changed.truncated == true)
        String lastUpdatedAt = changed.lastUpdatedAt
        log.info("reconcile: ${batch.size()} changed product(s) since ${sinceWithMargin}" +
                 (truncated ? " (TRUNCATED at ${maxPerRun * MAX_PAGES}; resuming from ${lastUpdatedAt} next run)" : ""))

        if (batch.isEmpty()) {
            // Nothing changed: advance the watermark so the window does not grow.
            state.diffSince = runStartedAt
            def emptyStateRes = Jcr.getOrCreateFile(repositorySession, STATE_PATH)
            emptyStateRes.write(Jcr.toJson(state))
            repositorySession.commit()
            return
        }

        def allDiffs = []
        int checked = 0
        int healed = 0
        int invRefreshed = 0
        def lastCost = null   // last Shopify cost extension, drives the throttle gate

        batch.each { name ->
            def productId = name.replace("product_", "").replace(".json", "")
            try {
                def res = repositorySession.getResource("${PRODUCTS_DIR}/${name}")
                if (res == null || !res.exists()) return
                def cmsProduct = JSON.parse(res.content.toString())

                // CMS inventory aggregate per variant inventory item.
                def cmsInvByItem = [:]
                if (cmsProduct?.variants instanceof List) {
                    cmsProduct.variants.each { v ->
                        def itemId = v?.inventory_item_id?.toString()
                        if (itemId && !cmsInvByItem.containsKey(itemId)) {
                            def levels = Locations.levels(repositorySession, itemId)
                            if (levels != null && !levels.isEmpty()) {
                                cmsInvByItem[itemId] = Locations.aggregate(repositorySession, itemId)
                            }
                        }
                    }
                }

                // Pace this call so the batch leaves API budget for foreground operations.
                throttleGate(lastCost, reservePct, minDelayMs)
                def fetched = fetchShopifyProduct(httpClient, endpoint, token, productId)
                lastCost = fetched?.cost
                def shopifyProduct = fetched?.product
                if (shopifyProduct == null) return
                checked++

                def diffs = Reconciliation.diffProduct(cmsProduct, cmsInvByItem, shopifyProduct)
                diffs.each { d ->
                    d.productId = productId
                    d.title = cmsProduct?.title?.toString()
                    // Shopify is the single source of truth: refresh the CMS mirror for every
                    // drifted field (status / price). Inventory is refreshed below.
                    if (d.field != "inventory") {
                        d.healed = Reconciliation.applyRefresh(repositorySession, log, res, d) ? "ok" : "skipped"
                        if (d.healed == "ok") healed++
                    }
                    allDiffs << d
                }

                // Refresh the per-location mirror for this changed product (compare-and-skip;
                // re-mirror changed items and mark them pending for the inventory-alert sweep).
                if (refreshInventoryMirror && cmsProduct?.variants instanceof List) {
                    def shopByVariant = [:]
                    def vedges = shopifyProduct?.variants?.edges
                    if (vedges instanceof List) {
                        vedges.each { e ->
                            def vid2 = Reconciliation.numericId(e?.node?.id)
                            if (vid2) shopByVariant[vid2] = e.node
                        }
                    }
                    cmsProduct.variants.each { v ->
                        def itemId = v?.inventory_item_id?.toString()
                        def vid2 = v?.id?.toString()
                        def sv = vid2 == null ? null : shopByVariant[vid2]
                        if (itemId == null || sv == null) return
                        def shopLevels = shopLevelsForVariant(sv)
                        if (shopLevels.isEmpty()) return  // untracked / no Shopify levels — leave the mirror as-is
                        def current = Locations.levels(repositorySession, itemId)
                        if (!Locations.sameLevels(current, shopLevels)) {
                            if (Locations.replaceLevels(repositorySession, log, itemId, shopLevels)) {
                                InventoryAlert.markPending(repositorySession, log, itemId)
                                invRefreshed++
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("reconcile: product ${productId} failed: ${e.message}")
            }
        }

        // --- Persist the report + diff watermark ----------------------------------
        def productsWithDrift = allDiffs.collect { it.productId }.unique().size()
        def now = java.time.Instant.now().toString()
        def report = [
            generatedAt       : now,
            scope             : "diff",
            checked           : checked,
            batchSize         : batch.size(),
            truncated         : truncated,
            productsWithDrift : productsWithDrift,
            totalDiffs        : allDiffs.size(),
            healed            : healed,
            inventoryRefreshed: invRefreshed,
            diffs             : allDiffs,
        ]
        def ym = new java.text.SimpleDateFormat("yyyy/MM").format(new Date())
        def reportPath = "${RECON_DIR}/${ym}/recon_${System.currentTimeMillis()}.json".toString()
        def reportRes = Jcr.getOrCreateFile(repositorySession, reportPath)
        reportRes.write(Jcr.toJson(report))
        reportRes.setProperty("commerce:total_diffs", allDiffs.size().toString())
        reportRes.setProperty("commerce:products_with_drift", productsWithDrift.toString())
        reportRes.setProperty("commerce:created_at", now)

        // Truncation-safe: if the changed set exceeded the page cap, resume next run from the
        // last fetched product's updatedAt (do NOT skip the un-fetched newer changes). Else
        // advance to the run start time.
        state.diffSince = (truncated && lastUpdatedAt) ? lastUpdatedAt : runStartedAt
        state.lastRunAt = now
        def stateRes = Jcr.getOrCreateFile(repositorySession, STATE_PATH)
        stateRes.write(Jcr.toJson(state))
        repositorySession.commit()

        log.info("reconcile: checked ${checked}, drift on ${productsWithDrift} product(s), ${allDiffs.size()} diff(s), healed ${healed}, inventory mirror refreshed ${invRefreshed}")

        // --- Alert on drift (debounced) -------------------------------------------
        if (alert && !allDiffs.isEmpty()) {
            def byField = allDiffs.groupBy { it.field }.collectEntries { k, v -> [(k): v.size()] }
            def message = NotificationMessage.create()
                .title("🔍", "Reconciliation")
                .status("⚠", "CMS ↔ Shopify drift detected")
                .field("Products checked", checked)
                .field("Products with drift", productsWithDrift)
                .field("Diffs", allDiffs.size())
                .field("By field", byField.collect { k, c -> "${k}: ${c}" }.join(", "))
                .field("Auto-healed", healed)
            // Debounce per run-window so a recurring batch does not spam.
            Alerts.fire(repositorySession, log, "${RECON_DIR}/alert-state.json", "drift", 30L * 60_000L, message)
        }
    } catch (Exception e) {
        try { log.warn("reconcile: ${e.message}") } catch (Exception ignore) {}
    }
} finally {
    __clusterLease.close()
}


// --- Helpers -----------------------------------------------------------------

// Fetch one product's current Shopify state. Returns [product, cost] where cost is the
// response's extensions.cost (used to pace the next call); product is null when not found.
def fetchShopifyProduct(httpClient, endpoint, token, productId) {
    def query = """
query {
  product(id: "gid://shopify/Product/${productId}") {
    id
    status
    variants(first: 100) {
      edges { node { id price inventoryQuantity inventoryItem {
        id
        inventoryLevels(first: 250) {
          edges { node { location { id } quantities(names: ["available"]) { name quantity } } }
        }
      } } }
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

// Per-location available for a Shopify variant node: numericLocationId -> available(int).
Map shopLevelsForVariant(sv) {
    def out = [:]
    def edges = sv?.inventoryItem?.inventoryLevels?.edges
    if (!(edges instanceof List)) return out
    edges.each { e ->
        def node = e?.node
        def locId = Reconciliation.numericId(node?.location?.id)
        if (locId == null) return
        def qs = node?.quantities
        def avail = null
        if (qs instanceof List) {
            def q = qs.find { it?.name?.toString() == "available" }
            avail = q?.quantity
        }
        if (avail != null) {
            try { out[locId] = (avail as int) } catch (Exception ignore) {}
        }
    }
    return out
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
