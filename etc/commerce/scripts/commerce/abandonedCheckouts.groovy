// Abandoned cart follow-up (category D, #14).
//
// Invoked by the commerce-crm-abandoned timer (as the service user). Finds checkouts
// that were never completed and have been idle past a threshold, and follows up:
//   - sends the customer a reminder email with the recovery link (OPT-IN, off by
//     default since it is outward-facing), staged up to maxReminders; and
//   - notifies operators with a (debounced) summary so the abandonment is visible
//     even when customer emails are disabled.
//
// Customer email reuses the notifications.yml SMTP transport (commerce.SmtpClient),
// addressed to the checkout's email. Best-effort throughout. Settings:
// /etc/commerce/config/crm.yml (abandonedCart).

import commerce.Checkouts
import commerce.Notifications
import commerce.NotificationMessage
import commerce.Alerts
import commerce.SmtpClient

try {
    def cfg = readYaml("/etc/commerce/config/crm.yml")
    if (cfg == null || cfg.enabled?.toString()?.toLowerCase() == "false") {
        return
    }
    def ac = (cfg.abandonedCart instanceof Map) ? cfg.abandonedCart : [:]
    if (ac.enabled?.toString()?.toLowerCase() == "false") {
        return
    }

    long now = System.currentTimeMillis()
    long abandonedAfterMs = longOr(ac.abandonedAfterMinutes, 60L) * 60_000L
    long intervalMs = longOr(ac.reminderIntervalMinutes, 1440L) * 60_000L
    int maxReminders = intOr(ac.maxReminders, 2)
    boolean sendToCustomer = ac.sendToCustomer?.toString()?.toLowerCase() == "true"

    def abandoned = Checkouts.findAbandoned(repositorySession, abandonedAfterMs, now)
    if (abandoned.isEmpty()) {
        return
    }

    int emailed = 0
    if (sendToCustomer) {
        def email = emailTransport()
        if (email == null) {
            log.info("abandonedCheckouts: sendToCustomer is on but notifications.yml email transport is not configured")
        } else {
            abandoned.each { co ->
                try {
                    boolean eligible = (co.remindersSent < maxReminders) &&
                        (co.remindersSent == 0 || (co.lastReminderMs > 0 && (now - co.lastReminderMs) >= intervalMs))
                    if (eligible && co.email) {
                        sendReminder(email, co)
                        Checkouts.markReminded(repositorySession, log, co.path, now)
                        emailed++
                    }
                } catch (Exception e) {
                    log.warn("abandonedCheckouts: reminder for ${co.path} failed: ${e.message}")
                }
            }
        }
    }

    log.info("abandonedCheckouts: ${abandoned.size()} abandoned cart(s), ${emailed} customer reminder(s) sent")

    // --- Operator summary (debounced) ----------------------------------------
    notifyOperators(abandoned, emailed, sendToCustomer)
} catch (Exception e) {
    try { log.warn("abandonedCheckouts: ${e.message}") } catch (Exception ignore) {}
}

// --- Helpers -----------------------------------------------------------------

void sendReminder(Map email, Map co) {
    def subject = (email.subjectPrefix ?: "") + "You left items in your cart"
    def body = new StringBuilder()
    body.append(co.name ? "Hi ${co.name},\n\n" : "Hi,\n\n")
    body.append("You left ${co.itemCount ?: 'some'} item(s) in your cart")
    if (co.total && co.currency) body.append(" (${co.total} ${co.currency})")
    body.append(".\n\n")
    if (co.recoveryUrl) body.append("Complete your purchase: ${co.recoveryUrl}\n\n")
    body.append("Thank you.")

    SmtpClient.send([
        host    : email.smtpHost,
        port    : (email.smtpPort ?: "587").toString(),
        security: (email.security ?: "starttls").toString(),
        username: email.username,
        password: email.password,
        from    : email.from,
        to      : co.email,
        subject : subject.trim(),
        body    : body.toString(),
    ])
    log.info("abandonedCheckouts: reminder emailed to ${co.email} (cart ${co.id})")
}

// The notifications.yml email block, only when minimally usable (host + from set).
Map emailTransport() {
    try {
        def node = repositorySession.getResource("/etc/commerce/config/notifications.yml")
        if (node == null || !node.exists()) return null
        def config = YAML.parse(node)
        def e = config?.email
        if (!(e instanceof Map)) return null
        def host = e.smtpHost?.toString()
        def from = e.from?.toString()
        if (host == null || host.startsWith("REPLACE") || from == null || from.startsWith("REPLACE")) return null
        return e
    } catch (Exception ex) {
        log.warn("abandonedCheckouts: could not read email transport: ${ex.message}")
        return null
    }
}

void notifyOperators(List abandoned, int emailed, boolean sendToCustomer) {
    try {
        def node = repositorySession.getResource("/etc/commerce/config/notifications.yml")
        if (node == null || !node.exists()) return
        def config = YAML.parse(node)

        def examples = abandoned.take(10).collect {
            def who = it.email ?: it.name ?: it.id
            def amt = (it.total && it.currency) ? " — ${it.total} ${it.currency}" : ""
            "${who}${amt}"
        }
        def message = NotificationMessage.create()
            .title("🛒", "Abandoned carts")
            .status("⏳", "Abandoned checkouts detected")
            .field("Abandoned", abandoned.size())
            .field("Customer reminders sent", sendToCustomer ? emailed : "disabled")
            .bullets("Carts", examples)

        // Debounce so a frequent timer does not spam operators.
        Alerts.fire(repositorySession, log, "/content/commerce/crm/abandoned-alert-state.json",
            "abandoned", 6L * 60L * 60_000L, message)
    } catch (Exception e) {
        log.warn("abandonedCheckouts: operator summary failed: ${e.message}")
    }
}

def readYaml(String path) {
    try {
        def res = repositorySession.getResource(path)
        if (res != null && res.exists()) return YAML.parse(res)
    } catch (Exception e) {
        log.warn("abandonedCheckouts: could not read ${path}: ${e.message}")
    }
    return null
}

int intOr(v, int dflt) { if (v == null) return dflt; try { return v.toString().trim() as int } catch (Exception e) { return dflt } }
long longOr(v, long dflt) { if (v == null) return dflt; try { return v.toString().trim() as long } catch (Exception e) { return dflt } }
