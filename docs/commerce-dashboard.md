# Commerce Dashboard

A read-only, real-time operational overview of the headless-commerce integration,
delivered as a Webtop application (`Commerce Dashboard`). It ties together the
data produced by the rest of the platform into KPI cards:

| Card | Shows | Source |
|---|---|---|
| **Sales trend** (hero) | revenue / orders / AOV over the selected window, a daily revenue sparkline, and the TOP5 products by base gross (net/gross + units, titled from the product mirror) | `commerce.SalesQuery.salesRange` + `topProducts` (sales facts) |
| **Sales** | orders and revenue per currency over the window, by processing status | `commerce.Dashboard.salesSummary` (sales facts + a status facet count) |
| **Inventory** | total products, low-stock (review_pending), by status | `commerce.Dashboard.inventorySummary` (product resources) |
| **Reorders** | purchase orders awaiting approval + recently ordered | replenishment workflow |
| **Locations** | location count, tracked items, out-at-location pairs | `commerce.Locations` |
| **Backorders** | lines awaiting stock, ready to release, units awaited | `commerce.Backorders` |
| **Customers** | total customers | `commerce.Dashboard.crmSummary` (customer store count) |
| **Tasks** | open tasks, unassigned, overdue / open / unclaimed (SLA) | BPMN engine + `commerce.TaskSla` |
| **Integration Health** | webhooks received, HMAC failures, API & processing error rates, avg latency | `commerce.Health` |
| **Event ingestion** | total events, failed (need replay), processed / received | `commerce.Events` |
| **Reconciliation** | products with drift, field diffs, auto-refreshed, last run | `commerce.Reconciliation` reports |
| **Outbound sync** | CMS → Shopify writes over the window: OK / failed / dry-run | `commerce.Reports.operations` |

## Interactions

- **Sales window selector** — a `7d / 30d / 90d` toggle in the toolbar controls the
  sales window; it refetches with `?salesDays=` (it also scopes the Outbound sync
  card's window). Other cards use the fixed health window.
- **Drill-down** — cards whose data has an operator screen carry a ↗ link that
  launches the **Commerce Operations** console focused on the matching view, via the
  Webtop shell's `open-app` message (`{ type: 'open-app', appId, options }`). The
  shell focuses the running console and re-targets it when it is already open
  (singleton), so a drill-down never spawns a second window:

  | Card | Launches | Options |
  |---|---|---|
  | **Event ingestion** | Operations → Events | `{ section: 'events', eventFilter: { status: 'error' } }` |
  | **Reconciliation** | Operations → Reconcile | `{ section: 'reconcile' }` |
  | **Outbound sync** | Operations → Sync | `{ section: 'sync' }` |

  The dashboard itself stays read-only. Cards without a dedicated operator screen
  yet (Sales trend, Backorders, Customers/CRM) carry no drill-down link until those
  surfaces exist; their summaries still render from the snapshot.

  The re-target mechanism is generic platform plumbing, not commerce-specific: any
  app may launch a singleton with `open-app` options, and the target app receives a
  `{ type: 'app-reopen', options }` window message while running (Commerce
  Operations routes it through `applyLaunchOptions`).

## Architecture

```
Commerce Dashboard (Webtop app, iframe)
   │  GET (same-origin, authenticated)
   ▼
/bin/cms.cgi/{workspace}/content/commerce/endpoints/dashboard.groovy
   │  assembles, each section degrading independently:
   ├─ commerce.Dashboard.salesSummary / inventorySummary   (sales facts / product resources)
   ├─ commerce.SalesQuery.salesRange / topProducts          (index-backed facet aggregation)
   ├─ commerce.Health.snapshot                              (health metrics)
   └─ BPMN engine + commerce.TaskSla.status                 (open tasks by SLA status)
```

The app fetches that single JSON snapshot and renders it. One server-side
endpoint keeps the browser logic thin and the aggregation reusable.

## Real-time updates

The dashboard stays current two ways:

1. **Content subscription** — `instance.api.eventHub.watchNode("/content/commerce", …, deep)`
   (SSE-backed GraphQL subscriptions). Orders, products and health metrics are
   JCR writes, so any of them triggers a debounced refetch. The nav-bar "Live"
   indicator reflects the subscription state.
2. **Slow poll** — a 60-second interval refetch catches task / SLA changes (which
   live in the BPMN engine, not JCR) and acts as a fallback when the subscription
   is unavailable.

A manual refresh button is also provided. Transient refresh failures keep the
last good data on screen rather than blanking the dashboard.

## Endpoint

```
GET /bin/cms.cgi/{workspace}/content/commerce/endpoints/dashboard.groovy?days=7&salesDays=30
```

- `days` — health window (default 7, max 90)
- `salesDays` — sales window (default 30, max 365)

`content/commerce/endpoints/dashboard.groovy` lives outside `/content/public`, so
the CGI enforces authentication and ACLs (admin-only, like the app). Each section
is wrapped independently so one failing source (e.g. the engine) does not take
down the whole view.

## Why a separate app

The existing **Commerce** app is the admin *config editor* (Shop, Notifications,
Health, Tasks) with a save/dirty model. The dashboard is a *read-only, real-time
view* with very different UX, so it ships as its own Webtop app
(`webtop/src/webtop/apps/commerce-dashboard`) built alongside the config app by
the same rollup build.

## Related

- [health-monitor.md](health-monitor.md) — the health card's data
- [task-sla.md](task-sla.md) — the tasks card's data
- [jcr-structure.md](jcr-structure.md) — endpoints and storage paths
