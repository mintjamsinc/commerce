// Event log + replay endpoint (admin).
//
//   GET  — list the event log (filter by status/source/topic + a from/to period,
//          both ISO-8601 instants) + a status summary. Read-only.
//   POST — manually replay events: a single event ({source,eventId}) or every
//          event matching a filter ({status,source,topic,from,to}; defaults to
//          status=error). Re-dispatches through the ingest core with replay=true.
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.
//
//   GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/events.groovy?status=error&limit=100
//   POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/events.groovy   {"status":"error"}

import commerce.Api
import commerce.Events
import com.fasterxml.jackson.databind.ObjectMapper

def mapper = new ObjectMapper()

try {
    if (request.getMethod() == "GET") {
        def statuses = splitParam(request.getParameter("status"))
        def source = blankToNull(request.getParameter("source"))
        def topic = blankToNull(request.getParameter("topic"))
        int limit = paramInt("limit", 100, 1, 1000)
        // from/to arrive as ISO-8601 instants (platform wire convention); 0 = unbounded.
        long fromMs = msParam(request.getParameter("from"))
        long toMs = msParam(request.getParameter("to"))

        // Wire contract (commerce.Api): camelCase keys, ms-precision ISO timestamps,
        // GID entity ids — the rows come pre-shaped from Events.list.
        def out = [
            generatedAt: Api.now(),
            summary    : Events.summary(repositorySession),
            events     : Events.list(repositorySession, statuses, source, topic, fromMs, toMs, limit),
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
                : Events.list(repositorySession, [], null, null, 0L, 0L, 0).find { it.eventId == req.eventId.toString() }
            if (ev != null) targets << ev
        } else {
            def statuses = req.status ? [req.status.toString()] : ["error"]
            def source = req.source?.toString()
            def topic = req.topic?.toString()
            long fromMs = req.from ? msParam(req.from.toString()) : 0L
            long toMs = req.to ? msParam(req.to.toString()) : 0L
            targets = Events.list(repositorySession, statuses, source, topic, fromMs, toMs, 1000)
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
                    // The Camel exchange headers stay snake_case — that is the
                    // internal EIP transport contract, not the wire.
                    .setHeader("event_id", ev.eventId)
                    .setHeader("received_at", ev.receivedAt)
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

// Parse an ISO-8601 instant param to epoch ms (0 when absent/invalid). from/to
// arrive as new Date(...).toISOString() from the client (platform wire convention).
long msParam(String iso) {
    if (iso == null || iso.trim().isEmpty()) return 0L
    try { return java.time.OffsetDateTime.parse(iso.trim()).toInstant().toEpochMilli() } catch (Exception ignore) {}
    return 0L
}

int paramInt(String name, int dflt, int lo, int hi) {
    try {
        def raw = request.getParameter(name)
        if (raw != null && !raw.trim().isEmpty()) return Math.max(lo, Math.min(hi, raw.trim() as int))
    } catch (Exception ignore) {}
    return dflt
}
