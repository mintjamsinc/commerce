package commerce

import com.fasterxml.jackson.databind.ObjectMapper
import javax.jcr.query.Query

/**
 * Shopify → CMS consistency reconciliation.
 *
 * Detects where the CMS mirror has drifted from Shopify's current truth — product
 * status and variant price — and refreshes it. Drift normally means a missed/failed
 * webhook (now also mitigated by ingest replay) or a CMS-authoritative value that
 * was never pushed. Inventory is NOT reconciled here — the full inventory audit is
 * the Bulk job broker (`inventory` scope).
 *
 * Shopify is the single source of truth for every reconciled field, so the only refresh
 * direction is Shopify→CMS: reconciliation detects + reports drift and refreshes
 * the CMS follower mirror from Shopify. There is no per-field source-of-truth and no
 * CMS→Shopify push. {@link #diffProduct} is pure (testable); {@link #applyRefresh}
 * (Shopify→CMS mirror patch for status / price) is defensive.
 *
 * RUN HISTORY: every reconciliation pass — the diff batch (scope "diff") AND the full
 * inventory audit (scope "inventory", recorded off the Bulk broker's terminal
 * transitions) — persists one run report under {@link #RECON_DIR}/{yyyy}/{MM}/ through
 * the single writer {@link #writeRunReport}, which stamps the typed queryable
 * properties the index-backed lister {@link #listRuns} needs. Lives under
 * /content/WEB-INF/classes; use via {@code import commerce.Reconciliation}.
 */
class Reconciliation {

    static final String RECON_DIR = "/content/commerce/reconciliation"

    /** Run-report scopes: the status/price diff batch vs the full inventory audit. */
    static final String SCOPE_DIFF = "diff"
    static final String SCOPE_INVENTORY = "inventory"

    /** The bulk job type the reconcile side enqueues for a full inventory audit. */
    static final String INVENTORY_FULL_JOB_TYPE = "inventory-full"

    /**
     * Bulk job types that ARE a full inventory audit. Both run the identical
     * streaming inventory reconcile (reconcileBulkResult), so both count as an
     * "inventory" run in the history: the scheduled audit (inventory-full) and the
     * operator's full import (inventory-backfill).
     */
    static final Set INVENTORY_AUDIT_TYPES = [INVENTORY_FULL_JOB_TYPE, "inventory-backfill"] as Set

    private static final ObjectMapper MAPPER = new ObjectMapper()

    /**
     * Compute the field-level diffs (status / variant price) between a CMS product
     * (parsed product JSON) and the Shopify product fetched from the Admin API.
     * PURE. Shopify is the source of truth, so the refresh direction is always
     * Shopify→CMS. Inventory is out of scope — the Bulk `inventory` audit owns it.
     *
     * @return list of diffs, each:
     *   { field, variantId, cms, shopify, sourceOfTruth: "shopify", action }
     *   action = "refresh" (Shopify→CMS mirror patch for status / price)
     */
    static List diffProduct(Map cmsProduct, Map shopifyProduct) {
        def diffs = []

        // --- status ---
        def cmsStatus = lower(cmsProduct?.status)
        def shopStatus = lower(shopifyProduct?.status)
        if (cmsStatus && shopStatus && cmsStatus != shopStatus) {
            diffs << diff("status", null, cmsStatus, shopStatus, "refresh")
        }

        // --- per-variant price ---
        def shopByVariant = [:]
        def edges = shopifyProduct?.variants?.edges
        if (edges instanceof List) {
            edges.each { e ->
                def n = e?.node
                def vid = numericId(n?.id)
                if (vid) shopByVariant[vid] = n
            }
        }
        def cmsVariants = cmsProduct?.variants
        if (cmsVariants instanceof List) {
            cmsVariants.each { v ->
                def vid = v?.id?.toString()
                def sv = vid == null ? null : shopByVariant[vid]
                if (sv == null) return

                def cP = Money.toNumber(v?.price)
                def sP = Money.toNumber(sv?.price)
                if (cP != null && sP != null && cP.compareTo(sP) != 0) {
                    diffs << diff("price", vid, cP.toString(), sP.toString(), "refresh")
                }
            }
        }
        return diffs
    }

    /**
     * Refresh a Shopify→CMS diff by patching the stored product mirror (status / price).
     * Inventory is not refreshed here (per-location data cannot be derived from the
     * aggregate). Defensive — returns whether the mirror was changed.
     */
    static boolean applyRefresh(session, log, productResource, Map diff) {
        try {
            if (productResource == null || !productResource.exists()) return false
            def field = diff?.field
            def product = MAPPER.readValue(productResource.content.toString(), Map.class)
            boolean changed = false

            if (field == "status") {
                product.status = diff.shopify
                productResource.setProperty("commerce:source_status", diff.shopify?.toString())
                changed = true
            } else if (field == "price" && diff.variantId && product.variants instanceof List) {
                // diff rows carry the wire GID form — peel to the numeric mirror id
                // for the match (commerce.Api owns both directions).
                def wantId = Api.legacyId(diff.variantId)
                product.variants.each { v ->
                    if (v?.id?.toString() == wantId) {
                        v.price = diff.shopify
                        changed = true
                    }
                }
            } else {
                return false  // unknown field: not auto-refreshable here
            }

            if (changed) {
                productResource.write(MAPPER.writeValueAsString(product))
                // Lifecycle rule: a mirror patch records WHEN it happened.
                productResource.setProperty("commerce:refreshed_at", new java.util.Date())
                session.commit()
                try { log.info("Reconciliation.applyRefresh: ${field} mirror updated for ${productResource.getPath()}") } catch (Exception ignore) {}
            }
            return changed
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Reconciliation.applyRefresh: ${e.message}") } catch (Exception ignore) {}
            return false
        }
    }

    /**
     * Persist ONE run-history report under {@code RECON_DIR/{yyyy}/{MM}} — the single
     * writer for BOTH scopes, so the report schema and the typed queryable properties
     * can never drift apart between the diff batch and the inventory audit. The body
     * is the full report JSON; the row fields every reader needs ride as typed
     * properties so listing the history never parses the bodies (a diff report embeds
     * the full diffs array):
     *
     *   commerce:scope         (String)  "diff" | "inventory"
     *   commerce:started_at    (Date)    run start — the history sort/range axis
     *   commerce:finished_at   (Date)    run end
     *   commerce:updated_count (Long)    items the run updated (diff: refreshed
     *                                    products; inventory: re-mirrored items)
     *   commerce:result        (String)  "success" | "error"
     *   commerce:created_at    (Date)    report write time
     *
     * Does NOT commit — the caller owns the transaction (the diff batch commits the
     * report together with its watermark; the audit recorder commits itself).
     */
    static def writeRunReport(session, Map report) {
        def scope = (report.scope ?: SCOPE_DIFF).toString()
        long nowMs = System.currentTimeMillis()
        def ym = new java.text.SimpleDateFormat("yyyy/MM").format(new java.util.Date(nowMs))
        // Scope-distinct filenames keep the store human-navigable; readers key off
        // the commerce:scope property, never the name.
        def prefix = (scope == SCOPE_INVENTORY) ? "inventory" : "recon"
        def res = Jcr.getOrCreateFile(session, "${RECON_DIR}/${ym}/${prefix}_${nowMs}.json".toString())
        res.write(Jcr.toJson(report))
        res.setProperty("commerce:scope", scope)
        res.setProperty("commerce:result", (report.result ?: "success").toString())
        res.setProperty("commerce:started_at", Api.date(report.startedAt) ?: new java.util.Date(nowMs))
        res.setProperty("commerce:finished_at", Api.date(report.finishedAt) ?: new java.util.Date(nowMs))
        res.setProperty("commerce:updated_count", (Api.count(report.updated ?: report.refreshedProducts) ?: 0L))
        if (report.totalDiffs != null) res.setProperty("commerce:total_diffs", (Api.count(report.totalDiffs) ?: 0L))
        if (report.productsWithDrift != null) res.setProperty("commerce:products_with_drift", (Api.count(report.productsWithDrift) ?: 0L))
        if (report.refreshedProducts != null) res.setProperty("commerce:refreshed_products", (Api.count(report.refreshedProducts) ?: 0L))
        res.setProperty("commerce:created_at", new java.util.Date(nowMs))
        return res
    }

    /**
     * Record a terminal Bulk-broker job as an INVENTORY run-history report — the hook
     * the broker calls on every terminal transition (COMPLETED/FAILED/TIMED_OUT/
     * CANCELED). No-op for job types that are not a full inventory audit
     * ({@link #INVENTORY_AUDIT_TYPES}). Exactly-once per job comes for free from the
     * broker's absorbing terminal states (a guarded transition applies only once).
     * Commits itself; best-effort — a bookkeeping failure must never break a broker
     * transition.
     */
    static void recordBulkAudit(session, log, Map job) {
        try {
            if (job == null || !INVENTORY_AUDIT_TYPES.contains(job.type?.toString())) return
            def status = job.status?.toString()
            def result = (status == "COMPLETED") ? "success" : "error"
            def stats = (job.stats instanceof Map) ? job.stats : [:]
            def report = [
                generatedAt: Api.now(),
                scope      : SCOPE_INVENTORY,
                jobId      : job.jobId,
                jobType    : job.type,
                startedAt  : Api.instant(job.startedAt ?: job.enqueuedAt ?: job.finishedAt) ?: Api.now(),
                finishedAt : Api.instant(job.finishedAt) ?: Api.now(),
                checked    : (Api.count(stats.checked) ?: 0L),
                updated    : (Api.count(stats.updated) ?: 0L),
                result     : result,
            ]
            if (result == "error") report.error = (job.error ?: status?.toLowerCase())?.toString()
            writeRunReport(session, report)
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Reconciliation.recordBulkAudit: ${e.message}") } catch (Exception ignore) {}
        }
    }

    /**
     * Run-history rows, newest-started first, via ONE index-backed XPath query over the
     * typed report properties (no body parsing, no folder walk — an earlier migration
     * stamped the properties onto reports written before this lister existed).
     *
     * opts (all optional):
     *   - scope   : "diff" | "inventory" (absent = both scopes)
     *   - result  : "success" | "error" (absent = any)
     *   - fromIso : full xs:dateTime literal (with zone) — started_at lower bound
     *   - limit   : max rows (absent/0 = unlimited)
     *
     * @return rows of { path, scope, startedAt, finishedAt, updated, result }
     *   with wire-shaped timestamps (ms-precision ISO).
     */
    static List listRuns(session, Map opts) {
        def o = opts ?: [:]
        // Whitelist every filter value BEFORE it reaches the XPath string — never
        // interpolate free-form input (an unknown value returns nothing rather than
        // a broken/injectable query).
        def scope = o.scope?.toString()
        if (scope != null && !(scope in [SCOPE_DIFF, SCOPE_INVENTORY])) return []
        def result = o.result?.toString()
        if (result != null && !(result in ["success", "error"])) return []
        String fromIso = null
        if (o.fromIso != null) {
            fromIso = Api.instant(o.fromIso)   // normalizes to the wire ISO form
            if (fromIso == null) return []
        }

        // The scope predicate doubles as the "is a run report" filter: state.json /
        // schedule-state.json under RECON_DIR carry no commerce:scope and never match.
        def preds = []
        if (scope != null) {
            preds << "@commerce:scope = '${scope}'".toString()
        } else {
            preds << "(@commerce:scope = '${SCOPE_DIFF}' or @commerce:scope = '${SCOPE_INVENTORY}')".toString()
        }
        if (result != null) preds << "@commerce:result = '${result}'".toString()
        // Cast the LITERAL to xs:dateTime — the property is a real JCR Date, so the
        // string bound must be promoted for a date-typed comparison; and cast the Date
        // sort key too (a bare order-by picks the String comparator against the date
        // docvalues and throws) — same forms Reports.operations uses.
        if (fromIso != null) {
            preds << "@commerce:started_at >= xs:dateTime('${fromIso}')".toString()
        }
        def stmt = "/jcr:root${RECON_DIR}//element(*, nt:file)[${preds.join(' and ')}]" +
                   " order by xs:dateTime(@commerce:started_at) descending"
        def jq = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
        long limit = Api.count(o.limit) ?: 0L
        if (limit > 0) jq.limit(limit)

        def rows = []
        def resources = jq.execute().getResources()
        if (resources != null) {
            resources.each { res ->
                try {
                    rows << [
                        path      : res.getPath(),
                        scope     : propVal(res, "commerce:scope")?.toString(),
                        startedAt : Api.instant(propVal(res, "commerce:started_at")),
                        finishedAt: Api.instant(propVal(res, "commerce:finished_at")),
                        updated   : (Api.count(propVal(res, "commerce:updated_count")) ?: 0L),
                        result    : propVal(res, "commerce:result")?.toString() ?: "success",
                    ]
                } catch (Exception ignore) {}
            }
        }
        return rows
    }

    // --- Helpers ---------------------------------------------------------------

    // The raw typed property value (Calendar for Date props) — Api.instant
    // normalizes it to the wire timestamp format.
    private static Object propVal(res, String name) {
        try { if (res.hasProperty(name)) return res.getProperty(name).getValue() } catch (Exception ignore) {}
        return null
    }

    // Report rows ride to the operator UI — ids go out in the wire GID form
    // (commerce.Api), the numeric form stays internal.
    private static Map diff(String field, String variantId, String cms, String shopify, String action) {
        return [field: field, variantId: Api.gid("ProductVariant", variantId),
                cms: cms, shopify: shopify, sourceOfTruth: "shopify", action: action]
    }

    /**
     * Numeric id from a Shopify gid ("gid://shopify/ProductVariant/123" → "123").
     * Delegates to the one id normalizer ({@link Api#legacyId}) — storage keys
     * stay numeric; the wire re-GIDs them on the way out.
     */
    static String numericId(id) {
        return Api.legacyId(id)
    }

    private static String lower(v) { v == null ? null : v.toString().trim().toLowerCase() }
}
