// Read the fulfiller's decision off the order resource, so the flow's gateway
// can route "record the fulfillment" vs. "close without fulfilling".
//
// The Fulfill Order form writes commerce:fulfillment_decision:
//   - "fulfill" (or absent): normal completion — record tracking and write the
//     fulfillment back to Shopify (recordFulfillment.groovy).
//   - "close": the order was cancelled in Shopify while the task was claimed;
//     the assignee confirmed and closed it, so the Shopify fulfillment
//     write-back must be BYPASSED (fulfilling a cancelled order is rejected by
//     the Admin API and would be wrong anyway).
//
// An absent/unknown decision defaults to "fulfill" — the pre-cancellation
// behaviour, and the safe reading for every order completed before this
// property existed.
//
// The decision is additionally FORCED to "close" when the order carries
// commerce:cancelled_at: the form's own gating runs on a render-time snapshot,
// so a cancellation landing between render and completion could still submit a
// "fulfill". This server-side check is the authoritative gate.
//
// Requires exchange headers: orderPath
// Sets exchange headers:     fulfillmentDecision ("fulfill" | "close")

import commerce.WorkflowStatus

if (!orderPath) {
    throw new IllegalArgumentException("Required header 'orderPath' is missing")
}

def decision = WorkflowStatus.readDecision(repositorySession, log, "readFulfillmentDecision",
    orderPath.toString(), "commerce:fulfillment_decision", "close", "fulfill")

if (decision != "close") {
    try {
        def resource = repositorySession.getResource(orderPath)
        if (resource != null && resource.exists() && resource.hasProperty("commerce:cancelled_at")) {
            decision = "close"
            log.info("readFulfillmentDecision: ${orderPath} is cancelled in Shopify - forcing close")
        }
    } catch (Exception e) {
        log.warn("readFulfillmentDecision: cancelled_at check failed for ${orderPath}: ${e.message} - keeping '${decision}'")
    }
}

context.setAttribute("fulfillmentDecision", decision)
log.info("readFulfillmentDecision: ${orderPath} -> ${decision}")
