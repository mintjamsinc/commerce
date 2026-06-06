// Advance the integration processing status (commerce:status) of an order as it
// moves through the order processing workflow (order-review-flow.bpmn).
//
// Background
// ----------
// commerce:status is the *processing lifecycle* status of our integration - the
// single, consistent axis an operator (or the EIP console) reads to answer
// "is this done, waiting on someone, or broken?". It must not be confused with
// commerce:source_status, which mirrors Shopify's *business* status for the
// order (financial_status, e.g. paid / pending / refunded).
//
// The Camel route sets commerce:status = "received" on ingestion. From there the
// BPMN workflow advances it:
//
//   received -> review_pending       (a screening rule matched; waiting on a reviewer)
//   *        -> approved             (review cleared, auto or manual; queued to fulfil)
//   approved -> fulfillment_pending  (Fulfill Order task raised; waiting on a fulfiller)
//   *        -> fulfilled            (workflow finished; terminal)
//
// See docs/commerce-status.md for the authoritative status list.
//
// Wiring
// ------
// This one script is attached at FOUR points via org.mintjams.script.bpm.CmsDelegate
// and resolves the target status from the BPMN element it fired on:
//
//   - "create" task listener on "Order Review"   (UserTask_orderReview)        -> review_pending
//   - service task                                (ServiceTask_approveOrder)    -> approved
//   - "create" task listener on "Fulfill Order"  (UserTask_fulfillOrder)        -> fulfillment_pending
//   - "end" execution listener on the end event  (EndEvent_orderProcessing)     -> fulfilled
//
// CmsDelegate exposes the current DelegateTask as the "task" context attribute
// (task listener) or the DelegateExecution as "execution" (service task /
// execution listener). The element id is read from whichever is present:
// task.getTaskDefinitionKey() for user tasks, execution.getCurrentActivityId()
// otherwise. This avoids depending on a custom field (CmsDelegate only honours
// path/inputs/outputs/runAs) and keeps every status transition in one place.
//
// Required process variable (mapped in via the listener's `inputs` field):
//   - orderPath: repository path to the order resource
//
// A status-update failure must never break the business process, so repository
// errors are logged and swallowed - the workflow continues regardless.

// --- Resolve invocation context (task listener vs. service task / execution) -
import commerce.WorkflowStatus

def task = context.hasAttribute("task") ? context.getAttribute("task") : null
def execution = context.hasAttribute("execution") ? context.getAttribute("execution") : null

// --- Resolve the BPMN element this listener fired on -------------------------
def elementId = WorkflowStatus.elementId(task, execution)
if (elementId == null) {
    log.warn("setOrderWorkflowStatus: neither 'task' nor 'execution' is available - cannot resolve status")
    return
}

// --- Map the element to the target processing status -------------------------
def statusByElement = [
    "UserTask_orderReview"     : "review_pending",
    "ServiceTask_approveOrder" : "approved",
    "UserTask_fulfillOrder"    : "fulfillment_pending",
    "EndEvent_orderProcessing" : "fulfilled",
]
def status = statusByElement[elementId]
if (!status) {
    log.warn("setOrderWorkflowStatus: no status mapping for element '${elementId}' - leaving commerce:status unchanged")
    return
}

// --- Resolve the order path --------------------------------------------------
def orderPath = WorkflowStatus.pathVariable(context, task, execution, "orderPath")
if (!orderPath) {
    log.warn("setOrderWorkflowStatus: 'orderPath' is not available - cannot update commerce:status to '${status}'")
    return
}

// --- Write commerce:status (defensive; never breaks the workflow) ------------
WorkflowStatus.write(repositorySession, log, "setOrderWorkflowStatus", orderPath.toString(), status, elementId)
