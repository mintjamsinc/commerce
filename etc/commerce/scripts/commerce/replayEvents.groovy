// Automatic replay of failed events + event-log housekeeping (category A, #4).
//
// Invoked periodically by the commerce-replay timer route (as the service user).
// Re-dispatches events that ended in "error" — up to a bounded number of attempts,
// after a backoff — back through the ingest core, then prunes old processed events.
// Manual, on-demand replay goes through the same path from the events endpoint.
//
// Re-dispatch carries replay=true so the backend handlers reprocess the event
// instead of skipping it as a duplicate (their idempotency guard honours the flag).
//
// Best-effort throughout: a failure is logged, never thrown.

import commerce.Events
import commerce.SimpleYaml

// Cluster guard: the timer fires on every node of a cluster, so only the
// node that wins this lease runs the task; the others skip this tick.
// Manual triggers are asynchronous fire-and-forget, so skipping while a
// run is already in flight on another node is correct for them as well.
// In a standalone deployment the lease is always granted immediately.
def __clusterLease = cluster.tryLock("commerce-replay", 600000)
if (__clusterLease == null) {
    log.info("replayEvents: another cluster node is running this task - skipping")
    return
}
try {
    try {
        def cfg = readConfig()
        if (cfg == null) {
            return
        }
        // Master switch + replay sub-switch.
        if (cfg.enabled?.toString()?.toLowerCase() == "false") {
            return
        }
        def replay = cfg.replay ?: [:]
        boolean replayEnabled = !(replay.enabled?.toString()?.toLowerCase() == "false")

        int maxAttempts = intOr(replay.maxAttempts, 5)
        long backoffMs = longOr(replay.backoffMinutes, 15L) * 60_000L
        long retentionMs = longOr(replay.retentionDays, 30L) * 86_400_000L
        long now = System.currentTimeMillis()

        if (replayEnabled) {
            def due = Events.findReplayable(repositorySession, maxAttempts, backoffMs, now)
            int sent = 0
            due.each { ev ->
                try {
                    def payload = Events.payloadJson(repositorySession, ev.path)
                    if (payload == null) {
                        return
                    }
                    IntegrationAPI.createMessageSender()
                        .setEndpointURI("direct:commerce-ingest")
                        .setBody(payload)
                        .setHeader("event_source", ev.source)
                        .setHeader("event_topic", ev.topic)
                        .setHeader("event_id", ev.event_id)
                        .setHeader("received_at", ev.received_at)
                        .setHeader("replay", "true")
                        .sendAsync()
                    sent++
                } catch (Exception e) {
                    log.warn("replayEvents: could not re-dispatch ${ev.path}: ${e.message}")
                }
            }
            if (sent > 0) {
                log.info("replayEvents: re-dispatched ${sent} failed event(s)")
            }
        }

        // Housekeeping: drop processed events past the retention window.
        if (retentionMs > 0) {
            Events.prune(repositorySession, log, retentionMs, now)
        }
    } catch (Exception e) {
        try { log.warn("replayEvents: ${e.message}") } catch (Exception ignore) {}
    }
} finally {
    __clusterLease.close()
}


// --- Helpers -----------------------------------------------------------------

def readConfig() {
    try {
        def res = repositorySession.getResource("/etc/commerce/config/ingest.yml")
        if (res == null || !res.exists()) {
            return null
        }
        return SimpleYaml.parse(res.content?.toString())
    } catch (Exception e) {
        log.warn("replayEvents: could not read ingest.yml: ${e.message}")
        return null
    }
}

int intOr(v, int dflt) {
    if (v == null) return dflt
    try { return v.toString().trim() as int } catch (Exception e) { return dflt }
}

long longOr(v, long dflt) {
    if (v == null) return dflt
    try { return v.toString().trim() as long } catch (Exception e) { return dflt }
}
