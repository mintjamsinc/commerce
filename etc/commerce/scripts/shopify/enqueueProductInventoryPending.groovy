// Enqueue a product's inventory items for alert evaluation. Wired as an end-event
// execution listener on product-update-flow: once a product is onboarded and its
// thresholds are (re)configured, mark each variant's inventory item pending so the
// inventory-alert sweep evaluates current stock against the threshold. Without this the
// alert trigger is only inventory_levels/update, so a product that is already low when a
// threshold is first set would not alert until its next inventory change or a reconcile.
//
// Defensive: never breaks the workflow.
//
// Required process variable (mapped via the listener's `inputs` field):
//   productPath : repository path to the product resource

import commerce.InventoryAlert
import commerce.WorkflowStatus

def task = context.hasAttribute("task") ? context.getAttribute("task") : null
def execution = context.hasAttribute("execution") ? context.getAttribute("execution") : null

def productPath = WorkflowStatus.pathVariable(context, task, execution, "productPath")
if (!productPath) {
    log.warn("enqueueProductInventoryPending: 'productPath' is not available - skipping")
    return
}

try {
    def res = repositorySession.getResource(productPath.toString())
    if (res == null || !res.exists()) {
        return
    }
    def productJson = JSON.parse(res.content.toString())
    def variants = productJson?.variants
    if (!(variants instanceof List)) {
        return
    }
    int n = 0
    variants.each { v ->
        def itemId = v?.inventory_item_id?.toString()
        if (itemId) {
            InventoryAlert.markPending(repositorySession, log, itemId)
            n++
        }
    }
    log.info("enqueueProductInventoryPending: ${productPath} - enqueued ${n} item(s) for alert evaluation")
} catch (Exception e) {
    try { log.warn("enqueueProductInventoryPending: ${e.message}") } catch (Exception ignore) {}
}
