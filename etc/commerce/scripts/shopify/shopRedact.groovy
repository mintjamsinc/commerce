// GDPR shop/redact handler (mandatory compliance webhook).
//
// Sent by Shopify 48 hours after the app is uninstalled from a shop: erase the
// shop's data wholesale. Deletes every store under /content/commerce plus the
// public catalog projection (config under /etc/commerce is the operator's, not
// the shop's data, and is left in place).
//
// Errors propagate to the route's error handler so the event is marked failed
// and can be replayed. (The event log is itself under /content/commerce, so
// this event's own entry is erased with everything else — by design.)

import commerce.Gdpr
import commerce.Notifications
import commerce.NotificationMessage

def summary = Gdpr.shopRedact(repositorySession, log)

try {
    def configNode = repositorySession.getResource("/etc/commerce/config/notifications.yml")
    if (configNode != null && configNode.exists()) {
        def config = YAML.parse(configNode)
        def message = NotificationMessage.create()
            .title("🛡", "GDPR")
            .status("🗑", "shop/redact applied — all shop data erased")
            .field("Removed store roots", summary.removedRoots)
        Notifications.dispatch(log, "shopRedact", config, message)
    }
} catch (Exception e) {
    log.warn("shopRedact: notification failed: ${e.message}")
}
