package commerce

/**
 * Shopify Bulk job broker — durable JCR queue + state machine with DOMAIN-BASED serialization.
 *
 * Instead of one global lane, each job carries the data DOMAINS it touches (targetDomains, e.g.
 * ["inventory"]) and serialization is per-domain across two independent lanes:
 *   - Shopify producer lane  : SINGLETON — Shopify allows only ONE bulk query RUNNING at a time
 *     per app, and a new bulk is never started for a domain that is still awaiting/undergoing
 *     CMS ingest (READY/PROCESSING).
 *   - CMS consumer lane       : runs the heavy download+reconcile IN PARALLEL for jobs whose
 *     domains are DISJOINT, and serializes only when domains overlap.
 * With today's single "inventory" domain this behaves as a safe serial lane; adding future
 * backfill domains (products/orders/customers) auto-pipelines disjoint work in parallel.
 *
 * State machine:
 *   QUEUED -> RUNNING (Shopify bulk running) -> READY (Shopify COMPLETED, awaiting a CMS ingest
 *   slot) -> PROCESSING (CMS downloading/reconciling) -> COMPLETED | FAILED | CANCELED | TIMED_OUT.
 *   isActive = QUEUED || RUNNING || READY || PROCESSING.
 *
 * Enqueue is idempotent (no duplicate active job of the same type; READY counts as active).
 * Defensive throughout: a bookkeeping failure must never break a route.
 *
 * BACKWARD-COMPAT: a job whose targetDomains is missing/empty is a WILDCARD that overlaps every
 * domain (conservative = serialize). domainsOf(job) returns an empty Set for such jobs; the
 * aggregate helper domainsInStatuses(...) collapses to the ALL_DOMAINS sentinel when a wildcard
 * job is present, and overlaps(...) treats both as "matches everything active".
 *
 * Storage: /content/commerce/jobs/shopify/{jobId}.json
 *   { jobId, type, targetDomains, status, bulkOperationGid, params, enqueuedAt, startedAt,
 *     readyAt, processingStartedAt, finishedAt, error, stats? }
 *
 * Terminal transitions additionally notify Reconciliation.recordBulkAudit (exactly-once via
 * the absorbing-state guard), which turns inventory-audit jobs into run-history reports.
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
    static final String READY      = "READY"        // Shopify bulk completed, awaiting a CMS ingest slot
    static final String PROCESSING = "PROCESSING"   // result downloading / reconciling
    static final String COMPLETED  = "COMPLETED"
    static final String FAILED     = "FAILED"
    static final String CANCELED   = "CANCELED"
    static final String TIMED_OUT  = "TIMED_OUT"

    /**
     * Reserved domain token: an aggregate domain set that matches EVERY domain. Returned by
     * domainsInStatuses(...) when a wildcard (empty-domains) job is present so callers/overlaps
     * treat the set as "blocks everything". Real domains ("inventory", ...) never use "*".
     */
    static final String ALL_DOMAINS = "*"

    private static boolean isActive(String s) {
        s == QUEUED || s == RUNNING || s == READY || s == PROCESSING
    }

    // --- Enqueue ---------------------------------------------------------------

    /**
     * Create a QUEUED job carrying the data domains it touches PLUS opaque job params, and return
     * its jobId (or null on failure). Defensive. A null/empty targetDomains is stored as [] and
     * treated as a WILDCARD (overlaps every domain) by domainsOf/overlaps. params (null -> {}) is
     * a free-form map the broker round-trips untouched — dynamic bulk types read it to build their
     * query (e.g. orders-backfill's from/to date range via BulkQueries.forJob).
     */
    static String create(session, log, String type, List targetDomains, Map params) {
        try {
            def domains = (targetDomains == null) ? [] : new ArrayList(targetDomains)
            def jobId = "job_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 8)}".toString()
            def res = Jcr.getOrCreateFile(session, "${JOBS_DIR}/${jobId}.json".toString())
            res.write(Jcr.toJson([
                jobId           : jobId,
                type            : type,
                targetDomains   : domains,
                status          : QUEUED,
                bulkOperationGid: null,
                params          : (params == null) ? [:] : params,
                enqueuedAt      : Api.now(),
                startedAt       : null,
                readyAt         : null,
                processingStartedAt: null,
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

    /**
     * Overload: create a QUEUED job with domains but NO params (stored as {}). Delegates to the
     * 5-arg form. For static bulk types whose query needs no per-job parameters.
     */
    static String create(session, log, String type, List targetDomains) {
        return create(session, log, type, targetDomains, [:])
    }

    /**
     * Backward-compat overload: create a QUEUED job with NO domains (a WILDCARD that serializes
     * against everything) and no params. New callers should pass explicit targetDomains.
     */
    static String create(session, log, String type) {
        return create(session, log, type, [])
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

    /** True if a job of this type is active — QUEUED/RUNNING/READY/PROCESSING (enqueue idempotency guard). */
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

    /** All READY jobs (Shopify done, awaiting a CMS ingest slot; the CMS lane drains these). */
    static List<Map> ready(session) {
        return list(session).findAll { it.status?.toString() == READY }
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

    // --- Domains ---------------------------------------------------------------

    /**
     * The data domains a job touches, as a Set. An EMPTY Set means WILDCARD (missing/empty
     * targetDomains — a backward-compat job that conservatively overlaps every domain).
     * Never returns null. Defensive against malformed docs.
     */
    static Set<String> domainsOf(Map job) {
        def out = new LinkedHashSet<String>()
        if (job == null) return out
        try {
            def td = job.targetDomains
            if (td instanceof Collection) {
                td.each { x ->
                    if (x != null) {
                        def s = x.toString().trim()
                        if (!s.isEmpty()) out << s
                    }
                }
            }
        } catch (Exception ignore) {}
        return out
    }

    /**
     * Union of targetDomains over all jobs whose status is in `statuses` (optionally excluding
     * one jobId). Returns an EMPTY Set when no such job exists (nothing active). If ANY matching
     * job is a WILDCARD (empty domains), the union collapses to the ALL_DOMAINS sentinel — a Set
     * that overlaps(...) treats as "blocks everything". Never returns null; never throws.
     */
    static Set<String> domainsInStatuses(session, List statuses, String excludeJobId = null) {
        def union = new LinkedHashSet<String>()
        if (statuses == null || statuses.isEmpty()) return union
        try {
            for (job in list(session)) {
                def st = job.status?.toString()
                if (st == null || !statuses.contains(st)) continue
                if (excludeJobId != null && job.jobId?.toString() == excludeJobId) continue
                def d = domainsOf(job)
                if (d.isEmpty()) {
                    // A wildcard job in one of these statuses blocks every domain.
                    def all = new LinkedHashSet<String>()
                    all << ALL_DOMAINS
                    return all
                }
                union.addAll(d)
            }
        } catch (Exception ignore) {}
        return union
    }

    /**
     * True if a candidate job's domains conflict with a set of currently-active domains.
     *
     *   overlaps(candidateDomains, activeDomains)
     *     candidateDomains : a single job's domains (from domainsOf; EMPTY == wildcard job)
     *     activeDomains    : an aggregate from domainsInStatuses (EMPTY == nothing active;
     *                        contains ALL_DOMAINS == a wildcard job is active)
     *
     * Rules (conservative = serialize):
     *   - nothing active                        -> no overlap (candidate may proceed)
     *   - an active WILDCARD (ALL_DOMAINS)       -> overlaps everything
     *   - a wildcard candidate (empty/ALL)       -> overlaps any active domain
     *   - otherwise                              -> overlap iff the concrete sets intersect
     */
    static boolean overlaps(Set a, Set b) {
        def candidate = (a == null) ? new LinkedHashSet() : a
        def active    = (b == null) ? new LinkedHashSet() : b
        if (active.isEmpty()) return false                 // nothing active blocks the candidate
        if (active.contains(ALL_DOMAINS)) return true      // an active wildcard blocks everything
        if (candidate.isEmpty() || candidate.contains(ALL_DOMAINS)) return true // wildcard candidate
        return !candidate.intersect(active).isEmpty()      // concrete vs concrete
    }

    // --- Transitions (GUARDED compare-and-set; terminal states are absorbing) --
    //
    // Every transition is a guarded CAS via patchIf(...): it writes ONLY when the job's current
    // status is one of the expected pre-states, so a duplicate Shopify finish webhook (at-least-once
    // delivery) or a watchdog race can NOT resurrect a PROCESSING/COMPLETED job back to READY and
    // trigger a DOUBLE concurrent reconcile of the same domain. Each returns true iff it applied the
    // transition (false = wrong/missing status → no write); existing callers may ignore the bool.
    // A forward transition never lists a terminal status among its expected states, which makes
    // COMPLETED/FAILED/CANCELED/TIMED_OUT absorbing.

    /** QUEUED -> RUNNING (records the bulk gid + startedAt). True iff applied. */
    static boolean markRunning(session, log, String jobId, String gid) {
        return patchIf(session, log, jobId, [QUEUED]) { d ->
            d.status = RUNNING
            d.bulkOperationGid = gid
            d.startedAt = Api.now()
        }
    }

    /**
     * RUNNING -> READY: Shopify bulk COMPLETED, awaiting a CMS ingest slot. The CMS consumer lane
     * later picks READY jobs up domain-safely (dispatches the result route + marks PROCESSING).
     * True iff applied (a duplicate finish webhook after PROCESSING/COMPLETED is a no-op).
     */
    static boolean markReady(session, log, String jobId) {
        return patchIf(session, log, jobId, [RUNNING]) { d ->
            d.status = READY
            d.readyAt = Api.now()
        }
    }

    /**
     * RUNNING|PROCESSING -> READY: put a job back to READY so the CMS consumer lane re-dispatches it.
     * Used by the result processors' transient-retry path: by the time a result script runs, the CMS
     * lane has ALREADY marked the job PROCESSING, so a plain markReady (RUNNING-only) would be a no-op
     * and freeze the job until the watchdog fails it. READY keeps the domain blocked in the producer
     * lane (no duplicate bulk). True iff applied. (Guarded: a terminal job is left untouched.)
     */
    static boolean markReadyForRetry(session, log, String jobId) {
        return patchIf(session, log, jobId, [RUNNING, PROCESSING]) { d ->
            d.status = READY
            d.readyAt = Api.now()
        }
    }

    /** READY -> PROCESSING (CMS lane claims the ingest slot). True iff applied. */
    static boolean markProcessing(session, log, String jobId) {
        return patchIf(session, log, jobId, [READY]) { d ->
            d.status = PROCESSING
            d.processingStartedAt = Api.now()
        }
    }

    /**
     * PROCESSING -> COMPLETED (terminal; releases the domains). True iff applied. The optional
     * stats map (free-form counters from the result processor, e.g. [checked: N, updated: M])
     * is persisted on the job doc and rides into the run-history report.
     */
    static boolean markCompleted(session, log, String jobId, Map stats = null) {
        return patchTerminal(session, log, jobId, [PROCESSING]) { d ->
            d.status = COMPLETED
            d.finishedAt = Api.now()
            if (stats != null) d.stats = stats
        }
    }

    /** any active state -> FAILED (terminal; releases the domains). True iff applied. */
    static boolean markFailed(session, log, String jobId, String reason) {
        return patchTerminal(session, log, jobId, [QUEUED, RUNNING, READY, PROCESSING]) { d ->
            d.status = FAILED
            d.error = reason
            d.finishedAt = Api.now()
        }
    }

    /** any active state -> TIMED_OUT (terminal; releases the domains). True iff applied. */
    static boolean markTimedOut(session, log, String jobId) {
        return patchTerminal(session, log, jobId, [QUEUED, RUNNING, READY, PROCESSING]) { d ->
            d.status = TIMED_OUT
            d.finishedAt = Api.now()
        }
    }

    /**
     * any active state -> CANCELED (terminal; releases the domains). Used by the watchdog hard cap
     * after it asks Shopify to cancel a runaway bulk. True iff applied (a job that already reached a
     * terminal state is left untouched).
     */
    static boolean markCanceled(session, log, String jobId, String reason) {
        return patchTerminal(session, log, jobId, [QUEUED, RUNNING, READY, PROCESSING]) { d ->
            d.status = CANCELED
            d.error = reason
            d.finishedAt = Api.now()
        }
    }

    /**
     * Best-effort increment of the numeric reconcileAttempts counter on a job doc, returning the
     * NEW value (or 0 on failure / missing job). Lets the CMS consumer lane bound reconcile retries
     * before it hard-fails a job. Intentionally NOT status-guarded (a retry may run from READY or
     * PROCESSING), but still defensive: a bookkeeping failure returns 0 without breaking the caller.
     */
    static int incrementReconcileAttempts(session, log, String jobId) {
        if (jobId == null) return 0
        try {
            def path = "${JOBS_DIR}/${jobId}.json".toString()
            def doc = Jcr.readMap(session, path)
            if (doc == null || doc.isEmpty()) return 0
            int n = 0
            try {
                n = (doc.reconcileAttempts == null) ? 0 : Integer.parseInt(doc.reconcileAttempts.toString())
            } catch (Exception ignore) { n = 0 }
            n = n + 1
            doc.reconcileAttempts = n
            Jcr.getOrCreateFile(session, path).write(Jcr.toJson(doc))
            session.commit()
            return n
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("BulkJobs.incrementReconcileAttempts ${jobId}: ${e.message}") } catch (Exception ignore) {}
            return 0
        }
    }

    // --- Helpers ---------------------------------------------------------------

    /**
     * Guarded terminal transition + run-history hook. Every path a job can end through
     * (result processors, finish webhook, watchdog) funnels into the four terminal markers
     * above, so this is the ONE choke point for terminal bookkeeping — and because terminal
     * states are absorbing (patchIf applies a terminal transition at most once), the
     * notification is exactly-once per job. The patched doc is captured from the CAS closure
     * (no re-read). Reconciliation decides whether the job is an inventory audit worth
     * recording; best-effort — bookkeeping must never break a broker transition.
     */
    private static boolean patchTerminal(session, log, String jobId, List expectedStatuses, Closure mut) {
        def patched = null
        boolean applied = patchIf(session, log, jobId, expectedStatuses) { d ->
            mut(d)
            patched = d
        }
        if (applied) {
            try {
                Reconciliation.recordBulkAudit(session, log, (Map) patched)
            } catch (Exception e) {
                try { log.warn("BulkJobs.patchTerminal ${jobId}: ${e.message}") } catch (Exception ignore) {}
            }
        }
        return applied
    }

    /**
     * Guarded compare-and-set patch. Reads the job doc and applies mut(doc) + persists + commits
     * ONLY when the doc's current status is one of expectedStatuses, returning true. If the doc is
     * missing/malformed, or its status is NOT in expectedStatuses (a stale/duplicate/racing
     * transition), it returns false WITHOUT writing. On any exception it rolls back and returns
     * false. Because forward transitions never list a terminal status among expectedStatuses,
     * terminal states become absorbing — the core invariant that keeps per-domain reconcile safe.
     */
    private static boolean patchIf(session, log, String jobId, List expectedStatuses, Closure mut) {
        if (jobId == null) return false
        try {
            def path = "${JOBS_DIR}/${jobId}.json".toString()
            def doc = Jcr.readMap(session, path)
            if (doc == null || doc.isEmpty()) return false
            def st = doc.status?.toString()
            if (expectedStatuses == null || !expectedStatuses.contains(st)) return false
            mut(doc)
            Jcr.getOrCreateFile(session, path).write(Jcr.toJson(doc))
            session.commit()
            return true
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("BulkJobs.patchIf ${jobId}: ${e.message}") } catch (Exception ignore) {}
            return false
        }
    }
}
