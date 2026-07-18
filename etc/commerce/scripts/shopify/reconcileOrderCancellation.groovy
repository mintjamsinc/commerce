// Reconcile the order processing workflow with a Shopify-side cancellation.
//
// Invoked from the orders/updated Camel route (order-updated.xml) AFTER the
// mirror refresh, and ONLY when the payload carries cancelled_at (the route
// gates the call, so the hot path never pays for this script). Shopify sets
// cancelled_at only on a full cancel (admin-side or via our own reject flow),
// and every cancel also emits orders/updated, so this is the single inbound
// hook for "the order no longer needs fulfilling".
//
// This is the ONE deliberate exception to that route's mirror-only rule: a
// cancellation makes the remaining workflow moot, so the mirror event is allowed
// to CLOSE the lifecycle (never to advance it). Policy:
//
//   - Open backorders for the order are cancelled (a cancel without a refund
//     would otherwise leave them waiting on stock forever; the refund path in
//     cancelBackorders.groovy only covers refund-accompanied cancels).
//   - An active order-review-flow instance whose open user task(s) are ALL
//     unassigned is terminated and the order is marked cancelled — nobody has
//     picked the work up, so there is nothing to hand back.
//   - If any open task IS assigned, the instance is left running: the operator
//     confirms and closes it through the task form (which shows the cancelled
//     badge and swaps the fulfill action for close-without-fulfilling). The
//     terminal status is then set by the flow's own end event.
//   - An active instance with NO open user task is between wait states (a
//     service task is executing); deleting mid-execution could interrupt an
//     outbound write, so it is skipped. A cancel emits several orders/updated
//     deliveries in practice (refund, restock, archive), so a later delivery —
//     or the operator via the form, backed by readFulfillmentDecision's
//     cancelled-order override — converges it.
//
// Idempotent, with a cheap steady-state: once the order's own processing status
// is terminal "cancelled" (set here on termination, or by the flow's reject /
// close end events) every later delivery returns after ONE repository read —
// cancelled orders keep emitting orders/updated (archive, notes) forever and
// must not pay engine queries each time.
//
// Inputs (?inputs=orderPath,order_id,order_cancelled_at):
//   - orderPath          : repository path of the order mirror node
//   - order_id           : the Shopify numeric order id (workflow business key)
//   - order_cancelled_at : the payload's cancelled_at (route-extracted; the
//                          gate already checked it, re-checked here defensively)
//
// Fully DEFENSIVE: a failure must never break the mirror route.

import commerce.Backorders
import commerce.WorkflowStatus

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }

try {
    if (!hv("order_cancelled_at")) {
        // Live order - nothing to reconcile (the route gate should not let this happen).
        return
    }
    def orderId = hv("order_id")?.toString()
    def orderPath = hv("orderPath")?.toString()
    if (!orderId) {
        return
    }

    // 0. Steady-state short-circuit: already converged to the terminal status.
    try {
        if (orderPath) {
            def r = repositorySession.getResource(orderPath)
            if (r != null && r.exists() && r.hasProperty("commerce:status")
                    && r.getProperty("commerce:status").getValue()?.toString() == "cancelled") {
                return
            }
        }
    } catch (Exception e) {
        log.warn("reconcileOrderCancellation: status pre-check failed for ${orderPath}: ${e.message} - continuing")
    }

    // 1. Unwind open backorders (idempotent: only still-"backordered" records
    //    are touched; "ready" ones stay with their running release task).
    try {
        Backorders.cancelOpenForOrder(repositorySession, log, orderId, "cancelled")
    } catch (Exception e) {
        log.warn("reconcileOrderCancellation: backorder cancel failed for order ${orderId}: ${e.message}")
    }

    // 2. Terminate the processing workflow when nobody has claimed its work.
    def engine = ProcessAPI.getEngine()
    def runtime = engine.getRuntimeService()
    def taskService = engine.getTaskService()

    def instances = runtime.createProcessInstanceQuery()
        .processDefinitionKey("order-review-flow")
        .processInstanceBusinessKey(orderId)
        .active().list()

    for (pi in instances) {
        def openTasks = taskService.createTaskQuery().processInstanceId(pi.getId()).list()
        if (openTasks == null || openTasks.isEmpty()) {
            // Between wait states (a service task is executing): deleting now
            // could interrupt an in-flight outbound write. A later delivery for
            // this cancelled order re-runs this reconcile.
            log.info("reconcileOrderCancellation: order ${orderId} instance ${pi.getId()} has no open task - skipping")
            continue
        }
        def assigned = openTasks.find { it.getAssignee() != null && !it.getAssignee().trim().isEmpty() }
        if (assigned != null) {
            // Claimed work is never pulled out from under an operator: the task
            // form shows the cancellation and offers close-without-fulfilling.
            log.info("reconcileOrderCancellation: order ${orderId} task '${assigned.getName()}' is assigned to ${assigned.getAssignee()} - leaving the workflow for a manual close")
            continue
        }
        runtime.deleteProcessInstance(pi.getId(), "Order cancelled in Shopify (orders/updated); no assignee to hand the task back to")
        log.info("reconcileOrderCancellation: order ${orderId} instance ${pi.getId()} terminated (all open tasks unassigned)")
        if (orderPath) {
            // Engine-level termination skips the flow's end-event listeners, so
            // the terminal status is written here (same closed-enum value the
            // reject branch uses).
            WorkflowStatus.write(repositorySession, log, "reconcileOrderCancellation", orderPath, "cancelled", null)
        }
    }
} catch (Exception e) {
    try { log.warn("reconcileOrderCancellation: ${e.message}") } catch (Exception ignore) {}
}
