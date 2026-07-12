// Customer delete marker (customers/delete → customer store).
//
// Marks the customer record deleted (commerce:status=deleted + deletedAt),
// keeping the node — parity with products/delete. This is a LIFECYCLE event,
// distinct from GDPR customers/redact (which anonymizes PII rather than just
// marking the record deleted).
//
// Input (mapped from the exchange): shopify_payload — the customers/delete JSON.

import commerce.Customers

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }
def raw = hv("shopify_payload")
if (!raw) {
    throw new IllegalArgumentException("deleteCustomer: no payload")
}
def payload = JSON.parse(raw.toString())
Customers.markDeleted(repositorySession, log, payload?.id)
log.info("deleteCustomer: customer ${payload?.id} marked deleted")
