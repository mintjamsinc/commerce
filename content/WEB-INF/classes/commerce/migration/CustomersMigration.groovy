package commerce.migration

import commerce.Customers
import commerce.Gdpr
import commerce.Jcr

/**
 * Customer store migration — build the first-class customer store and retire
 * the legacy CRM rollup + generic customer mirror.
 *
 * The legacy CRM records (crm/customers) were DERIVED data — a full-order-scan
 * rollup with the multi-currency bug. Rather than copy flawed numbers, this
 * migration rebuilds the profile store from the source of truth (the mirror) and
 * drops the derived rollup entirely (lifetime figures now come from the Shopify
 * mirror body, not from recomputed props):
 *
 *   1. seed profiles  — entities/{src}/customers/*.json (the dormant generic
 *                        mirror) → Customers.upsertFromWebhook (body + typed
 *                        profile props, consent flag included)
 *   2. verify         — every legacy CRM record and every mirrored customer
 *                        must be represented in the new store (id key, or the
 *                        email folded into a member record)
 *   3. hard delete    — crm/customers and entities/{src}/customers
 *
 * Idempotent: re-running re-seeds the same profiles. On verification failure the
 * migration returns ok:false so the framework retries next boot.
 */
class CustomersMigration {

    static Map run(session, log) {
        int profiles = 0

        // --- 1. Seed profiles from the generic customer mirror --------------------
        def mirrorDirs = []
        def entities = Jcr.safeGet(session, Gdpr.ENTITIES_DIR)
        Migrations.children(entities).each { srcFolder ->
            def dir = Jcr.safeGet(session, "${srcFolder.getPath()}/customers")
            if (dir == null || !dir.exists()) return
            mirrorDirs << dir.getPath()
            Migrations.children(dir).each { res ->
                try {
                    if (!res.getName().endsWith(".json")) return
                    def body = res.content?.toString()
                    def customer = Jcr.parseMap(body)
                    if (customer?.id == null) return
                    if (Customers.upsertFromWebhook(session, log, body, customer) != null) profiles++
                } catch (Exception e) {
                    try { log.warn("m001: seed ${res.getPath()}: ${e.message}") } catch (Exception ignore) {}
                }
            }
        }

        // --- 2. Verify --------------------------------------------------------------
        def missing = []
        Migrations.children(Jcr.safeGet(session, "/content/commerce/crm/customers")).each { res ->
            try {
                if (!res.getName().endsWith(".json")) return
                def legacy = Jcr.parseMap(res.content?.toString())
                if (!covered(session, legacy?.customerId?.toString(), legacy?.email?.toString())) {
                    missing << res.getName()
                }
            } catch (Exception ignore) {}
        }
        mirrorDirs.each { dirPath ->
            Migrations.children(Jcr.safeGet(session, dirPath)).each { res ->
                try {
                    if (!res.getName().endsWith(".json")) return
                    def id = Jcr.parseMap(res.content?.toString())?.id?.toString()
                    if (id != null && !covered(session, id, null)) missing << "${dirPath}/${res.getName()}".toString()
                } catch (Exception ignore) {}
            }
        }
        if (!missing.isEmpty()) {
            return [ok: false, reason: "uncovered legacy records", missing: missing.take(20), profiles: profiles]
        }

        // --- 3. Hard delete the retired stores --------------------------------------
        int deleted = 0
        if (Migrations.hardDelete(session, log, "/content/commerce/crm/customers")) deleted++
        mirrorDirs.each { if (Migrations.hardDelete(session, log, it)) deleted++ }

        return [ok: true, profiles: profiles, deletedPaths: deleted]
    }

    /** Is this legacy identity represented in the new store? */
    private static boolean covered(session, String customerId, String email) {
        if (customerId) {
            def res = Jcr.safeGet(session, Customers.pathFor("customer_${customerId}".toString()))
            if (res != null && res.exists()) return true
        }
        if (email) {
            return Customers.findByEmail(session, email) != null
        }
        // Neither id nor email — nothing identifiable to carry over.
        return customerId == null && email == null
    }
}
