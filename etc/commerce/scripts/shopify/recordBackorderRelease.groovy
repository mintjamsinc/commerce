// Record a backorder release at the end of backorder-release-flow.bpmn.
//
// The operator has confirmed the "Release Backorder" task, meaning the awaited
// stock is now allocated to this order line and it can proceed to fulfilment. This
// service task stamps the release time on the backorder record; the end-event
// listener (setBackorderWorkflowStatus) sets the terminal commerce:status =
// "released". No money is moved and nothing is written back to Shopify - releasing
// a backorder is an internal hand-off to normal fulfilment.
//
// Required process variable (mapped in via the service task's `inputs` field):
//   - backorderPath: repository path to the backorder record
//
// Defensive: a failure here is logged and swallowed so the workflow still completes.

import commerce.Backorders

if (!backorderPath) {
    log.warn("recordBackorderRelease: 'backorderPath' is missing - skipping")
    return
}

Backorders.markReleased(repositorySession, log, backorderPath.toString())
