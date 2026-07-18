package commerce

import javax.jcr.query.Query

/**
 * Manual business-data purge — permanently deletes order / payment / refund mirror
 * records older than a caller-supplied age (in days). Triggered by an operator from
 * the Commerce app (Maintenance > Retention); NEVER runs automatically.
 *
 * Each store is filtered by its own index-backed business-date axis:
 *   orders   /content/commerce/orders/raw    → commerce:ordered_at
 *   payments /content/commerce/payments/raw   → commerce:paid_at
 *   refunds  /content/commerce/refunds/raw    → commerce:refunded_at
 *
 * Only the raw mirror stores are purged; the derived sales-fact index is left
 * intact (it is a recomputed aggregate, out of the retention scope). Deletes commit
 * in batches so a large purge never holds one giant transaction. run() writes a
 * {@link MaintenanceAudit} record (who / when / days / counts). Defensive — a bad
 * node is skipped, never fatal.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.Purge}.
 */
class Purge {

    private static final long DAY_MS = 86_400_000L
    private static final int COMMIT_BATCH = 200

    /** The purgeable stores, each with its index-backed business-date property. */
    private static final List STORES = [
        [key: "orders",   dir: Orders.STORE_DIR,               dateProp: "commerce:ordered_at"],
        [key: "payments", dir: PaymentMirror.PAYMENTS_RAW_DIR,  dateProp: "commerce:paid_at"],
        [key: "refunds",  dir: RefundMirror.REFUNDS_RAW_DIR,    dateProp: "commerce:refunded_at"],
    ]

    static String cutoffIso(int days, long nowMs) {
        return Api.instant(nowMs - (long) days * DAY_MS)
    }

    /** Count-only preview: how many records each store would delete. No writes. */
    static Map preview(session, int days) {
        String iso = cutoffIso(days, System.currentTimeMillis())
        def out = [cutoff: iso]
        STORES.each { s -> out[s.key] = countOlder(session, s.dir.toString(), s.dateProp.toString(), iso) }
        return out
    }

    /**
     * Perform the delete and write an audit record. actor is the operator's user id
     * (writes carry that identity via the session). Returns the deleted counts +
     * cutoff. Any store-level failure is contained; the audit still records what ran.
     */
    static Map run(session, log, int days, String actor) {
        String iso = cutoffIso(days, System.currentTimeMillis())
        def counts = [cutoff: iso, days: days]
        STORES.each { s -> counts[s.key] = deleteOlder(session, log, s.dir.toString(), s.dateProp.toString(), iso) }
        try {
            MaintenanceAudit.recordPurge(session, log, actor, days, iso,
                (int) (counts.orders ?: 0), (int) (counts.payments ?: 0), (int) (counts.refunds ?: 0), "ok", null)
        } catch (Exception e) {
            try { log.warn("Purge.run: audit failed: ${e.message}") } catch (Exception ignore) {}
        }
        return counts
    }

    // --- Internals ---------------------------------------------------------------

    private static int countOlder(session, String dir, String dateProp, String cutoffIso) {
        try {
            def base = session.getResource(dir)
            if (base == null || !base.exists()) return 0
            def rs = query(session, dir, dateProp, cutoffIso)
            int n = 0
            if (rs != null) rs.each { n++ }
            return n
        } catch (Exception e) { return 0 }
    }

    private static int deleteOlder(session, log, String dir, String dateProp, String cutoffIso) {
        int removed = 0
        try {
            def base = session.getResource(dir)
            if (base == null || !base.exists()) return 0
            def rs = query(session, dir, dateProp, cutoffIso)
            int inBatch = 0
            if (rs != null) {
                rs.each { res ->
                    try {
                        if (res != null && res.exists()) {
                            res.remove(); removed++; inBatch++
                            if (inBatch >= COMMIT_BATCH) { session.commit(); inBatch = 0 }
                        }
                    } catch (Exception ignore) {}
                }
            }
            if (inBatch > 0) session.commit()
            if (removed > 0) log.info("Purge: ${dir} removed ${removed} record(s) older than ${cutoffIso}")
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Purge.deleteOlder(${dir}): ${e.message}") } catch (Exception ignore) {}
        }
        return removed
    }

    // Cast the LITERAL (not the property) to xs:dateTime — the property is a real
    // JCR Date, so the string bound must be promoted for a date-typed comparison.
    private static query(session, String dir, String dateProp, String cutoffIso) {
        def stmt = "/jcr:root${dir}//element(*, nt:file)[@${dateProp} <= xs:dateTime('${cutoffIso}')]".toString()
        def q = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
        return q.execute().getResources()
    }
}
