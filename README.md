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
- **Commerce apps** — a family of virtual-desktop apps for the Webtop
  (settings console, dashboard, PIM, operations, publishing) that centralize
  configuration and day-to-day operations. Source under [`webtop/`](webtop/).
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
   we warn."
2. **Manual Inventory Check** — raised when a variant drops below its
   threshold. The operator reviews the situation and marks it reviewed.

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
Admin API — **set stock at a location**, **update a price**, **publish/unpublish a
product** — via `content/commerce/endpoints/sync.groovy`. Gated on `adminApi.enabled`
(the same switch as metafield enrichment and fulfillment write-back), with a
`dryRun` mode for safe rollout, `api` health timing, and an audit trail under
`/content/commerce/sync/`. These are also the write primitives the reconciliation
job uses to auto-heal drift. See
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
write-back reuses the Admin API settings in `shopify.yml` (gated on
`adminApi.enabled`). Tasks are worked in the Webtop **Tasks** app and share the
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

- **Dynamic thresholds (inventory rules)** — instead of every product needing a
  manually configured threshold, an **effective threshold** is resolved per
  variant: a manual per-variant override wins, else the first matching rule in
  `inventory-rules.yml` (product attributes / calendar / sales velocity), else a
  config default, else "not monitored." Every alert records *why* a threshold
  applied. See [`docs/inventory-rules.md`](docs/inventory-rules.md).
- **Multi-location inventory** — Shopify's per-location stock is ingested
  (`inventory_levels/update`, `locations/*`), aggregated per variant, and exposed
  for cross-location **allocation** decision support (which locations to draw
  from). Advisory only — it does not override Shopify's fulfillment routing. See
  [`docs/multi-location.md`](docs/multi-location.md).
- **Sales velocity & stockout forecast** — a periodic batch computes units/day
  per variant from order history, caches it, and predicts when each variant will
  run out — warning before stock hits zero and feeding the threshold rules. See
  [`docs/sales-velocity.md`](docs/sales-velocity.md).
- **Auto-reorder / replenishment** — closes the loop: a batch proposes purchase
  orders for variants that will run short, an operator approves (and can adjust
  quantity) via a **Approve Reorder** human task, and the approved PO is recorded
  and sent to the supplier. See [`docs/auto-reorder.md`](docs/auto-reorder.md).

Settings live in `etc/commerce/config/inventory-rules.yml`, `locations.yml`,
`velocity.yml` and `reorder.yml` respectively.

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
- **Reconciliation** — a periodic batch compares the CMS mirror with Shopify
  (status / price / inventory), reports drift, alerts, and — opt-in, per a per-field
  source-of-truth — heals it (push via `ShopifyWrite`, or refresh the mirror). Detect
  + report is the default. See [`docs/reconciliation.md`](docs/reconciliation.md).
- **Reports & audit export** — sales (daily orders + revenue per currency + top
  products) and the outbound-write audit trail, as JSON or CSV, built from the JCR
  audit trails. See [`docs/reports.md`](docs/reports.md).

---

## Customer CRM & marketing

Built on the ingested `customers/*` and `checkouts/*` plus the order history:

- **Segmentation** — a daily batch rolls up each customer's purchase history
  from orders and classifies them (`new` / `repeat` / `vip` / `at_risk` / `dormant`)
  into a CRM store.
- **Behaviour-change alerts** — operators are notified when a customer becomes
  newly VIP, at-risk or dormant.
- **Abandoned cart follow-up** — a batch finds idle, un-completed checkouts and
  follows up: a customer recovery email (opt-in, off by default) plus a debounced
  operator summary.

Settings live in `etc/commerce/config/crm.yml`. See [`docs/crm.md`](docs/crm.md).

---

## Headless storefront

A customer-facing storefront built with ichigo.js, served entirely from
a public, sanitized catalog projection — the admin product store is never exposed:

- **Storefront** — a single ichigo.js page (catalog/search → product → cart)
  reading `/content/public/commerce/catalog/` (published from the product mirror +
  PIM + inventory by a service-user batch). **Checkout** redirects to Shopify's
  hosted checkout via a cart permalink, so no Storefront API token is needed.
- **Real-time inventory** — the `inventory_levels/update` route refreshes a
  public `inventory.json` within seconds; the storefront polls it and shows
  "only a few left" / "sold out" badges live.
- **Content commerce** — CMS-authored block landing pages (`hero` / `markdown`
  / `html` / `products`) mix articles with product showcases; the publisher resolves
  product blocks against the catalog and an ichigo.js renderer serves them, with the
  embedded cards linking into the storefront and showing live stock. A `welcome` seed
  page ships.

Settings live in `etc/commerce/config/storefront.yml`. See
[`docs/storefront.md`](docs/storefront.md).

---

## Operations & observability

Beyond the business workflows, the platform watches itself and gives operators a
single place to see what is happening:

- **Commerce Dashboard** — a read-only, real-time Webtop app that ties the
  platform's data together into KPI cards (sales trend, inventory, forecast,
  reorders, locations, backorders, customers, tasks, integration health, event
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
  CRM, reconciliation, tasks, health, …) and operator edits (PIM/pages,
  outbound-sync audit, on-demand recompute).
- `/content/public/commerce` — the public storefront projection (anonymous read
  via the cms0 public-access rule; the publishers get write).
- `/etc/commerce/config` — read-only config for everyone; the service user
  additionally gets write to cache the Shopify Admin API access token.

The built-in `admin` user bypasses ACLs and needs no grant; add real operators to
`commerce-operators` from the Webtop **Identity Manager** app.

The same descriptor registers the JCR **`commerce` namespace**
(`http://www.mintjams.jp/commerce/1.0`) used by every commerce metadata property
(see [Status model](#status-model)); a one-time migration of legacy pre-namespace
data is in `content/WEB-INF/classes/commerce/NamespaceMigration.groovy`.

---

## Status model

Entity status is modelled on **two independent axes** so that "what the record
is in the source system" never gets conflated with "how far our pipeline has
processed it." This is a platform invariant — see
[`docs/commerce-status.md`](docs/commerce-status.md) for the authoritative,
closed enumeration.

| Property | Axis | Owner |
|---|---|---|
| `commerce:status` | Integration processing lifecycle (`received`, `threshold_pending`, `review_pending`, `monitored`, `approved`, `fulfillment_pending`, `fulfilled`, `resolved`, `backordered`, `ready`, `released`, `cancelled`, `error`, `deleted`) | This pipeline (EIP + BPMN) |
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
*Connection* (Shop, Notifications), *Intake & sync* (Ingestion, Reconciliation),
*Inventory* (Locations, Inventory rules, Forecast, Replenishment, Backorders),
*Workflows* (Order review, Refund review, Task SLA), *Storefront* (Storefront,
Customers/CRM) and *Monitoring* (Integration health) — each tracking its own
unsaved-changes marker, all persisted together with a single **Save**.

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
| `inventory-rules.yml` | Inventory rules | [`docs/inventory-rules.md`](docs/inventory-rules.md) |
| `velocity.yml` | Forecast | [`docs/sales-velocity.md`](docs/sales-velocity.md) |
| `reorder.yml` | Replenishment | [`docs/auto-reorder.md`](docs/auto-reorder.md) |
| `backorder.yml` | Backorders | [`docs/backorders.md`](docs/backorders.md) |
| `order-review.yml` | Order review | [below](#etccommerceconfigorder-reviewyml--order-screening-rules) · [`docs/order-review-tool.md`](docs/order-review-tool.md) |
| `refund-review.yml` | Refund review | [`docs/refund-tool.md`](docs/refund-tool.md) |
| `sla.yml` | Task SLA | [`docs/task-sla.md`](docs/task-sla.md) |
| `storefront.yml` | Storefront | [`docs/storefront.md`](docs/storefront.md) |
| `crm.yml` | Customers/CRM | [`docs/crm.md`](docs/crm.md) |
| `health.yml` | Integration health | [`docs/health-monitor.md`](docs/health-monitor.md) |

### `etc/commerce/config/shopify.yml` — Shop

| Group | Field | Required | Purpose |
|---|---|---|---|
| Webhook | `webhookSecret` | **yes** | Shared secret from Shopify Admin → Notifications → Webhooks. Verifies incoming webhooks (HMAC-SHA256). Required regardless of the Admin API setting. |
| Admin API | `adminApi.enabled` | no | When `true`, product webhooks are enriched with metafields from the Shopify Admin API (GraphQL) and completed Fulfill Order tasks are written back as fulfillments. When `false`, no Admin API calls are made and the fields below are ignored. |
| Admin API | `adminApi.shopDomain` / `apiVersion` / `clientID` / `clientSecret` | yes *(when enabled)* | Connection and OAuth credentials from Shopify Partners. All four are required once the Admin API is enabled. |
| Admin API | `adminApi.notifyCustomer` | no | When writing a fulfillment back to Shopify, whether Shopify emails the customer a shipping notification. Off by default to avoid surprise/duplicate emails. |

### `etc/commerce/config/notifications.yml` — Notifications

Each manual task builds one channel-agnostic message and dispatches it to every
**enabled** channel; each channel renders it in its own format. A channel is on
unless you set `enabled: false`.

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
order/refund maths, Shopify token + GraphQL, inventory-rule resolution, sales
velocity, allocation, reconciliation, reports, PIM, the notification channels,
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
    storefront/             Public storefront page (ichigo.js)
    landing/                Published content-commerce landing pages
  commerce/
    endpoints/              Admin/operator endpoints (sync, replay, publish, …)
    forms/shopify/          Task UI forms (threshold / review / reorder)
    pages/                  Landing-page editor content
  WEB-INF/classes/commerce/ Shared Groovy class layer (package commerce)

etc/            Server-side integration assets
  commerce/config/          15 YAML config files (see Config file index)
  commerce/scripts/shopify/ Groovy task/route helper scripts
  eip/routes/commerce/shopify/   EIP integration routes
  bpm/processes/commerce/shopify/  BPMN workflow processes

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

The **Commerce** Webtop apps are the only buildable component — the **Commerce**
settings console, the read-only **Commerce Dashboard**, the **Commerce PIM**
product-enrichment editor, the **Commerce Operations** console (outbound sync /
reconciliation / event replay), and **Commerce Publishing** (storefront publish
status / rebuild + landing-page editor). They are self-contained — their only
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
