# Reconciliation (CMS ↔ Shopify)

Category G, #24. Detects where the CMS mirror has drifted from Shopify's current
truth — product **status**, variant **price**, variant **inventory** — reports it,
alerts, and refreshes the CMS mirror from Shopify (Shopify → CMS; there is no
CMS → Shopify push). Drift normally means a missed/failed webhook (also mitigated by
ingest replay #4).

## Flow

```
diff schedule (scheduleReconcile.groovy)  /  POST …/endpoints/reconcile.groovy
   → direct:commerce-reconcile (scope=diff)
reconcile.groovy
   ├─ batch = products Shopify changed since the diff watermark
   │          (diffSince in state.json; products(query:"updated_at:>…"), paginated)
   ├─ per product: fetch Shopify (status / variant price / per-location inventory)
   │              compare to the CMS mirror (commerce.Reconciliation.diffProduct)
   ├─ write a drift report → /content/commerce/reconciliation/{yyyy}/{MM}/recon_*.json
   ├─ alert on drift (debounced) → commerce.Alerts → Notifications (#17)
   └─ refresh the CMS mirror FROM Shopify (status / price; + inventory mirror for the changed products)
```

> The hourly round-robin timer was **retired on 2026-06-30**. status/price drift is caught by
> `diff` schedules; the full inventory audit is the Bulk job broker (`inventory` scope).

The diff watermark (`diffSince` in `reconciliation/state.json`) advances each run with a
few-minutes overlap margin. Requires the Admin API to be configured (`shopify.yml → adminApi`).

## Schedules & scope (wall-clock, diff)

Beyond the hourly baseline timer, `reconcile.yml` can declare **wall-clock schedules** —
additional passes at specific local times — each with a **scope**:

- `diff` — only the products Shopify reports as changed since the last `diff` pass
  (`products(query: "updated_at:>…")`, paginated), tracked by a per-scope watermark
  (`diffSince` in `state.json`) with a few-minutes overlap margin. Cheap; meant to run often.
  Catches **status/price** only — a product's `updatedAt` does NOT change on inventory-only
  edits, so `diff` is not an inventory mechanism.
- `inventory` — a full inventory audit via the **Bulk job broker** (not reconcile.groovy):
  `scheduleReconcile.groovy` enqueues an `inventory-full` bulk job. This is the timely,
  scalable inventory path.

The CMS has no cron primitive, so a 1-minute heartbeat route (`reconcile-scheduler.xml`) runs
`scheduleReconcile.groovy`: it matches each schedule's `HH:mm` against the current minute
(with a small grace window), de-dupes per slot/day in `schedule-state.json`, and fires
`direct:commerce-reconcile` for `diff` (or enqueues a bulk job for `inventory`) once
cluster-wide, while reconciliation is enabled (`enabled` + Admin API). The hourly round-robin
baseline was retired (2026-06-30): status/price is caught by `diff`, inventory by `inventory`.

```yaml
schedules:
  - at: "00:00"      # shipped default: a daily full inventory audit
    scope: inventory
  - at: "02:00"      # optional: a periodic status/price diff pass
    scope: diff
```

## Refresh direction (Shopify → CMS)

Shopify is the **single source of truth** for every reconciled field, so reconciliation only
ever refreshes the CMS follower mirror FROM Shopify — there is no per-field "source of truth"
and no CMS→Shopify push. To change a value, change it in Shopify (an app writes it via the
Admin API) and it flows back here and through webhooks.

For each drifted field:

- **status / price** — patched in place in the stored product JSON (and
  `commerce:source_status`) by `commerce.Reconciliation.applyRefresh`.
- **inventory** — re-mirrored from the authoritative **per-location** stock
  (`inventoryItem.inventoryLevels`): for any item whose levels drifted, the mirror is
  overwritten and the item is marked pending so the inventory-alert sweep re-evaluates it —
  the **"nothing missed" backstop** for missed/lost inventory webhooks. Controlled by
  `refreshInventoryMirror` (on by default).

Drift is always recorded in the run's diff report (a useful webhook-reliability signal).

## Endpoint

```
GET  …/endpoints/reconcile.groovy     # latest drift report + cursor/run state
POST …/endpoints/reconcile.groovy     # trigger a run now (202; runs as service user)
```

## Configuration (`/etc/commerce/config/reconcile.yml`)

| Key | Meaning |
|---|---|
| `enabled` | master switch |
| `maxPerRun` | diff page size (per page; per-run cap = `maxPerRun` × 20, excess carries over) |
| `refreshInventoryMirror` | refresh the inventory mirror from Shopify + re-evaluate alerts (default on) |
| `alert` | notify (debounced) on drift |
| `reserveBudgetPercent` | diff throttle — keep this % of the cost bucket free for foreground (default 50) |
| `minDelayMsPerCall` | diff throttle — fixed floor (ms) between per-product calls (default 0) |
| `bulkWatchdogTimeoutMinutes` | RUNNING bulk job re-check threshold (default 90; lower for testing) |
| `bulkProcessingTimeoutMinutes` | stuck PROCESSING bulk job fail threshold (default 180) |
| `schedules` | wall-clock passes — list of `{ at: "HH:mm", scope: diff\|inventory }` (default `00:00 inventory`) |

## Relationship to #2 / #4

Reconciliation only refreshes the CMS mirror from Shopify; the outbound write primitives
(`commerce.ShopifyWrite`, #2) are used by explicit app actions (fulfillment write-back, PIM
sync) — **not** by reconciliation. For drift caused by missed webhooks, replaying the event
(#4) is an alternative recovery path. See [bidirectional-sync.md](bidirectional-sync.md) and
[ingestion.md](ingestion.md).

## Operator UI

The **Commerce Operations** Webtop app (`webtop/src/webtop/apps/commerce-ops`)
exposes reconciliation on its **Reconcile** tab: the latest drift report (products
with drift / field diffs / auto-healed / checked, plus a per-field drift table with
CMS vs Shopify values and the heal direction), the cursor state, and a **Run now**
trigger (`POST reconcile.groovy`, confirmed). The same app's **Sync** and **Events**
tabs cover the outbound-write (#2) and replay (#4) surfaces.
