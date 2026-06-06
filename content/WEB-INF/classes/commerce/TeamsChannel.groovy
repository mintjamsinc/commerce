package commerce

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Microsoft Teams incoming-webhook channel.
 *
 * Posts an Adaptive Card wrapped in the {@code message}/{@code attachments}
 * envelope expected by Teams "Workflows" incoming webhooks (the supported
 * replacement for the retired Office 365 connectors). The body is a single
 * wrapping TextBlock rendered in markdown (double-asterisk bold, "-" bullets),
 * which Teams renders best-effort.
 *
 * Config (notifications.yml > teams):
 *   enabled    : on unless explicitly false
 *   webhookUrl : the Teams workflow / connector webhook URL
 */
class TeamsChannel extends NotificationChannel {

    String type() { "teams" }

    void send(log, String source, Map cfg, NotificationMessage message) {
        def url = str(cfg, "webhookUrl")
        def text = message.render("**", "-", true)
        def card = [
            type        : "message",
            attachments : [[
                contentType: "application/vnd.microsoft.card.adaptive",
                content    : [
                    type     : "AdaptiveCard",
                    '$schema': "http://adaptivecards.io/schemas/adaptive-card.json",
                    version  : "1.4",
                    body     : [[
                        type: "TextBlock",
                        text: text,
                        wrap: true
                    ]]
                ]
            ]]
        ]
        def body = new ObjectMapper().writeValueAsString(card)
        postJson(log, source, "Teams", url, body)
    }
}
