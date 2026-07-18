# MintJams Commerce (commerce)

A headless-commerce **orchestration layer** built on
[cms0](https://github.com/mintjamsinc/cms0)
(JCR 2.0 + an EIP integration engine + a BPMN workflow engine). It connects an external commerce
platform (currently Shopify) to MintJams CMS: webhooks are received and
verified, normalized into the JCR repository, and driven through BPMN
workflows that raise human tasks and fire notifications when an operator
needs to step in.

- **Integration assets** — Groovy webhook endpoint, EIP integration routes,
  BPMN workflow processes, a shared Groovy class layer, task-helper scripts, and
  YAML configuration. Sources under [`content/`](content/) and [`etc/`](etc/).
- **Commerce apps** — a family of virtual-desktop apps for the Webtop that
  centralize configuration and day-to-day operations: a settings console, a
  read-only dashboard, three editor + facet-browser pairs (product, customer,
  order), and four single-concern operations apps (event log, historical
  import, outbound-write audit, sales reports) plus a dedicated reconciliation
  console. Source under [`webtop/`](webtop/).
- **Provisioning** — the identity, group and ACL model the integration runs
  under, applied at first boot. Source under [`provisioning/`](provisioning/).

These artifacts are **bundled, pre-deployed, into the same `mintjams/cms`
Docker image as cms0**. When you start MintJams CMS, the Commerce tooling is
already in place — there is nothing extra to install, only to configure.

> Status: **public preview.** APIs, JCR layouts, configuration keys, and the
> bundled apps may change before 1.0.

---

## Quick start

Commerce has no image of its own. Its assets travel inside the `mintjams/cms`
image, so the quick start *is* the cms0 quick start — run the CMS container and
the Commerce tooling comes up with it.

```bash
docker run --rm \
  -p 8080:8080 \
  -e CMS_PUBLIC_BASE_URL=http://localhost:8080 \
  -v cms-repository:/data/repository \
  -v cms-secrets:/data/secrets \
  --tmpfs /opt/felix/tmp:size=512m,mode=0700 \
  mintjams/cms:latest
```

See the [cms0 README](https://github.com/mintjamsinc/cms0) for the full set of
environment variables, volumes, first-login flow, and `docker compose` example.

Once the CMS is up, open <http://localhost:8080/>, log in as `admin`, and open
the **Commerce** app from the Webtop menu to configure the integration (see
[Configuration](#configuration)).

---

## What ships today: the Shopify inventory alert tool

The current workflow watches Shopify stock levels. When a product's inventory
falls below a per-variant threshold, it raises a manual review task for an
operator and announces it to the configured notification channels — going beyond
a fire-and-forget alert by tracking *who* owns the follow-up.

```
Shopify (product updated / order paid)
   │  Webhook (HMAC-SHA256 verified)
   ▼
Groovy endpoint ──→ EIP route ──→ JCR (store + normalize)
                                    │  signal "process this"
                                    ▼
                              BPMN workflow
                                    │  threshold set? stock low?
                                    ▼
                          Human task ──→ notification (Slack / Discord / Teams / LINE / …)
```

Two task types make up the flow:

1. **Set Inventory Threshold** — raised the first time a product is seen with
   no threshold yet. The operator decides, per variant, "how few units before
   we reorder."
2. **Inventory & Reorder Review** — raised when a variant drops below its
   threshold. It shows the current stock, the fixed threshold and the previous
   order (date + quantity) for reference; the operator enters the order quantity
   by hand and, on completion, it is recorded in Shopify as incoming stock.

Operators work these in the Webtop **Tasks** app; notifications are posted as
each task is created. A re-entrancy guard prevents a product with an in-flight
workflow from launching a duplicate (the latest data is still stored, so
nothing is lost).

For the end-to-end operator manual, see
[`docs/inventory-alert-tool.md`](docs/inventory-alert-tool.md)
(日本語版: [`docs/inventory-alert-tool.ja.md`](docs/inventory-alert-tool.ja.md)).

---

## Event ingestion (all topics · multi-backend · replay)

Every inbound integration event funnels through one **source-agnostic core**
(`direct:commerce-ingest`): it is logged with its raw payload to a durable event
log, then either handled by a dedicated workflow (orders / products / refunds /
inventory / locations) or **normalized generically** into a current-state entity
record. So `customers/*`, `fulfillments/*`, `carts/*`, `checkouts/*` — and any topic
Shopify adds later — are captured as first-class business events with no new
plumbing.

```
backend adapter (verifies signature) ──► direct:commerce-ingest
                                            ├─ event log (raw payload, replayable)
                                            ├─ bespoke topic → its workflow route
                                            └─ other topic   → normalized entity
```

- **Multi-backend** — the core knows nothing Shopify-specific beyond a small
  topic→route table. A new backend (Rakuten / BASE / in-house storefront / ERP) is just an adapter
  that posts the same envelope (`event_source` / `event_topic` / `event_id` +
  payload) to the core — downstream is unchanged.
- **Replay** — because the raw payload is kept, failed events are retried
  automatically (bounded attempts + backoff) and any event can be replayed manually
  from the events endpoint. Replays reprocess rather than skip as duplicates.

Settings live in `etc/commerce/config/ingest.yml`. For the full design, see
[`docs/ingestion.md`](docs/ingestion.md).

### Bidirectional sync (CMS → Shopify)

The write side: operators / tooling push corrections back to Shopify through the
Admin API via `content/commerce/endpoints/sync.groovy` — **set stock at a
location**, **update a price**, **publish/unpublish a product**, push PIM
metafields, edit a product's base fields (title / description / vendor / type /
tags / handle / status) and media (Product 360's write hub), and edit a
**customer**'s or **order**'s shop-curated fields. Requires the Admin API to be
configured (the same as metafield enrichment and fulfillment write-back), with a
`dryRun` mode for safe rollout, `api` health timing, and an audit trail under
`/content/commerce/sync/`. These are also the write primitives the reconciliation
job uses to auto-refresh drift. See
[`docs/bidirectional-sync.md`](docs/bidirectional-sync.md).

---

## Order processing workflow

The same pattern now covers **orders** end to end. When an `orders/paid` webhook
arrives, the order is stored and run through `order-review-flow`, which spans
screening, review, approval and fulfillment:

1. **Screen** — evaluate the order against configurable rules (high value, risky
   payment status, bulk quantity, billing/shipping mismatch, …). Orders that
   match no rule are auto-approved with no human step.
2. **Review** *(only if flagged)* — raise a manual **Order Review** task and a
   notification, so operators only handle the orders that need it.
3. **Approve** — mark the order cleared for fulfillment.
4. **Fulfill** — raise a manual **Fulfill Order** task for the warehouse to pick,
   pack and record tracking.
5. **Record fulfillment** — persist tracking on the order and, when the Admin API
   is enabled, write the fulfillment back to Shopify (best-effort).

```
Shopify (orders/paid) ──→ order-paid route ──→ JCR ──→ order-review-flow
                                                          │  screen order
                                          ┌───────────────┴───────────────┐
                                          │ no rule matched                │ rule matched
                                          ▼                                ▼
                                          └────────────→ approved ←── Order Review task → notice
                                                            │
                                                            ▼
                                                Fulfill Order task → notice
                                                            │ tracking recorded
                                                            ▼
                                            record fulfillment (→ Shopify, gated) → fulfilled
```

Screening rules live in `etc/commerce/config/order-review.yml`; the Shopify
write-back reuses the Admin API settings in `shopify.yml` (requires the Admin API to
be configured). Tasks are worked in the Webtop **Tasks** app and share the
notification destinations with the inventory alert tool. A re-entrancy guard
prevents duplicate workflows for the same order. For the end-to-end operator
manual, see [`docs/order-review-tool.md`](docs/order-review-tool.md).

---

## Refund review workflow

Refunds get the same treatment. When a `refunds/create` webhook arrives, the
refund is stored and run through `refund-review-flow`: a screening step evaluates
it against configurable rules (high refund value, full refund, items returned
without restocking). Refunds that match raise a manual **Refund Review** task and
a notification; refunds that match nothing are auto-acknowledged. Either way the
refund is recorded and the order's cumulative refund summary is updated.

A refund is already executed in Shopify by the time the webhook fires, so this is
an **audit / fraud-monitoring** tool: it never moves money and never writes back
to Shopify.

```
Shopify (refunds/create) ──→ refund-created route ──→ JCR ──→ refund-review-flow
                                                                │  screen refund
                                                ┌───────────────┴───────────────┐
                                                │ no rule matched                │ rule matched
                                                ▼                                ▼
                                                └──→ record refund ←── Refund Review task → notice
                                                          │  update order refund summary
                                                          ▼
                                                       resolved
```

Screening rules live in `etc/commerce/config/refund-review.yml`. For the operator
manual, see [`docs/refund-tool.md`](docs/refund-tool.md).

---

## Inventory intelligence

The basic inventory alert above is the entry point to a fuller inventory layer.
Each piece is independently configurable and degrades gracefully when its inputs
are absent, so a shop can adopt as much or as little as it needs.

- **Planning layer (per-variant reorder threshold)** — every variant carries a
  single fixed **reorder threshold** (in units), the stock level at or below which
  it is reordered. Resolution: explicit per-variant value (`pim.planning`) →
  `planning.yml` default → "not monitored." The system never derives or rewrites
  it — operators set it, per variant or in bulk across a search result. See
  [`docs/planning.md`](docs/planning.md).
- **Multi-location inventory** — Shopify's per-location stock is ingested
  (`inventory_levels/update`, `locations/*`), aggregated per variant, and exposed
  for cross-location **allocation** decision support (which locations to draw
  from). Advisory only — it does not override Shopify's fulfillment routing. See
  [`docs/multi-location.md`](docs/multi-location.md).
- **Reorder review (unified with the alert)** — when stock crosses below the
  threshold, ONE **Inventory & Reorder Review** task shows the current stock, the
  fixed threshold and the previous order (date + quantity) for reference. The
  operator enters the order quantity by hand — there is no system-suggested
  quantity — purchases through their own channel, and the confirmed quantity is
  recorded in Shopify as **incoming stock** (inventory transfer); receiving flows
  back via the `inventory_levels/update` webhook. See
  [`docs/inventory-alert-tool.md`](docs/inventory-alert-tool.md).

Settings live in `etc/commerce/config/planning.yml` and `locations.yml`.

---

## Backorder & pre-order management

When a paid order cannot be fulfilled from on-hand stock — or a product is sold
ahead of stock as a pre-order — the order-paid route records a **line-level
backorder** and tracks it through its own lifecycle. When stock later arrives, the
`inventory_levels/update` route releases covered backorders (oldest-first, FIFO) by
raising a **Release Backorder** task; a refund cancels an order's still-waiting
backorders. A shortfall is only raised for stock-tracked items, so a shop without
inventory webhooks is never flooded with false records.

```
Shopify (orders/paid) ─────────→ detectBackorders ─→ backordered
Shopify (inventory_levels/update) → releaseBackorders ─→ backorder-release-flow
                                       (FIFO)              Release Backorder task → released
Shopify (refunds/create) ────────→ cancelBackorders ─→ cancelled
```

Settings live in `etc/commerce/config/backorder.yml`. For the full design, see
[`docs/backorders.md`](docs/backorders.md).

---

## Data platform: PIM · reconciliation · reports

The product mirror is also a **data platform**:

- **PIM** — a CMS-authoritative overlay of extended attributes (multi-language
  titles/descriptions, rich descriptions, custom attributes, metafields) stored on
  the product node, so it inherits JCR **version history**, **full-text search** and
  **ACLs**. A unified view composes Shopify base + metafields + overlay; CMS-authored
  metafields push back through the sync endpoint. See
  [`docs/pim.md`](docs/pim.md).
- **Reconciliation** — two complementary passes, not one. A lightweight `diff`
  pass (status / price, `products.updated_at`) runs on a schedule and reports
  drift; the full inventory audit — where a product's `updated_at` does not
  change on a stock move, so nothing short of reading everything is correct —
  runs through the **Bulk job broker**: a durable JCR job queue and guarded
  state machine (`commerce.BulkJobs`/`BulkQueries`) that drives Shopify Bulk
  Operations across a Shopify-producer lane and a CMS-consumer lane, serialized
  per data domain, with an event-driven completion path and a watchdog safety
  net. Detect + report is the default; healing (push via `ShopifyWrite`, or
  refresh the mirror) is opt-in per a per-field source-of-truth. See
  [`docs/reconciliation.md`](docs/reconciliation.md).
- **Historical backfill** — the same Bulk job broker also drives a one-time (or
  re-runnable) full import of orders, customers, products and inventory from
  Shopify. The orders backfill chains in the missing refund detail and then
  automatically seeds the sales facts below, so a shop's full order history is
  queryable without a separate replay step. Surfaced in the **Commerce Import**
  app. See [`docs/reconciliation.md`](docs/reconciliation.md).
- **Reports & audit export** — sales reports read from an index-backed sales-fact
  store (`commerce.SalesQuery`, facet passes, no row cap): the occurrence-date
  summary (every event counted on its own date — the view the Commerce Reports
  app and the dashboard's sales-trend hero share) and the outbound-write audit
  trail, as JSON or CSV. The former order-date P/L / refund cash-out report was
  retired end to end (endpoint, webtop tabs, dashboard Sales card and the P/L
  read layer); the fact write path is untouched, and the browse sort axes
  (product sales, customer spend) still read the same facts.
  See [`docs/reports.md`](docs/reports.md).

---

## Customers

Customers follow the same philosophy as the product mirror: the CMS **mirrors
what Shopify owns and lets an operator edit it through the Admin API** — it does
not compute its own view of the customer.

- **Mirror** — `customers/*` webhooks store the raw Shopify customer JSON as the
  node body (`/content/commerce/customers/customer_{id}.json`), with profile and
  lifecycle fields promoted to typed, auto-indexed JCR properties.
- **No self-maintained wallet, segmentation or scoring** — there is no daily
  classification batch, no behaviour-change alerting and no abandoned-cart
  follow-up. **VIP is simply a manual Shopify customer tag**; anything else the
  shop wants to track about a customer is a tag, a note, or a Shopify-native
  field.
- **Editing — Admin API** — operator edits (tags, note, tax exemption, marketing
  consent) go through the outbound sync endpoint's `customer` action and are
  pushed to Shopify; the mirror follows on the webhook round-trip.
- **Two Webtop apps** — **Commerce Customer**, the singular MIME-launched editor
  and write hub, and **Commerce Customers**, a read-only facet browser (search
  plus tag / marketing-consent / source-status facets) that hands rows to the
  editor. A spend-ranking facet (`sort=spend`, exact, uncapped) is computed
  live from the sales facts, not a stored rollup.

There is no dedicated config file for this domain (no `crm.yml`). See
[`docs/crm.md`](docs/crm.md).

---

## Public product feed: a read endpoint + a thin JS client

The platform does **not** ship a storefront, a published catalog projection, or
GSP starter templates — those existed in an earlier design and were retired
(along with the `commerce-publish` app) in favor of something simpler: you build
your own promotion / product pages as ordinary same-origin CMS pages, and pull
product data in on demand.

- **Read endpoint** — `/content/public/commerce/endpoints/catalog.groovy` reads
  the admin product mirror directly (everyone has repository read on
  `/content`) and sanitizes it per request via `commerce.Catalog` — there is no
  pre-built projection to go stale, so the data is always fresh. Only
  Shopify-active products are returned, and admin metadata / cost / internal PIM
  attributes are never emitted. Cacheable (`max-age=30`).
- **Client SDK — data only** — `/content/public/commerce/sdk/commerce.js` (v2)
  is a tiny, dependency-free data client: `Commerce.product()` /
  `Commerce.products()` / `Commerce.checkoutUrl()` / `Commerce.formatMoney()`.
  It renders nothing and stores no cart — you design and style the page
  yourself. (An earlier version shipped declarative widgets — buy buttons,
  banners, a mini cart; those were removed.)
- **Checkout** — redirects to Shopify's hosted checkout via a cart permalink
  (no Storefront API token needed).

There is no dedicated config file for this domain (no `storefront.yml`) and no
publish/rebuild console — reads are always on demand. See
[`docs/storefront.md`](docs/storefront.md).

---

## Operations & observability

Beyond the business workflows, the platform watches itself and gives operators a
single place to see what is happening:

- **Commerce Dashboard** — a read-only, real-time Webtop app that ties the
  platform's data together into KPI cards (sales trend, inventory, reorders,
  locations, backorders, customers, tasks, integration health, event
  ingestion, reconciliation, outbound sync). See
  [`docs/commerce-dashboard.md`](docs/commerce-dashboard.md).
- **Integration health monitor** — observes webhook intake, route processing
  (per-topic success/error + latency) and Admin API calls, and raises alerts
  through the same notification channels when something degrades. See
  [`docs/health-monitor.md`](docs/health-monitor.md).
- **Task SLA monitor** — keeps human tasks from stalling: a periodic scan
  escalates any open task (order / refund / inventory review) that breaches a
  service-level rule, bumping priority and alerting. See
  [`docs/task-sla.md`](docs/task-sla.md).

Settings live in `etc/commerce/config/health.yml` and `sla.yml`.

---

## Identity & access model

The integration does **not** run as `admin`. It is provisioned with its own
identity, group and ACLs at first boot via
[`provisioning/commerce.yml`](provisioning/commerce.yml), so background work is a
regular principal that is fully subject to repository ACLs — not an unbounded
super-user.

| Principal | Kind | Purpose |
|---|---|---|
| `commerce-service-user` | Service account (`service: true`, no password, cannot sign in) | The non-interactive identity the integration runs background work as (EIP routes / BPMN service tasks via `runAs`). A regular principal subject to ACLs. |
| `commerce-service-group` | Group | Carries the write grants the service user needs. The service user is a member. |
| `commerce-operators` | Group | Delegates commerce administration to non-admin operators. Admin endpoints run as the logged-in operator, so operators need this group's grants. |

Write access is **added** on top of the repository-wide read that cms0 already
grants, on exactly the paths the platform owns:

- `/content/commerce` — business data (event log, orders, inventory, products,
  customers, reconciliation, tasks, health, …) and operator edits (the PIM
  overlay, outbound-sync audit, on-demand recompute).
- `/content/public/commerce` — the public product feed's read endpoint and
  client SDK (anonymous read via the cms0 public-access rule). No runtime
  process publishes here any more — the service group keeps write only so the
  storefront-retire migration can delete the old, now-removed projection data.
- `/etc/commerce/config` — read-only config for everyone; the service user
  additionally gets write to cache the Shopify Admin API access token.

The built-in `admin` user bypasses ACLs and needs no grant; add real operators to
`commerce-operators` from the Webtop **Identity Manager** app.

The same descriptor registers the JCR **`commerce` namespace**
(`http://www.mintjams.jp/commerce/1.0`) used by every commerce metadata property
(see [Status model](#status-model)); a one-time migration of legacy pre-namespace
data is in `content/WEB-INF/classes/commerce/migration/NamespaceMigration.groovy`.

---

## Status model

Entity status is modelled on **two independent axes** so that "what the record
is in the source system" never gets conflated with "how far our pipeline has
processed it." This is a platform invariant — see
[`docs/commerce-status.md`](docs/commerce-status.md) for the authoritative,
closed enumeration.

| Property | Axis | Owner |
|---|---|---|
| `commerce:status` | Integration processing lifecycle (`received`, `threshold_pending`, `review_pending`, `monitored`, `approved`, `fulfillment_pending`, `fulfilled`, `resolved`, `backordered`, `ready`, `released`, `cancelled`, `processed`, `ok`/`failed`/`dryrun`, `error`, `deleted`) | This pipeline (EIP + BPMN) |
| `commerce:source_status` | Source-system business status, mirrored verbatim from Shopify | Shopify |

The runtime JCR paths these properties live on (orders, products, error
handling) are described in [`docs/jcr-structure.md`](docs/jcr-structure.md).

---

## Configuration

All settings are edited from the **Commerce** Webtop app (admin-only) and
persisted as YAML in the repository. Connection and notification settings are
kept in **separate files** on purpose, so notification destinations can be
managed without touching API secrets.

The app presents every config below as a section in a grouped sidebar —
*Connection* (Shop, Notifications, Webhooks), *Intake & sync* (Ingestion,
Reconciliation), *Inventory* (Locations, Inventory alert, Planning, Backorders),
*Workflows* (Order review, Refund review, Task SLA) and *Monitoring*
(Integration health) — each tracking its own unsaved-changes marker, all
persisted together with a single **Save**. (There is no longer a *Storefront*
group — see [Public product feed](#public-product-feed-a-read-endpoint--a-thin-js-client)
and [Customers](#customers) above.)

### Config file index

Every config file lives under `etc/commerce/config/`. The two connection files
are documented in full below; each remaining file is documented in detail by its
linked guide.

| File | Section | Reference |
|---|---|---|
| `shopify.yml` | Shop | [below](#etccommerceconfigshopifyyml--shop) |
| `notifications.yml` | Notifications | [below](#etccommerceconfignotificationsyml--notifications) · [`docs/notification-channels.md`](docs/notification-channels.md) |
| `ingest.yml` | Ingestion | [`docs/ingestion.md`](docs/ingestion.md) |
| `reconcile.yml` | Reconciliation | [`docs/reconciliation.md`](docs/reconciliation.md) |
| `locations.yml` | Locations | [`docs/multi-location.md`](docs/multi-location.md) |
| `inventory-alert.yml` | Inventory alert | [`docs/inventory-alert-tool.md`](docs/inventory-alert-tool.md) |
| `planning.yml` | Planning | [`docs/planning.md`](docs/planning.md) |
| `backorder.yml` | Backorders | [`docs/backorders.md`](docs/backorders.md) |
| `order-review.yml` | Order review | [below](#etccommerceconfigorder-reviewyml--order-screening-rules) · [`docs/order-review-tool.md`](docs/order-review-tool.md) |
| `refund-review.yml` | Refund review | [`docs/refund-tool.md`](docs/refund-tool.md) |
| `sla.yml` | Task SLA | [`docs/task-sla.md`](docs/task-sla.md) |
| `health.yml` | Integration health | [`docs/health-monitor.md`](docs/health-monitor.md) |

There is no longer a `storefront.yml`, a `crm.yml`, or a `sales.yml` — those
domains were simplified down to a read endpoint, an Admin-API mirror, and
built-in defaults with nothing left to configure (see
[Public product feed](#public-product-feed-a-read-endpoint--a-thin-js-client)
and [Customers](#customers)). The sales-report population now uses BUILT-IN
defaults (`financialStatus` = all statuses, cancelled excluded, `returnsBasis` =
order), still overridable per request via the reports endpoint params — see
[`docs/reports.md`](docs/reports.md).

### `etc/commerce/config/shopify.yml` — Shop

| Group | Field | Required | Purpose |
|---|---|---|---|
| Webhook | `webhookSecret` | **yes** | Shared secret from Shopify Admin → Notifications → Webhooks. Verifies incoming webhooks (HMAC-SHA256). Required regardless of the Admin API setting. |
| Admin API | `adminApi.shopDomain` / `apiVersion` / `clientID` / `clientSecret` | **yes** | Connection and OAuth credentials from Shopify Partners. The Admin API is **required** — product-webhook metafield enrichment, the inventory mirror / reconciliation, and fulfillment write-back all use it. It is active once all four fields are filled (there is no enable toggle); until then those features are skipped with a warning. |
| Admin API | `adminApi.notifyCustomer` | no | When writing a fulfillment back to Shopify, whether Shopify emails the customer a shipping notification. Off by default to avoid surprise/duplicate emails. |

### `etc/commerce/config/notifications.yml` — Notifications

Each notification (task created, backorder event, GDPR action, monitoring alert)
builds one channel-agnostic message tagged with a **category** (`inventory` /
`orders` / `refunds` / `fulfillment` / `backorders` / `compliance` /
`operations`). The file holds a `default` channel set plus, optionally, a
dedicated channel set per category under `categories:` — a listed category
delivers **only** through its own set, every other category uses `default`. The
message goes to every **enabled** channel of the chosen set; each channel renders
it in its own format. A channel is on unless you set `enabled: false`. The
channel fields below apply inside `default:` and inside each category set.

| Channel | Fields | Purpose |
|---|---|---|
| Slack | `slack.enabled`, `slack.webhookUrl` | Slack [incoming webhook](https://api.slack.com/messaging/webhooks) (mrkdwn). |
| Discord | `discord.enabled`, `discord.webhookUrl` | Discord [incoming webhook](https://support.discord.com/hc/en-us/articles/228383668-Intro-to-Webhooks) (markdown). |
| Teams | `teams.enabled`, `teams.webhookUrl` | Microsoft Teams incoming webhook (Adaptive Card). |
| LINE | `line.enabled`, `line.accessToken`, `line.to` | LINE Messaging API push (plain text). |
| Webhook | `webhook.enabled`, `webhook.url`, `webhook.textField` | Generic structured-JSON post to any HTTP endpoint / iPaaS. |
| Email | `email.enabled`, `email.smtpHost`, `smtpPort`, `security`, `username`, `password`, `from`, `to`, `subjectPrefix` | Email over SMTP (`none` / `starttls` / `ssl`). |

Notification delivery is best-effort: a failed or unconfigured channel is
logged and skipped, and never blocks the business process. Channels use the
JDK's built-in HTTP and SMTP clients — no extra JARs are required. The channel
adapters live under `content/WEB-INF/classes/commerce/` (`SlackChannel`,
`DiscordChannel`, `TeamsChannel`, `LineChannel`, `WebhookChannel`,
`EmailChannel`). See [`docs/notification-channels.md`](docs/notification-channels.md).

### `etc/commerce/config/order-review.yml` — Order screening rules

| Rule | Purpose |
|---|---|
| `highValue` | Flag orders whose total meets a per-currency threshold. |
| `flaggedFinancialStatus` | Flag orders with a risky `financial_status`. |
| `largeQuantity` | Flag orders with a bulk single-line quantity. |
| `newCustomer` | Flag first-time customers (off by default). |
| `addressMismatch` | Flag billing/shipping country mismatches. |

Set `enabled: false` to auto-approve every order. Screening is fail-open: a
missing or unparseable file auto-approves and logs a warning rather than
blocking. See [`docs/order-review-tool.md`](docs/order-review-tool.md).

---

## Shared Groovy class layer

Common logic lives in exactly one place: a set of shared Groovy classes under
`content/WEB-INF/classes/commerce/` (package `commerce`), the per-workspace
classpath root the CMS exposes to the Groovy script engine. The integration
scripts (BPMN service tasks / listeners and EIP route steps) keep only their own
business logic and call into these for everything reusable — money formatting,
order/refund maths, Shopify token + GraphQL, inventory threshold resolution,
allocation, reconciliation, reports, PIM, the notification channels,
`commerce:status` writes, and so on.

They are `.groovy` **source** — no compilation or `.jar` step; the workspace
classloader compiles them on deploy. All methods are `static` and the classes
hold no state, so they are safe to call from any context (service task, listener,
route). For the full catalogue, conventions and design rules, see
[`docs/commerce-shared-classes.md`](docs/commerce-shared-classes.md).

---

## Repository layout

```
content/        JCR content deployed into the repository
  public/commerce/
    endpoints/shopify/      Shopify webhook receiver (webhook.groovy)
    endpoints/              Public product read endpoint (catalog.groovy)
    sdk/                    Data-only storefront client (commerce.js)
  commerce/
    endpoints/              Admin/operator endpoints (sync, backfill, reconcile,
                             events, reports, dashboard, tasks, GDPR, …)
    forms/shopify/          Task UI forms (threshold / review / fulfillment)
  WEB-INF/classes/commerce/ Shared Groovy class layer (package commerce)

etc/            Server-side integration assets
  commerce/config/          13 YAML config files (see Config file index)
  commerce/scripts/shopify/ Groovy task/route helper scripts
  eip/routes/commerce/shopify/   EIP integration routes (incl. the Bulk job broker)
  bpm/processes/commerce/shopify/  BPMN workflow processes
  i18n/                     Webtop message bundles (EN/JA), one pair per app

provisioning/   First-boot identity / group / ACL / namespace descriptor (commerce.yml)
webtop/         Commerce Webtop app sources (TypeScript + Rollup)
docs/           Reference docs: architecture, status model, JCR structure, per-feature guides
```

---

## Building from source

The integration assets under `content/` and `etc/` are plain JCR
content/configuration: they are deployed to the matching repository paths and
require no compilation (the shared Groovy classes included — the workspace
classloader compiles them on deploy). The published `mintjams/cms` image already
includes them in its seed.

The **Commerce** Webtop apps are the only buildable component:

- **Commerce** — the settings console.
- **Commerce Dashboard** — the read-only KPI overview.
- **Commerce Product** / **Commerce Products** — the "product 360" editor
  (content, price/stock, metafields, planning, media; the write hub toward
  Shopify) and its read-only facet browser.
- **Commerce Customer** / **Commerce Customers** — the customer editor (Shopify
  mirror + Admin-API edits; tags/note/tax-exempt/marketing consent) and its
  read-only facet browser.
- **Commerce Order** / **Commerce Orders** — the order editor (note/tags/custom
  attributes) and its read-only facet browser.
- **Commerce Events**, **Commerce Import**, **Commerce Operation Log**,
  **Commerce Reconcile**, **Commerce Reports** — five single-concern apps
  (event log / historical backfill / outbound-write audit / drift detection &
  mirror refresh / sales reports) that replaced an earlier single "Commerce
  Operations" console.

(There is no publishing app — the public product feed above needs no rebuild
step.) All of these apps are self-contained — their only
build-time dependency is the published
[`@mintjamsinc/ichigojs`](https://github.com/mintjamsinc/ichigojs) runtime, so
they build independently of cms0:

```bash
cd webtop
npm install
npm run build        # development: unminified, inline sourcemaps
npm run build:prod   # production:  minified JS + CSS, external sourcemaps
```

The output mirrors the cms0 Webtop layout (`dist/webtop/apps/commerce/`), so
`app.js` / `index.html` / `assets/` / `app.yml` can be dropped straight into a
deployed Webtop's `apps/` directory.

### Internationalization (EN/JA)

Every Commerce Webtop app is fully localized in English and Japanese. UI text is
resolved through the shell's i18n service at runtime; the translated strings live
in per-app message bundles under [`etc/i18n/`](etc/i18n/)
(`<appId>.en.json` / `<appId>.ja.json`), deployed to JCR `/etc/i18n/` and merged
with the cms0 core bundle. The UI re-localizes live when the user changes their
**Preferences → Localization** (language, time zone, number format, currency) or
when a bundle is hot-reloaded. See [`docs/i18n.md`](docs/i18n.md) for the
conventions, the localization composable, and how to add a string, locale, or
app.

---

## License

MIT. See [`LICENSE`](LICENSE).

## Links

- Source: <https://github.com/mintjamsinc/commerce>
- Runtime platform (cms0): <https://github.com/mintjamsinc/cms0>
- UI framework (ichigojs): <https://github.com/mintjamsinc/ichigojs>
- Vendor: <https://www.mintjams.jp/>

## Trademarks

All trademarks are the property of their respective owners.
