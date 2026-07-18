package commerce.migration

import commerce.Jcr

/**
 * Customer MIME type migration — retroactively stamp the dedicated customer
 * MIME type on the mirrored customer nodes.
 *
 * New ingestion stores customer JSON as
 * {@code application/vnd.mintjams.commerce.customer+json}
 * (Customers.upsertFromWebhook), which is what associates customer nodes with the
 * customer editor (the Content Browser's MIME→app launch). Customers mirrored
 * before the change still carry {@code application/json}; this migration restamps
 * them. The {@code +json} suffix keeps every JSON consumer working — they parse
 * the body, not the type.
 *
 * Idempotent: already-stamped nodes are skipped. Verification: no customer file
 * left with a different MIME type. Nothing is deleted.
 */
class CustomerMimeTypeMigration {

    static final String CUSTOMERS_DIR = "/content/commerce/customers"
    static final String CUSTOMER_MIME = "application/vnd.mintjams.commerce.customer+json"

    static Map run(session, log) {
        int stamped = 0, skipped = 0, failed = 0
        def dir = Jcr.safeGet(session, CUSTOMERS_DIR)
        Migrations.children(dir).each { res ->
            try {
                if (!res.getName().endsWith(".json")) return
                def current = null
                try {
                    if (res.hasProperty("jcr:mimeType")) current = res.getProperty("jcr:mimeType").getValue()?.toString()
                } catch (Exception ignore) {}
                if (current == CUSTOMER_MIME) {
                    skipped++
                    return
                }
                res.setProperty("jcr:mimeType", CUSTOMER_MIME)
                session.commit()
                stamped++
            } catch (Exception e) {
                try { session.rollback() } catch (Exception ignore) {}
                failed++
                try { log.warn("m006: ${res.getPath()}: ${e.message}") } catch (Exception ignore) {}
            }
        }

        // Verify: every customer file now carries the customer MIME type.
        int wrong = 0
        Migrations.children(Jcr.safeGet(session, CUSTOMERS_DIR)).each { res ->
            try {
                if (!res.getName().endsWith(".json")) return
                def m = res.hasProperty("jcr:mimeType") ? res.getProperty("jcr:mimeType").getValue()?.toString() : null
                if (m != CUSTOMER_MIME) wrong++
            } catch (Exception ignore) {}
        }
        return [ok: wrong == 0, stamped: stamped, alreadyStamped: skipped, failed: failed, remaining: wrong]
    }
}
