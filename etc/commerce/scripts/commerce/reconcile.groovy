// CMS ↔ Shopify reconciliation batch (category G, #24).
//
// Invoked by the commerce-reconcile timer (as the service user) and on demand from
// the reconcile endpoint. For a bounded, cursor-advanced batch of products it
// fetches the current Shopify state (status / variant price / variant inventory),
// compares it to the CMS mirror, writes a drift report, alerts on drift, and — only
// for fields whose auto-heal is enabled — heals (CMS→Shopify via ShopifyWrite, or
// Shopify→CMS by refreshing the mirror) per the configured source of truth.
//
// Detect + report + alert is the default; healing is opt-in (reconcile.yml).
// Best-effort throughout: a failure is logged, never thrown.

import java.net.http.HttpClient
import commerce.ShopifyAdmin
import commerce.ShopifyWrite
import commerce.Reconciliation
import commerce.Locations
import commerce.Health
import commerce.Jcr
import commerce.Alerts
import commerce.NotificationMessage

// Cluster guard: the timer fires on every node of a cluster, so only the
// node that wins this lease runs the task; the others skip this tick.
// Manual triggers are asynchronous fire-and-forget, so skipping while a
// run is already in flight on another node is correct for them as well.
// In a standalone deployment the lease is always granted immediately.
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
            log.info("reconcile: Admin API disabled - skipping")
            return
        }

        int maxPerRun = intOr(cfg.maxPerRun, 50)
        def sourceOfTruth = (cfg.sourceOfTruth instanceof Map) ? cfg.sourceOfTruth : [:]
        def autoHeal = (cfg.autoHeal instanceof Map) ? cfg.autoHeal : [:]
        boolean alert = !(cfg.alert?.toString()?.toLowerCase() == "false")

        // --- Pick the batch (round-robin by product file name) -------------------
        def names = productNames(repositorySession, PRODUCTS_DIR)
        if (names.isEmpty()) {
            return
        }
        def state = Jcr.readMap(repositorySession, STATE_PATH)
        def cursor = state.cursor?.toString()
        def batch = nextBatch(names, cursor, maxPerRun)
        if (batch.isEmpty()) {
            return
        }

        def endpoint = ShopifyAdmin.endpoint(adminApi)
        def token = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
        def httpClient = HttpClient.newHttpClient()

        def allDiffs = []
        int checked = 0
        int healed = 0

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

                def shopifyProduct = fetchShopifyProduct(httpClient, endpoint, token, productId)
                if (shopifyProduct == null) return
                checked++

                def diffs = Reconciliation.diffProduct(cmsProduct, cmsInvByItem, shopifyProduct, sourceOfTruth)
                diffs.each { d ->
                    d.productId = productId
                    d.title = cmsProduct?.title?.toString()
                    // Opt-in heal.
                    if (healEnabled(autoHeal, d.field)) {
                        d.healed = applyHeal(httpClient, endpoint, token, res, productId, d)
                        if (d.healed == "ok") healed++
                    }
                    allDiffs << d
                }
            } catch (Exception e) {
                log.warn("reconcile: product ${productId} failed: ${e.message}")
            }
        }

        // --- Persist the report ---------------------------------------------------
        def productsWithDrift = allDiffs.collect { it.productId }.unique().size()
        def now = java.time.Instant.now().toString()
        def report = [
            generatedAt      : now,
            checked          : checked,
            batchSize        : batch.size(),
            productsWithDrift: productsWithDrift,
            totalDiffs       : allDiffs.size(),
            healed           : healed,
            diffs            : allDiffs,
        ]
        def ym = new java.text.SimpleDateFormat("yyyy/MM").format(new Date())
        def reportPath = "${RECON_DIR}/${ym}/recon_${System.currentTimeMillis()}.json".toString()
        def reportRes = Jcr.getOrCreateFile(repositorySession, reportPath)
        reportRes.write(Jcr.toJson(report))
        reportRes.setProperty("commerce:total_diffs", allDiffs.size().toString())
        reportRes.setProperty("commerce:products_with_drift", productsWithDrift.toString())
        reportRes.setProperty("commerce:created_at", now)

        // Advance + persist the cursor.
        state.cursor = batch[batch.size() - 1]
        state.lastRunAt = now
        def stateRes = Jcr.getOrCreateFile(repositorySession, STATE_PATH)
        stateRes.write(Jcr.toJson(state))
        repositorySession.commit()

        log.info("reconcile: checked ${checked}, drift on ${productsWithDrift} product(s), ${allDiffs.size()} diff(s), healed ${healed}")

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

def fetchShopifyProduct(httpClient, endpoint, token, productId) {
    def query = """
query {
  product(id: "gid://shopify/Product/${productId}") {
    id
    status
    variants(first: 100) {
      edges { node { id price inventoryQuantity inventoryItem { id } } }
    }
  }
}
""".trim()
    def resp = Health.timeApi(repositorySession, log, "reconcile:product") {
        ShopifyAdmin.graphql(httpClient, endpoint, token, [query: query])
    }
    def p = resp?.data?.product
    if (p == null) return null
    // Shopify status is upper-case (ACTIVE/DRAFT/ARCHIVED); the mirror is lower-case.
    if (p.status != null) p.status = p.status.toString().toLowerCase()
    return p
}

// Apply an enabled heal for one diff. Returns "ok" / "skipped" / "failed:<reason>".
String applyHeal(httpClient, endpoint, token, productResource, String productId, Map d) {
    try {
        if (d.heal == "refresh") {
            return Reconciliation.applyRefresh(repositorySession, log, productResource, d) ? "ok" : "skipped"
        }
        if (d.heal == "push") {
            switch (d.field) {
                case "status":
                    // Mirror status active → publish, anything else → unpublish (draft).
                    ShopifyWrite.setPublished(httpClient, endpoint, token, productId, d.cms?.toString() == "active")
                    return "ok"
                case "price":
                    ShopifyWrite.updatePrice(httpClient, endpoint, token, productId, d.variantId, d.cms?.toString())
                    return "ok"
                default:
                    return "skipped"
            }
        }
        return "skipped"
    } catch (Exception e) {
        log.warn("reconcile: heal ${d.field} for ${productId} failed: ${e.message}")
        return "failed:${e.message}"
    }
}

boolean healEnabled(Map autoHeal, String field) {
    def v = autoHeal?.get(field)
    return v != null && v.toString().trim().toLowerCase() == "true"
}

List productNames(session, String dir) {
    def out = []
    try {
        def d = session.getResource(dir)
        if (d != null && d.exists()) {
            def it = d.list()
            while (it.hasNext()) {
                def c = it.next()
                def n = c.getName()
                if (n.startsWith("product_") && n.endsWith(".json")) out << n
            }
        }
    } catch (Exception e) {
        log.warn("reconcile: could not list products: ${e.message}")
    }
    out.sort()
    return out
}

// The next maxPerRun names after the cursor, wrapping around at the end.
List nextBatch(List names, String cursor, int maxPerRun) {
    if (names.isEmpty()) return []
    int start = 0
    if (cursor != null) {
        int idx = names.indexOf(cursor)
        start = (idx >= 0) ? (idx + 1) % names.size() : 0
    }
    def batch = []
    int n = Math.min(maxPerRun, names.size())
    for (int i = 0; i < n; i++) {
        batch << names[(start + i) % names.size()]
    }
    return batch
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
