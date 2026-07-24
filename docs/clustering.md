# Clustering

The cms0 platform can run as a multi-node cluster (see
`documents/clustering.md` in the cms0 repository). Commerce content is
cluster-ready out of the box — webhook and event-driven routes correctly
run on whichever node receives the request — with one exception that
this application handles itself: **timer-fired routes execute on every
node**, so each scheduled script guards itself with a task lock.

## The guard

A task lock is an ordinary session-scoped JCR lock on a lock resource
under `/var/locks` (see `documents/task-locks.md` in the cms0
repository). The `commerce.Locks` helper wraps the get-or-create of the
lock resource, and every timer-fired script begins with:

```groovy
import commerce.Locks

def lock = Locks.tryLock(repositorySession, "<lock name>", <timeout seconds>)
if (lock == null) {
    log.info("...: another execution is already running this task - skipping")
    return
}
try {
    // task body
} finally {
    Locks.unlock(lock)
}
```

- The lock excludes overlapping executions **on the same node and
  across cluster nodes alike**: lock state lives in the workspace
  database, and every script execution runs in its own JCR session, so
  a timer tick racing an async kick of the same task is excluded
  exactly like a second cluster node.
- The lock is session-scoped: it is released by `Locks.unlock` on
  completion, and by the session close if the script throws or forgets.
  The timeout only bounds how long a **crashed** owner (a node that
  died without closing its session) can keep the lock, and is sized at
  roughly twice each task's worst-case runtime.
- Manual triggers (the admin endpoints fire the same scripts through
  `direct:` routes, asynchronously) take the same lock: a manual run
  that lands while the task is already in flight is skipped — the work
  is genuinely already happening.
- The lock resources are plain folders under `/var/locks`, created on
  first use by the helper; `provisioning/commerce.yml` grants the
  service group and operators write there.

## Guarded tasks

| Lock name | Script | Timer | Timeout |
|-----------|--------|-------|---------|
| `commerce-reconcile` | `commerce/reconcile.groovy` | 1 h | 30 min |
| `commerce-replay` | `commerce/replayEvents.groovy` | 5 min | 10 min |
| `commerce-task-sla` | `shopify/scanTaskSla.groovy` | 15 min | 15 min |
| `commerce-inventory-alert-sweep` | `shopify/sweepInventoryAlerts.groovy` | 15 s (+ async kick) | 2 min |
| `commerce-sales-materialize` | `commerce/sweepSalesFacts.groovy` | 30 s (+ async kick) | 5 min |
| `commerce-sales-backfill` | `commerce/seedSalesFactBackfill.groovy` | chained kick (orders backfill completion) | 30 min |
| `commerce-reconcile-scheduler` | `commerce/scheduleReconcile.groovy` | 1 min | 50 s |
| `commerce-shopify-bulk-lane` | `shopify/runBulkLane.groovy` | 30 s | 1 min |
| `commerce-shopify-bulk-cms-lane` | `shopify/runBulkCmsLane.groovy` | 30 s | 1 min |
| `commerce-shopify-bulk-watchdog` | `shopify/watchdogBulkJobs.groovy` | 5 min | 1 min |
| `commerce-migrations` | `commerce/runMigrations.groovy` | once per boot | 30 min |
| `commerce-housekeeping` | `commerce/houseKeeping.groovy` | 1 h | 10 min |

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

Two operational notes:

- Timers fire independently per node (offset by each node's start
  time), so a frequent task can run more often than its period across
  the cluster — never concurrently, thanks to the lock. The frequent
  sweep tasks are idempotent, so this only costs a little extra work.
- If a task ever takes longer than its timeout, another execution can
  acquire the lock while it still runs. Keep timeouts above worst-case
  runtimes when tuning timer periods; a long-running execution can also
  extend its lock via the standard JCR `Lock.refresh()`.
