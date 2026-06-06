// Advance the integration processing status (commerce:status) of a backorder as it
// moves through the release workflow (backorder-release-flow.bpmn). Feature #12.
//
// commerce:status is the processing lifecycle of our integration - the single axis
// an operator reads to answer "is this done, waiting on someone, or broken?". For a
// backorder the Camel route sets "backordered" at detection; from there this
// workflow advances it:
//
//   backordered -> ready      (stock arrived; "Release Backorder" task raised)
//   ready       -> released   (operator released it; terminal)
//
// See docs/commerce-status.md for the authoritative status list.
//
// Wiring (mirrors setOrderWorkflowStatus): this one script is attached at two
// points via org.mintjams.script.bpm.CmsDelegate and resolves the target status
// from the BPMN element it fired on:
//
//   - "create" task listener on "Release Backorder" (UserTask_releaseBackorder) -> ready
//   - "end" execution listener on the end event      (EndEvent_backorderRelease)  -> released
//
// Required process variable (mapped in via the listener's `inputs` field):
//   - backorderPath: repository path to the backorder record
//
// A status-update failure must never break the business process, so repository
// errors are logged and swallowed - the workflow continues regardless.

import commerce.WorkflowStatus

def task = context.hasAttribute("task") ? context.getAttribute("task") : null
def execution = context.hasAttribute("execution") ? context.getAttribute("execution") : null

def elementId = WorkflowStatus.elementId(task, execution)
if (elementId == null) {
    log.warn("setBackorderWorkflowStatus: neither 'task' nor 'execution' is available - cannot resolve status")
    return
}

def statusByElement = [
    "UserTask_releaseBackorder": "ready",
    "EndEvent_backorderRelease": "released",
]
def status = statusByElement[elementId]
if (!status) {
    log.warn("setBackorderWorkflowStatus: no status mapping for element '${elementId}' - leaving commerce:status unchanged")
    return
}

def backorderPath = WorkflowStatus.pathVariable(context, task, execution, "backorderPath")
if (!backorderPath) {
    log.warn("setBackorderWorkflowStatus: 'backorderPath' is not available - cannot update commerce:status to '${status}'")
    return
}

WorkflowStatus.write(repositorySession, log, "setBackorderWorkflowStatus", backorderPath.toString(), status, elementId)
