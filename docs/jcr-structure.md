# JCR Runtime Structure

Runtime paths created dynamically by Camel routes. Not stored in this repository.

## Order Storage

```
/content/commerce/orders/
├── raw/                              # Successfully received orders
│   └── {yyyy}/
│       └── {MM}/
│           └── order_{id}.json       # Raw Shopify JSON
└── error/                            # Orders that failed processing
    └── order_{id}.json               # Moved here on error
```

## Refund Storage

```
/content/commerce/refunds/
├── raw/                              # Successfully received refunds
│   └── {yyyy}/
│       └── {MM}/
│           └── refund_{id}.json      # Raw Shopify JSON (refunds/create)
└── error/                            # Refunds that failed processing
    └── refund_{id}.json              # Moved here on error
```

## Node Properties

Status is modelled on two independent axes. See
[`commerce-status.md`](commerce-status.md) for the authoritative status list.

- `commerce:status` — our **integration processing lifecycle** (closed enum:
  `received`, `threshold_pending`, `review_pending`, `monitored`, `approved`,
  `fulfillment_pending`, `fulfilled`, `resolved`, `error`, `deleted`).
- `commerce:source_status` — a mirror of Shopify's **business status**
  (products: `active`/`archived`/`draft`; orders: `financial_status`).

### Order properties

Each order file carries the following JCR properties:

| Property | Type | Description |
|---|---|---|
| `commerce:order_id` | String | Shopify order ID |
| `commerce:customer_email` | String | Customer email |
| `commerce:total_price` | String | Order total |
| `commerce:currency` | String | Currency code (e.g., JPY, USD) |
| `commerce:order_number` | String | Human-readable order number |
| `commerce:status` | String | Processing status: `received` / `review_pending` / `approved` / `fulfillment_pending` / `fulfilled` / `error` |
| `commerce:source_status` | String | Shopify `financial_status` (e.g. `paid`) |
| `commerce:errorMessage` | String | Error message (on failure) |
| `commerce:stackTrace` | String | Stack trace (on failure) |
| `commerce:tracking_number` | String | Tracking number entered by the fulfiller (when provided) |
| `commerce:tracking_company` | String | Carrier entered by the fulfiller (when provided) |
| `commerce:fulfilled_at` | String | ISO timestamp the order was fulfilled |
| `commerce:fulfillment_writeback` | String | Shopify write-back outcome: `ok` / `skipped` / `failed` |
| `commerce:fulfillment_id` | String | Shopify fulfillment GID (on a successful write-back) |
| `commerce:fulfillment_error` | String | Write-back error detail (on a failed write-back) |
| `fulfillment` | String | JSON tracking details from the Fulfill Order form (`{trackingNumber,trackingCompany,trackingUrl,at}`) |
| `commerce:refunded_amount` | String | Cumulative amount refunded across all refunds for this order |
| `commerce:refund_count` | String | Number of refunds recorded against this order |
| `internal_memo` | String | JSON memo authored by an operator in the order forms (`{id,name,at,content}`) |

Note: `commerce:source_status` on an order becomes `partially_refunded` /
`refunded` once refunds are recorded (set by `recordRefund.groovy`).

### Refund properties

Each refund file carries the following JCR properties:

| Property | Type | Description |
|---|---|---|
| `commerce:refund_id` | String | Shopify refund ID |
| `commerce:order_id` | String | Shopify order ID the refund belongs to |
| `commerce:status` | String | Processing status: `received` / `review_pending` / `resolved` / `error` |
| `commerce:refund_amount` | String | Total refunded amount (sum of successful refund transactions) |
| `commerce:currency` | String | Currency code (e.g., JPY, USD) |
| `commerce:restocked` | String | `true` if any refunded line item restocked inventory |
| `commerce:line_item_count` | String | Number of refunded line items |
| `commerce:refund_note` | String | Refund note/reason (truncated to 2048 chars) |
| `commerce:order_updated` | String | `true` once the order's refund summary has been applied (idempotency guard) |
| `commerce:errorMessage` | String | Error message (on failure) |
| `commerce:stackTrace` | String | Stack trace (on failure) |
| `internal_memo` | String | JSON memo authored by a reviewer in the Refund Review form (`{id,name,at,content}`) |

### Product properties

Each product file (`/content/commerce/products/product_{id}.json`) carries:

| Property | Type | Description |
|---|---|---|
| `commerce:product_id` | String | Shopify product ID |
| `commerce:title` | String | Product title |
| `commerce:handle` | String | URL handle |
| `commerce:status` | String | Processing status: `received` / `threshold_pending` / `review_pending` / `monitored` / `error` / `deleted` |
| `commerce:source_status` | String | Shopify business status: `active` / `archived` / `draft` |
| `commerce:vendor` | String | Vendor |
| `commerce:product_type` | String | Product type |
| `commerce:tags` | String | Comma-separated tags |
| `commerce:updated_at` | Date | Shopify `updated_at` |
| `commerce:deletedAt` | String | Deletion timestamp (set on `products/delete`) |
| `metafields` | String | Shopify metafields mirror (when the Admin API is enabled) |
| `pim` | String | CMS-authored PIM overlay JSON (extended attributes, multi-language, metafields) — #23 |
| `pim:updated_at` | String | Last PIM overlay edit timestamp |

### Property namespace & legacy-data migration

Commerce metadata lives in the **`commerce:`** namespace (e.g. `commerce:product_id`),
which every consumer and query relies on. The Shopify ingest routes set these via
`cms:setProperties?includes=commerce:*` from `commerce:*` exchange headers (the
trailing `*` keeps the full, namespaced name).

> Note: earlier route revisions used `includes=commerce_~`, where the trailing `~`
> **strips** the `commerce_` prefix — so values were written to the bare names
> (`product_id`, `title`, …) instead of `commerce:*`. Nodes mirrored before the fix
> carry the legacy names. Run the one-time migration to bring them in line:
> `GET …/endpoints/migrate-namespace.groovy` (dry run, reports what would change)
> then `POST …/endpoints/migrate-namespace.groovy` (applies it). It is idempotent
> and type-preserving — see `commerce.NamespaceMigration`.

## Configuration

```
/etc/commerce/
├── config/
│   ├── shopify.yml                   # Shopify API settings
│   ├── notifications.yml             # Notification channels (Slack/Discord/Teams/LINE/webhook/email)
│   ├── health.yml                    # Integration health monitor alert thresholds
│   ├── sla.yml                       # Task SLA escalation rules
│   ├── inventory-rules.yml           # Dynamic inventory threshold rules (category/tag/season/velocity)
│   ├── velocity.yml                  # Sales velocity & stockout forecast settings
│   ├── reorder.yml                   # Auto-reorder / replenishment settings
│   ├── locations.yml                 # Multi-location allocation strategy
│   ├── order-review.yml              # Order screening (review) rules
│   ├── refund-review.yml             # Refund screening (review) rules
│   ├── backorder.yml                 # Backorder / pre-order detection settings
│   ├── ingest.yml                    # Event ingestion / replay settings
│   ├── reconcile.yml                 # CMS <-> Shopify reconciliation settings
│   ├── crm.yml                       # Customer segmentation / abandoned cart settings
│   └── storefront.yml                # Headless storefront / catalog publishing settings
├── routes/
│   ├── shopify/
│   │   └── order-paid.yaml           # Order received route
│   └── common/
│       └── error-handler.yaml        # Shared error handler
└── processes/                        # BPMN (future)
```

## Health Monitor Storage

Integration health metrics recorded by the routes / webhook endpoint. See
[health-monitor.md](health-monitor.md).

```
/content/commerce/health/
├── metrics/
│   └── {yyyy}/
│       └── {MM}/
│           └── {yyyy-MM-dd}.json      # daily counters (webhook / route / api)
└── state.json                         # per-alert cooldown timestamps
```

## Multi-Location Inventory Storage

Per-location stock + location metadata from Shopify. See
[multi-location.md](multi-location.md).

```
/content/commerce/inventory/
├── levels/{inventory_item_id}.json    # { inventory_item_id, locations: { "<id>": { available, updatedAt } } }
└── locations/{location_id}.json       # raw Shopify location payload (name, …)
```

## Purchase Order Storage

Reorder proposals / purchase orders created by the replenishment workflow. See
[auto-reorder.md](auto-reorder.md).

```
/content/commerce/purchase-orders/
└── {yyyy}/
    └── {MM}/
        └── po_{id}.json               # PO record; commerce:status:
                                        # review_pending → approved/rejected → ordered/order_failed
```

## Backorder Storage

Line-level backorder / pre-order records created by the order-paid route. See
[backorders.md](backorders.md).

```
/content/commerce/backorders/
└── {yyyy}/
    └── {MM}/
        └── backorder_{orderId}_{lineItemId}.json   # one per backordered line;
                                                      # commerce:status:
                                                      # backordered → ready → released,
                                                      # or → cancelled
```

Each backorder record carries the following JCR properties:

| Property | Type | Description |
|---|---|---|
| `commerce:status` | String | Processing status: `backordered` / `ready` / `released` / `cancelled` / `error` |
| `commerce:reason` | String | `shortfall` (stock too low) or `preorder` (sold ahead of stock) |
| `commerce:order_id` | String | Shopify order ID the line belongs to |
| `commerce:order_number` | String | Human-readable order number |
| `commerce:line_item_id` | String | Shopify order line-item ID (the record key, with order ID) |
| `commerce:variant_id` | String | Shopify variant ID |
| `commerce:inventory_item_id` | String | Inventory item ID (links to `inventory/levels`; the release key) |
| `commerce:quantity` | String | Units awaited (the shortfall, or the whole line for a pre-order) |
| `commerce:ordered_quantity` | String | Units ordered on the line |
| `commerce:customer_email` | String | Customer email |
| `commerce:title` | String | Line-item title |
| `commerce:sku` | String | Line-item SKU |
| `commerce:created_at` | String | ISO timestamp the backorder was detected |
| `commerce:released_at` | String | ISO timestamp the backorder was released |
| `commerce:cancelled_at` | String | ISO timestamp the backorder was cancelled |
| `commerce:cancel_reason` | String | Why it was cancelled (e.g. `refunded`) |
| `internal_memo` | String | JSON memo authored by an operator in the release form (`{id,name,at,content}`) |

## Event Log & Generic Entity Storage

The source-agnostic ingestion core (`direct:commerce-ingest`) records every inbound
event and normalizes topics that have no bespoke workflow. See
[ingestion.md](ingestion.md).

```
/content/commerce/events/                 # durable event log (replay + audit)
└── {source}/                             # backend id, e.g. shopify
    └── {yyyy}/
        └── {MM}/
            └── {eventId}.json            # { source, topic, event_id, entity_type,
                                          #   entity_id, received_at, status,
                                          #   attempts, payload } + commerce:* props
                                          # commerce:status: received → processed | error

/content/commerce/entities/             # normalized current-state entities for the
└── {source}/                           # generic topics (no bespoke workflow),
    └── {collection}/                   # namespaced by source + collection so they
        └── {id}.json                   # never collide with the curated stores or
                                        # across backends. e.g. shopify/customers/123.json,
                                        # shopify/fulfillments/ , carts/ , checkouts/ .
                                        # latest-wins; commerce:status received|deleted
```

Event-log properties: `commerce:status`, `commerce:source`, `commerce:topic`,
`commerce:event_id`, `commerce:entity_type`, `commerce:entity_id`,
`commerce:received_at`, `commerce:attempts`, `commerce:last_error` (on failure).

Generic entity properties: `commerce:status`, `commerce:source`, `commerce:topic`,
`commerce:entity_type`, `commerce:entity_id`, `commerce:updated_at`, plus best-effort
`commerce:customer_email` / `commerce:order_id`, and `commerce:deletedAt` on delete.

## Outbound Sync Audit

Audit trail of CMS → Shopify writes (#2), one record per attempt (incl. dry runs
and failures). See [bidirectional-sync.md](bidirectional-sync.md).

```
/content/commerce/sync/
└── {yyyy}/
    └── {MM}/
        └── sync_{epochMs}.json    # { at, source:"cms", action, request, status,
                                    #   result, error } + commerce:status
                                    # commerce:status: ok | failed | dryrun
```

## Content-Commerce Pages

Editorial landing pages (#22): CMS-authored block documents and their published
projection. See [storefront.md](storefront.md).

```
/content/commerce/pages/{slug}.json      # authored block document (admin; a `welcome` seed ships)
/content/public/commerce/
├── landing/index.html                   # the ichigo.js landing renderer (?slug=)
└── pages/
    ├── index.json                       # { meta, pages:[ {slug,title} ] }
    └── {slug}.json                      # resolved public page (product blocks → cards)
```

## Public Storefront Catalog

Sanitized, anonymous-readable projection of the active catalog for the headless
storefront (#20/#21), built by the publisher from the admin product store + PIM +
inventory levels. The admin data under `/content/commerce/products` is never
exposed. See [storefront.md](storefront.md).

```
/content/public/commerce/
├── storefront/
│   └── index.html                 # the ichigo.js storefront SPA
└── catalog/                       # published projection (service user writes, anon reads)
    ├── index.json                 # { meta, products:[ card … ] } — list/search
    ├── products/{id}.json         # full public product detail (variants, images, localized)
    ├── inventory.json             # { updatedAt, items:{ itemId: available } } — realtime (#21)
    └── store.json                 # { name, shopDomain, currency, lowStock } — store + checkout
```

## CRM Storage

Per-customer purchase-history rollup + segment from the CRM batch (#13/#15). See
[crm.md](crm.md).

```
/content/commerce/crm/
├── customers/
│   └── {key}.json                 # key = id_{customerId} | email_{hash};
│                                   # { orders, totalSpent, currency, aov,
│                                   #   firstOrderAt, lastOrderAt, segment, vip,
│                                   #   recency } + commerce:segment / :vip /
│                                   #   :recency / :orders / :total_spent /
│                                   #   :last_order_at / :email
└── abandoned-alert-state.json      # abandoned-cart operator-alert cooldown
```

Abandoned-cart reminder bookkeeping is stored on the **checkout entity** records
(`/content/commerce/entities/{source}/checkouts/{id}.json`): `commerce:reminders_sent`
and `commerce:last_reminder_at` (#14).

## Reconciliation Storage

Drift reports + the round-robin cursor from the CMS ↔ Shopify reconciliation batch
(#24). See [reconciliation.md](reconciliation.md).

```
/content/commerce/reconciliation/
├── {yyyy}/
│   └── {MM}/
│       └── recon_{epochMs}.json   # { generatedAt, checked, productsWithDrift,
│                                   #   totalDiffs, healed, diffs:[…] } + commerce:* props
├── state.json                      # { cursor, lastRunAt } (batch resume point)
└── alert-state.json                # drift-alert cooldown
```

## Analytics Storage

Sales velocity cache + stockout-forecast cooldown state. See
[sales-velocity.md](sales-velocity.md).

```
/content/commerce/analytics/
├── velocity.json                      # cached per-variant velocity (units/day)
└── forecast-state.json                # per-variant stockout alert cooldowns
```

## Task SLA Storage

Cooldown state for task SLA escalations. See [task-sla.md](task-sla.md).

```
/content/commerce/tasks/
└── sla-state.json                     # per task+rule escalation cooldown timestamps
```

## Endpoints

```
/content/public/commerce/
└── endpoints/
    └── shopify/
        └── webhook.groovy            # Webhook receiver (public)

/content/commerce/endpoints/
├── health.groovy                     # Health snapshot JSON (admin/authenticated)
├── tasks.groovy                      # Open tasks + SLA status JSON (admin/authenticated)
├── forecast.groovy                   # Stockout forecast JSON (admin/authenticated)
├── inventory-locations.groovy        # Per-location breakdown + allocation JSON (admin/authenticated)
├── backorders.groovy                 # Backorder book snapshot JSON (admin/authenticated)
├── events.groovy                     # Event log list + replay (admin/authenticated)
├── sync.groovy                       # CMS → Shopify outbound sync (admin/authenticated)
├── pim.groovy                        # PIM overlay view/edit + product search (admin/authenticated)
├── reconcile.groovy                  # Reconciliation report + trigger (admin/authenticated)
├── reports.groovy                    # Sales / operations reports (JSON/CSV) (admin/authenticated)
├── crm.groovy                        # Customer segments + abandoned carts (admin/authenticated)
├── storefront.groovy                 # Storefront publish status + rebuild (admin/authenticated)
├── pages.groovy                      # Landing page CRUD (content commerce) (admin/authenticated)
└── dashboard.groovy                  # Aggregated dashboard snapshot JSON (admin/authenticated)
```

The webhook receiver is a backend adapter: it verifies Shopify's HMAC and forwards
**every** topic to the source-agnostic ingest core (`direct:commerce-ingest`). Topics
with a dedicated workflow — `orders/paid`, `products/create`, `products/update`,
`products/delete`, `refunds/create`, `inventory_levels/update`, `locations/create`,
`locations/update` — are handled by their routes; every other topic (`customers/*`,
`fulfillments/*`, `carts/*`, `checkouts/*`, …) is normalized generically. See
[ingestion.md](ingestion.md).

HTTP access:
- `GET  /bin/cms.cgi/{workspace}/content/public/commerce/storefront/index.html`  (public storefront)
- `GET  /bin/cms.cgi/{workspace}/content/public/commerce/catalog/index.json`  (public catalog)
- `GET  /bin/cms.cgi/{workspace}/content/public/commerce/landing/index.html?slug=welcome`  (content-commerce page)
- `POST /bin/cms.cgi/{workspace}/content/public/commerce/endpoints/shopify/webhook.groovy`
- `GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/health.groovy?days=7`
- `GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/tasks.groovy`
- `GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/forecast.groovy?warnDays=7`
- `GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/inventory-locations.groovy?productId=123`
- `GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/backorders.groovy?limit=50`
- `GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/events.groovy?status=error&limit=100`
- `POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/events.groovy   {"status":"error"}`
- `GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/sync.groovy`  (capability)
- `POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/sync.groovy   {"action":"inventory",…}`
- `GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/pim.groovy?productId=123`
- `POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/pim.groovy   {"productId":123,"pim":{…}}`
- `GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/reconcile.groovy`
- `POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/reconcile.groovy`
- `GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/reports.groovy?type=sales&days=30&format=csv`
- `GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/crm.groovy?view=segments`
- `POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/crm.groovy`
- `GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/dashboard.groovy?days=7&salesDays=30`

## Finder App Workflow

1. Open Finder, navigate to `/content/commerce/orders/`
2. `raw/` folder: successfully received orders, organized by year/month
3. `error/` folder: failed orders with error details in properties
4. Select a file > view properties to inspect `commerce:error_log` and `commerce:error_detail`
5. After fixing the issue, drag the file back to trigger reprocessing
