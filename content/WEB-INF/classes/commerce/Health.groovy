package commerce

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Integration health monitor for the commerce pipeline.
 *
 * Records lightweight operational metrics to JCR and raises alerts (through the
 * pluggable {@link Notifications} channels) when a configured threshold is
 * breached. Three signal sources feed it:
 *   • webhook receipt  — counts of received / hmac_failure / unhandled / dispatch_error
 *                        (from the public webhook endpoint, via the health route)
 *   • route processing — success / error and processing latency
 *                        (received_at → completion, recorded by each Camel route)
 *   • Admin API calls  — success / error and call latency (recorded by callers
 *                        wrapping {@link #timeApi})
 *
 * Storage (best-effort, never blocks the caller):
 *   /content/commerce/health/metrics/{yyyy}/{MM}/{yyyy-MM-dd}.json  — daily counters
 *   /content/commerce/health/state.json                            — alert cooldowns
 * Config: /etc/commerce/config/health.yml (thresholds); alerts reuse
 *   /etc/commerce/config/notifications.yml (channels).
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
     * After recording, the relevant alert rule (if any) is evaluated.
     */
    static void count(session, log, String group, String metric, int by = 1) {
        try {
            update(session, log) { Map day ->
                def g = ((Map) day.computeIfAbsent(group, { [:] }))
                g[metric] = ((g[metric] ?: 0) as long) + by
            }
            evaluate(session, log, group, metric, null, null)
        } catch (Exception e) {
            warn(log, "count(${group}.${metric}) failed: ${e.message}")
        }
    }

    /**
     * Record the outcome of an operation under a named bucket, e.g.
     * outcome(session, log, "route", "orders/paid", true, 1234). Increments
     * {@code <name>.success}/{@code <name>.error} and, when {@code latencyMs} is
     * given, updates the latency aggregate (sum / count / max).
     */
    static void outcome(session, log, String group, String name, boolean ok, Long latencyMs = null) {
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
            evaluate(session, log, group, name, ok, latencyMs)
        } catch (Exception e) {
            warn(log, "outcome(${group}.${name}) failed: ${e.message}")
        }
    }

    /**
     * Time a (Admin API) call, record its outcome+latency under group "api", and
     * return the call's result. The original exception, if any, propagates so the
     * caller's error handling is unchanged.
     *
     * Recording commits health nodes on {@code session}, so call this only at a
     * point where {@code session} has no uncommitted business changes (the API
     * callers commit before/after their calls, not around them). Webhook and route
     * metrics instead go through recordHealth.groovy, which owns its own session.
     */
    static Object timeApi(session, log, String label, Closure call) {
        long t0 = System.currentTimeMillis()
        boolean ok = false
        try {
            def result = call.call()
            ok = true
            return result
        } finally {
            outcome(session, log, "api", label, ok, System.currentTimeMillis() - t0)
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
        def today = LocalDate.now(ZoneId.systemDefault())
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

    private static void evaluate(session, log, String group, String name, Boolean ok, Long latencyMs) {
        Map cfg
        try {
            cfg = loadConfig(session)
        } catch (Exception e) {
            return
        }
        if (cfg == null || !truthy(cfg.enabled, true)) {
            return
        }
        def today = loadDay(session, LocalDate.now(ZoneId.systemDefault()))

        if (group == "webhook" && name == "hmac_failure") {
            def rule = cfg.hmacFailures
            if (rule != null && truthy(rule.enabled, true)) {
                long count = num(((Map) (today.webhook ?: [:])).hmac_failure)
                long threshold = num(rule.threshold, 5)
                if (count >= threshold) {
                    maybeAlert(session, log, cfg, "hmacFailures",
                        message("🔐", "HMAC verification failures",
                            ["Failures today": count, "Threshold": threshold,
                             "Action": "Check the webhook shared secret in shopify.yml and Shopify Admin."]))
                }
            }
        }

        if (group == "api") {
            def rule = cfg.apiErrorRate
            if (rule != null && truthy(rule.enabled, true)) {
                def agg = sumGroup((Map) today.api)
                long total = agg.success + agg.error
                double rate = total > 0 ? (agg.error / (double) total) : 0
                long minSample = num(rule.minSample, 10)
                double threshold = dbl(rule.threshold, 0.2d)
                if (total >= minSample && rate >= threshold) {
                    maybeAlert(session, log, cfg, "apiErrorRate",
                        message("📉", "Shopify Admin API error rate high",
                            ["Errors": agg.error, "Calls": total,
                             "Error rate": pct(rate), "Threshold": pct(threshold)]))
                }
            }
        }

        if (group == "route") {
            def rateRule = cfg.routeErrorRate
            if (rateRule != null && truthy(rateRule.enabled, true)) {
                def agg = sumGroup((Map) today.route)
                long total = agg.success + agg.error
                double rate = total > 0 ? (agg.error / (double) total) : 0
                long minSample = num(rateRule.minSample, 10)
                double threshold = dbl(rateRule.threshold, 0.2d)
                if (total >= minSample && rate >= threshold) {
                    maybeAlert(session, log, cfg, "routeErrorRate",
                        message("⚙", "Webhook processing error rate high",
                            ["Errors": agg.error, "Processed": total,
                             "Error rate": pct(rate), "Threshold": pct(threshold)]))
                }
            }
            def latRule = cfg.processingLatency
            if (latRule != null && truthy(latRule.enabled, true) && latencyMs != null) {
                long maxMs = num(latRule.maxMs, 30000)
                if (latencyMs > maxMs) {
                    maybeAlert(session, log, cfg, "processingLatency:" + name,
                        message("🐢", "Slow webhook processing",
                            ["Topic": name, "Latency": latencyMs + " ms",
                             "Threshold": maxMs + " ms"]))
                }
            }
        }
    }

    /** Fire the alert unless the same key fired within the configured cooldown. */
    private static void maybeAlert(session, log, Map cfg, String key, NotificationMessage message) {
        long cooldownMs = num(cfg.cooldownMinutes, 30) * 60_000L
        Alerts.fire(session, log, STATE_PATH, key, cooldownMs, message)
    }

    private static NotificationMessage message(String icon, String headline, Map fields) {
        def m = NotificationMessage.create()
            .title("🩺", "Integration health")
            .status(icon, headline)
        fields.each { k, v -> m.field(k.toString(), v) }
        m.field("Detected at", Instant.now().toString())
        return m
    }

    // ============================================================== storage

    /** Read-modify-write today's metrics doc with an optimistic retry loop. */
    private static void update(session, log, Closure mutator) {
        def date = LocalDate.now(ZoneId.systemDefault())
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

    private static Map sumGroup(Map group) {
        long success = 0, error = 0
        group?.each { name, b ->
            if (b instanceof Map) {
                success += num(b.success)
                error += num(b.error)
            }
        }
        return [success: success, error: error]
    }

    private static long num(v, long dflt = 0) {
        if (v == null) return dflt
        if (v instanceof Number) return ((Number) v).longValue()
        try { return Long.parseLong(v.toString().trim()) } catch (Exception e) { return dflt }
    }

    private static double dbl(v, double dflt) {
        if (v == null) return dflt
        if (v instanceof Number) return ((Number) v).doubleValue()
        try { return Double.parseDouble(v.toString().trim()) } catch (Exception e) { return dflt }
    }

    private static boolean truthy(v, boolean dflt) {
        if (v == null) return dflt
        return v.toString().trim().toLowerCase() == "true"
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0d
    }

    private static String pct(double v) {
        return (Math.round(v * 1000) / 10.0d) + "%"
    }

    private static void warn(log, String msg) {
        try { log?.warn("healthMonitor: ${msg}") } catch (Exception ignore) {}
    }
}
