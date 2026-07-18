package commerce

/**
 * Pluggable notification dispatch for the commerce workflows.
 *
 * A caller builds one channel-agnostic {@link NotificationMessage} describing the
 * event and hands it here together with the parsed notifications.yml and the
 * notification CATEGORY the event belongs to. Dispatch first picks the channel
 * set for that category — the category's own set when one is defined under
 * `categories`, otherwise the `default` set — and then walks the channel
 * registry: every channel that is present and enabled in the chosen set renders
 * and delivers the message.
 *
 * This keeps the roles separated: callers say WHAT to send and which category it
 * is, the configuration says WHERE it goes (default vs a per-category channel
 * set), and each channel decides HOW to render it. A category either uses the
 * default set or its own complete set — the two are never merged, so what the
 * operator configures per category is exactly what is delivered.
 *
 * Channels supported out of the box (config key → adapter):
 *   slack → SlackChannel, discord → DiscordChannel, teams → TeamsChannel,
 *   line → LineChannel, webhook → WebhookChannel, email → EmailChannel.
 *
 * Adding a channel is additive: write a {@link NotificationChannel} subclass and
 * add it to {@link #registry()} — the dispatch signature and every caller stay
 * unchanged.
 *
 * Delivery never breaks the surrounding business process: each channel is invoked
 * inside its own try/catch and failures are only logged.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.Notifications}.
 */
class Notifications {

    // The fixed vocabulary of notification categories. Callers declare which
    // category their event belongs to; the configuration routes each category
    // to the default or a dedicated channel set.
    static final String CAT_INVENTORY = "inventory"
    static final String CAT_ORDERS = "orders"
    static final String CAT_REFUNDS = "refunds"
    static final String CAT_FULFILLMENT = "fulfillment"
    static final String CAT_BACKORDERS = "backorders"
    static final String CAT_COMPLIANCE = "compliance"
    static final String CAT_OPERATIONS = "operations"

    static final List<String> CATEGORIES = [
        CAT_INVENTORY, CAT_ORDERS, CAT_REFUNDS, CAT_FULFILLMENT,
        CAT_BACKORDERS, CAT_COMPLIANCE, CAT_OPERATIONS
    ].asImmutable()

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
     * Pick the channel set for {@code category} from the parsed notifications.yml:
     * the category's own set when one is defined under `categories`, otherwise the
     * `default` set. The sets are never merged — a category set is used exactly as
     * written. An unknown category falls back to the default set with a warning
     * (it signals a coding error, not an operator mistake — the category vocabulary
     * is fixed). Returns null when no usable set exists.
     */
    static Map channelSet(config, String category, log, String source) {
        if (!(config instanceof Map)) {
            return null
        }
        def cat = category
        if (cat != null && !CATEGORIES.contains(cat)) {
            try { log?.warn("${source}: unknown notification category '${cat}' - using default destinations") } catch (Exception ignore) {}
            cat = null
        }
        def categories = config["categories"]
        if (cat != null && categories instanceof Map) {
            def set = categories[cat]
            if (set instanceof Map) {
                return (Map) set
            }
        }
        def dflt = config["default"]
        return dflt instanceof Map ? (Map) dflt : null
    }

    /**
     * Deliver {@code message} to every enabled, configured channel in the channel
     * set resolved for {@code category} (see {@link #channelSet}). {@code config}
     * is the parsed notifications.yml; {@code source} is the calling script name,
     * used purely as a log prefix.
     */
    static void dispatch(log, String source, config, NotificationMessage message, String category) {
        if (config == null || message == null) {
            return
        }
        Map set = channelSet(config, category, log, source)
        if (set == null) {
            return
        }
        registry().each { channel ->
            try {
                def section = set[channel.type()]
                if (section instanceof Map && isEnabled(section)) {
                    channel.send(log, source, (Map) section, message)
                }
            } catch (Exception e) {
                log.warn("${source}: ${channel.type()} channel error: ${e.message}")
            }
        }
    }
}
