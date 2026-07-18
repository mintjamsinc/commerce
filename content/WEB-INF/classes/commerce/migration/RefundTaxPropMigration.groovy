package commerce.migration

import com.fasterxml.jackson.databind.ObjectMapper
import javax.jcr.query.Query

import commerce.Refunds
import commerce.SalesFacts

/**
 * One-time migration: stamp {@code commerce:refund_tax_base} onto refund raw nodes that predate the
 * property. The value is DERIVED from the stored refund BODY ({@link commerce.Refunds#taxBase} —
 * refund_line_items[].total_tax, shop_money), so no Shopify call is needed and the derivation matches
 * what the webhook/backfill writers stamp today. Nodes that already carry the property, or whose body
 * carries no tax amounts, are skipped. Idempotent and defensive: one bad node never stops the run.
 *
 * Lives under /content/WEB-INF/classes; registered in {@link commerce.migration.Migrations}.
 */
class RefundTaxPropMigration {

    static final String REFUNDS_RAW_DIR = SalesFacts.REFUNDS_RAW_DIR

    private static final ObjectMapper MAPPER = new ObjectMapper()
    private static final int COMMIT_BATCH = 100

    /** Returns a summary map [ok, checked, stamped, skipped]. Never throws. */
    static Map run(session, log) {
        int checked = 0, stamped = 0, skipped = 0, sinceCommit = 0
        boolean ok = true
        try {
            def stmt = "/jcr:root${REFUNDS_RAW_DIR}//element(*, nt:file)".toString()
            def q = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
            def rs = q.execute().getResources()
            (rs ?: []).each { res ->
                try {
                    def name = res.getName()
                    if (name == null || !name.startsWith("refund_") || !name.endsWith(".json")) return
                    checked++
                    if (res.hasProperty("commerce:refund_tax_base")) { skipped++; return }
                    def body = MAPPER.readValue(res.content?.toString() ?: "{}", Map.class)
                    def taxBase = Refunds.taxBase(body)
                    if (taxBase == null) { skipped++; return }
                    res.setProperty("commerce:refund_tax_base", (BigDecimal) taxBase)
                    stamped++
                    if (++sinceCommit >= COMMIT_BATCH) {
                        session.commit()
                        sinceCommit = 0
                    }
                } catch (Exception e) {
                    try { log.warn("RefundTaxPropMigration: ${res?.getPath()}: ${e.message}") } catch (Exception ignore) {}
                }
            }
            session.commit()
        } catch (Exception e) {
            ok = false
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("RefundTaxPropMigration: ${e.message}") } catch (Exception ignore) {}
        }
        try { log.info("RefundTaxPropMigration: checked ${checked}, stamped ${stamped}, skipped ${skipped}") } catch (Exception ignore) {}
        return [ok: ok, checked: checked, stamped: stamped, skipped: skipped]
    }
}
