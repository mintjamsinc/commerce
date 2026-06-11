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

- In a **standalone** deployment the lease is granted immediately
  (no-op), so behaviour is unchanged.
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
| `commerce-crm-segment` | `commerce/segmentCustomers.groovy` | 24 h | 60 min |
| `commerce-crm-abandoned` | `commerce/abandonedCheckouts.groovy` | 30 min | 30 min |
| `commerce-catalog-publish` | `commerce/publishCatalog.groovy` | 5 min | 10 min |
| `commerce-pages-publish` | `commerce/publishPages.groovy` | 5 min | 10 min |
| `commerce-velocity` | `shopify/computeVelocity.groovy` | 6 h | 30 min |
| `commerce-reorder` | `shopify/proposeReorders.groovy` | 24 h | 30 min |
| `commerce-task-sla` | `shopify/scanTaskSla.groovy` | 15 min | 15 min |

Webhook/event-driven routes (`commerce-ingest`, the `shopify-*` routes,
`health`, etc.) are deliberately **not** guarded: they must run on the
node that received the request.

Two cluster notes:

- Timers fire independently per node (offset by each node's start
  time), so a frequent task can run more often than its period across
  the cluster — never concurrently, thanks to the lease. The 5-minute
  publish tasks are idempotent full rebuilds, so this only costs a
  little extra work.
- If a task ever takes longer than its TTL, another node can acquire
  the lease while it still runs. Keep TTLs above worst-case runtimes
  when tuning timer periods.
