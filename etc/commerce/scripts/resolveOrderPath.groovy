// Resolve the mirror node path for an order so an orders/updated refresh
// OVERWRITES the original node instead of creating a duplicate.
//
// The order store is the one store nested by the PAID month
// (/content/commerce/orders/raw/{yyyy}/{MM}/order_{id}.json). Deriving the path
// from the CURRENT month — as order-paid does at ingest time — would MISS an
// order paid in an earlier month and write a SECOND same-named node under the
// update month. So look up the existing node by name (commerce.Orders.findResource,
// the same lookup refund/fulfillment use) and reuse its path; fall back to the
// current-month path only for a brand-new order never captured by orders/paid.
//
// Inputs (?inputs=order_id): order_id  — the Shopify numeric order id.
// Output (?outputs=orderPath): the resource path to store / setProperties on.
//
// Defensive: a lookup failure falls back to the current-month path rather than
// breaking the mirror refresh.

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
    def ym = new java.text.SimpleDateFormat("yyyy/MM").format(new java.util.Date())
    path = "/content/commerce/orders/raw/${ym}/order_${id}.json".toString()
}
context.setAttribute("orderPath", path)
