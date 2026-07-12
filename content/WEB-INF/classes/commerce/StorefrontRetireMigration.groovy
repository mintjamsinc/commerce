package commerce

/**
 * Storefront-retire migration — retire the public catalog PROJECTION.
 *
 * The self-EC toolkit was simplified: the pre-built public projection under /content/public/commerce/catalog/
 * (index.json / products/{id}.json / inventory.json / store.json) is gone, replaced by
 * an ON-DEMAND sanitized read endpoint (content/public/commerce/endpoints/catalog.groovy),
 * which reads the admin mirror directly with the caller's session (everyone has /content
 * READ) and sanitizes per request. The projection publisher (publishCatalog.groovy /
 * publishInventory.groovy), the server GSP
 * templates and the commerce-publish app were removed at deploy; this migration hard-
 * deletes the stale projection DATA left in the repository.
 *
 * Verification before deleting: the replacement read endpoint must actually be deployed
 * (a half-rolled-out deploy never removes the old projection while offering nothing new).
 * The SDK asset (sdk/commerce.js) is untouched — it is replaced in place, not deleted.
 */
class StorefrontRetireMigration {

    static final String READ_ENDPOINT = "/content/public/commerce/endpoints/catalog.groovy"
    static final String CATALOG_PROJECTION = "/content/public/commerce/catalog"

    static Map run(session, log) {
        // Verify the replacement read endpoint is present before deleting the projection.
        def ep = Jcr.safeGet(session, READ_ENDPOINT)
        if (ep == null || !ep.exists()) {
            return [ok: false, reason: "catalog read endpoint not deployed yet (${READ_ENDPOINT})"]
        }
        boolean deleted = Migrations.hardDelete(session, log, CATALOG_PROJECTION)
        return [ok: true,
                deleted      : deleted ? [CATALOG_PROJECTION] : [],
                alreadyAbsent: deleted ? [] : [CATALOG_PROJECTION]]
    }
}
