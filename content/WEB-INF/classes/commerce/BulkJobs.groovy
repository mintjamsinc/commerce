package commerce

/**
 * Shopify Bulk job broker — durable JCR queue + state machine that serializes Shopify Bulk
 * Operations into a single lane (one bulk per shop).
 *
 * A job moves QUEUED -> RUNNING -> (COMPLETED | FAILED | CANCELED | TIMED_OUT). The lane
 * worker starts a new bulk only when no job is RUNNING, honoring the singleton Bulk
 * constraint. Enqueue is idempotent (no duplicate active job of the same type). Defensive
 * throughout: a bookkeeping failure must never break a route.
 *
 * Storage: /content/commerce/jobs/shopify/{jobId}.json
 *   { jobId, type, status, bulkOperationGid, enqueuedAt, startedAt, finishedAt, error }
 *
 * NOTE (retention): completed jobs accumulate under JOBS_DIR; a future cleanup should prune
 * old terminal jobs (the lane/enqueue scans this dir, so keep it small).
 *
 * Lives under /content/WEB-INF/classes; use via `import commerce.BulkJobs`.
 */
class BulkJobs {

    static final String JOBS_DIR = "/content/commerce/jobs/shopify"

    static final String QUEUED     = "QUEUED"
    static final String RUNNING    = "RUNNING"      // bulk submitted, awaiting Shopify finish
    static final String PROCESSING = "PROCESSING"   // result downloading / reconciling
    static final String COMPLETED  = "COMPLETED"
    static final String FAILED     = "FAILED"
    static final String CANCELED   = "CANCELED"
    static final String TIMED_OUT  = "TIMED_OUT"

    private static boolean isActive(String s) { s == QUEUED || s == RUNNING || s == PROCESSING }

    // --- Enqueue ---------------------------------------------------------------

    /** Create a QUEUED job and return its jobId (or null on failure). Defensive. */
    static String create(session, log, String type) {
        try {
            def jobId = "job_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 8)}".toString()
            def res = Jcr.getOrCreateFile(session, "${JOBS_DIR}/${jobId}.json".toString())
            res.write(Jcr.toJson([
                jobId           : jobId,
                type            : type,
                status          : QUEUED,
                bulkOperationGid: null,
                enqueuedAt      : java.time.Instant.now().toString(),
                startedAt       : null,
                finishedAt      : null,
                error           : null,
            ]))
            session.commit()
            return jobId
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("BulkJobs.create ${type}: ${e.message}") } catch (Exception ignore) {}
            return null
        }
    }

    // --- Queries ---------------------------------------------------------------

    /** All jobs (as maps). */
    static List<Map> list(session) {
        def out = []
        def base = Jcr.safeGet(session, JOBS_DIR)
        if (base == null || !base.exists()) return out
        try {
            def it = base.list()
            while (it.hasNext()) {
                def c = it.next()
                def n = c.getName()
                if (n != null && n.endsWith(".json")) {
                    def doc = Jcr.readMap(session, "${JOBS_DIR}/${n}".toString())
                    if (doc != null && !doc.isEmpty()) out << doc
                }
            }
        } catch (Exception ignore) {}
        return out
    }

    /** True if a job of this type is QUEUED or RUNNING (enqueue idempotency guard). */
    static boolean hasActive(session, String type) {
        return list(session).any { it.type?.toString() == type && isActive(it.status?.toString()) }
    }

    /** True if any job is RUNNING (the single lane is busy). */
    static boolean hasRunning(session) {
        return list(session).any { it.status?.toString() == RUNNING }
    }

    /** True if a job is RUNNING (awaiting Shopify) or PROCESSING (downloading/reconciling). */
    static boolean laneBusy(session) {
        return list(session).any { def s = it.status?.toString(); s == RUNNING || s == PROCESSING }
    }

    /** The oldest QUEUED job (FIFO by jobId), or null. */
    static Map nextQueued(session) {
        def q = list(session).findAll { it.status?.toString() == QUEUED }
                             .sort { it.jobId?.toString() }
        return q.isEmpty() ? null : q[0]
    }

    /** All RUNNING jobs (awaiting Shopify; for the watchdog's lost-webhook recovery). */
    static List<Map> running(session) {
        return list(session).findAll { it.status?.toString() == RUNNING }
    }

    /** All PROCESSING jobs (downloading/reconciling; for the watchdog's stuck-processing check). */
    static List<Map> processing(session) {
        return list(session).findAll { it.status?.toString() == PROCESSING }
    }

    /** Find a job by its Shopify Bulk Operation GID (finish-webhook correlation). */
    static Map findByGid(session, String gid) {
        if (gid == null) return null
        return list(session).find { it.bulkOperationGid?.toString() == gid }
    }

    // --- Transitions (always release the lane on any terminal state) ----------

    static void markRunning(session, log, String jobId, String gid) {
        patch(session, log, jobId) { d ->
            d.status = RUNNING
            d.bulkOperationGid = gid
            d.startedAt = java.time.Instant.now().toString()
        }
    }

    static void markProcessing(session, log, String jobId) {
        patch(session, log, jobId) { d ->
            d.status = PROCESSING
            d.processingStartedAt = java.time.Instant.now().toString()
        }
    }

    static void markCompleted(session, log, String jobId) {
        patch(session, log, jobId) { d ->
            d.status = COMPLETED
            d.finishedAt = java.time.Instant.now().toString()
        }
    }

    static void markFailed(session, log, String jobId, String reason) {
        patch(session, log, jobId) { d ->
            d.status = FAILED
            d.error = reason
            d.finishedAt = java.time.Instant.now().toString()
        }
    }

    static void markTimedOut(session, log, String jobId) {
        patch(session, log, jobId) { d ->
            d.status = TIMED_OUT
            d.finishedAt = java.time.Instant.now().toString()
        }
    }

    // --- Helpers ---------------------------------------------------------------

    private static void patch(session, log, String jobId, Closure mut) {
        if (jobId == null) return
        try {
            def path = "${JOBS_DIR}/${jobId}.json".toString()
            def doc = Jcr.readMap(session, path)
            if (doc == null || doc.isEmpty()) return
            mut(doc)
            Jcr.getOrCreateFile(session, path).write(Jcr.toJson(doc))
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("BulkJobs.patch ${jobId}: ${e.message}") } catch (Exception ignore) {}
        }
    }
}
