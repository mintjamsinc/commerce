// Sales-fact drainer. Drains the pending queue; runs on a short timer (sales-materialize.xml) and is
// also kicked asynchronously by every source path (direct:commerce-sales-materialize) for
// near-immediate processing. It is the SINGLE writer of both sales-fact grains
// (/content/commerce/sales/{orders,lines}/index), serialized by the commerce-sales-materialize task lock.
// For each pending order it:
//   1. deletes the pending marker FIRST (delete-before-evaluate: a concurrent source event re-creates
//      the marker, so the next tick re-recomputes and nothing is lost);
//   2. recomputes BOTH fact grains from source (SalesFacts.recompute — resolve the authoritative body
//      preferring components-complete, fold refund bodies, Sales.compute, upsert order + line facts,
//      prune stale facts, never downgrade a complete fact);
//   3. when the raw order body could NOT be resolved (the async search index may not have surfaced a
//      just-imported order yet), re-stamps the pending marker with a bounded retry counter so the
//      order is retried on a later tick instead of silently losing its fact. A marker whose id is
//      genuinely body-less gives up after the bound with a clear warning.
//
// Aggregation is delegated to read-time facet accumulate; this only materializes the facts.
// Defensive throughout: one order's failure must never stop the drain.

import commerce.Jcr
import commerce.SalesFacts
import commerce.Locks

// A no-body order is retried on later ticks up to this many times (30s apart — generous headroom
// for the async search index to surface a fresh import), then dropped with a warning.
final int MAX_NO_BODY_RETRIES = 10

// Single-writer guard: exactly one execution drains at a time, on this node or any other
// (the task lock excludes both). Timeout ~2x the worst-case drain, larger than the inventory
// sweep because a recompute reads the order + refund bodies; an overrun is safe
// (recompute-from-source is idempotent, and the next drain resumes from the remaining markers).
def __lock = Locks.tryLock(repositorySession, SalesFacts.LOCK_NAME, 300)
if (__lock == null) {
    log.info("sweepSalesFacts: another execution is already draining - skipping")
    return
}
try {

def pending = SalesFacts.pendingOrderIds(repositorySession)
if (pending == null || pending.isEmpty()) {
    return
}

// Bound the work per drain to a wall-clock budget WELL UNDER the 300s lock timeout, so a large
// backlog (the one-time backfill seeds the whole order history at once) can NEVER run past the
// timeout and let a second drainer start concurrently — which would thrash the same fact nodes with
// write conflicts. The remaining markers drain on the next tick (30s timer); each drain is sized
// to the lock timeout.
long deadline = System.currentTimeMillis() + 240000L
int recomputed = 0
boolean truncated = false
for (oid in pending) {
    if (System.currentTimeMillis() >= deadline) {
        truncated = true
        break
    }
    try {
        // Read the marker's retry counter BEFORE the delete-before-evaluate (the marker doc is
        // the only place it lives). Absent/malformed counts as 0.
        int noBodyRetries = 0
        try {
            def marker = Jcr.readMap(repositorySession, "${SalesFacts.PENDING_DIR}/${oid}.json".toString())
            noBodyRetries = (marker?.no_body_retries ?: 0) as int
        } catch (Exception ignore) {}

        // delete-before-evaluate
        SalesFacts.clearPending(repositorySession, log, oid)
        boolean resolved = SalesFacts.recompute(repositorySession, log, oid)
        if (!resolved) {
            if (noBodyRetries < MAX_NO_BODY_RETRIES) {
                SalesFacts.markPendingRetry(repositorySession, log, oid, noBodyRetries + 1)
            } else {
                log.warn("sweepSalesFacts: order ${oid}: raw body still missing after ${noBodyRetries} retries - giving up (a webhook or the next backfill re-enqueues it)")
            }
        }
        recomputed++
    } catch (Exception e) {
        try { log.warn("sweepSalesFacts: order ${oid}: ${e.message}") } catch (Exception ignore) {}
    }
}
if (truncated) {
    log.info("sweepSalesFacts: recomputed ${recomputed} order fact(s); time budget reached, ${pending.size() - recomputed} remaining drain on the next tick")
} else {
    log.info("sweepSalesFacts: recomputed ${recomputed} order fact(s)")
}

} finally {
    Locks.unlock(__lock)
}
