package commerce

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Generic outbound-webhook channel.
 *
 * POSTs a structured JSON payload so an arbitrary downstream system (an iPaaS,
 * a custom endpoint, an automation) can consume the event either as a flat
 * message or field-by-field. The payload always carries:
 *   {
 *     "source"  : <calling script name>,
 *     "title"   : <title text, no icons>,
 *     "status"  : <status headline, no icons>,
 *     "summary" : "<title> — <status>",
 *     "fields"  : [ { "label": .., "value": .. }, ... ],
 *     "<textField>": <full plain-text rendering>
 *   }
 * {@code textField} (default "text") lets you match a receiver that expects the
 * message under a specific key (e.g. "content", "message").
 *
 * Config (notifications.yml > webhook):
 *   enabled   : on unless explicitly false
 *   url       : the destination URL
 *   textField : JSON key for the plain-text body (default "text")
 */
class WebhookChannel extends NotificationChannel {

    String type() { "webhook" }

    void send(log, String source, Map cfg, NotificationMessage message) {
        def url = str(cfg, "url")
        def textField = str(cfg, "textField") ?: "text"

        def payload = [
            source : source,
            title  : message.titleText(),
            status : message.statusText(),
            summary: message.summary(),
            fields : message.fields()
        ]
        payload[textField] = message.plainText()

        def body = new ObjectMapper().writeValueAsString(payload)
        postJson(log, source, "Webhook", url, body)
    }
}
