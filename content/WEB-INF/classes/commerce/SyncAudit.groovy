package commerce

/**
 * Outbound-write audit trail (every CMS→Shopify write is observable).
 *
 * One record per attempted write under /content/commerce/sync/{yyyy}/{MM}/,
 * so the ops console can answer "who / when / against what / what action" with typed,
 * queryable properties:
 *   commerce:status (ok / failed / dryrun), commerce:action, commerce:actor
 *   (WHO — the human operator / decider), commerce:entity + commerce:entity_id
 *   (WHAT-TARGET — the Shopify entity kind and id), commerce:source (the
 *   integration platform, "cms" today — reserved for a future multi-target
 *   split), commerce:created_at (Date).
 *
 * Shared by the sync endpoint and the workflow-driven writes (fulfillment,
 * order cancel, incoming inventory transfers). Best-effort — never throws.
 *
 * The {@code actor}/{@code entity}/{@code entityId} args are optional (default
 * null) so older call sites keep compiling; when {@code actor} is null the
 * record falls back to the system source "cms" as before.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.SyncAudit}.
 */
class SyncAudit {

    static final String SYNC_DIR = "/content/commerce/sync"

    static void record(session, log, String action, Map request, String status, Object result, String error,
                       String actor = null, String entity = null, String entityId = null) {
        try {
            // ms-precision ISO (commerce.Api wire format) — never the raw
            // Instant.now().toString(), whose fraction digits drift (0/3/6/9).
            def now = Api.now()
            def ts = System.currentTimeMillis()
            // Month fold in UTC — the shared storage fold rule (Api.utcYearMonth).
            def ym = Api.utcYearMonth(ts).join("/")
            def path = "${SYNC_DIR}/${ym}/sync_${ts}.json".toString()
            // WHO: the human operator / decider. Absent (legacy / true system
            // writes) falls back to "cms"; BPM-service callers pass "workflow".
            def actorValue = actor ?: "cms"
            def record = [
                at       : now,
                actor    : actorValue,
                source   : "cms",
                entity   : entity,
                entity_id: entityId,
                action   : action,
                request  : request,
                status   : status,
                result   : result,
                error    : error,
            ]
            def res = Jcr.getOrCreateFile(session, path)
            res.write(Jcr.toJson(record))
            res.setProperty("commerce:status", status)
            res.setProperty("commerce:action", action ?: "")
            res.setProperty("commerce:actor", actorValue)
            res.setProperty("commerce:source", "cms")
            if (entity != null) res.setProperty("commerce:entity", entity)
            if (entityId != null) res.setProperty("commerce:entity_id", entityId)
            res.setProperty("commerce:created_at", new java.util.Date())
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("SyncAudit: could not write audit record: ${e.message}") } catch (Exception ignore) {}
        }
    }
}
