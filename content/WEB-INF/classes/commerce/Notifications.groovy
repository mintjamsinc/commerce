package commerce

/**
 * Pluggable notification dispatch for the commerce workflows.
 *
 * A caller builds one channel-agnostic {@link NotificationMessage} describing the
 * event and hands it here together with the parsed notifications.yml. Dispatch
 * walks the channel registry and, for every section that is present and enabled,
 * lets the matching {@link NotificationChannel} render and deliver it.
 *
 * Channels supported out of the box (config key → adapter):
 *   slack → SlackChannel, discord → DiscordChannel, teams → TeamsChannel,
 *   line → LineChannel, webhook → WebhookChannel, email → EmailChannel.
 *
 * Adding a channel is additive: write a {@link NotificationChannel} subclass and
 * add it to {@link #registry()} — the dispatch signature and every caller stay
 * unchanged. This is the platform "completion form": callers say WHAT to send,
 * channels decide HOW.
 *
 * Delivery never breaks the surrounding business process: each channel is invoked
 * inside its own try/catch and failures are only logged.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.Notifications}.
 */
class Notifications {

    /**
     * The known channels, keyed by their config section. Returned fresh on each
     * call (the adapters are stateless), so callers never share mutable state.
     */
    static List<NotificationChannel> registry() {
        return [
            new SlackChannel(),
            new DiscordChannel(),
            new TeamsChannel(),
            new LineChannel(),
            new WebhookChannel(),
            new EmailChannel()
        ]
    }

    /**
     * A channel is enabled unless explicitly disabled (enabled: false). A missing
     * `enabled` flag means the section's presence implies it is on.
     */
    static boolean isEnabled(channel) {
        if (channel == null) {
            return false
        }
        def enabled = channel.enabled
        if (enabled == null) {
            return true
        }
        return enabled.toString().toLowerCase() == "true"
    }

    /**
     * Safely read a process variable off the task (a Camunda DelegateTask) as a
     * String, returning null if absent or on any error.
     */
    static String taskVar(task, String name) {
        try {
            def v = task?.getVariable(name)
            return v == null ? null : v.toString()
        } catch (Exception e) {
            return null
        }
    }

    /**
     * Deliver {@code message} to every enabled, configured channel in {@code config}
     * (the parsed notifications.yml). {@code source} is the calling script name,
     * used purely as a log prefix.
     */
    static void dispatch(log, String source, config, NotificationMessage message) {
        if (config == null || message == null) {
            return
        }
        registry().each { channel ->
            try {
                def section = config[channel.type()]
                if (section instanceof Map && isEnabled(section)) {
                    channel.send(log, source, (Map) section, message)
                }
            } catch (Exception e) {
                log.warn("${source}: ${channel.type()} channel error: ${e.message}")
            }
        }
    }
}
