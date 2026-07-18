// Read the operator's Order Review decision off the order resource, so the
// flow's gateway can route approve vs. reject (order cancellation).
//
// The review form writes commerce:review_decision (approved | rejected) and,
// for rejections, the required commerce:cancel_reason. An absent/unknown
// decision defaults to "approved" — the pre-rejection behaviour, and the safe
// reading for auto-approved orders that never showed the form.
//
// Requires exchange headers: orderPath
// Sets exchange headers:     reviewDecision ("approved" | "rejected")

import commerce.WorkflowStatus

if (!orderPath) {
    throw new IllegalArgumentException("Required header 'orderPath' is missing")
}

def decision = WorkflowStatus.readDecision(repositorySession, log, "readOrderReviewDecision",
    orderPath.toString(), "commerce:review_decision", "rejected", "approved")

context.setAttribute("reviewDecision", decision)
log.info("readOrderReviewDecision: ${orderPath} -> ${decision}")
