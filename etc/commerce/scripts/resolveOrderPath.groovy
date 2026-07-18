// Resolve the mirror node path for an order so an orders/updated refresh
// OVERWRITES the original node instead of creating a duplicate.
//
// The order store is nested by the order's created_at month in UTC
// (/content/commerce/orders/raw/{yyyy}/{MM}/order_{id}.json — the shared fold
// rule, see commerce.Api.utcYearMonth). Deriving the path from the update's
// arrival time would MISS an order created in an earlier month and write a
// SECOND same-named node under the update month. So look up the existing node
// by name (commerce.Orders.findResource, the same lookup refund/fulfillment
// use) and reuse its path; fall back to the created_at-month path only for a
// brand-new order never captured by orders/paid.
//
// Inputs (?inputs=order_id,order_created_at):
//   order_id         — the Shopify numeric order id.
//   order_created_at — the order's created_at (ISO, offset honoured) — optional,
//                      used only for the brand-new-order fallback fold.
// Output (?outputs=orderPath): the resource path to store / setProperties on.
//
// Defensive: a lookup failure falls back to the fold-rule path rather than
// breaking the mirror refresh.

import commerce.Api
import commerce.Orders

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }
def id = hv("order_id")?.toString()

String path = null
try {
    def res = id ? Orders.findResource(repositorySession, id) : null
    if (res != null && res.exists()) path = res.getPath()
} catch (Exception e) {
    try { log.warn("resolveOrderPath: lookup failed for order ${id}: ${e.message}") } catch (Exception ignore) {}
}
if (path == null) {
    def ym = Api.utcYearMonth(hv("order_created_at"))
    path = "${Orders.STORE_DIR}/${ym[0]}/${ym[1]}/order_${id}.json".toString()
}
context.setAttribute("orderPath", path)
