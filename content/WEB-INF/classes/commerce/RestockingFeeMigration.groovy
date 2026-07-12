package commerce

import com.fasterxml.jackson.databind.ObjectMapper
import javax.jcr.query.Query

/**
 * One-time re-drain. The refund reconcile DEFINITIONS changed: the restocking fee (refund_discrepancy) is
 * now folded into the expected cash (so A′ measures cash, and stays silent on the fee), and
 * {@link commerce.Refunds#amountBase} no longer adds the discrepancy. So the stamped refund props and the
 * new order-grain {@code commerce:restocking_fee_base} must be recomputed for existing data. Unlike an
 * earlier migration that only stamped values that were absent, this re-stamps UNCONDITIONALLY (the old
 * values are stale, not absent). Idempotent under the new definitions; defensive — one bad node never
 * stops the run.
 *
 * Two passes:
 *   refunds — re-derive refund_amount_base / refund_tax_base / reconcile props from each stored body.
 *             A ring now means an UNACCOUNTED adjustment (the restocking fee is no longer a ring).
 *   orders  — recompute each refund-bearing order's sales fact so restocking_fee_base lands on it.
 *
 * Lives under /content/WEB-INF/classes; registered in {@link commerce.Migrations}.
 */
class RestockingFeeMigration {

    static final String REFUNDS_RAW_DIR = SalesFacts.REFUNDS_RAW_DIR

    private static final ObjectMapper MAPPER = new ObjectMapper()
    private static final int COMMIT_BATCH = 100

    /** Returns a summary map [ok, refunds, orders, rings]. Never throws. */
    static Map run(session, log) {
        int refunds = 0, rings = 0, orders = 0, sinceCommit = 0
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
                    def ab = Refunds.amountBase(body)
                    if (ab != null) res.setProperty("commerce:refund_amount_base", (BigDecimal) ab)
                    def tb = Refunds.taxBase(body)
                    if (tb != null) res.setProperty("commerce:refund_tax_base", (BigDecimal) tb)
                    SalesReconcile.reconProps(body).props.each { k, v ->
                        if (v instanceof Boolean) res.setProperty(k.toString(), (boolean) v)
                        else if (v != null) res.setProperty(k.toString(), (BigDecimal) v)
                    }
                    def rc = SalesReconcile.refundReconcile(body)
                    if (rc.rings) {
                        rings++
                        try { log.warn("RestockingFeeMigration: A' ${res.getPath()} residual ${rc.delta} (returned ${rc.returnedValue} fee ${rc.restockingFee} vs cash ${rc.cash})") } catch (Exception ignore) {}
                    }
                    def oid = (body.order_id != null) ? Api.legacyId(body.order_id) : null
                    if (oid != null) orderIds << oid.toString()
                    refunds++
                    if (++sinceCommit >= COMMIT_BATCH) { session.commit(); sinceCommit = 0 }
                } catch (Exception e) {
                    try { log.warn("RestockingFeeMigration refund ${res?.getPath()}: ${e.message}") } catch (Exception ignore) {}
                }
            }
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("RestockingFeeMigration refunds: ${e.message}") } catch (Exception ignore) {}
            return [ok: false, refunds: refunds, orders: orders, rings: rings]
        }
        // Recompute the refund-bearing orders so restocking_fee_base is stamped on the order fact.
        orderIds.each { oid ->
            try { if (SalesFacts.recompute(session, log, oid)) orders++ }
            catch (Exception e) { try { log.warn("RestockingFeeMigration order ${oid}: ${e.message}") } catch (Exception ignore) {} }
        }
        try { log.info("RestockingFeeMigration: refunds ${refunds}, rings ${rings}, orders ${orders}") } catch (Exception ignore) {}
        return [ok: true, refunds: refunds, orders: orders, rings: rings]
    }
}
