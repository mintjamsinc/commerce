// Event log + replay endpoint (admin). Category A (#1 visibility, #4 replay).
//
//   GET  — list the event log (filter by status/source/topic/since) + a status
//          summary. Read-only.
//   POST — manually replay events: a single event ({source,eventId}) or every
//          event matching a filter ({status,source,topic,sinceDays}; defaults to
//          status=error). Re-dispatches through the ingest core with replay=true.
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.
//
//   GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/events.groovy?status=error&limit=100
//   POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/events.groovy   {"status":"error"}

import commerce.Events
import com.fasterxml.jackson.databind.ObjectMapper

def mapper = new ObjectMapper()

try {
    if (request.getMethod() == "GET") {
        def statuses = splitParam(request.getParameter("status"))
        def source = blankToNull(request.getParameter("source"))
        def topic = blankToNull(request.getParameter("topic"))
        int limit = paramInt("limit", 100, 1, 1000)
        long sinceMs = sinceMs(request.getParameter("sinceDays"))

        def out = [
            generatedAt: java.time.Instant.now().toString(),
            summary    : Events.summary(repositorySession),
            events     : Events.list(repositorySession, statuses, source, topic, sinceMs, limit),
        ]
        response.setStatus(200)
        response.setHeader("Content-Type", "application/json")
        response.getWriter().write(mapper.writeValueAsString(out))
        return
    }

    if (request.getMethod() == "POST") {
        def body = new String(request.getInputStream().readAllBytes(), "UTF-8")
        def req = body.trim().isEmpty() ? [:] : mapper.readValue(body, Map.class)

        def targets = []
        if (req.eventId) {
            def ev = req.source
                ? Events.find(repositorySession, req.source.toString(), req.eventId.toString())
                : Events.list(repositorySession, [], null, null, 0L, 0).find { it.event_id == req.eventId.toString() }
            if (ev != null) targets << ev
        } else {
            def statuses = req.status ? [req.status.toString()] : ["error"]
            def source = req.source?.toString()
            def topic = req.topic?.toString()
            long since = req.sinceDays ? (System.currentTimeMillis() - (req.sinceDays.toString() as long) * 86_400_000L) : 0L
            targets = Events.list(repositorySession, statuses, source, topic, since, 1000)
        }

        int replayed = 0
        targets.each { ev ->
            try {
                def payload = Events.payloadJson(repositorySession, ev.path)
                if (payload == null) return
                IntegrationAPI.createMessageSender()
                    .setEndpointURI("direct:commerce-ingest")
                    .setBody(payload)
                    .setHeader("event_source", ev.source)
                    .setHeader("event_topic", ev.topic)
                    .setHeader("event_id", ev.event_id)
                    .setHeader("received_at", ev.received_at)
                    .setHeader("replay", "true")
                    // Run the replay as the operator who triggered it (this endpoint
                    // is authenticated), so the re-processed event-log entries and
                    // records carry their identity instead of the service user.
                    .setHeader("runAs", repositorySession.getUserID())
                    .sendAsync()
                replayed++
            } catch (Exception e) {
                log.warn("events: could not replay ${ev.path}: ${e.message}")
            }
        }

        response.setStatus(200)
        response.setHeader("Content-Type", "application/json")
        response.getWriter().write(mapper.writeValueAsString([replayed: replayed, matched: targets.size()]))
        return
    }

    response.setStatus(405)
} catch (Exception e) {
    log.error("events endpoint error: ${e.message}", e)
    response.setStatus(500)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"Internal error"}')
}

// --- Helpers -----------------------------------------------------------------

List splitParam(String raw) {
    if (raw == null || raw.trim().isEmpty()) return []
    return raw.split(",").collect { it.trim() }.findAll { it }
}

String blankToNull(String s) { (s == null || s.trim().isEmpty()) ? null : s.trim() }

long sinceMs(String days) {
    if (days == null || days.trim().isEmpty()) return 0L
    try { return System.currentTimeMillis() - (days.trim() as long) * 86_400_000L } catch (Exception e) { return 0L }
}

int paramInt(String name, int dflt, int lo, int hi) {
    try {
        def raw = request.getParameter(name)
        if (raw != null && !raw.trim().isEmpty()) return Math.max(lo, Math.min(hi, raw.trim() as int))
    } catch (Exception ignore) {}
    return dflt
}
