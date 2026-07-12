# Clustering

The cms0 platform can run as a multi-node cluster (see
`documents/clustering.md` in the cms0 repository). Commerce content is
cluster-ready out of the box — webhook and event-driven routes correctly
run on whichever node receives the request — with one exception that
this application handles itself: **timer-fired routes execute on every
node**, so each scheduled script guards itself with the platform's
`cluster` script API.

## The guard

Every timer-fired script begins with:

```groovy
def __clusterLease = cluster.tryLock("<lock name>", <ttl millis>)
if (__clusterLease == null) {
    log.info("...: another cluster node is running this task - skipping")
    return
}
try {
    // task body
} finally {
    __clusterLease.close()
}
```

- In a **standalone** deployment the lease is held in an in-JVM lock
  table, so overlapping executions on the single node (a timer tick
  racing an async kick of the same task) exclude each other exactly as
  cluster nodes do.
- In a **cluster**, exactly one node runs the task per tick; the other
  nodes log the skip at INFO. The lease is released on completion; the
  TTL only bounds how long a crashed node can hold the lock, and is
  sized at roughly twice each task's worst-case runtime.
- Manual triggers (the admin endpoints fire the same scripts through
  `direct:` routes, asynchronously) take the same lease: a manual run
  that lands while the task is already in flight on another node is
  skipped — the work is genuinely already happening.

## Guarded tasks

| Lock name | Script | Timer | TTL |
|-----------|--------|-------|-----|
| `commerce-reconcile` | `commerce/reconcile.groovy` | 1 h | 30 min |
| `commerce-replay` | `commerce/replayEvents.groovy` | 5 min | 10 min |
| `commerce-catalog-publish` | `commerce/publishCatalog.groovy` | 5 min | 10 min |
| `commerce-task-sla` | `shopify/scanTaskSla.groovy` | 15 min | 15 min |
| `commerce-inventory-alert-sweep` | `shopify/sweepInventoryAlerts.groovy` | 15 s (+ async kick) | 2 min |
| `commerce-sales-materialize` | `commerce/sweepSalesFacts.groovy` | 30 s (+ async kick) | 5 min |
| `commerce-sales-backfill` | `commerce/seedSalesFactBackfill.groovy` | chained kick (orders backfill completion) | 30 min |
| `commerce-reconcile-scheduler` | `commerce/scheduleReconcile.groovy` | 1 min | 2 min |
| `commerce-shopify-bulk-lane` | `shopify/runBulkLane.groovy` | 30 s | 1 min |
| `commerce-shopify-bulk-cms-lane` | `shopify/runBulkCmsLane.groovy` | 30 s | 1 min |
| `commerce-shopify-bulk-watchdog` | `shopify/watchdogBulkJobs.groovy` | 5 min | 1 min |
| `commerce-migrations` | `commerce/runMigrations.groovy` | once per boot | 30 min |

Webhook/event-driven routes (`commerce-ingest`, the `shopify-*` routes,
`health`, etc.) are deliberately **not** guarded: they must run on the
node that received the request.

The three `commerce-shopify-bulk-*` locks are the **Shopify Bulk job
broker**, which serializes bulk work by data *domain* across two
independently-locked lanes plus a watchdog:

- `commerce-shopify-bulk-lane` — the **Shopify producer** lane. A
  *singleton*: Shopify permits only one bulk query RUNNING per app, so
  this lane starts at most one bulk at a time and never re-fetches a
  domain that is still awaiting/undergoing CMS ingest.
- `commerce-shopify-bulk-cms-lane` — the **CMS consumer** lane, on its
  own lock. It drains completed (READY) jobs, running the heavy
  download+reconcile **in parallel for disjoint domains** and serially
  only when domains overlap.
- `commerce-shopify-bulk-watchdog` — recovers a lost
  `bulk_operations/finish` webhook and enforces the absolute RUNNING
  hard cap.

Splitting the producer and consumer onto separate locks is what lets a
new bulk export and an unrelated-domain ingest proceed at the same time.
See [reconciliation.md](reconciliation.md).

Two cluster notes:

- Timers fire independently per node (offset by each node's start
  time), so a frequent task can run more often than its period across
  the cluster — never concurrently, thanks to the lease. The 5-minute
  publish tasks are idempotent full rebuilds, so this only costs a
  little extra work.
- If a task ever takes longer than its TTL, another node can acquire
  the lease while it still runs. Keep TTLs above worst-case runtimes
  when tuning timer periods.
