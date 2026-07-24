// Enqueue an order for a sales-fact recompute, from a source route (order-paid / order-updated /
// refund-created) right after the raw body + core props are persisted. The recompute itself is done
// asynchronously by the single cluster-guarded sales-fact sweep drainer, so bursts for the
// same order collapse into one evaluation.
//
// Defensive: never breaks the caller (a kick failure is backstopped by the drainer's timer).
//
// Input (script attribute, mapped from an exchange header):
//   order_id : the Shopify order numeric id (String)

import commerce.SalesFacts

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }
def orderId = hv("order_id")?.toString()
if (!orderId || orderId.trim().isEmpty()) {
    return
}
try {
    SalesFacts.markPending(repositorySession, log, orderId)

    // Kick the drain asynchronously so the fact recompute runs within milliseconds instead of waiting
    // for the next timer heartbeat. The drainer's task lock coalesces a burst of kicks into one
    // drain; a kick failure must never break the source route (the timer drain is the backstop).
    try {
        IntegrationAPI.createMessageSender()
            .setEndpointURI("direct:commerce-sales-materialize")
            .setBody("")
            .setHeader("runAs", "commerce-service-user")
            .sendAsync()
    } catch (Exception ke) {
        try { log.warn("markSalesPending: kick failed (timer drain will pick it up): ${ke.message}") } catch (Exception ignore) {}
    }
} catch (Exception e) {
    try { log.warn("markSalesPending: ${e.message}") } catch (Exception ignore) {}
}
