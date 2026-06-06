// Advance the integration processing status (commerce:status) of a refund as it
// moves through the refund processing workflow (refund-review-flow.bpmn).
//
// Background
// ----------
// commerce:status is the *processing lifecycle* status of our integration - the
// single axis an operator reads to answer "is this done, waiting on someone, or
// broken?". For refunds the lifecycle is:
//
//   received -> review_pending   (a screening rule matched; waiting on a reviewer)
//   *        -> resolved         (workflow finished; terminal)
//
// A refund is already executed in Shopify, so "resolved" means our audit/triage
// of it is complete - not that money moved. See docs/commerce-status.md.
//
// Wiring
// ------
// Attached at two points via org.mintjams.script.bpm.CmsDelegate. The target
// status is resolved from the BPMN element it fired on (CmsDelegate only honours
// path/inputs/outputs/runAs fields, so the element id - not a custom field -
// carries the intent):
//
//   - "create" task listener on "Refund Review"   (UserTask_refundReview)        -> review_pending
//   - "end" execution listener on the end event   (EndEvent_refundProcessing)     -> resolved
//
// Required process variable (mapped in via the listener's `inputs` field):
//   - refundPath: repository path to the refund resource
//
// A status-update failure must never break the business process, so repository
// errors are logged and swallowed.

// --- Resolve invocation context (task listener vs. execution listener) -------
import commerce.WorkflowStatus

def task = context.hasAttribute("task") ? context.getAttribute("task") : null
def execution = context.hasAttribute("execution") ? context.getAttribute("execution") : null

// --- Resolve the BPMN element this listener fired on -------------------------
def elementId = WorkflowStatus.elementId(task, execution)
if (elementId == null) {
    log.warn("setRefundWorkflowStatus: neither 'task' nor 'execution' is available - cannot resolve status")
    return
}

// --- Map the element to the target processing status -------------------------
def statusByElement = [
    "UserTask_refundReview"      : "review_pending",
    "EndEvent_refundProcessing"  : "resolved",
]
def status = statusByElement[elementId]
if (!status) {
    log.warn("setRefundWorkflowStatus: no status mapping for element '${elementId}' - leaving commerce:status unchanged")
    return
}

// --- Resolve the refund path -------------------------------------------------
def refundPath = WorkflowStatus.pathVariable(context, task, execution, "refundPath")
if (!refundPath) {
    log.warn("setRefundWorkflowStatus: 'refundPath' is not available - cannot update commerce:status to '${status}'")
    return
}

// --- Write commerce:status (defensive; never breaks the workflow) ------------
WorkflowStatus.write(repositorySession, log, "setRefundWorkflowStatus", refundPath.toString(), status, elementId)
