// Customer upsert (customers/create|update|enable|disable → customer store).
//
// Stores the raw Shopify customer JSON as the node body (the product-mirror
// convention) and promotes profile/lifecycle fields to typed JCR properties —
// commerce:marketing_enabled (Boolean, email marketing subscription state),
// commerce:source_status (Shopify account state; enable/disable arrive as the
// same customer object), commerce:tax_exempt (Boolean), dates as Date.
//
// Errors propagate to the route's error handler so the event is marked failed
// and can be replayed.
//
// Input (mapped from the exchange): shopify_payload — the customers/* JSON.

import commerce.Customers

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }
def raw = hv("shopify_payload")
if (!raw) {
    throw new IllegalArgumentException("recordCustomer: no payload")
}
def customer = JSON.parse(raw.toString())
def key = Customers.upsertFromWebhook(repositorySession, log, raw.toString(), customer)
if (key != null) {
    log.info("recordCustomer: customer ${customer?.id} stored as ${key}")
}
