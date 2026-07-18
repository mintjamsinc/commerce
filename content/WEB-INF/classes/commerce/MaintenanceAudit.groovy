package commerce

/**
 * Maintenance-action audit trail (every operator-triggered data maintenance is
 * observable). Distinct from {@link SyncAudit}, which records outbound CMS→Shopify
 * writes; this records CMS-internal maintenance operations — today the manual
 * business-data purge.
 *
 * One record per action under /content/commerce/maintenance/{yyyy}/{MM}/, with
 * typed, queryable properties so the ops console can answer "who / when / what /
 * how much":
 *   commerce:status (ok / failed), commerce:action (e.g. "purge-business"),
 *   commerce:actor (WHO — the human operator), commerce:created_at (Date),
 *   commerce:purge_days (Long) and the deleted counts (Long) per store.
 *
 * Best-effort — never throws.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.MaintenanceAudit}.
 */
class MaintenanceAudit {

    static final String MAINT_DIR = "/content/commerce/maintenance"

    static final String ACTION_PURGE = "purge-business"

    /** Record a completed (or failed) purge. actor = the operator (session user id). */
    static void recordPurge(session, log, String actor, int days, String cutoffIso,
                            int orders, int payments, int refunds, String status, String error) {
        try {
            def now = Api.now()
            def ts = System.currentTimeMillis()
            // Month fold in UTC — the shared storage fold rule (Api.utcYearMonth).
            def ym = Api.utcYearMonth(ts).join("/")
            def path = "${MAINT_DIR}/${ym}/purge_${ts}.json".toString()
            def actorValue = actor ?: "cms"
            def record = [
                at      : now,
                actor   : actorValue,
                action  : ACTION_PURGE,
                days    : days,
                cutoff  : cutoffIso,
                orders  : orders,
                payments: payments,
                refunds : refunds,
                status  : status,
                error   : error,
            ]
            def res = Jcr.getOrCreateFile(session, path)
            res.write(Jcr.toJson(record))
            res.setProperty("commerce:status", status ?: "")
            res.setProperty("commerce:action", ACTION_PURGE)
            res.setProperty("commerce:actor", actorValue)
            res.setProperty("commerce:created_at", new java.util.Date())
            res.setProperty("commerce:purge_days", (long) days)
            res.setProperty("commerce:deleted_orders", (long) orders)
            res.setProperty("commerce:deleted_payments", (long) payments)
            res.setProperty("commerce:deleted_refunds", (long) refunds)
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("MaintenanceAudit: could not write purge record: ${e.message}") } catch (Exception ignore) {}
        }
    }

    /**
     * Recent purge records, newest first (for the Retention panel's history list).
     *
     * Walks the {yyyy}/{MM} folders directly (resource.list()) rather than an
     * index-backed XPath query: the JCR search index updates ASYNCHRONOUSLY after
     * commit, so a query run right after a purge would miss the record just written.
     * A tree walk reads committed state immediately, so the history is always fresh.
     * Purge records are few (one per manual purge), so the walk + in-memory sort is
     * cheap. Defensive: an unreadable folder / node is skipped, never fatal.
     */
    static List listRecent(session, int limit) {
        def out = []
        try {
            def base = Jcr.safeGet(session, MAINT_DIR)
            if (base == null || !base.exists()) return out
            def files = []
            children(base).each { year ->                       // {yyyy}
                children(year).each { month ->                  // {MM}
                    children(month).each { res ->               // purge_{ts}.json
                        try { if (res.getName().startsWith("purge_")) files << res } catch (Exception ignore) {}
                    }
                }
            }
            // Newest first by creation time; then apply the row cap.
            files.sort { a, b -> createdMs(b) <=> createdMs(a) }
            if (limit > 0 && files.size() > limit) files = files.subList(0, limit)
            files.each { res ->
                try {
                    def doc = Jcr.readMap(session, res.getPath()) ?: [:]
                    out << [
                        at      : Api.instant(doc.at ?: propVal(res, "commerce:created_at")),
                        actor   : doc.actor ?: propStr(res, "commerce:actor"),
                        days    : doc.days,
                        orders  : doc.orders,
                        payments: doc.payments,
                        refunds : doc.refunds,
                        status  : doc.status ?: propStr(res, "commerce:status"),
                    ]
                } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
        return out
    }

    // Immediate children of a folder resource (index-independent). Mirrors the
    // walk helper the migrations use for pre-index tree scans.
    private static List children(resource) {
        def out = []
        try {
            def it = resource.list()
            while (it.hasNext()) { out << it.next() }
        } catch (Exception ignore) {}
        return out
    }

    private static long createdMs(res) {
        try { return res.getCreated().getTime() } catch (Exception e) { return 0L }
    }

    private static Object propVal(res, String name) {
        try { return res.hasProperty(name) ? res.getProperty(name).getString() : null } catch (Exception e) { return null }
    }

    private static String propStr(res, String name) {
        try { return res.hasProperty(name) ? res.getProperty(name).getString() : "" } catch (Exception e) { return "" }
    }
}
