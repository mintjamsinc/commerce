package commerce

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * LINE channel via the Messaging API push endpoint.
 *
 * Posts a plain-text message (LINE does not render markdown, so bold markup is
 * dropped; emoji are kept) to {@code https://api.line.me/v2/bot/message/push}
 * using a channel access token. The recipient {@code to} is a LINE user, group
 * or room ID obtained by your bot.
 *
 * (LINE Notify, the old one-token webhook, reached end of service in 2025; the
 * Messaging API is its supported successor.)
 *
 * Config (notifications.yml > line):
 *   enabled     : on unless explicitly false
 *   accessToken : Messaging API channel access token (Bearer)
 *   to          : destination user/group/room ID
 *   endpoint    : optional override (defaults to the public push endpoint)
 */
class LineChannel extends NotificationChannel {

    private static final String DEFAULT_ENDPOINT = "https://api.line.me/v2/bot/message/push"

    // LINE rejects text messages longer than 5000 characters.
    private static final int MAX_TEXT = 5000

    String type() { "line" }

    void send(log, String source, Map cfg, NotificationMessage message) {
        def token = str(cfg, "accessToken")
        def to = str(cfg, "to")
        def endpoint = str(cfg, "endpoint") ?: DEFAULT_ENDPOINT

        if (token == null || token.startsWith("REPLACE")) {
            log.info("${source}: LINE access token not configured - skipping")
            return
        }
        if (to == null || to.startsWith("REPLACE")) {
            log.info("${source}: LINE recipient (to) not configured - skipping")
            return
        }

        def text = message.plainText()
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT - 1) + "…"
        }

        def payload = [to: to, messages: [[type: "text", text: text]]]
        def body = new ObjectMapper().writeValueAsString(payload)
        postJson(log, source, "LINE", endpoint, body, ["Authorization": "Bearer ${token}".toString()])
    }
}
