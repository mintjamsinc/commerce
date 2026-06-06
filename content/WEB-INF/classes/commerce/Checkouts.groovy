package commerce

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Abandoned checkout (cart) detection (category D, #14).
 *
 * The ingestion platform (#1) normalizes checkouts/* into the entity mirror at
 * /content/commerce/entities/{source}/checkouts/{id}.json (latest update winning,
 * so a later "completed" update is reflected). A checkout is <em>abandoned</em> when
 * it has no completed_at and has been idle longer than a threshold. This class finds
 * those and tracks reminder bookkeeping; the follow-up batch sends the reminders.
 *
 * Defensive JCR traversal (jackson). Lives under /content/WEB-INF/classes; use via
 * {@code import commerce.Checkouts}.
 */
class Checkouts {

    static final String ENTITIES_DIR = "/content/commerce/entities"

    private static final ObjectMapper MAPPER = new ObjectMapper()

    /**
     * Abandoned checkouts (no completed_at, idle ≥ abandonedAfterMs), newest-idle
     * first. Each row: { path, id, email, name, createdAt, updatedAt, ageMs, total,
     * currency, recoveryUrl, itemCount, remindersSent, lastReminderMs }.
     */
    static List findAbandoned(session, long abandonedAfterMs, long nowMs) {
        def out = []
        eachCheckout(session) { res ->
            try {
                def co = MAPPER.readValue(res.content.toString(), Map.class)
                if (co == null) return
                if (notBlank(co.completed_at)) return  // converted to an order

                long idleMs = idleMs(co, res, nowMs)
                if (idleMs < abandonedAfterMs) return

                out << [
                    path         : res.getPath(),
                    id           : str(co.id ?: co.token),
                    email        : str(co.email ?: co.contact_email),
                    name         : customerName(co),
                    createdAt    : str(co.created_at),
                    updatedAt    : str(co.updated_at),
                    ageMs        : idleMs,
                    total        : str(co.total_price),
                    currency     : str(co.currency),
                    recoveryUrl  : str(co.abandoned_checkout_url),
                    itemCount    : (co.line_items instanceof List) ? co.line_items.size() : 0,
                    remindersSent: intOr(prop(res, "commerce:reminders_sent"), 0),
                    lastReminderMs: parseMs(prop(res, "commerce:last_reminder_at")),
                ]
            } catch (Exception ignore) {}
        }
        out.sort { a, b -> (b.ageMs ?: 0L) <=> (a.ageMs ?: 0L) }
        return out
    }

    /** Record that a reminder was sent: bump commerce:reminders_sent + timestamp. Defensive. */
    static boolean markReminded(session, log, String path, long nowMs) {
        try {
            def res = session.getResource(path)
            if (res == null || !res.exists()) return false
            int sent = intOr(prop(res, "commerce:reminders_sent"), 0) + 1
            res.setProperty("commerce:reminders_sent", sent.toString())
            res.setProperty("commerce:last_reminder_at", java.time.Instant.ofEpochMilli(nowMs).toString())
            session.commit()
            return true
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Checkouts.markReminded: ${path}: ${e.message}") } catch (Exception ignore) {}
            return false
        }
    }

    /** Counts for the endpoint/dashboard: abandoned + how many have been reminded. */
    static Map summary(session, long abandonedAfterMs, long nowMs) {
        long abandoned = 0, reminded = 0
        findAbandoned(session, abandonedAfterMs, nowMs).each {
            abandoned++
            if ((it.remindersSent ?: 0) > 0) reminded++
        }
        return [abandoned: abandoned, reminded: reminded]
    }

    // --- Helpers ---------------------------------------------------------------

    private static void eachCheckout(session, Closure cb) {
        def base = Jcr.safeGet(session, ENTITIES_DIR)
        if (base == null || !base.exists()) return
        // entities/{source}/checkouts/*.json
        children(base).each { srcFolder ->
            def coFolder = Jcr.safeGet(session, "${srcFolder.getPath()}/checkouts")
            if (coFolder == null || !coFolder.exists()) return
            children(coFolder).each { f ->
                try { if (f.getName().endsWith(".json")) cb(f) } catch (Exception ignore) {}
            }
        }
    }

    private static List children(resource) {
        def out = []
        try { def it = resource.list(); while (it.hasNext()) out << it.next() } catch (Exception ignore) {}
        return out
    }

    private static long idleMs(Map co, res, long nowMs) {
        long t = parseMs(co.updated_at?.toString())
        if (t <= 0) t = parseMs(co.created_at?.toString())
        if (t <= 0) { try { t = res.getLastModified().getTime() } catch (Exception ignore) {} }
        return t > 0 ? (nowMs - t) : 0L
    }

    private static String customerName(Map co) {
        def c = co.customer
        if (c instanceof Map) {
            def n = [c.first_name, c.last_name].findAll { it }.join(" ").trim()
            if (n) return n
        }
        def bn = co.billing_address?.name ?: co.shipping_address?.name
        return bn?.toString()
    }

    private static long parseMs(String iso) {
        if (iso == null || iso.trim().isEmpty()) return 0L
        try { return java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
        catch (Exception e) {
            try { return java.time.Instant.parse(iso).toEpochMilli() } catch (Exception ignore) { return 0L }
        }
    }

    private static boolean notBlank(v) { v != null && !v.toString().trim().isEmpty() }

    private static String prop(res, String name) {
        try { if (res.hasProperty(name)) return res.getProperty(name).getValue()?.toString() } catch (Exception ignore) {}
        return null
    }

    private static int intOr(v, int dflt) {
        if (v == null) return dflt
        try { return v.toString().trim() as int } catch (Exception e) { return dflt }
    }

    private static String str(v) { v == null ? null : v.toString() }
}
