// Historical sales-fact backfill SEED — CHAINED off the orders backfill.
// Fired by the orders bulk-result importer when an orders-backfill bulk import completes
// (direct:commerce-sales-backfill-seed → this script, run as the service user). There is no
// standalone operator trigger any more; progress stays readable via the sales-backfill endpoint
// (GET only).
//
// Walks the ENTIRE order mirror and ENQUEUES every distinct order for a fact recompute through the
// EXISTING single-writer drainer (SalesFactBackfill.seed → SalesFacts.writePending). It NEVER writes a
// fact node itself — enqueue-only, single-writer discipline: only the sales-fact sweep drainer may write
// /content/commerce/sales/*/index. The seed only STAGES pending markers (batched commits of 300); the
// drainer materializes the whole backlog across its 30s timer ticks (its per-tick time budget is sized
// for exactly this one-time seed).
//
// Cluster-guarded by a DISTINCT lease from the drainer ("commerce-sales-backfill" vs the drainer's
// "commerce-sales-materialize"), so a seed and a drain can run concurrently and a duplicate seed fire is
// coalesced (the second tryLock returns null and this script skips). TTL is sized for a full-history
// walk: the walk only STAGES markers (I/O over the order mirror, no recompute), so 30 min is generous.
//
// On completion it KICKS the drainer once (direct:commerce-sales-materialize, async) so the drain starts
// within milliseconds instead of waiting for the next timer heartbeat. Resume: the seed is a full,
// idempotent re-walk (writePending upserts, recompute is idempotent-from-source), so a killed run simply
// re-runs from the start with no special resume logic needed.

import commerce.SalesFactBackfill
import commerce.Jcr

// DISTINCT lock name from the drainer; generous TTL (30 min) for a full-history marker walk.
def __lease = cluster.tryLock("commerce-sales-backfill", 1800000)
if (__lease == null) {
    log.info("seedSalesFactBackfill: another cluster node is seeding - skipping")
    return
}
try {

    def summary = SalesFactBackfill.seed(repositorySession, log)
    try { log.info("seedSalesFactBackfill: seed complete: ${Jcr.toJson(summary)}") } catch (Exception ignore) {}

    // Kick the single-writer drainer once so the backlog starts draining immediately instead of waiting
    // for the 30s timer. The drainer's cluster lease coalesces this with any in-flight drain, and its
    // time-budget loop drains the whole seeded backlog across subsequent ticks. A kick failure is
    // harmless (the timer drain is the backstop).
    try {
        IntegrationAPI.createMessageSender()
            .setEndpointURI("direct:commerce-sales-materialize")
            .setBody("")
            .setHeader("runAs", "commerce-service-user")
            .sendAsync()
    } catch (Exception ke) {
        try { log.warn("seedSalesFactBackfill: drain kick failed (timer drain will pick it up): ${ke.message}") } catch (Exception ignore) {}
    }

} catch (Exception e) {
    try { log.warn("seedSalesFactBackfill: ${e.message}") } catch (Exception ignore) {}
} finally {
    __lease.close()
}
