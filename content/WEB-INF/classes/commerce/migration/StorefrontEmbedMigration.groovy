package commerce.migration

import commerce.Jcr

/**
 * Storefront-embed migration — retire the fixed storefront SPA and the block-page
 * (LP) stores, replaced by the embed toolkit:
 * catalog projection + server GSP templates + the client SDK.
 *
 * Removes (hard delete):
 *   /content/public/commerce/storefront   the ichigo.js SPA
 *   /content/public/commerce/pages        published block pages
 *   /content/public/commerce/landing      published landing page
 *   /content/commerce/pages               the block-page authoring store
 *
 * Verification before deleting: the replacement toolkit must actually be
 * deployed — the SDK asset exists (code deploy) — so a half-rolled-out
 * deployment never deletes the old storefront while offering nothing new.
 * The catalog projection (data) is untouched either way.
 */
class StorefrontEmbedMigration {

    static final String SDK_PATH = "/content/public/commerce/sdk/commerce.js"

    static final List RETIRED_PATHS = [
        "/content/public/commerce/storefront",
        "/content/public/commerce/pages",
        "/content/public/commerce/landing",
        "/content/commerce/pages",
    ]

    static Map run(session, log) {
        // Verify the replacement is present before destroying the old front.
        def sdk = Jcr.safeGet(session, SDK_PATH)
        if (sdk == null || !sdk.exists()) {
            return [ok: false, reason: "embed SDK not deployed yet (${SDK_PATH})"]
        }

        def deleted = []
        def absent = []
        RETIRED_PATHS.each { path ->
            if (Migrations.hardDelete(session, log, path)) {
                deleted << path
            } else {
                absent << path
            }
        }
        return [ok: true, deleted: deleted, alreadyAbsent: absent]
    }
}
