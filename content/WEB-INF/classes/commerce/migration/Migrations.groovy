package commerce.migration

import commerce.Api
import commerce.Jcr

/**
 * Boot-time one-shot migration framework.
 *
 * Deploy-time data reshaping ("move the customer store", "stamp a MIME type",
 * "delete a retired public path") must not be left to operators. Each migration
 * runs ONCE per repository, automatically, shortly after the routes come up:
 *
 *   etc/eip/routes/commerce/migration.xml   timer repeatCount=1 (per boot)
 *     → runMigrations.groovy                task lock (one execution)
 *         → Migrations.runAll(session, log) ordered registry + JCR markers
 *
 * "Once" is enforced in two layers: the timer fires once per BOOT, and a JCR
 * marker (/content/commerce/migrations/{id}.json) makes each migration run once
 * per REPOSITORY — a redeploy re-fires the timer but every marked migration is
 * skipped.
 *
 * Contract for a migration entry:
 *   - `run` MUST be idempotent (it may be re-attempted after a partial failure)
 *   - migrate → verify → hard delete: destructive cleanup only happens after
 *     the migration verified its own result. On verification failure the entry
 *     returns ok:false, NO marker is written, and the next boot retries.
 *   - the returned map is persisted in the marker for audit
 *     ({ ok, migrated, deleted, ... } — free-form counters welcome).
 *
 * Individual migrations live in their own classes (CustomersMigration, …); this
 * class owns the ordered registry and the marker bookkeeping only.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.migration.Migrations}.
 */
class Migrations {

    static final String MARKERS_DIR = "/content/commerce/migrations"

    /**
     * Ordered migration registry. Append-only: new deployments add entries at
     * the end; ids are never reused. Each entry: [id: String, run: Closure
     * (session, log) -> Map (must contain ok: boolean)].
     */
    static List registry() {
        return [
            [id: "m001-customers-store",  run: { s, l -> CustomersMigration.run(s, l) }],
            [id: "m002-product-mimetype", run: { s, l -> ProductMimeTypeMigration.run(s, l) }],
            [id: "m003-storefront-embed", run: { s, l -> StorefrontEmbedMigration.run(s, l) }],
            [id: "m004-property-types",   run: { s, l -> PropertyTypeMigration.run(s, l) }],
            [id: "m005-storefront-retire", run: { s, l -> StorefrontRetireMigration.run(s, l) }],
            [id: "m006-customer-mimetype", run: { s, l -> CustomerMimeTypeMigration.run(s, l) }],
            [id: "m007-order-mimetype",   run: { s, l -> OrderMimeTypeMigration.run(s, l) }],
            [id: "m008-recon-report-props", run: { s, l -> ReconReportPropsMigration.run(s, l) }],
            [id: "m009-refund-tax-prop",  run: { s, l -> RefundTaxPropMigration.run(s, l) }],
            [id: "m010-refund-recon-prop", run: { s, l -> RefundReconMigration.run(s, l) }],
            [id: "m011-restocking-fee",   run: { s, l -> RestockingFeeMigration.run(s, l) }],
            [id: "m012-refund-cashout",   run: { s, l -> RefundCashOutMigration.run(s, l) }],
            // Re-run of m007/m002 under new ids: the ingest routes used to pass the
            // MIME type as a %2B-encoded URI parameter, which Camel double-decodes
            // into a space ("...order json"). Nodes ingested after the original
            // migrations ran carry that corrupted type; the runs are idempotent and
            // restamp anything that differs from the canonical "+json" type.
            [id: "m013-order-mimetype-restamp",   run: { s, l -> OrderMimeTypeMigration.run(s, l) }],
            [id: "m014-product-mimetype-restamp", run: { s, l -> ProductMimeTypeMigration.run(s, l) }],
        ]
    }

    /**
     * Run every unapplied migration in order. Stops at the first failure (later
     * migrations may depend on earlier ones). Defensive: never throws; returns
     * a report [ran: [...], skipped: [...], failed: id|null].
     */
    static Map runAll(session, log) {
        def ran = [], skipped = []
        String failed = null
        for (m in registry()) {
            def id = m.id.toString()
            try {
                if (applied(session, id)) {
                    skipped << id
                    continue
                }
                log.info("Migrations: running ${id}")
                Map result = m.run(session, log)
                if (result?.ok) {
                    writeMarker(session, log, id, result)
                    ran << id
                    log.info("Migrations: ${id} done: ${Jcr.toJson(result)}")
                } else {
                    failed = id
                    log.warn("Migrations: ${id} did not verify (${Jcr.toJson(result ?: [:])}) - no marker written, will retry next boot")
                    break
                }
            } catch (Exception e) {
                try { session.rollback() } catch (Exception ignore) {}
                failed = id
                try { log.warn("Migrations: ${id} failed: ${e.message} - will retry next boot") } catch (Exception ignore) {}
                break
            }
        }
        return [ran: ran, skipped: skipped, failed: failed]
    }

    /** True when the migration's marker exists (already applied on this repository). */
    static boolean applied(session, String id) {
        def res = Jcr.safeGet(session, "${MARKERS_DIR}/${id}.json".toString())
        return res != null && res.exists()
    }

    /** Persist the audit marker; from now on the migration is permanently skipped. */
    static void writeMarker(session, log, String id, Map result) {
        def res = Jcr.getOrCreateFile(session, "${MARKERS_DIR}/${id}.json".toString())
        res.write(Jcr.toJson([id: id, at: Api.now(), result: result]))
        session.commit()
    }

    // --- Shared helpers for migration implementations ---------------------------

    /** Recursively delete a path (hard delete, no trash). Returns whether it was removed. */
    static boolean hardDelete(session, log, String path) {
        try {
            def res = Jcr.safeGet(session, path)
            if (res == null || !res.exists()) {
                return false
            }
            res.remove()
            session.commit()
            return true
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Migrations.hardDelete ${path}: ${e.message}") } catch (Exception ignore) {}
            return false
        }
    }

    /** Children of a resource as a List (defensive; empty on error). */
    static List children(resource) {
        def out = []
        try {
            def it = resource.list()
            while (it.hasNext()) { out << it.next() }
        } catch (Exception ignore) {}
        return out
    }
}
