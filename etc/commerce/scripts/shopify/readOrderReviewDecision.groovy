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

if (!orderPath) {
    throw new IllegalArgumentException("Required header 'orderPath' is missing")
}

def decision = "approved"
try {
    def resource = repositorySession.getResource(orderPath)
    if (resource != null && resource.exists() && resource.hasProperty("commerce:review_decision")) {
        def v = resource.getProperty("commerce:review_decision").getValue()?.toString()?.trim()?.toLowerCase()
        if (v == "rejected") decision = "rejected"
    }
} catch (Exception e) {
    log.warn("readOrderReviewDecision: ${orderPath}: ${e.message} - defaulting to approved")
}

context.setAttribute("reviewDecision", decision)
log.info("readOrderReviewDecision: ${orderPath} -> ${decision}")
