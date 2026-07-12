package commerce

/**
 * One-time, operator-triggered, resumable historical sales-fact SEED. Walks the ENTIRE order mirror
 * and ENQUEUES every distinct order for a
 * fact recompute through the EXISTING single-writer drainer — it does NOT materialize anything itself.
 *
 * Single-writer discipline: ONLY the drainer (sweepSalesFacts.groovy → SalesFacts.recompute)
 * may write the two fact grains under /content/commerce/sales/{orders,lines}/index. This seeder therefore
 * NEVER writes a fact node; it only stages pending markers ({@link SalesFacts#writePending} — no commit,
 * the caller batches the commit) and lets the drainer drain the whole backlog across its timer ticks
 * (sweepSalesFacts.groovy's time-budget loop explicitly anticipates "the one-time backfill seeds
 * the whole order history at once"). Keeping the seed enqueue-only is what makes it safe to re-run.
 *
 * Walk: reuses {@link OrderMimeTypeMigration#eachOrderFile} (raw → year → month → order_*.json via
 * {@link Migrations#children}). The order id is derived from the node name (order_{id}.json → strip
 * prefix/suffix, then {@link Api#legacyId} for safety); non order_{digits}.json names are skipped.
 *
 * Dedup: an order can be mirrored under TWO month folders (paid-month vs created_at-month fold), so an
 * in-run HashSet of order ids guarantees each distinct id is enqueued at most ONCE per run (the drainer's
 * {@link SalesFacts#resolveOrderBody} then picks the components-complete body among the duplicates).
 *
 * Batching: markers are STAGED (no commit) and the session is committed every {@link #COMMIT_BATCH}
 * markers, like a bulk import, with the remainder committed at the end.
 *
 * Resume design — FULL RE-WALK (no fragile cursor). A killed run simply RE-WALKS from the start on the
 * next trigger: enqueue is idempotent ({@link SalesFacts#writePending} upserts the same {order_id}.json
 * marker) and the drainer is idempotent recompute-from-source, so re-staging an already-staged (or
 * already-drained) order is a no-op — safer than persisting a cursor that could point past a partially
 * scanned folder. Progress IS still persisted to {@link #STATE_PATH} (scanned / enqueued / distinctOrders
 * / timestamps) so the GET endpoint can show live progress and remaining, but it is a REPORT, not a
 * resume pointer: the walk never skips anything based on it. (This is the "simplest correct design" the
 * backfill task allows; a folder-level cursor is a documented "plus" that we deliberately skip for safety.)
 *
 * The JCR methods are DEFENSIVE: one unreadable order file, one failed marker or one failed commit must
 * never stop the seed, and bookkeeping never throws. Lives under /content/WEB-INF/classes; use via
 * {@code import commerce.SalesFactBackfill}.
 */
class SalesFactBackfill {

    /** Progress/report doc — a REPORT for the GET endpoint, not a resume cursor. */
    static final String STATE_PATH = "/content/commerce/sales/backfill-state.json"

    /** Commit every N staged markers (bulk-import cadence). */
    static final int COMMIT_BATCH = 300

    static final String STATUS_RUNNING = "running"
    static final String STATUS_DONE    = "done"
    static final String STATUS_IDLE     = "idle"

    // order_{digits}.json — the mirrored order file naming (Order id is the numeric tail).
    private static final java.util.regex.Pattern ORDER_FILE = ~/^order_(\d+)\.json$/

    /**
     * Walk EVERY order file, enqueue each DISTINCT order for a fact recompute (enqueue-only — never
     * writes a fact node), committing staged markers every {@link #COMMIT_BATCH}. Persists progress to
     * {@link #STATE_PATH} at start, on every batch commit and at the end. Defensive: never throws; a bad
     * order file is skipped. Returns a summary Map [scanned, enqueued, distinctOrders].
     */
    static Map seed(session, log) {
        String startedAt = Api.now()
        Set seen = new HashSet()
        int scanned = 0
        int enqueued = 0
        int sinceCommit = 0

        // Announce "running" up front so the GET endpoint reflects an in-flight seed immediately.
        writeState(session, log, STATUS_RUNNING, startedAt, scanned, enqueued, seen.size(), null)

        try {
            OrderMimeTypeMigration.eachOrderFile(session) { res ->
                try {
                    def name = res.getName()
                    def m = (name == null) ? null : ORDER_FILE.matcher(name)
                    if (m == null || !m.matches()) return          // not an order_{digits}.json file
                    scanned++
                    def id = Api.legacyId(m.group(1))              // numeric tail; legacyId is a safety no-op here
                    if (id == null || seen.contains(id)) return    // paid-month vs created_at-month dup → once
                    seen.add(id)
                    if (SalesFacts.writePending(session, log, id)) {   // stage marker (NO commit)
                        enqueued++
                        sinceCommit++
                    }
                    if (sinceCommit >= COMMIT_BATCH) {
                        commit(session, log)                       // flush the staged markers
                        sinceCommit = 0
                        writeState(session, log, STATUS_RUNNING, startedAt, scanned, enqueued, seen.size(), null)
                    }
                } catch (Exception e) {
                    // One bad order file must not stop the seed.
                    try { log.warn("SalesFactBackfill.seed: ${safePath(res)}: ${e.message}") } catch (Exception ignore) {}
                }
            }
        } catch (Exception e) {
            // A failure in the walk itself is swallowed: the committed markers stand, and a re-run
            // (full re-walk, idempotent) resumes. Never throw from the seed.
            try { log.warn("SalesFactBackfill.seed: walk aborted: ${e.message}") } catch (Exception ignore) {}
        }

        // Commit the remainder, then record completion.
        commit(session, log)
        writeState(session, log, STATUS_DONE, startedAt, scanned, enqueued, seen.size(), Api.now())
        try {
            log.info("SalesFactBackfill.seed: scanned ${scanned}, enqueued ${enqueued} distinct order(s)")
        } catch (Exception ignore) {}

        return [scanned: scanned, enqueued: enqueued, distinctOrders: seen.size()]
    }

    /**
     * Progress for the GET endpoint: the {@link #STATE_PATH} report plus the LIVE remaining count
     * (SalesFacts.pendingOrderIds(session).size()). Numbers are normalized via {@link Api#count};
     * timestamps are already ms-ISO (written by {@link Api#now}). Never throws.
     */
    static Map progress(session) {
        def doc = Jcr.readMap(session, STATE_PATH)
        long remaining = 0L
        try { remaining = SalesFacts.pendingOrderIds(session).size() } catch (Exception ignore) {}
        def out = new LinkedHashMap()
        out.status         = (doc?.status ?: STATUS_IDLE).toString()
        out.scanned        = Api.count(doc?.scanned) ?: 0L
        out.enqueued       = Api.count(doc?.enqueued) ?: 0L
        out.distinctOrders = Api.count(doc?.distinctOrders) ?: 0L
        out.remaining      = remaining
        out.startedAt      = doc?.startedAt
        out.updatedAt      = doc?.updatedAt
        out.finishedAt     = doc?.finishedAt
        return out
    }

    // --- Helpers ---------------------------------------------------------------

    /** Commit the session; on failure roll back and log, never throw (a lost batch re-stages on re-run). */
    private static void commit(session, log) {
        try {
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("SalesFactBackfill: batch commit failed: ${e.message}") } catch (Exception ignore) {}
        }
    }

    /** Persist the progress/report doc (its own commit). Defensive — bookkeeping never breaks the seed. */
    private static void writeState(session, log, String status, String startedAt,
                                   int scanned, int enqueued, int distinct, String finishedAt) {
        try {
            def res = Jcr.getOrCreateFile(session, STATE_PATH)
            def doc = new LinkedHashMap()
            doc.status         = status
            doc.scanned        = scanned
            doc.enqueued       = enqueued
            doc.distinctOrders = distinct
            doc.startedAt      = startedAt
            doc.updatedAt      = Api.now()
            doc.finishedAt     = finishedAt
            res.write(Jcr.toJson(doc))
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("SalesFactBackfill.writeState: ${e.message}") } catch (Exception ignore) {}
        }
    }

    private static String safePath(res) {
        try { return res.getPath() } catch (Exception ignore) { return "?" }
    }
}
