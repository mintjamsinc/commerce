package commerce.migration

import com.fasterxml.jackson.databind.ObjectMapper
import javax.jcr.query.Query

import commerce.SalesFacts
import commerce.SalesReconcile

/**
 * One-time migration: stamp the A′ reconciliation props ({@code commerce:refund_reconciled},
 * {@code commerce:refund_recon_delta_base}, {@code commerce:refund_transactionless_value}) onto refund raw
 * nodes that predate them. Values are DERIVED from the stored refund BODY via {@link commerce.SalesReconcile}
 * (cash transactions vs the returned-value decomposition), so no Shopify call is needed and the derivation
 * matches what the webhook / backfill writers stamp today.
 *
 * This re-drain is also the first time A′ runs over the REAL refunds end-to-end: a ring (returned value ≠
 * cash refunded) is WARNed here (e.g. a restocking fee the store kept), the same as the live webhook path.
 * Nodes already carrying the marker are skipped. Idempotent and defensive: one bad node never stops the run.
 *
 * Lives under /content/WEB-INF/classes; registered in {@link commerce.migration.Migrations}.
 */
class RefundReconMigration {

    static final String REFUNDS_RAW_DIR = SalesFacts.REFUNDS_RAW_DIR

    private static final ObjectMapper MAPPER = new ObjectMapper()
    private static final int COMMIT_BATCH = 100

    /** Returns a summary map [ok, checked, stamped, skipped, rings, transactionless]. Never throws. */
    static Map run(session, log) {
        int checked = 0, stamped = 0, skipped = 0, rings = 0, transactionless = 0, sinceCommit = 0
        try {
            def stmt = "/jcr:root${REFUNDS_RAW_DIR}//element(*, nt:file)".toString()
            def q = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
            def rs = q.execute().getResources()
            (rs ?: []).each { res ->
                try {
                    def name = res.getName()
                    if (name == null || !name.startsWith("refund_") || !name.endsWith(".json")) return
                    checked++
                    if (res.hasProperty(SalesReconcile.P_RECONCILED)) { skipped++; return }
                    def body = MAPPER.readValue(res.content?.toString() ?: "{}", Map.class)
                    def recon = SalesReconcile.reconProps(body)
                    recon.props.each { k, v ->
                        if (v instanceof Boolean) res.setProperty(k.toString(), (boolean) v)
                        else if (v != null) res.setProperty(k.toString(), (BigDecimal) v)
                    }
                    def rc = recon.reconcile
                    def base = SalesReconcile.baseCurrencyOf(body)
                    if (rc.currency != null && base != null && rc.currency != base) {
                        try { log.warn("RefundReconMigration: A' ${res.getPath()} cross-currency (${rc.currency} != base ${base}) - cash anchor is native, base ladder has no anchor") } catch (Exception ignore) {}
                    }
                    if (rc.rings) {
                        rings++
                        try { log.warn("RefundReconMigration: A' ${res.getPath()} residual ${rc.delta} (returned ${rc.refundExpected} vs cash ${rc.cash})") } catch (Exception ignore) {}
                    } else if (rc.classification == SalesReconcile.TRANSACTIONLESS_WITH_VALUE) {
                        transactionless++
                        try { log.warn("RefundReconMigration: A' ${res.getPath()} transactionless with value ${rc.refundExpected}") } catch (Exception ignore) {}
                    }
                    stamped++
                    if (++sinceCommit >= COMMIT_BATCH) { session.commit(); sinceCommit = 0 }
                } catch (Exception e) {
                    try { log.warn("RefundReconMigration: ${res?.getPath()}: ${e.message}") } catch (Exception ignore) {}
                }
            }
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("RefundReconMigration: ${e.message}") } catch (Exception ignore) {}
            return [ok: false, checked: checked, stamped: stamped, skipped: skipped, rings: rings, transactionless: transactionless]
        }
        try { log.info("RefundReconMigration: checked ${checked}, stamped ${stamped}, skipped ${skipped}, rings ${rings}, transactionless ${transactionless}") } catch (Exception ignore) {}
        return [ok: true, checked: checked, stamped: stamped, skipped: skipped, rings: rings, transactionless: transactionless]
    }
}
