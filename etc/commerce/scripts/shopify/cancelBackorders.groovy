// Cancel an order's open backorders when a refund arrives (feature #12).
//
// Invoked from the refunds/create Camel route (refund-created.xml) as the service
// user. A refund means the order is being unwound, so any backorders for it that
// are still merely waiting on stock (status "backordered") should not linger; we
// cancel them. Backorders that have already reached "ready" are left untouched —
// an operator is actively releasing them through a task, and cancelling out from
// under that workflow would leave dangling engine state.
//
// Input (mapped from the route header via ?inputs=order_id):
//   - order_id : Shopify order ID the refund belongs to
//
// Fully DEFENSIVE: a failure must never break the refund route.

import commerce.Backorders

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }

try {
    def orderId = hv("order_id")?.toString()
    if (!orderId) {
        return
    }
    Backorders.cancelOpenForOrder(repositorySession, log, orderId, "refunded")
} catch (Exception e) {
    try { log.warn("cancelBackorders: ${e.message}") } catch (Exception ignore) {}
}
