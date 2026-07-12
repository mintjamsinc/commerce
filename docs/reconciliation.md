# Reconciliation (Shopify → CMS)

Detects where the CMS mirror has drifted from Shopify's current
truth — product **status** and variant **price** — records it, and refreshes the
CMS mirror from Shopify (Shopify → CMS; there is no CMS → Shopify push). Drift
normally means a missed/failed webhook (also mitigated by ingest replay).
Inventory is NOT part of the diff scope — the full inventory audit is the Bulk
job broker (`inventory` schedule scope).

## Flow

```
diff schedule (scheduleReconcile.groovy)  /  POST …/endpoints/reconcile.groovy
   → direct:commerce-reconcile (scope=diff)
reconcile.groovy
   ├─ batch = products Shopify changed since the diff watermark
   │          (diffSince in state.json; products(query:"updated_at:>…"), paginated)
   ├─ per product: fetch Shopify (status / variant price — no inventory, so the
   │              inventory GraphQL cost is zero)
   │              compare to the CMS mirror (commerce.Reconciliation.diffProduct)
   ├─ refresh the CMS mirror FROM Shopify (status / price patched in place)
   └─ write a run-history report → /content/commerce/reconciliation/{yyyy}/{MM}/recon_*.json
      (EVERY run — including no-change and failed runs — result: success | error)
```

> The hourly round-robin timer was **retired on 2026-06-30**. status/price drift is caught by
> `diff` schedules; the full inventory audit is the Bulk job broker (`inventory` scope).

The diff watermark (`diffSince` in `reconciliation/state.json`) advances each run with a
few-minutes overlap margin. Requires the Admin API to be configured (`shopify.yml → adminApi`).

## Schedules & scope (wall-clock, diff)

Since the hourly baseline was retired, the **wall-clock schedules** declared in `reconcile.yml`
are the only periodic reconciliation trigger — passes at specific **UTC** times (fixed;
independent of the server's default timezone, matching the API convention that server-side
times are UTC), each with a **scope**:

- `diff` — only the products Shopify reports as changed since the last `diff` pass
  (`products(query: "updated_at:>…")`, paginated), tracked by a per-scope watermark
  (`diffSince` in `state.json`) with a few-minutes overlap margin. Cheap; meant to run often.
  Catches **status/price** only — a product's `updatedAt` does NOT change on inventory-only
  edits, so `diff` is not an inventory mechanism.
- `inventory` — a full inventory audit via the **Bulk job broker** (not reconcile.groovy):
  `scheduleReconcile.groovy` enqueues an `inventory-full` bulk job. This is the timely,
  scalable inventory path.

The CMS has no cron primitive, so a 1-minute heartbeat route (`reconcile-scheduler.xml`) runs
`scheduleReconcile.groovy`: it matches each schedule's `HH:mm` against the current **UTC**
minute (with a small grace window), de-dupes per slot/UTC-day in `schedule-state.json`, and fires
`direct:commerce-reconcile` for `diff` (or enqueues a bulk job for `inventory`) once
cluster-wide, while reconciliation is enabled (`enabled` + Admin API). The hourly round-robin
baseline was retired (2026-06-30): status/price is caught by `diff`, inventory by `inventory`.

```yaml
schedules:
  - at: "16:00"      # daily full inventory audit (16:00 UTC = 01:00 JST)
    scope: inventory
  - at: "02:00"      # optional: a periodic status/price diff pass (UTC)
    scope: diff
```

`reconcile.yml` itself always stores/receives `at` in UTC (the scheduler evaluates in UTC —
see `scheduleReconcile.groovy`). The Commerce app's Reconciliation settings display and edit
these times in the operator's effective Preferences time zone, converting to/from UTC
automatically (the same pattern the commerce-events date filters use — see
`webtop/src/webtop/composables/wire-datetime.ts`), so the config file's UTC contract never
leaks into the UI.

## Bulk job broker (inventory audit)

The `inventory` scope does not run in `reconcile.groovy`; it enqueues an `inventory-full` job into
the **Bulk job broker** — a durable JCR queue + guarded state machine (`commerce.BulkJobs`) that runs
the full-catalog inventory audit through Shopify's Bulk Operations API. Jobs carry the data
**domains** they touch (`targetDomains`, e.g. `["inventory"]`, resolved from
`BulkQueries.domainsForType`) and are serialized **per domain** across two independently
cluster-locked lanes (see [clustering.md](clustering.md)):

- **Shopify producer lane** (`commerce-shopify-bulk-lane`) — a *singleton*: Shopify permits only one
  bulk query RUNNING per app, so this lane starts at most one bulk at a time, and never re-fetches a
  domain still awaiting/undergoing CMS ingest.
- **CMS consumer lane** (`commerce-shopify-bulk-cms-lane`) — drains completed (**READY**) jobs,
  running the streaming download + mirror reconcile **in parallel for disjoint domains** and serially
  only when domains overlap.

State machine: `QUEUED → RUNNING` (Shopify bulk running) `→ READY` (Shopify COMPLETED, awaiting a CMS
ingest slot) `→ PROCESSING` (CMS downloading/reconciling) `→ COMPLETED | FAILED | CANCELED |
TIMED_OUT`. Completion is event-driven — the `bulk_operations/finish` webhook marks a job **READY**
and the CMS consumer lane then claims it (`PROCESSING`) and ingests it — with a **watchdog**
(`commerce-shopify-bulk-watchdog`) as the safety net: it recovers a lost finish webhook
(RUNNING-but-COMPLETED → READY), fails a stuck PROCESSING job, and enforces an absolute **RUNNING hard
cap** (`bulkRunningHardCapMinutes`, default 720 min) by cancelling the runaway Shopify bulk
(`ShopifyAdmin.cancelBulk`) and marking the job TIMED_OUT. All transitions are guarded
compare-and-set, so duplicate webhooks / watchdog races cannot double-reconcile a domain.

**Run history**: every terminal transition of an inventory-audit job (`inventory-full` and the
operator's `inventory-backfill` — both run the identical full inventory reconcile) is recorded as
an `inventory`-scope run report next to the diff reports
(`Reconciliation.recordBulkAudit`, hooked into the broker's terminal markers; exactly-once because
terminal states are absorbing). The report carries the counters the result processor stamped on
the job (`stats: { checked, updated }`) and `result: success | error` (FAILED / CANCELED /
TIMED_OUT all record as `error` with the job's error detail).

Today's single `inventory` domain makes this a safe serial lane; future disjoint-domain backfills
(products/orders/customers) auto-pipeline in parallel by adding one row to the `BulkQueries.TYPES`
registry.

## Refresh direction (Shopify → CMS)

Shopify is the **single source of truth** for every reconciled field, so reconciliation only
ever refreshes the CMS follower mirror FROM Shopify — there is no per-field "source of truth"
and no CMS→Shopify push. To change a value, change it in Shopify (an app writes it via the
Admin API) and it flows back here and through webhooks.

For each drifted field:

- **status / price** — patched in place in the stored product JSON (and
  `commerce:source_status`) by `commerce.Reconciliation.applyRefresh`.

Inventory is not reconciled by the diff scope (its GraphQL query fetches no inventory
at all); missed/lost inventory webhooks are recovered by the Bulk `inventory` audit.

Drift is always recorded in the run's report (a useful webhook-reliability signal).

## Endpoint

```
GET  …/endpoints/reconcile.groovy?window=24h|7d|30d   # run state + run history (default 24h)
POST …/endpoints/reconcile.groovy                     # body {scope: diff|inventory}; trigger a run now (202)
```

The history covers BOTH scopes — the diff batch (`scope: diff`) and the full inventory audit
(`scope: inventory`) — newest-started first, from one index-backed XPath query over the typed
report properties (`Reconciliation.listRuns`; bodies are never parsed). Each row is
`scope` / `startedAt` / `finishedAt` / `updated` / `result`; `updated` counts **products**
whose mirror was patched (diff) or inventory items re-mirrored (inventory), and `result` is
`success` even for a 0-update run (`error` only when the run failed).

`POST {scope: "diff"}` (the default for an empty body) fires `direct:commerce-reconcile`;
`POST {scope: "inventory"}` enqueues an `inventory-full` bulk job (idempotent per type — an
already-active audit answers `{triggered: false, alreadyRunning: true}`). Both run as the
operator who triggered them.

## Configuration (`/etc/commerce/config/reconcile.yml`)

| Key | Meaning |
|---|---|
| `enabled` | master switch |
| `maxPerRun` | diff page size (per page; per-run cap = `maxPerRun` × 20, excess carries over) |
| `reserveBudgetPercent` | diff throttle — keep this % of the cost bucket free for foreground (default 50) |
| `minDelayMsPerCall` | diff throttle — fixed floor (ms) between per-product calls (default 0) |
| `bulkWatchdogTimeoutMinutes` | RUNNING bulk job re-check threshold (default 90; lower for testing) |
| `bulkProcessingTimeoutMinutes` | stuck PROCESSING bulk job fail threshold (default 180) |
| `bulkRunningHardCapMinutes` | absolute RUNNING ceiling — past this the watchdog cancels the Shopify bulk + marks the job TIMED_OUT (default 720) |
| `maxConcurrentIngest` | cap on CMS-side reconcile ingests running at once across DISJOINT domains — the CMS lane never runs two same-domain ingests concurrently; this bounds the total so a multi-domain-backfill burst can't overload the CMS (0 = unlimited; default 3) |
| `schedules` | wall-clock passes — list of `{ at: "HH:mm", scope: diff\|inventory }`; `at` is **UTC** (default `16:00 inventory` = 01:00 JST) |

## Relationship to outbound writes and event replay

Reconciliation only refreshes the CMS mirror from Shopify; the outbound write primitives
(`commerce.ShopifyWrite`) are used by explicit app actions (fulfillment write-back, PIM
sync) — **not** by reconciliation. For drift caused by missed webhooks, replaying the event
is an alternative recovery path. See [bidirectional-sync.md](bidirectional-sync.md) and
[ingestion.md](ingestion.md).

## Operator UI

The **Commerce Reconcile** Webtop app (`webtop/src/webtop/apps/commerce-reconcile`)
is the reconciliation console: a **Run now** trigger with a **target** selector
(Inventory = the full bulk audit / Products = the diff pass; `POST reconcile.groovy`
with the matching scope, confirmed; disabled — with a warning banner — while the
Admin API is unconfigured) and the **run history** of both scopes over a selectable
window (24h / 7d / 30d toolbar selector, default 24h): type / started / finished /
updated / result, newest-started first, exportable as a BOM-prefixed UTF-8 CSV of
exactly the shown rows (Excel-safe, the commerce-oplog pattern). It is one of the
four single-concern apps the former single Commerce Operations console was split
into; the outbound-write audit is now the **commerce-oplog** app and event replay
the **commerce-events** app.
