// GDPR customers/data_request handler (mandatory compliance webhook).
//
// Collects every record held for the customer (orders, refunds, backorders,
// checkout mirrors, the customer node) into a single report under
// /content/commerce/gdpr/data-requests/ (admin-only) and notifies operators —
// the merchant is responsible for conveying the data to the customer.
//
// Errors propagate to the route's error handler so the event is marked failed
// and can be replayed.
//
// Input (mapped from the exchange): shopify_payload — the compliance webhook JSON.

import commerce.Gdpr
import commerce.Notifications
import commerce.NotificationMessage

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }
def raw = hv("shopify_payload")
if (!raw) {
    throw new IllegalArgumentException("customers/data_request: no payload")
}
def payload = JSON.parse(raw.toString())

def summary = Gdpr.dataRequest(repositorySession, log, payload)

// Operator notification is the point here: someone must act on the request.
try {
    def configNode = repositorySession.getResource("/etc/commerce/config/notifications.yml")
    if (configNode != null && configNode.exists()) {
        def config = YAML.parse(configNode)
        def message = NotificationMessage.create()
            .title("🛡", "GDPR")
            .status("📋", "customers/data_request — action required")
            .field("Customer", summary.customerId ?: "unknown")
            .field("Orders", summary.orders)
            .field("Refunds", summary.refunds)
            .field("Report", summary.path)
        Notifications.dispatch(log, "customerDataRequest", config, message, Notifications.CAT_COMPLIANCE)
    }
} catch (Exception e) {
    log.warn("customerDataRequest: notification failed: ${e.message}")
}
