package commerce

/**
 * Email channel over SMTP (see {@link SmtpClient}).
 *
 * Renders the message as plain text (no markup, emoji kept) and sends it as a
 * single UTF-8 text/plain part. The Subject is the configurable prefix plus the
 * message summary ("<title> — <status>"). Delivery is defensive: an SMTP failure
 * is logged, never thrown.
 *
 * Config (notifications.yml > email):
 *   enabled       : on unless explicitly false
 *   smtpHost      : SMTP server host
 *   smtpPort      : SMTP server port (587 starttls / 465 ssl / 25 none)
 *   security      : none | starttls | ssl
 *   username      : SMTP auth user (optional)
 *   password      : SMTP auth password (optional)
 *   from          : envelope/From address
 *   to            : comma-separated recipient list
 *   subjectPrefix : prepended to every subject (optional)
 */
class EmailChannel extends NotificationChannel {

    String type() { "email" }

    void send(log, String source, Map cfg, NotificationMessage message) {
        def host = str(cfg, "smtpHost")
        def from = str(cfg, "from")
        def to = str(cfg, "to")

        if (host == null || host.startsWith("REPLACE")) {
            log.info("${source}: SMTP host not configured - skipping email")
            return
        }
        if (from == null || from.startsWith("REPLACE")) {
            log.info("${source}: email from-address not configured - skipping email")
            return
        }
        if (to == null || to.startsWith("REPLACE")) {
            log.info("${source}: email recipients (to) not configured - skipping email")
            return
        }

        def prefix = str(cfg, "subjectPrefix") ?: ""
        def subject = (prefix + (message.summary() ?: "Commerce notification")).trim()

        try {
            SmtpClient.send([
                host    : host,
                port    : (str(cfg, "smtpPort") ?: "587"),
                security: (str(cfg, "security") ?: "starttls"),
                username: str(cfg, "username"),
                password: str(cfg, "password"),
                from    : from,
                to      : to,
                subject : subject,
                body    : message.plainText()
            ])
            log.info("${source}: Email notification sent to ${to}")
        } catch (Exception e) {
            log.warn("${source}: Email notification error: ${e.message}")
        }
    }
}
