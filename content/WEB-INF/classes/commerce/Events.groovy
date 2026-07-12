package commerce

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Source-agnostic event ingestion.
 *
 * Every inbound integration event — from any backend (Shopify today; Rakuten /
 * BASE / a self-hosted store / an ERP tomorrow) — funnels through one core
 * (`direct:commerce-ingest`). The core persists a durable, normalized record of the
 * event here and then either forwards it to a backend-specific handler (for topics
 * that have a bespoke workflow) or stores a generic normalized entity record (for
 * every other topic). This class is the storage + normalization + replay engine
 * behind that core; the routes/scripts stay thin.
 *
 * Two stores, with distinct jobs:
 *
 *   1. Event log  — /content/commerce/events/{source}/{yyyy}/{MM}/{eventId}.json
 *      One entry per inbound event, carrying the RAW payload, so any event can be
 *      replayed and audited. `commerce:status` tracks the pipeline outcome
 *      (received -> processed | error); `commerce:attempts` counts ingest passes.
 *
 *   2. Normalized entity store — /content/commerce/entities/{source}/{collection}/{id}.json
 *      Current state per business entity for the generic topics that have no
 *      bespoke handler yet (customers / fulfillments / carts / checkouts / …),
 *      so they become the foundation for future business events. Latest
 *      update wins, mirroring the inventory levels / locations stores. Namespaced
 *      by source so multiple backends never clash.
 *
 * Design (mirrors the other commerce.* classes): the topic→collection mapping is
 * pure; the JCR methods are defensive (a logging/normalization failure must never
 * break the ingest pipeline). Lives under /content/WEB-INF/classes; use via
 * {@code import commerce.Events}.
 */
class Events {

    static final String EVENTS_DIR = "/content/commerce"  + "/events"
    static final String CONTENT_DIR = "/content/commerce"

    private static final ObjectMapper MAPPER = new ObjectMapper()
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy/MM")

    // Topics handled by a dedicated backend workflow. The core forwards these to
    // their existing routes; everything else is normalized generically. Kept here
    // (not just in the route) so the endpoint / replay can report handling too.
    static final java.util.Set BESPOKE_TOPICS = [
        "orders/paid",
        "products/create", "products/update", "products/delete",
        "refunds/create",
        "inventory_levels/update",
        "locations/create", "locations/update",
        "customers/create", "customers/update", "customers/enable",
        "customers/disable", "customers/delete",
        "customers/redact", "customers/data_request", "shop/redact",
    ] as java.util.Set

    // -------------------------------------------------------------------------
    // Pure topic mapping
    // -------------------------------------------------------------------------

    /** The entity collection a topic belongs to (the part before the first '/'). */
    static String collectionFor(String topic) {
        if (topic == null) return "events"
        def i = topic.indexOf('/')
        def c = (i >= 0 ? topic.substring(0, i) : topic).trim()
        return c.isEmpty() ? "events" : c
    }

    /** The action of a topic (the part after the first '/'), or "" when none. */
    static String actionFor(String topic) {
        if (topic == null) return ""
        def i = topic.indexOf('/')
        return i >= 0 ? topic.substring(i + 1).trim() : ""
    }

    /** True when a topic has a dedicated backend handler (vs. generic normalization). */
    static boolean isBespoke(String topic) {
        return topic != null && BESPOKE_TOPICS.contains(topic)
    }

    // -------------------------------------------------------------------------
    // Event log (defensive)
    // -------------------------------------------------------------------------

    /**
     * Record (or re-record, on replay) an inbound event in the event log with the
     * raw payload, status {@code received} and an incremented attempt counter.
     * Defensive — never throws. Returns the event-log path (or null on failure) so
     * the caller can hand it to {@link #setStatus} without re-finding it.
     */
    static String logEvent(session, log, String source, String topic, String eventId, String receivedAt, String payloadJson) {
        try {
            if (!source || !eventId) {
                log.warn("Events.logEvent: missing source/eventId - skipping event log")
                return null
            }
            // Keep an existing entry's path/attempts stable across replays. We check
            // the deterministic current/previous-month paths (O(1)) rather than
            // scanning, matching the rest of the codebase; a replay older than that
            // window simply starts a fresh entry (rare, acceptable).
            def existing = findRecent(session, source, eventId)
            String path = existing?.path
            int attempts = 1
            if (existing != null) {
                attempts = intOr(prop(existing.resource, "commerce:attempts"), 0) + 1
            } else {
                def now = LocalDate.now(ZoneId.systemDefault())
                path = "${EVENTS_DIR}/${sanitize(source)}/${now.format(YM)}/${sanitize(eventId)}.json".toString()
            }

            def payload = parse(payloadJson)
            def collection = collectionFor(topic)
            def entityId = entityId(payload)

            def record = [
                source     : source,
                topic      : topic,
                event_id   : eventId,
                entity_type: collection,
                entity_id  : entityId,
                received_at: receivedAt ?: Api.now(),
                status     : "received",
                attempts   : attempts,
                logged_at  : Api.now(),
                payload    : payload,
            ]

            def res = Jcr.getOrCreateFile(session, path)
            res.write(MAPPER.writeValueAsString(record))
            res.setProperty("commerce:status", "received")
            res.setProperty("commerce:source", source)
            res.setProperty("commerce:topic", str(topic))
            res.setProperty("commerce:event_id", eventId)
            res.setProperty("commerce:entity_type", str(collection))
            res.setProperty("commerce:entity_id", str(entityId))
            setDate(res, "commerce:received_at", record.received_at)
            res.setProperty("commerce:attempts", (long) attempts)
            session.commit()
            return path
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Events.logEvent: ${e.message}") } catch (Exception ignore) {}
            return null
        }
    }

    /**
     * Set the terminal status of an event-log entry ({@code processed} or
     * {@code error}, with an optional error detail). Defensive — never throws.
     */
    static void setStatus(session, log, String path, String status, String error) {
        if (!path) return
        try {
            def res = session.getResource(path)
            if (res == null || !res.exists()) return
            res.setProperty("commerce:status", status)
            if (error != null) {
                def msg = error.length() > 2048 ? error.substring(0, 2048) : error
                res.setProperty("commerce:last_error", msg)
            }
            // Keep the JSON body's status in step (best-effort).
            try {
                def doc = Jcr.readMap(session, path)
                if (!doc.isEmpty()) {
                    doc.status = status
                    if (error != null) doc.last_error = (error.length() > 2048 ? error.substring(0, 2048) : error)
                    res.write(MAPPER.writeValueAsString(doc))
                }
            } catch (Exception ignore) {}
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Events.setStatus: ${path}: ${e.message}") } catch (Exception ignore) {}
        }
    }

    // -------------------------------------------------------------------------
    // Generic normalization (defensive)
    // -------------------------------------------------------------------------

    /**
     * Store/refresh the normalized entity record for a generic (non-bespoke) topic
     * under /content/commerce/{collection}/{id}.json, latest update winning. A
     * delete action marks the record deleted rather than removing it (parity with
     * products/delete). Defensive — never throws. Returns true when written.
     */
    static boolean normalize(session, log, String source, String topic, String eventId, String payloadJson) {
        try {
            def payload = parse(payloadJson)
            def collection = collectionFor(topic)
            def action = actionFor(topic)
            def id = entityId(payload)
            if (!id) {
                log.info("Events.normalize: no entity id for ${topic} (event ${eventId}) - logged only")
                return false
            }
            // Namespaced under entities/{source}/{collection} so generic records never
            // collide with the curated bespoke stores (orders/raw, products, …) and so
            // entity ids from different backends cannot clash.
            def path = "${CONTENT_DIR}/entities/${sanitize(source)}/${sanitize(collection)}/${sanitize(id)}.json".toString()
            boolean deleted = action != null && action.toLowerCase().contains("delete")

            def res = Jcr.getOrCreateFile(session, path)
            res.write(payloadJson ?: "{}")
            res.setProperty("commerce:status", deleted ? "deleted" : "received")
            res.setProperty("commerce:source", source)
            res.setProperty("commerce:topic", str(topic))
            res.setProperty("commerce:entity_type", str(collection))
            res.setProperty("commerce:entity_id", str(id))
            res.setProperty("commerce:updated_at", new java.util.Date())
            // Best-effort cross-references that make the records useful downstream.
            def email = firstNonBlank(payload?.email, payload?.contact_email, payload?.customer?.email)
            if (email) res.setProperty("commerce:customer_email", email)
            def orderId = firstNonBlank(payload?.order_id)
            if (orderId) res.setProperty("commerce:order_id", orderId)
            if (deleted) res.setProperty("commerce:deletedAt", new java.util.Date())
            session.commit()
            log.info("Events.normalize: stored ${collection} ${id} (${topic})")
            return true
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Events.normalize: ${e.message}") } catch (Exception ignore) {}
            return false
        }
    }

    // -------------------------------------------------------------------------
    // Queries / replay support (defensive)
    // -------------------------------------------------------------------------

    /**
     * Resolve an existing event-log entry by source+id at the deterministic
     * current/previous-month paths (no scan). Returns { path, resource } or null.
     */
    static Map findRecent(session, String source, String eventId) {
        if (!source || !eventId) return null
        def today = LocalDate.now(ZoneId.systemDefault())
        for (int i = 0; i <= 1; i++) {
            def path = "${EVENTS_DIR}/${sanitize(source)}/${today.minusMonths(i).format(YM)}/${sanitize(eventId)}.json".toString()
            def res = Jcr.safeGet(session, path)
            if (res != null && res.exists()) {
                return [path: path, resource: res]
            }
        }
        return null
    }

    /** Find an event-log entry by source+id (scans that source's tree). */
    static Map find(session, String source, String eventId) {
        if (!source || !eventId) return null
        def want = sanitize(eventId) + ".json"
        Map hit = null
        eachEvent(session, source) { res ->
            if (hit != null) return
            try {
                if (res.getName() == want) {
                    hit = toRow(session, res)
                }
            } catch (Exception ignore) {}
        }
        return hit
    }

    /**
     * Events matching the filters, newest first. {@code source}/{@code topic} null
     * means any; {@code statuses} empty means any; {@code fromMs}/{@code toMs} bound
     * the received_at range (0 = unbounded on that side). Each row is WIRE-SHAPED
     * (camelCase keys, GID entity id, ms-precision ISO timestamps — commerce.Api):
     * { path, source, topic, eventId, entityType, entityId, status, attempts,
     * receivedAt, lastError }.
     */
    static List list(session, java.util.Collection statuses, String source, String topic, long fromMs, long toMs, int limit) {
        def states = (statuses ?: []) as java.util.Set
        def rows = []
        eachEvent(session, source) { res ->
            try {
                def row = toRow(session, res)
                if (!states.isEmpty() && !states.contains(row.status)) return
                if (topic != null && row.topic != topic) return
                // Range filter on the event's received_at (the displayed date), so the
                // period matches what the operator sees; fall back to the node time
                // when received_at is missing/unparseable. 0 = unbounded on that side.
                if (fromMs > 0 || toMs > 0) {
                    long t = msOf(row.receivedAt)
                    if (t <= 0) t = createdMs(res)
                    if (fromMs > 0 && t > 0 && t < fromMs) return
                    if (toMs > 0 && t > 0 && t > toMs) return
                }
                rows << row
            } catch (Exception ignore) {}
        }
        rows.sort { a, b -> (b.receivedAt?.toString() ?: "") <=> (a.receivedAt?.toString() ?: "") }
        return limit > 0 && rows.size() > limit ? rows.subList(0, limit) : rows
    }

    /**
     * Failed events eligible for automatic replay: status {@code error}, fewer than
     * {@code maxAttempts} ingest passes, and last touched more than {@code backoffMs}
     * ago. Oldest first (fair retry order).
     */
    static List findReplayable(session, int maxAttempts, long backoffMs, long nowMs) {
        def out = []
        eachEvent(session, null) { res ->
            try {
                if (prop(res, "commerce:status") != "error") return
                if (intOr(prop(res, "commerce:attempts"), 0) >= maxAttempts) return
                long t = lastModifiedMs(res)
                if (t > 0 && (nowMs - t) < backoffMs) return
                out << toRow(session, res)
            } catch (Exception ignore) {}
        }
        out.sort { a, b -> (a.receivedAt?.toString() ?: "") <=> (b.receivedAt?.toString() ?: "") }
        return out
    }

    /** Read the stored raw payload (JSON string) for an event-log entry. */
    static String payloadJson(session, String path) {
        try {
            def doc = Jcr.readMap(session, path)
            def p = doc?.payload
            return p == null ? null : MAPPER.writeValueAsString(p)
        } catch (Exception e) {
            return null
        }
    }

    /** Delete processed event-log entries older than retentionMs. Defensive. */
    static int prune(session, log, long retentionMs, long nowMs) {
        int removed = 0
        def victims = []
        eachEvent(session, null) { res ->
            try {
                if (prop(res, "commerce:status") != "processed") return
                long t = createdMs(res)
                if (t > 0 && (nowMs - t) > retentionMs) victims << res.getPath()
            } catch (Exception ignore) {}
        }
        victims.each { p ->
            try {
                def r = session.getResource(p)
                if (r != null && r.exists()) { r.remove(); removed++ }
            } catch (Exception ignore) {}
        }
        if (removed > 0) {
            try { session.commit(); log.info("Events.prune: removed ${removed} processed event(s)") }
            catch (Exception e) { try { session.rollback() } catch (Exception ignore) {} }
        }
        return removed
    }

    /** Counts by status across the event log (dashboard / endpoint). Defensive. */
    static Map summary(session) {
        def byStatus = [:]
        long total = 0
        eachEvent(session, null) { res ->
            try {
                total++
                def st = prop(res, "commerce:status") ?: "unknown"
                byStatus[st] = ((byStatus[st] ?: 0L) as long) + 1L
            } catch (Exception ignore) {}
        }
        return [total: total, byStatus: byStatus]
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    // The wire row (commerce.Api contract): camelCase keys, the entity id as a
    // Shopify GID (numeric REST ids are completed HERE, never patched downstream;
    // non-numeric ids like cart/checkout tokens pass through), timestamps in
    // ms-precision ISO-8601.
    private static Map toRow(session, res) {
        def entityType = prop(res, "commerce:entity_type")
        return [
            path      : res.getPath(),
            source    : prop(res, "commerce:source"),
            topic     : prop(res, "commerce:topic"),
            eventId   : prop(res, "commerce:event_id"),
            entityType: entityType,
            entityId  : Api.gid(Api.gidTypeFor(entityType), prop(res, "commerce:entity_id")),
            status    : prop(res, "commerce:status"),
            attempts  : intOr(prop(res, "commerce:attempts"), 0),
            receivedAt: Api.instant(propVal(res, "commerce:received_at")),
            lastError : prop(res, "commerce:last_error"),
        ]
    }

    /** Parse an ISO-8601 timestamp string to epoch ms (0 when absent/unparseable). */
    private static long msOf(String iso) {
        if (iso == null || iso.trim().isEmpty()) return 0L
        try { return OffsetDateTime.parse(iso.trim()).toInstant().toEpochMilli() } catch (Exception ignore) {}
        try { return Instant.parse(iso.trim()).toEpochMilli() } catch (Exception ignore) {}
        return 0L
    }

    /** Walk event-log files: EVENTS_DIR/{source}/{yyyy}/{MM}/*.json. source null = all sources. */
    private static void eachEvent(session, String source, Closure cb) {
        def base = Jcr.safeGet(session, EVENTS_DIR)
        if (base == null || !base.exists()) return
        children(base).each { srcFolder ->
            try {
                if (source != null && srcFolder.getName() != sanitize(source)) return
                children(srcFolder).each { yearFolder ->
                    if (!(yearFolder.getName() ==~ /\d{4}/)) return
                    children(yearFolder).each { monthFolder ->
                        if (!(monthFolder.getName() ==~ /\d{1,2}/)) return
                        children(monthFolder).each { child ->
                            try { if (child.getName().endsWith(".json")) cb(child) } catch (Exception ignore) {}
                        }
                    }
                }
            } catch (Exception ignore) {}
        }
    }

    private static List children(resource) {
        def out = []
        try {
            def it = resource.list()
            while (it.hasNext()) { out << it.next() }
        } catch (Exception ignore) {}
        return out
    }

    private static String entityId(payload) {
        if (!(payload instanceof Map)) return null
        def id = payload.id ?: payload.token ?: payload.admin_graphql_api_id
        return id == null ? null : id.toString()
    }

    private static Map parse(String json) {
        if (json == null || json.trim().isEmpty()) return [:]
        try { return MAPPER.readValue(json, Map.class) } catch (Exception e) { return [:] }
    }

    private static String firstNonBlank(Object... vals) {
        for (v in vals) {
            if (v != null && !v.toString().trim().isEmpty()) return v.toString().trim()
        }
        return null
    }

    private static String prop(res, String name) {
        try { if (res.hasProperty(name)) return res.getProperty(name).getValue()?.toString() } catch (Exception ignore) {}
        return null
    }

    // The raw typed property value (Calendar/Date/Number/String) — Api.instant
    // normalizes any of these to the wire timestamp format.
    private static Object propVal(res, String name) {
        try { if (res.hasProperty(name)) return res.getProperty(name).getValue() } catch (Exception ignore) {}
        return null
    }

    // Write an ISO timestamp (or now, when absent/unparseable) as a typed Date.
    private static void setDate(res, String name, value) {
        long ms = 0L
        if (value != null) {
            try { ms = java.time.OffsetDateTime.parse(value.toString()).toInstant().toEpochMilli() } catch (Exception ignore) {}
            if (ms == 0L) { try { ms = Instant.parse(value.toString()).toEpochMilli() } catch (Exception ignore) {} }
        }
        res.setProperty(name, ms > 0 ? new java.util.Date(ms) : new java.util.Date())
    }

    private static long createdMs(res) {
        try { return res.getCreated().getTime() } catch (Exception e) { return 0L }
    }

    private static long lastModifiedMs(res) {
        try { return res.getLastModified().getTime() } catch (Exception e) { return createdMs(res) }
    }

    // JCR node names must be safe; Shopify webhook ids and topics are alphanumerics
    // + hyphens, but normalize defensively.
    private static String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9_.-]", "_")
    }

    private static String str(v) { return v == null ? "" : v.toString() }

    private static int intOr(v, int dflt) {
        if (v == null) return dflt
        if (v instanceof Number) return ((Number) v).intValue()
        try { return Integer.parseInt(v.toString().trim()) } catch (Exception e) { return dflt }
    }
}
