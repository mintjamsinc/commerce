// GDPR customers/redact handler (mandatory compliance webhook).
//
// Anonymizes everything held for the customer named in the payload — orders,
// refunds, backorders, checkout mirrors, event-log payloads — keeping the
// accounting facts and reducing the customer node to a shell. Idempotent: a
// duplicate webhook for an already-redacted customer is a no-op.
//
// Errors propagate to the route's error handler so the event is marked failed
// and can be replayed (a partially-applied redaction re-runs safely).
//
// Input (mapped from the exchange): shopify_payload — the compliance webhook JSON.

import commerce.Gdpr
import commerce.Notifications
import commerce.NotificationMessage

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }
def raw = hv("shopify_payload")
if (!raw) {
    throw new IllegalArgumentException("customers/redact: no payload")
}
def payload = JSON.parse(raw.toString())

def summary = Gdpr.redactCustomer(repositorySession, log, payload)

// Operator visibility (best-effort): compliance actions should be seen.
try {
    def configNode = repositorySession.getResource("/etc/commerce/config/notifications.yml")
    if (configNode != null && configNode.exists()) {
        def config = YAML.parse(configNode)
        def message = NotificationMessage.create()
            .title("🛡", "GDPR")
            .status(summary.alreadyRedacted ? "ℹ" : "✅",
                summary.alreadyRedacted ? "customers/redact (already redacted)" : "customers/redact applied")
            .field("Customer", summary.customerId ?: "unknown")
        if (!summary.alreadyRedacted) {
            message.field("Orders", summary.orders)
                .field("Refunds", summary.refunds)
                .field("Backorders", summary.backorders)
                .field("Checkouts", summary.checkouts)
                .field("Events", summary.events)
        }
        Notifications.dispatch(log, "customerRedact", config, message)
    }
} catch (Exception e) {
    log.warn("customerRedact: notification failed: ${e.message}")
}
