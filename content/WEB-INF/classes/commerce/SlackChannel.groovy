package commerce

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Slack incoming-webhook channel.
 *
 * Renders the message in Slack mrkdwn (single-asterisk bold) and posts
 * {@code {"text": ...}} to the configured webhook URL.
 *
 * Config (notifications.yml > slack):
 *   enabled    : on unless explicitly false
 *   webhookUrl : https://hooks.slack.com/services/...
 */
class SlackChannel extends NotificationChannel {

    String type() { "slack" }

    void send(log, String source, Map cfg, NotificationMessage message) {
        def url = str(cfg, "webhookUrl")
        def text = message.render("*", "•", true)
        def body = new ObjectMapper().writeValueAsString([text: text])
        postJson(log, source, "Slack", url, body)
    }
}
