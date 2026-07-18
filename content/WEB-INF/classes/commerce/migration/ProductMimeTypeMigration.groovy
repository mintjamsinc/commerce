package commerce.migration

import commerce.Jcr

/**
 * One-time migration: retroactively stamp the dedicated product MIME type
 * on the mirrored product nodes.
 *
 * New ingestion stores product JSON as
 * {@code application/vnd.mintjams.commerce.product+json} (product-update.xml),
 * which is what associates product nodes with the product editor (the Content
 * Browser's MIME→app launch). Products mirrored before the change still carry
 * {@code application/json}; this migration restamps them. The {@code +json}
 * suffix keeps every JSON consumer working — they parse the body, not the type.
 *
 * Idempotent: already-stamped nodes are skipped. Verification: no product file
 * left with a different MIME type. Nothing is deleted.
 */
class ProductMimeTypeMigration {

    static final String PRODUCTS_DIR = "/content/commerce/products"
    static final String PRODUCT_MIME = "application/vnd.mintjams.commerce.product+json"

    static Map run(session, log) {
        int stamped = 0, skipped = 0, failed = 0
        def dir = Jcr.safeGet(session, PRODUCTS_DIR)
        Migrations.children(dir).each { res ->
            try {
                if (!res.getName().endsWith(".json")) return
                def current = null
                try {
                    if (res.hasProperty("jcr:mimeType")) current = res.getProperty("jcr:mimeType").getValue()?.toString()
                } catch (Exception ignore) {}
                if (current == PRODUCT_MIME) {
                    skipped++
                    return
                }
                res.setProperty("jcr:mimeType", PRODUCT_MIME)
                session.commit()
                stamped++
            } catch (Exception e) {
                try { session.rollback() } catch (Exception ignore) {}
                failed++
                try { log.warn("m002: ${res.getPath()}: ${e.message}") } catch (Exception ignore) {}
            }
        }

        // Verify: every product file now carries the product MIME type.
        int wrong = 0
        Migrations.children(Jcr.safeGet(session, PRODUCTS_DIR)).each { res ->
            try {
                if (!res.getName().endsWith(".json")) return
                def m = res.hasProperty("jcr:mimeType") ? res.getProperty("jcr:mimeType").getValue()?.toString() : null
                if (m != PRODUCT_MIME) wrong++
            } catch (Exception ignore) {}
        }
        return [ok: wrong == 0, stamped: stamped, alreadyStamped: skipped, failed: failed, remaining: wrong]
    }
}
