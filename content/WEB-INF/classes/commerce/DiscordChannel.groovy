package commerce

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Discord incoming-webhook channel.
 *
 * Renders the message in Discord markdown (double-asterisk bold) and posts
 * {@code {"content": ...}} to the configured webhook URL.
 *
 * Config (notifications.yml > discord):
 *   enabled    : on unless explicitly false
 *   webhookUrl : https://discord.com/api/webhooks/...
 */
class DiscordChannel extends NotificationChannel {

    String type() { "discord" }

    void send(log, String source, Map cfg, NotificationMessage message) {
        def url = str(cfg, "webhookUrl")
        def text = message.render("**", "•", true)
        def body = new ObjectMapper().writeValueAsString([content: text])
        postJson(log, source, "Discord", url, body)
    }
}
