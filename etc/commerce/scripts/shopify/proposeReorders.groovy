// Reorder proposal batch.
//
// Invoked periodically by the commerce-reorder timer route (as the service
// user). For each variant whose stock will not cover the lead time + target
// cover at the current (cached) sales velocity, it records a purchase-order
// proposal and starts the replenishment workflow (operator approval → supplier
// order). Cheap: reads the cached velocity, never re-scans order history.
//
// Dedup: a variant is skipped when a replenishment workflow is already running
// for it, or a non-rejected purchase order was proposed within the lead time
// (so a just-ordered item is not re-proposed before it can arrive).
//
// Best-effort: a failure is logged, never thrown.

import commerce.SalesVelocity
import commerce.Replenishment
import commerce.SimpleYaml
import commerce.Jcr
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

def PO_DIR = "/content/commerce/purchase-orders"

try {
    def cfgRes = repositorySession.getResource(Replenishment.CONFIG_PATH)
    if (cfgRes == null || !cfgRes.exists()) {
        return
    }
    def cfg = SimpleYaml.parse(cfgRes.content?.toString())
    if (cfg == null || cfg.enabled?.toString()?.toLowerCase() != "true") {
        return
    }

    int leadTimeDays = intOr(cfg.leadTimeDays, 7)
    def perDay = SalesVelocity.loadPerDay(repositorySession)
    def engine = ProcessAPI.getEngine()
    def runtime = engine.getRuntimeService()

    int proposed = 0
    SalesVelocity.variants(repositorySession, perDay).each { v ->
        try {
            int qty = Replenishment.suggest(v.perDay == null ? null : (v.perDay as double), v.quantity, cfg)
            if (qty <= 0) {
                return
            }
            def variantId = v.variantId
            def businessKey = "reorder:${variantId}".toString()

            // Skip if a replenishment workflow is already running for this variant.
            long active = runtime.createProcessInstanceQuery()
                .processDefinitionKey("replenishment-flow")
                .processInstanceBusinessKey(businessKey)
                .active().count()
            if (active > 0) {
                return
            }
            // Skip if a non-rejected PO was proposed within the lead time.
            if (recentlyProposed(repositorySession, PO_DIR, variantId, leadTimeDays)) {
                return
            }

            // Record the proposal (review_pending) and start the approval workflow.
            def id = "${System.currentTimeMillis()}_${variantId}".toString()
            def now = LocalDate.now(ZoneId.systemDefault())
            def path = "${PO_DIR}/${now.format(DateTimeFormatter.ofPattern('yyyy/MM'))}/po_${id}.json".toString()
            def record = [
                id          : id,
                status      : "review_pending",
                productId   : v.productId,
                productPath : v.productPath,
                variantId   : variantId,
                variantTitle: v.variantTitle,
                title       : v.title,
                currentStock: v.quantity,
                velocity    : v.perDay,
                suggestedQty: qty,
                createdAt   : java.time.Instant.now().toString(),
            ]
            def res = Jcr.getOrCreateFile(repositorySession, path)
            res.write(Jcr.toJson(record))
            res.setProperty("commerce:status", "review_pending")
            res.setProperty("reorder:variant_id", variantId ?: "")
            res.setProperty("reorder:product_id", v.productId ?: "")
            res.setProperty("reorder:suggested_qty", qty.toString())
            if (v.title) res.setProperty("reorder:title", v.title.toString())
            repositorySession.commit()

            ProcessAPI.createProcessStarter()
                .setProcessDefinitionKey("replenishment-flow")
                .setBusinessKey(businessKey)
                .setVariables([
                    reorderPath : path,
                    productPath : v.productPath,
                    productID   : v.productId,
                    variantId   : variantId,
                    variantTitle: v.variantTitle,
                    productTitle: v.title,
                    suggestedQty: qty,
                ])
                .start()
            proposed++
        } catch (Exception e) {
            try { repositorySession.rollback() } catch (Exception ignore) {}
            log.warn("proposeReorders: variant ${v?.variantId} failed: ${e.message}")
        }
    }

    if (proposed > 0) {
        log.info("proposeReorders: created ${proposed} reorder proposal(s)")
    }
} catch (Exception e) {
    try { log.warn("proposeReorders: ${e.message}") } catch (Exception ignore) {}
}

// True when a non-rejected purchase order for this variant was created within the
// last `days` days (scans the current and previous month folders).
boolean recentlyProposed(session, String poDir, String variantId, int days) {
    long cutoff = System.currentTimeMillis() - days * 86400000L
    def open = ["review_pending", "approved", "ordered"] as Set
    def ym = DateTimeFormatter.ofPattern("yyyy/MM")
    def today = LocalDate.now(ZoneId.systemDefault())
    for (int i = 0; i <= 1; i++) {
        def folder
        try { folder = session.getResource("${poDir}/${today.minusMonths(i).format(ym)}") } catch (Exception e) { folder = null }
        if (folder == null || !folder.exists()) {
            continue
        }
        def it = folder.list()
        while (it.hasNext()) {
            def child = it.next()
            try {
                if (!child.getName().endsWith(".json")) continue
                if (child.hasProperty("reorder:variant_id")
                        && child.getProperty("reorder:variant_id").getValue()?.toString() == variantId) {
                    def status = child.hasProperty("commerce:status") ? child.getProperty("commerce:status").getValue()?.toString() : null
                    if (open.contains(status) && child.getCreated().getTime() >= cutoff) {
                        return true
                    }
                }
            } catch (Exception ignore) {}
        }
    }
    return false
}

int intOr(v, int dflt) {
    if (v == null) return dflt
    try { return v.toString().trim() as int } catch (Exception e) { return dflt }
}
