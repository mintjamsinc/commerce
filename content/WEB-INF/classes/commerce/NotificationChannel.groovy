package commerce

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Base class for a pluggable notification channel.
 *
 * Each channel knows its config key ({@link #type()}) and how to deliver a
 * {@link NotificationMessage} ({@link #send}). {@link Notifications#dispatch}
 * owns the registry: it iterates the known channels, hands each the matching
 * section from notifications.yml, and lets the channel render + deliver.
 *
 * Adding a channel is therefore purely additive — subclass this, implement two
 * methods, and add an instance to the registry in {@link Notifications}. Nothing
 * else (the dispatch signature, the four callers) changes.
 *
 * Delivery must never break the surrounding business process, so subclasses are
 * expected to be defensive and only log on error; the shared {@link #postJson}
 * helper already follows that rule.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.NotificationChannel}.
 */
abstract class NotificationChannel {

    /** The notifications.yml section key this channel reads (e.g. "slack"). */
    abstract String type()

    /**
     * Render {@code message} and deliver it using {@code channelConfig} (the
     * parsed section for {@link #type()}). Called only when the section exists and
     * is enabled; the channel still validates its own required fields and skips
     * (with a log line) when they are missing.
     */
    abstract void send(log, String source, Map channelConfig, NotificationMessage message)

    // --- Shared helpers --------------------------------------------------------

    /** Read a string setting, trimmed, or null when absent/blank. */
    protected static String str(Map cfg, String key) {
        def v = cfg?.get(key)
        if (v == null) {
            return null
        }
        def s = v.toString().trim()
        return s.isEmpty() ? null : s
    }

    /** True when {@code url} is present and not a placeholder template value. */
    protected static boolean usableUrl(String url) {
        return url != null && !url.startsWith("REPLACE")
    }

    /**
     * POST a JSON body and treat any 2xx as success. Defensive: never throws,
     * logs the outcome with the {@code source}/{@code label} prefix used across
     * the commerce tooling. {@code headers} are optional extra request headers.
     */
    protected static boolean postJson(log, String source, String label, String url,
                                      String body, Map<String, String> headers = [:]) {
        if (!usableUrl(url)) {
            log.info("${source}: ${label} endpoint not configured - skipping")
            return false
        }
        try {
            def client = HttpClient.newHttpClient()
            def builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
            headers?.each { k, v -> builder.header(k, v) }
            def request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
            def res = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                log.info("${source}: ${label} notification sent (status ${res.statusCode()})")
                return true
            }
            log.warn("${source}: ${label} notification failed: ${res.statusCode()} - ${res.body()}")
            return false
        } catch (Exception e) {
            log.warn("${source}: ${label} notification error: ${e.message}")
            return false
        }
    }
}
