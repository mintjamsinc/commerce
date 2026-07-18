package commerce.migration

import commerce.Jcr

/**
 * Retroactively stamp the dedicated order MIME type on the mirrored order nodes.
 *
 * New ingestion stores order JSON as
 * {@code application/vnd.mintjams.commerce.order+json}
 * (order-paid.xml / order-updated.xml), which is what associates order nodes with
 * the order editor (the Content Browser's MIME→app launch). Orders mirrored
 * before the change still carry {@code application/json}; this migration restamps
 * them. The {@code +json} suffix keeps every JSON consumer working — they parse
 * the body, not the type.
 *
 * Unlike the flat customer/product stores, the order mirror is nested by
 * year/month (/content/commerce/orders/raw/{yyyy}/{MM}/order_{id}.json), so this
 * migration recurses raw → year → month → order_*.json files.
 *
 * Idempotent: already-stamped nodes are skipped. Verification: no order file left
 * with a different MIME type. Nothing is deleted.
 */
class OrderMimeTypeMigration {

    static final String ORDERS_RAW_DIR = "/content/commerce/orders/raw"
    static final String ORDER_MIME = "application/vnd.mintjams.commerce.order+json"

    static Map run(session, log) {
        int stamped = 0, skipped = 0, failed = 0
        eachOrderFile(session) { res ->
            try {
                def current = null
                try {
                    if (res.hasProperty("jcr:mimeType")) current = res.getProperty("jcr:mimeType").getValue()?.toString()
                } catch (Exception ignore) {}
                if (current == ORDER_MIME) {
                    skipped++
                    return
                }
                res.setProperty("jcr:mimeType", ORDER_MIME)
                session.commit()
                stamped++
            } catch (Exception e) {
                try { session.rollback() } catch (Exception ignore) {}
                failed++
                try { log.warn("m007: ${res.getPath()}: ${e.message}") } catch (Exception ignore) {}
            }
        }

        // Verify: every order file now carries the order MIME type.
        int wrong = 0
        eachOrderFile(session) { res ->
            try {
                def m = res.hasProperty("jcr:mimeType") ? res.getProperty("jcr:mimeType").getValue()?.toString() : null
                if (m != ORDER_MIME) wrong++
            } catch (Exception ignore) {}
        }
        return [ok: wrong == 0, stamped: stamped, alreadyStamped: skipped, failed: failed, remaining: wrong]
    }

    /**
     * Walk the nested order mirror (raw → year → month → order_*.json) and hand
     * each order file resource to the closure. Defensive: a missing raw root or
     * any unreadable intermediate folder is simply skipped (Migrations.children
     * returns an empty list on error).
     */
    static void eachOrderFile(session, Closure body) {
        def raw = Jcr.safeGet(session, ORDERS_RAW_DIR)
        Migrations.children(raw).each { year ->
            Migrations.children(year).each { month ->
                Migrations.children(month).each { res ->
                    if (!res.getName().endsWith(".json")) return
                    body(res)
                }
            }
        }
    }
}
