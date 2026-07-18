package commerce

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Integration health monitor for the commerce pipeline.
 *
 * Records lightweight operational metrics to JCR and raises alerts (through the
 * pluggable {@link Notifications} channels) as events happen. Three signal
 * sources feed it:
 *   • webhook receipt  — counts of received / hmac_failure / unhandled / dispatch_error
 *                        (from the public webhook endpoint, via the health route)
 *   • route processing — success / error and processing latency
 *                        (received_at → completion, recorded by each Camel route)
 *   • Admin API calls  — success / error and call latency (recorded by callers
 *                        wrapping {@link #timeApi})
 *
 * Alerting is event-driven, not aggregate-driven: a single HMAC verification
 * failure alerts (debounced by the rule's cooldown), and every Admin API /
 * route processing error is notified individually with its error detail.
 *
 * Storage (best-effort, never blocks the caller):
 *   /content/commerce/health/metrics/{yyyy}/{MM}/{yyyy-MM-dd}.json  — daily counters
 *   /content/commerce/health/state.json                            — alert cooldowns
 * Daily buckets fold on the UTC day — the shared storage fold rule, so metric
 * files line up with the raw mirror folders and never depend on the server's
 * timezone.
 * Config: /etc/commerce/config/health.yml (alert rules); alerts reuse
 *   /etc/commerce/config/notifications.yml (the operations category's channels).
 *
 * Metrics are intentionally best-effort: a recording failure is swallowed and
 * logged, so monitoring can never break the business process it observes. Counter
 * updates use an optimistic read-modify-write with a short retry loop, which is
 * accurate at the modest volumes of a webhook integration.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.Health}.
 */
class Health {

    static final String HEALTH_DIR = "/content/commerce/health"
    static final String METRICS_DIR = HEALTH_DIR + "/metrics"
    static final String STATE_PATH = HEALTH_DIR + "/state.json"
    static final String CONFIG_PATH = "/etc/commerce/config/health.yml"

    private static final int MAX_RETRIES = 6

    // ===================================================================== API

    /**
     * Increment a simple counter, e.g. count(session, log, "webhook", "hmac_failure").
     * An HMAC verification failure alerts immediately (debounced by the rule's
     * cooldown); other counters only record.
     */
    static void count(session, log, String group, String metric, int by = 1) {
        try {
            update(session, log) { Map day ->
                def g = ((Map) day.computeIfAbsent(group, { [:] }))
                g[metric] = ((g[metric] ?: 0) as long) + by
            }
            if (group == "webhook" && metric == "hmac_failure") {
                alertHmacFailure(session, log)
            }
        } catch (Exception e) {
            warn(log, "count(${group}.${metric}) failed: ${e.message}")
        }
    }

    /**
     * Record the outcome of an operation under a named bucket, e.g.
     * outcome(session, log, "route", "orders/paid", true, 1234). Increments
     * {@code <name>.success}/{@code <name>.error} and, when {@code latencyMs} is
     * given, updates the latency aggregate (sum / count / max). An error outcome
     * is notified individually, carrying {@code error} as the detail.
     */
    static void outcome(session, log, String group, String name, boolean ok, Long latencyMs = null, String error = null) {
        try {
            update(session, log) { Map day ->
                def g = ((Map) day.computeIfAbsent(group, { [:] }))
                def b = ((Map) g.computeIfAbsent(name, { [:] }))
                def key = ok ? "success" : "error"
                b[key] = ((b[key] ?: 0) as long) + 1
                if (latencyMs != null) {
                    b.latency_sum = ((b.latency_sum ?: 0) as long) + latencyMs
                    b.latency_count = ((b.latency_count ?: 0) as long) + 1
                    if (latencyMs > ((b.latency_max ?: 0) as long)) {
                        b.latency_max = latencyMs
                    }
                }
            }
            if (!ok) {
                alertError(session, log, group, name, error)
            }
        } catch (Exception e) {
            warn(log, "outcome(${group}.${name}) failed: ${e.message}")
        }
    }

    /**
     * Time a (Admin API) call, record its outcome+latency under group "api", and
     * return the call's result. The original exception, if any, propagates so the
     * caller's error handling is unchanged; its message becomes the error detail
     * of the failure notification.
     *
     * Recording commits health nodes on {@code session}, so call this only at a
     * point where {@code session} has no uncommitted business changes (the API
     * callers commit before/after their calls, not around them). Webhook and route
     * metrics instead go through recordHealth.groovy, which owns its own session.
     */
    static Object timeApi(session, log, String label, Closure call) {
        long t0 = System.currentTimeMillis()
        try {
            def result = call.call()
            outcome(session, log, "api", label, true, System.currentTimeMillis() - t0)
            return result
        } catch (Throwable t) {
            outcome(session, log, "api", label, false, System.currentTimeMillis() - t0, t.message)
            throw t
        }
    }

    /**
     * Aggregate the last {@code days} daily docs (including today) into a snapshot:
     *   {
     *     "from": .., "to": .., "days": N,
     *     "webhook": { received, hmac_failure, unhandled, dispatch_error },
     *     "route": { "<name>": { success, error, error_rate, latency_avg, latency_max } },
     *     "api":   { "<name>": { ... same ... } }
     *   }
     */
    static Map snapshot(session, int days = 7) {
        def today = LocalDate.now(ZoneOffset.UTC)
        def webhook = [:]
        def route = [:]
        def api = [:]
        for (int i = 0; i < Math.max(days, 1); i++) {
            def date = today.minusDays(i)
            Map day = loadDay(session, date)
            if (day.isEmpty()) {
                continue
            }
            mergeCounters(webhook, day.webhook)
            mergeBuckets(route, day.route)
            mergeBuckets(api, day.api)
        }
        finalizeBuckets(route)
        finalizeBuckets(api)
        return [
            from   : today.minusDays(Math.max(days, 1) - 1).toString(),
            to     : today.toString(),
            days   : Math.max(days, 1),
            webhook: webhook,
            route  : route,
            api    : api,
        ]
    }

    // ============================================================== alerting

    /**
     * Alert on a single HMAC verification failure. One failure is enough to
     * notify; repeats are suppressed for the rule's cooldownMinutes.
     */
    private static void alertHmacFailure(session, log) {
        def rule = enabledRule(session, "hmacFailures")
        if (rule == null) {
            return
        }
        long cooldownMs = num(rule.cooldownMinutes, 30) * 60_000L
        Alerts.fire(session, log, STATE_PATH, "hmacFailures", cooldownMs,
            message("🔐", "HMAC verification failure",
                ["Action": "Check the webhook shared secret in shopify.yml and Shopify Admin."]))
    }

    /**
     * Notify an Admin API / route processing error individually, carrying the
     * error detail. Every error is reported — no cooldown, no aggregation.
     */
    private static void alertError(session, log, String group, String name, String error) {
        def detail = (error == null || error.trim().isEmpty()) ? "(no message)" : error
        if (group == "api" && enabledRule(session, "apiErrors") != null) {
            Alerts.send(session, log,
                message("📉", "Shopify Admin API error",
                    ["Call": name, "Error": detail]))
        }
        if (group == "route" && enabledRule(session, "routeErrors") != null) {
            Alerts.send(session, log,
                message("⚙", "Webhook processing error",
                    ["Topic": name, "Error": detail]))
        }
    }

    /**
     * The rule's config map when the master switch and the rule are both
     * enabled, else null (missing config or rule section means no alerting).
     */
    private static Map enabledRule(session, String key) {
        Map cfg
        try {
            cfg = loadConfig(session)
        } catch (Exception e) {
            return null
        }
        if (cfg == null || !truthy(cfg.enabled, true)) {
            return null
        }
        def rule = cfg[key]
        if (!(rule instanceof Map) || !truthy(((Map) rule).enabled, true)) {
            return null
        }
        return (Map) rule
    }

    private static NotificationMessage message(String icon, String headline, Map fields) {
        def m = NotificationMessage.create()
            .title("🩺", "Integration health")
            .status(icon, headline)
        fields.each { k, v -> m.field(k.toString(), v) }
        m.field("Detected at", Api.now())
        return m
    }

    // ============================================================== storage

    /** Read-modify-write today's metrics doc with an optimistic retry loop. */
    private static void update(session, log, Closure mutator) {
        def date = LocalDate.now(ZoneOffset.UTC)
        def path = dayPath(date)
        Exception lastError = null
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                def res = Jcr.getOrCreateFile(session, path)
                def content = res.exists() ? res.content?.toString() : null
                Map day = (content != null && !content.trim().isEmpty()) ? Jcr.parseMap(content) : [date: date.toString()]
                mutator.call(day)
                res.write(Jcr.toJson(day))
                session.commit()
                return
            } catch (Exception e) {
                lastError = e
                try { session.rollback() } catch (Exception ignore) {}
                try { Thread.sleep(20L * (attempt + 1)) } catch (Exception ignore) {}
            }
        }
        if (lastError != null) {
            warn(log, "metrics update gave up after ${MAX_RETRIES} attempts: ${lastError.message}")
        }
    }

    private static Map loadDay(session, LocalDate date) {
        return Jcr.readMap(session, dayPath(date))
    }

    private static Map loadConfig(session) {
        def res = Jcr.safeGet(session, CONFIG_PATH)
        if (res == null || !res.exists()) {
            return null
        }
        return SimpleYaml.parse(res.content?.toString())
    }

    private static String dayPath(LocalDate date) {
        def y = date.format(DateTimeFormatter.ofPattern("yyyy"))
        def m = date.format(DateTimeFormatter.ofPattern("MM"))
        return "${METRICS_DIR}/${y}/${m}/${date.toString()}.json"
    }

    // ============================================================== helpers

    private static void mergeCounters(Map acc, src) {
        if (!(src instanceof Map)) return
        src.each { k, v -> acc[k] = num(acc[k]) + num(v) }
    }

    private static void mergeBuckets(Map acc, src) {
        if (!(src instanceof Map)) return
        src.each { name, b ->
            if (!(b instanceof Map)) return
            def t = ((Map) acc.computeIfAbsent(name, { [:] }))
            t.success = num(t.success) + num(b.success)
            t.error = num(t.error) + num(b.error)
            t.latency_sum = num(t.latency_sum) + num(b.latency_sum)
            t.latency_count = num(t.latency_count) + num(b.latency_count)
            t.latency_max = Math.max(num(t.latency_max), num(b.latency_max))
        }
    }

    private static void finalizeBuckets(Map buckets) {
        buckets.each { name, b ->
            long total = num(b.success) + num(b.error)
            b.error_rate = total > 0 ? round((num(b.error) / (double) total)) : 0
            b.latency_avg = num(b.latency_count) > 0 ? Math.round(num(b.latency_sum) / (double) num(b.latency_count)) : 0
        }
    }

    private static long num(v, long dflt = 0) {
        if (v == null) return dflt
        if (v instanceof Number) return ((Number) v).longValue()
        try { return Long.parseLong(v.toString().trim()) } catch (Exception e) { return dflt }
    }

    private static boolean truthy(v, boolean dflt) {
        if (v == null) return dflt
        return v.toString().trim().toLowerCase() == "true"
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0d
    }

    private static void warn(log, String msg) {
        try { log?.warn("healthMonitor: ${msg}") } catch (Exception ignore) {}
    }
}
