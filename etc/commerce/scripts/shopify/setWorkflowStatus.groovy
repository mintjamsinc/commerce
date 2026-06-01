// Advance the integration processing status (commerce:status) of a product as it
// moves through the product-update workflow.
//
// Background
// ----------
// commerce:status is the *processing lifecycle* status of our integration - the
// single, consistent axis an operator (or the EIP console) reads to answer
// "is this done, waiting on someone, or broken?". It must not be confused with
// commerce:source_status, which mirrors Shopify's *business* status
// (active / archived / draft) of the product itself.
//
// The Camel route sets commerce:status = "received" on ingestion. From there the
// BPMN workflow advances it:
//
//   received -> threshold_pending  (waiting for an operator to set thresholds)
//   received -> review_pending     (a variant dropped below its threshold)
//   *        -> monitored          (workflow finished: thresholds in place,
//                                   stock OK or review completed)
//
// See docs/commerce-status.md for the authoritative status list.
//
// Wiring
// ------
// This one script is attached at three points via org.mintjams.script.bpm.CmsDelegate
// and resolves the target status from its invocation context:
//
//   - "create" task listener on "Set Inventory Threshold" -> threshold_pending
//   - "create" task listener on "Manual Inventory Check"   -> review_pending
//   - "end" execution listener on the end event            -> monitored
//
// CmsDelegate exposes the current DelegateTask as the "task" context attribute
// (task listener) or the DelegateExecution as "execution" (execution listener),
// so we detect which one is present rather than depending on a custom field.
//
// Required process variable (mapped in via the listener's `inputs` field):
//   - productPath: repository path to the product resource
//
// A status-update failure must never break the business process, so repository
// errors are logged and swallowed - the workflow continues regardless.

// --- Resolve invocation context (task listener vs. execution listener) -------
def task = context.hasAttribute("task") ? context.getAttribute("task") : null
def execution = context.hasAttribute("execution") ? context.getAttribute("execution") : null

// --- Determine the target processing status ----------------------------------
def status
if (task != null) {
    def key = task.getTaskDefinitionKey()
    switch (key) {
        case "UserTask_setThreshold":
            status = "threshold_pending"
            break
        case "UserTask_355fcd40":
            status = "review_pending"
            break
        default:
            log.warn("setWorkflowStatus: no status mapping for task '${key}' - leaving commerce:status unchanged")
            return
    }
} else if (execution != null) {
    // The only execution-listener wiring is the end event.
    status = "monitored"
} else {
    log.warn("setWorkflowStatus: neither 'task' nor 'execution' is available - cannot resolve status")
    return
}

// --- Resolve the product path ------------------------------------------------
// Prefer the `inputs`-mapped attribute; fall back to the variable scope so the
// script keeps working even if the inputs mapping is omitted.
def productPath = context.hasAttribute("productPath") ? context.getAttribute("productPath") : null
if (productPath == null && task != null) {
    productPath = task.getVariable("productPath")
}
if (productPath == null && execution != null) {
    productPath = execution.getVariable("productPath")
}
if (!productPath) {
    log.warn("setWorkflowStatus: 'productPath' is not available - cannot update commerce:status to '${status}'")
    return
}
productPath = productPath.toString()

// --- Write commerce:status ---------------------------------------------------
// NOTE: `resource` (bound by CmsDelegate) refers to THIS script, not the
// product, so we resolve the product resource explicitly.
try {
    def productResource = repositorySession.getResource(productPath)
    if (productResource == null || !productResource.exists()) {
        log.warn("setWorkflowStatus: product resource not found: ${productPath} - skipping status update")
        return
    }

    productResource.setProperty("commerce:status", status)
    repositorySession.commit()
    log.info("setWorkflowStatus: ${productPath} commerce:status -> ${status}")
} catch (Exception e) {
    try {
        repositorySession.rollback()
    } catch (Exception ignore) {
    }
    // Defensive: never let a status-update failure break the workflow.
    log.warn("setWorkflowStatus: failed to update commerce:status to '${status}' for ${productPath}: ${e.message}")
}
