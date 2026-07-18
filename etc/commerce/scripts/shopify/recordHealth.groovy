// Health metric recorder.
//
// The single writer for integration health metrics. It always runs in its own
// script context/session (invoked either by the Camel business routes via
// `cms:/.../recordHealth.groovy` as the service user, or by the public webhook
// endpoint via IntegrationAPI -> direct:commerce-health -> this script), so it
// never shares a session with business processing.
//
// Inputs (script attributes, mapped from exchange headers; all optional):
//   health_group       : metric group  ("webhook" | "route" | "api")
//   health_metric      : counter name  (e.g. "received" | "hmac_failure" |
//                        "unhandled" | "dispatch_error")  -> increments a counter
//   health_name        : outcome bucket (e.g. a webhook topic) -> records success/error
//   health_ok          : "true"/"false" outcome flag (for health_name)
//   health_error       : error detail for a failed outcome (notified per error)
//   health_latency_ms  : explicit latency in ms (for health_name)
//   received_at        : ISO instant set by the webhook endpoint; when present and
//                        health_latency_ms is absent, latency = now - received_at
//
// Recording is best-effort: any failure is swallowed so monitoring never breaks
// the process it observes.

import commerce.Health
import java.time.Instant

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }

try {
    def group = hv("health_group")?.toString()
    if (!group) {
        return
    }

    def metric = hv("health_metric")?.toString()
    if (metric) {
        // Simple counter (e.g. webhook receipts / HMAC failures).
        Health.count(repositorySession, log, group, metric)
        return
    }

    def name = hv("health_name")?.toString()
    if (name) {
        def okRaw = hv("health_ok")
        boolean ok = okRaw == null ? true : okRaw.toString().trim().toLowerCase() == "true"

        Long latencyMs = null
        def latRaw = hv("health_latency_ms")
        if (latRaw != null) {
            try { latencyMs = latRaw.toString().trim() as Long } catch (Exception ignore) {}
        } else {
            def receivedAt = hv("received_at")?.toString()
            if (receivedAt) {
                try {
                    latencyMs = System.currentTimeMillis() - Instant.parse(receivedAt).toEpochMilli()
                    if (latencyMs < 0) latencyMs = 0L
                } catch (Exception ignore) {}
            }
        }

        def error = hv("health_error")?.toString()
        Health.outcome(repositorySession, log, group, name, ok, latencyMs, error)
    }
} catch (Exception e) {
    try { log.warn("recordHealth: ${e.message}") } catch (Exception ignore) {}
}
