# Sales Velocity & Stockout Forecast

Computes how fast each variant is selling and predicts when it will run out, so
the platform can warn before stock hits zero — and so the inventory threshold
rules can react to demand.

## How it works

```
timer:commerce-velocity (every 6h, as service user)
        │
        ▼
computeVelocity.groovy
   ├─ commerce.SalesVelocity.computeByVariant  — units/day per variant from order line items
   ├─ writeCache → /content/commerce/analytics/velocity.json   (cheap to read)
   └─ forecast + stockout alerts → commerce.Alerts → Notifications (#17)

per webhook (cheap):
   checkInventoryLevel / checkThresholdConfig / notifyTaskCreated
        └─ SalesVelocity.loadPerDay → feeds InventoryRules.minVelocityPerDay (#5)
```

Velocity is an expensive scan of the order history, so it runs as a periodic
batch and is **cached**; the per-webhook inventory scripts only read the cached
file. This keeps webhook processing fast while activating the velocity-based
inventory rules and powering the forecast.

## Velocity

`commerce.SalesVelocity.computeByVariant(session, log, windowDays)` sums each
variant's sold `quantity` from the `line_items` of orders whose `created_at`
falls in the window (falling back to the resource's ingestion time), and divides
by the window:

```
perDay = units sold in window / windowDays
```

Cached to `/content/commerce/analytics/velocity.json`:

```json
{
  "generatedAt": "2026-06-02T03:00:00Z",
  "windowDays": 30,
  "variants": { "44820...": { "units": 45, "perDay": 1.5 } }
}
```

## Stockout prediction

```
daysToStockout = current stock / perDay      (null when perDay is 0 — no risk)
```

`SalesVelocity.forecast(session, perDayByVariant, warnDays)` scans products
(skipping deleted ones) and returns the variants predicted to run out within
`warnDays`, soonest first. The batch alerts on each (debounced per variant via
`commerce.Alerts`, the same plumbing as the health / SLA monitors); the cooldown
state is pruned as variants recover (restock / slowdown).

## Configuration (`/etc/commerce/config/velocity.yml`)

Managed from **Webtop → Commerce → Forecast**.

| Key | Meaning |
|---|---|
| `enabled` | master switch for the batch |
| `windowDays` | velocity averaging window (default 30) |
| `cooldownMinutes` | min minutes between repeat stockout alerts per variant (default 720) |
| `stockout.enabled` | enable stockout alerting |
| `stockout.warnDays` | alert when predicted stockout is within this many days (default 7) |

## Feeding the threshold rule engine (#5)

The cached velocity is passed to `commerce.InventoryRules`, activating the
previously-dormant `minVelocityPerDay` rule criterion: fast-moving variants can
get a higher effective threshold so the workflow reacts earlier. See
[inventory-rules.md](inventory-rules.md).

## Reading the forecast

```
GET /bin/cms.cgi/{workspace}/content/commerce/endpoints/forecast.groovy?warnDays=7
```

`content/commerce/endpoints/forecast.groovy` returns the at-risk variants
(soonest first). The Commerce **Dashboard** also surfaces an at-risk count and the
most urgent variants in its Forecast card (see
[commerce-dashboard.md](commerce-dashboard.md)).
