package commerce

import javax.jcr.query.Query

/**
 * One-time migration: bring already-written reconciliation run reports in
 * line with the typed queryable properties the index-backed run-history lister
 * (Reconciliation.listRuns) filters and sorts on.
 *
 * The lister needs, on EVERY report: commerce:scope (String), commerce:started_at
 * and commerce:finished_at (real JCR Dates — the range/sort axis), commerce:result
 * (String) and commerce:updated_count (Long). Reports written before this rework
 * fall into two generations:
 *
 *   1. run-history-era reports — carry the row props, but started_at/finished_at
 *      were written as Strings (retype to Date) and scope/updated_count are missing.
 *   2. pre-run-history legacy reports — carry no row props at all; every value is
 *      derived from the report body (with the filename epoch as the timestamp
 *      fallback), the same derivation the old endpoint did per request.
 *
 * Idempotent: a report whose commerce:scope exists AND whose started_at is already
 * a non-String value is skipped, so re-running after a partial failure only touches
 * the remainder. Nothing is deleted; verification is "no node errored".
 */
class ReconReportPropsMigration {

    static Map run(session, log) {
        int scanned = 0, migrated = 0, skipped = 0, errors = 0
        def resources = []
        try {
            def rootRes = Jcr.safeGet(session, Reconciliation.RECON_DIR)
            if (rootRes != null && rootRes.exists()) {
                def stmt = "/jcr:root${Reconciliation.RECON_DIR}//element(*, nt:file)"
                def jq = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
                resources = jq.execute().getResources()
            }
        } catch (Exception e) {
            try { log.warn("m008: query failed: ${e.message}") } catch (Exception ignore) {}
            return [ok: false, error: e.message]
        }

        (resources ?: []).each { res ->
            def name = null
            try {
                name = res.getName()
                // Run reports only — state.json / schedule-state.json live in the same
                // store and must stay untouched.
                if (!(name ==~ /(recon|inventory)_\d+\.json/)) return
                scanned++
                if (alreadyMigrated(res)) {
                    skipped++
                    return
                }

                def body = Jcr.readMap(session, res.getPath())
                long fileMs = Long.parseLong((name =~ /_(\d+)\.json/)[0][1])

                def started = Api.date(body.startedAt) ?: Api.date(body.generatedAt) ?: new java.util.Date(fileMs)
                def finished = Api.date(body.finishedAt) ?: Api.date(body.generatedAt) ?: new java.util.Date(fileMs)
                res.setProperty("commerce:scope", (body.scope ?: Reconciliation.SCOPE_DIFF).toString())
                res.setProperty("commerce:started_at", started)
                res.setProperty("commerce:finished_at", finished)
                res.setProperty("commerce:result", (body.result ?: "success").toString())
                res.setProperty("commerce:updated_count", updatedCount(body))
                if (!res.hasProperty("commerce:created_at")) {
                    res.setProperty("commerce:created_at", new java.util.Date(fileMs))
                }

                migrated++
                if (migrated % 300 == 0) session.commit()
            } catch (Exception e) {
                // No rollback here: it would discard the staged (uncommitted) properties
                // of every report since the last batch commit. A half-stamped node fails
                // alreadyMigrated and is simply re-stamped on the next boot's retry
                // (errors > 0 defers the marker).
                errors++
                try { log.warn("m008: ${name}: ${e.message}") } catch (Exception ignore) {}
            }
        }
        try {
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            errors++
            try { log.warn("m008: commit failed: ${e.message}") } catch (Exception ignore) {}
        }
        return [ok: errors == 0, scanned: scanned, migrated: migrated, skipped: skipped, errors: errors]
    }

    // Migrated = the scope prop exists AND started_at is already a real (non-String)
    // typed value. Reports written by the current writer always satisfy both.
    private static boolean alreadyMigrated(res) {
        if (!res.hasProperty("commerce:scope")) return false
        if (!res.hasProperty("commerce:started_at")) return false
        return !(res.getProperty("commerce:started_at").getValue() instanceof CharSequence)
    }

    // Items the run updated: the stored count, else (oldest legacy diff reports) the
    // unique products with an applied refresh among the diff rows.
    private static long updatedCount(Map body) {
        def direct = body.updated ?: body.refreshedProducts
        if (direct != null) {
            return Api.count(direct) ?: 0L
        }
        def ids = [] as Set
        if (body.diffs instanceof List) {
            body.diffs.each { d -> if (d?.refreshed == "ok" && d.productId != null) ids << d.productId }
        }
        return (long) ids.size()
    }
}
