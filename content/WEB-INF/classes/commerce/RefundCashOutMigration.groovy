package commerce

import com.fasterxml.jackson.databind.ObjectMapper
import javax.jcr.query.Query

/**
 * One-time migration: backfill the cash-out (refunds block) props on existing refund nodes —
 * {@code refunded_day} (facet axis), {@code refund_returns_base} / {@code refund_returns_shipping_base} /
 * {@code refund_restocking_fee_income_base} (breakdown, via {@link commerce.SalesReconcile#reconProps}),
 * and {@code refund_ordered_at} (parent order's ordered_at, for the crossPeriod flag) — and recompute each
 * refund-bearing order so the renamed {@code restocking_fee_income_base} lands and the pre-rename
 * {@code restocking_fee_base} is cleared. The read layer dual-reads both names, so pl.restockingFees is
 * correct throughout; this migration is the cleanup that converges the old name to zero.
 *
 * Defensive: one bad node never stops the run. Lives under /content/WEB-INF/classes; registered in
 * {@link commerce.Migrations}.
 */
class RefundCashOutMigration {

    static final String REFUNDS_RAW_DIR = SalesFacts.REFUNDS_RAW_DIR

    private static final ObjectMapper MAPPER = new ObjectMapper()
    private static final int COMMIT_BATCH = 100

    /** Returns a summary map [ok, refunds, orders]. Never throws. */
    static Map run(session, log) {
        int refunds = 0, orders = 0, sinceCommit = 0
        def orderIds = new LinkedHashSet()
        try {
            def rs = session.getWorkspace().getQueryManager()
                .createQuery("/jcr:root${REFUNDS_RAW_DIR}//element(*, nt:file)".toString(), Query.XPATH)
                .execute().getResources()
            (rs ?: []).each { res ->
                try {
                    def name = res.getName()
                    if (name == null || !name.startsWith("refund_") || !name.endsWith(".json")) return
                    def body = MAPPER.readValue(res.content?.toString() ?: "{}", Map.class)
                    SalesReconcile.reconProps(body).props.each { k, v ->
                        if (v instanceof Boolean) res.setProperty(k.toString(), (boolean) v)
                        else if (v != null) res.setProperty(k.toString(), (BigDecimal) v)
                    }
                    def refundedMs = Api.epochMs(body.created_at)
                    if (refundedMs != null) res.setProperty("commerce:refunded_day", SalesReconcile.dayOf(refundedMs))
                    def orderedAt = RefundMirror.orderedAtOf(session, body.order_id)
                    if (orderedAt != null) res.setProperty("commerce:refund_ordered_at", new java.util.Date(orderedAt))
                    def oid = (body.order_id != null) ? Api.legacyId(body.order_id) : null
                    if (oid != null) orderIds << oid.toString()
                    refunds++
                    if (++sinceCommit >= COMMIT_BATCH) { session.commit(); sinceCommit = 0 }
                } catch (Exception e) {
                    try { log.warn("RefundCashOutMigration refund ${res?.getPath()}: ${e.message}") } catch (Exception ignore) {}
                }
            }
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("RefundCashOutMigration refunds: ${e.message}") } catch (Exception ignore) {}
            return [ok: false, refunds: refunds, orders: orders]
        }
        // Recompute refund-bearing orders: writes restocking_fee_income_base, clears the pre-rename name.
        orderIds.each { oid ->
            try { if (SalesFacts.recompute(session, log, oid)) orders++ }
            catch (Exception e) { try { log.warn("RefundCashOutMigration order ${oid}: ${e.message}") } catch (Exception ignore) {} }
        }
        try { log.info("RefundCashOutMigration: refunds ${refunds}, orders ${orders}") } catch (Exception ignore) {}
        return [ok: true, refunds: refunds, orders: orders]
    }
}
