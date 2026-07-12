# Commerce JCR Property Catalog

Every custom JCR property written by the commerce integration, grouped by entity. Because the
platform **auto-indexes every property** (cms0), each row below is also a **queryable axis** — the
moment a property is written it is available to XPath queries, no index configuration needed. This
doc is the map of "what can I filter / sort / paginate on".

## Conventions & caveats

- **Namespaces**: `commerce:*` (integration metadata, the bulk — including the reorder-review
  form handoff + the durable last-order record, `commerce:reorder_*`), `pim` / `pim:*` (PIM
  overlay). A few JSON blobs are stored as plain-named string properties (`metafields`, `pim`,
  `fulfillment`, `internal_memo`, `inventory_level_config`).
  - **Reorder props were renamed 2026-07-08**: they formerly used a `reorder:` prefix, but that
    namespace is NOT registered in JCR, so every `setProperty("reorder:…")` threw. They now live
    under the registered `commerce:` namespace. No data migration was needed (the failed writes left
    nothing behind).
  - **Reorder flows are now PER INVENTORY ITEM** (`inventory-alert-flow` businessKey =
    inventory_item_id, 2026-07-08), so several variants of one product can have an open review /
    receive at once. To keep their handoff from colliding, the transient draft / receipt are stored
    as **PER-ITEM JSON maps on the product node** — `commerce:reorder_draft` and
    `commerce:reorder_receipt` (`{ "<itemId>": {…} }`, exactly like `commerce:reorder_last_orders`).
    NOT flat props (which collide across items) and NOT process variables (a form's
    `setProcessVariables` has **REPLACE semantics** — it drops every variable not in the call,
    including the sweep's `inventoryItemId` / `productPath`; forms hand off via node properties +
    `completeTask([])`, the proven order-review pattern). The service tasks read the item's entry and
    clear it on completion.
- **Two status axes** (see [commerce-status.md](commerce-status.md)):
  - `commerce:status` — our **integration processing lifecycle** (closed enum, per entity).
  - `commerce:source_status` — a mirror of **Shopify's business status**.
- **Stored types are REAL types** (re-implementation, 2026-07): Boolean flags, Long counts,
  Decimal money, Date timestamps — so numeric/date range predicates work natively
  (`@commerce:total_price_base >= 10000`, `@commerce:updated_at < xs:dateTime(...)`,
  `@commerce:marketing_enabled = true`). Strings remain for ids (Shopify ids exceed what a
  UI-safe integer needs and are never ranged), enums, and free text. Legacy String-typed
  values on already-written nodes are retyped by an earlier boot migration.
- **Bulk / time-series data is ONE String-JSON property**, never child nodes or multi-value
  properties (they would bloat the auto-index): `commerce:reorder_last_orders`,
  `pim`, `metafields`, `inventory_level_config`.
- **Lifecycle timestamps**: every state mutation records WHEN —
  `commerce:status_updated_at` (Date, stamped by `commerce.WorkflowStatus.write` on every
  status transition), `commerce:refreshed_at` (Date, stamped by `Reconciliation.applyRefresh`
  mirror patches), `commerce:fulfillment_writeback_at` (Date). New write paths must follow.
- **Storage ids vs wire ids**: `commerce:*_id` properties store the NUMERIC Shopify id
  (query axis); the wire exposes the GID form via `commerce.Api.gid` at the endpoint exit.
  Never store GIDs in these properties, never emit numeric ids on the wire.
- **Writer** = the route/script that sets it. Core fields are mapped from exchange headers in
  the ingest routes (typed via `transform:toDecimal|toLong|toDate`); operational fields are set
  directly via `res.setProperty` with typed values.

## How to query (XPath)

```
// by status
/jcr:root/content/commerce/orders/raw//element(*, nt:file)[@commerce:status='review_pending']

// numeric range — real numbers compare numerically
/jcr:root/content/commerce/inventory/index//element(*, nt:file)[@commerce:available_total < 5]

// composite flag + range (customers)
/jcr:root/content/commerce/customers//element(*, nt:file)
    [@commerce:marketing_enabled = true and @commerce:created_at >= xs:dateTime('2026-01-01T00:00:00.000Z')]
```

---

## Products — `/content/commerce/products/product_{id}.json`

MIME type: `application/vnd.mintjams.commerce.product+json` (associates the node with the
product editor; stamped retroactively on existing nodes by an earlier boot migration).
Writer: `etc/eip/routes/commerce/shopify/product-update.xml` (headers → `commerce:*`), except where noted.

| Property | Type | Meaning |
|---|---|---|
| `commerce:product_id` | String | Shopify product ID |
| `commerce:title` | String | Product title |
| `commerce:handle` | String | URL handle |
| `commerce:status` | String | Processing lifecycle: `received` / `threshold_pending` / `review_pending` / `monitored` / `error` / `deleted` |
| `commerce:source_status` | String | Shopify business status: `active` / `archived` / `draft` (also refreshed by reconcile) |
| `commerce:vendor` | String | Vendor |
| `commerce:product_type` | String | Product type |
| `commerce:tags` | String | Comma-separated tags |
| `commerce:updated_at` | **Date** | Shopify `updated_at` |
| `commerce:deletedAt` | **Date** | Deletion timestamp (`product-delete.xml`) |
| `metafields` | String(JSON) | Shopify metafields mirror, when the Admin API is on |
| `pim` | String(JSON) | CMS-authored PIM overlay (`commerce.Pim`) — includes `planning` |
| `pim:updated_at` | String | Last PIM overlay edit |
| `pim.planning` (inside `pim` JSON) | JSON, per-variant | **Per-variant reorder threshold** keyed by variantId: `threshold` — a plain unit count, the fixed reorder point. Operator-set values only; the system never derives or auto-writes it |
| `inventory_level_config` | String(JSON) | Legacy per-variant manual threshold (Set Inventory Threshold form); slots between the planning value and the global default |
| `commerce:reorder_draft` | String(JSON map) | Reorder DRAFT handoff, per item: `{ "<itemId>": { qty, destination, at, by, activate } }`. The review form saves it incrementally; `createIncomingTransfer.groovy` reads the item's entry, records the incoming stock, moves it to `commerce:reorder_last_orders`, and removes the entry. `activate` (bool, default true / ON when absent) is the "auto-enable fulfillment" checkbox — when the destination does not yet stock this item, `createIncomingTransfer` calls `ShopifyWrite.ensureStockedAt` (`inventoryActivate`) before creating the transfer so it is accepted. |
| `commerce:reorder_receipt` | String(JSON map) | Receipt handoff, per item: `{ "<itemId>": { qty, at, by } }`. The receiving form writes it; `recordReceived.groovy` reads the item's entry, merges it into `commerce:reorder_last_orders[itemId]`, and removes the entry. |
| `commerce:reorder_last_orders` | String(JSON map) | Previous-reorder record map keyed by inventory_item_id: `{ "<itemId>": { "at": ISO8601, "qty": N, "receivedAt": ISO8601, "receivedQty": N, ... } }`. On stock check + reorder task completion `createIncomingTransfer.groovy` writes `{at,qty}`; on receive-confirmation task completion `recordReceived.groovy` writes `receivedAt` etc. onto the product node, so the next task's form shows the previous reorder (order date / quantity / received date / previous lead time) for reference. Based on the manually entered confirmed quantity; no system suggestion |

## Inventory index — `/content/commerce/inventory/index/{inventory_item_id}.json`

Body JSON written by `indexInventoryItems.groovy` (products/*); properties by the writers noted.

| Property | Type | Meaning |
|---|---|---|
| `commerce:product_id` | String | Owning product (lookup/facet axis). Writer: `indexInventoryItems.groovy` |
| `commerce:variant_id` | String | Variant (variant → item resolution axis). Writer: `indexInventoryItems.groovy` |
| `commerce:available_total` | **Long** | Materialized total available across locations. Writer: alert sweep → `Locations.materializeTotal` (single writer, cluster-guarded) |
| `commerce:available_total_at` | String | ISO timestamp the total was computed |

*(Body JSON, not properties: `inventory_item_id`, `product_id`, `product_path`, `variant_id`, `variant_title`, `updatedAt`.)*

## Orders — `/content/commerce/orders/raw/{yyyy}/{MM}/order_{id}.json`

Core fields: `order-paid.xml` (headers → `commerce:*`, typed via transforms). Operational fields: scripts below. Body MIME is `application/vnd.mintjams.commerce.order+json` (stamped by `order-paid.xml`; existing nodes restamped by an earlier boot migration), which launches the Commerce Order editor. `orders/updated` (`order-updated.xml`) re-upserts the body + Shopify-derived core props on any order change — a mirror-only refresh that never touches `commerce:status` or the operational fields.

| Property | Type | Meaning | Writer |
|---|---|---|---|
| `commerce:order_id` | String | Shopify order ID | order-paid.xml |
| `commerce:customer_email` | String | Customer email (dummied by GDPR redact) | order-paid.xml |
| `commerce:customer_id` | String | Shopify customer numeric id (`legacyId`; empty for guest orders) — the customer-level grouping axis for sales aggregation. Survives GDPR redaction (redact only dummies `customer_email`). | order-paid.xml, order-updated.xml, `importBulkResult.groovy` |
| `commerce:total_price` | **Decimal** | Order total (native currency) | order-paid.xml |
| `commerce:total_price_base` | **Decimal** | Order total in the SHOP (base) currency, from `total_price_set.shop_money` — the base-currency aggregation axis | order-paid.xml |
| `commerce:currency` | String | Native currency (JPY/USD…) | order-paid.xml |
| `commerce:base_currency` | String | Shop currency of `total_price_base` | order-paid.xml |
| `commerce:order_number` | **Long** | Human-readable number | order-paid.xml |
| `commerce:ordered_at` | **Date** | Shopify order `created_at` — the primary date/period index axis for sales aggregation (facet range/timeseries, PoP windows; order-cohort basis). The order's true business date, NOT the paid month the node is foldered under. Survives GDPR redaction. | order-paid.xml, order-updated.xml, `importBulkResult.groovy` |
| `commerce:status` | String | `received`/`review_pending`/`approved`/`fulfillment_pending`/`fulfilled`/`cancelled`/`error` | order-paid.xml, workflow scripts |
| `commerce:source_status` | String | Shopify `financial_status`; becomes `partially_refunded`/`refunded` after refunds | order-paid.xml, `recordRefund.groovy` |
| `commerce:review_decision` | String | Order Review outcome: `approved` / `rejected` | order-review form |
| `commerce:cancel_reason` | String | Operator's rejection reason (required on reject; sent to Shopify as the staff note) | order-review form |
| `commerce:cancel_writeback` | String | Shopify Order Cancel outcome: `ok`/`failed`/`skipped` | `cancelOrder.groovy` |
| `commerce:cancel_error` | String | Cancel write-back error detail | `cancelOrder.groovy` |
| `commerce:cancelled_at` | **Date** | Cancelled in Shopify (on success) | `cancelOrder.groovy` |
| `commerce:tracking_number` | String | Tracking number entered by fulfiller | `recordFulfillment.groovy` |
| `commerce:tracking_company` | String | Carrier | `recordFulfillment.groovy` |
| `commerce:fulfilled_at` | **Date** | Fulfilled timestamp | `recordFulfillment.groovy` |
| `commerce:fulfillment_writeback` | String | Shopify write-back outcome: `ok`/`skipped`/`failed` | `recordFulfillment.groovy` |
| `commerce:fulfillment_writeback_at` | **Date** | WHEN the write-back outcome was recorded (lifecycle rule) | `recordFulfillment.groovy` |
| `commerce:fulfillment_id` | String | Shopify fulfillment id, numeric (on success; legacy rows may hold the GID) | `recordFulfillment.groovy` |
| `commerce:fulfillment_error` | String | Write-back error detail (on failure) | `recordFulfillment.groovy` |
| `commerce:refunded_amount` | **Decimal** | Cumulative refunded across all refunds | `recordRefund.groovy` |
| `commerce:refund_count` | **Long** | Number of refunds against this order | `recordRefund.groovy` |
| `commerce:gdpr_redacted` | **Boolean** | PII anonymized (GDPR redact idempotency guard) | `commerce.Gdpr` |
| `commerce:redacted_at` | **Date** | Redaction timestamp | `commerce.Gdpr` |
| `fulfillment` | String(JSON) | Tracking details from the Fulfill Order form | task form |
| `internal_memo` | String(JSON) | Operator memo `{id,name,at,content}` | order form |

## Refunds — `/content/commerce/refunds/raw/{yyyy}/{MM}/refund_{id}.json`

Core: `refund-created.xml`. Amounts: `recordRefund.groovy`.

| Property | Type | Meaning |
|---|---|---|
| `commerce:refund_id` | String | Shopify refund ID |
| `commerce:order_id` | String | Order the refund belongs to |
| `commerce:refunded_at` | **Date** | Refund business date (Shopify `created_at`) — the index axis for the refund-period sales view (`returnsBasis=refund`) | `refund-created.xml` |
| `commerce:status` | String | `received`/`review_pending`/`resolved`/`error` |
| `commerce:refund_amount` | **Decimal** | Total refunded (sum of successful txns, native currency) |
| `commerce:refund_amount_base` | **Decimal** | Total refunded in the SHOP (base) currency, from refund line-item / adjustment `shop_money` — the cross-currency axis for the refund-period view | `recordRefund.groovy` |
| `commerce:currency` | String | Currency |
| `commerce:restocked` | **Boolean** | Any line restocked inventory |
| `commerce:line_item_count` | **Long** | Refunded line-item count |
| `commerce:refund_note` | String | Note/reason (≤2048 chars) |
| `commerce:order_updated` | **Boolean** | Order's refund summary applied (idempotency) |
| `commerce:gdpr_redacted` / `commerce:redacted_at` | **Boolean** / **Date** | GDPR redaction markers |
| `internal_memo` | String(JSON) | Reviewer memo from the Refund Review form |

## Sales facts — `/content/commerce/sales/{orders,lines}/index/{yyyy}/{MM}/…`

Derived, index-backed sales facts materialized by the SINGLE cluster-guarded drainer
(`sweepSalesFacts.groovy` → `commerce.SalesFacts.recompute` → `commerce.Sales.compute`) from the raw order
body + its refund bodies. Every money component is a typed **Decimal** so `facet accumulate sum(@…)`
(`commerce.SalesQuery`) reads its SortedNumericDocValues — the aggregation axes. NO pre-selected
"sales" number is stored: gross/net/total are synthesized at read time (operator sovereignty). The money
decomposition components are OMITTED when `commerce:components_complete` is false (a lossy historical
order recorded before the fact-writer began persisting the full money decomposition) so a facet SUM
never counts a fake 0 — only `total_price(_base)` + dimensions survive ("not decomposable"). Writer for all: `commerce.SalesFacts` (single writer). Reader: `commerce.SalesQuery`.

**Order grain** — `/content/commerce/sales/orders/index/{yyyy}/{MM}/order_{id}.json` (foldered by `ordered_at`; one node per order id):

| Property | Type | Meaning |
|---|---|---|
| `commerce:order_id` | String | Order id (fact key; both grains query `[@commerce:order_id='…']`) |
| `commerce:order_number` | **Long** | Human number |
| `commerce:customer_id` | String | Customer grouping axis |
| `commerce:currency` / `commerce:base_currency` | String | Native (presentment) / base (shop) currency |
| `commerce:source_status` | String | Shopify `financial_status` — the population axis |
| `commerce:cancelled` | **Boolean** | Cancelled (population axis) |
| `commerce:ordered_at` | **Date** | Order `created_at` — the period/range/PoP axis (order-cohort) |
| `commerce:ordered_day` / `commerce:ordered_month` | String | `yyyy-MM-dd` / `yyyy-MM` (server zone) grouping keys for the timeseries |
| `commerce:gross`(`_base`) | **Decimal** | Σ line price×qty (native / base). Decomposition; omitted when incomplete |
| `commerce:discounts`(`_base`) | **Decimal** | Σ line discount_allocations. Decomposition |
| `commerce:tax`(`_base`) | **Decimal** | Order tax. Decomposition |
| `commerce:shipping`(`_base`) | **Decimal** | Shipping. Decomposition |
| `commerce:tips`(`_base`) | **Decimal** | Tips (no `_set` in Shopify → base==native). Decomposition |
| `commerce:duties`(`_base`) | **Decimal** | Duties (cross-border only). Decomposition |
| `commerce:returns`(`_base`) | **Decimal** | Σ refund_line_items subtotal (order-cohort). Decomposition |
| `commerce:returns_tax`(`_base`) | **Decimal** | Refund tax. Decomposition |
| `commerce:returns_shipping`(`_base`) | **Decimal** | Refund shipping (positive magnitude). Decomposition |
| `commerce:total_price`(`_base`) | **Decimal** | Reconciliation key (NOT a metric). ALWAYS present (kept even when incomplete) |
| `commerce:recon_delta`(`_base`) | **Decimal** | Σcomponents − total_price (unallocated order discount / rounding). Diagnostic; omitted when incomplete |
| `commerce:refund_count` | **Long** | Refund bodies folded |
| `commerce:components_complete` | **Boolean** | Body carried its full decomposition (source-set presence, never value==0) |
| `commerce:computed_at` | **Date** | Drainer clock (freshness) |

**Line grain** — `/content/commerce/sales/lines/index/{yyyy}/{MM}/order_{oid}_line_{lid}.json` (product-attributed; order dimensions denormalized to avoid a join):

| Property | Type | Meaning |
|---|---|---|
| `commerce:order_id` / `commerce:line_id` | String | Keys |
| `commerce:product_id` / `commerce:variant_id` / `commerce:sku` | String | Product axes (topProducts groups on real `product_id` — resolves the legacy sku\|title collision) |
| `commerce:customer_id` / `commerce:currency` / `commerce:source_status` / `commerce:cancelled` / `commerce:ordered_day` | (denorm) | Denormalized order dimensions |
| `commerce:ordered_at` | **Date** | Denormalized order date (range axis) |
| `commerce:quantity` / `commerce:returned_quantity` | **Long** | Ordered / returned units (returned counted even for restock-only / zero-money refunds) |
| `commerce:gross`(`_base`) / `commerce:discounts`(`_base`) / `commerce:tax`(`_base`) / `commerce:returns`(`_base`) | **Decimal** | Line components. Omitted when the order is incomplete |
| `commerce:computed_at` | **Date** | Drainer clock |

**Queue / state (JSON body, not query props):** `/content/commerce/sales/_pending/{order_id}.json` (drainer input, `{order_id, at}`); `/content/commerce/sales/backfill-state.json` (seed progress report); `/content/commerce/sales/refund-backfill-state.json` (refund-backfill progress).

## Backorders — `/content/commerce/backorders/{yyyy}/{MM}/backorder_{orderId}_{lineItemId}.json`

Writer: `commerce.Backorders`.

| Property | Type | Meaning |
|---|---|---|
| `commerce:status` | String | `backordered`/`ready`/`released`/`cancelled`/`error` |
| `commerce:reason` | String | `shortfall` / `preorder` |
| `commerce:order_id` | String | Shopify order ID |
| `commerce:order_number` | String | Human-readable number |
| `commerce:line_item_id` | String | Line-item ID (record key with order ID) |
| `commerce:variant_id` | String | Variant ID |
| `commerce:inventory_item_id` | String | Inventory item ID (release key) |
| `commerce:quantity` | **Long** | Units awaited |
| `commerce:ordered_quantity` | **Long** | Units ordered on the line |
| `commerce:customer_email` | String | Customer email (dummied by GDPR redact) |
| `commerce:title` | String | Line-item title |
| `commerce:sku` | String | Line-item SKU |
| `commerce:created_at` | **Date** | Detected timestamp |
| `commerce:released_at` | **Date** | Released timestamp |
| `commerce:cancelled_at` | **Date** | Cancelled timestamp |
| `commerce:cancel_reason` | String | e.g. `refunded` |
| `internal_memo` | String(JSON) | Operator memo (release form) |

## Customers — `/content/commerce/customers/customer_{id}.json` (guest: `customer_email_{hash}.json`)

First-class customer store (replaced `crm/customers` — migrated
& removed by an earlier boot migration). **Body = the raw Shopify customer JSON only** (guest:
`{}`); lifecycle/profile live in typed properties. A single writer — the customers/* webhook
upsert (`Customers.upsertFromWebhook`, groups (a)(b)) — sets every property; `customers/delete`
marks the lifecycle terminal (`commerce:status=deleted`). The mirror is display-only; edits go
to Shopify via the Admin API (`ShopifyWrite.updateCustomer`), never written back onto these
properties directly.

MIME type: `application/vnd.mintjams.commerce.customer+json` (associates the node with the
customer editor; new nodes stamped by `upsertFromWebhook`, existing nodes stamped by an earlier boot migration).

**(a) Lifecycle / meta**
| Property | Type | Meaning |
|---|---|---|
| `commerce:status` | String | `received` / `deleted` / `redacted` |
| `commerce:customer_id` | String | Shopify customer id (empty for guest) |
| `commerce:source_status` | String | Shopify account state: `enabled`/`disabled`/`invited`/`declined` |
| `commerce:updated_at` | **Date** | Shopify `updated_at` |
| `commerce:redacted_at` | **Date** | GDPR redaction timestamp (shell; normally unset) |
| `commerce:deletedAt` | **Date** | customers/delete timestamp |

**(b) Profile**
| Property | Type | Meaning |
|---|---|---|
| `commerce:email` | String | Email (guest: from orders; dummied by redact) |
| `commerce:name` | String | Name |
| `commerce:marketing_enabled` | **Boolean** | Subscribed (`email_marketing_consent.state == subscribed`) — composite-query flag |
| `commerce:marketing_consent` | String | Raw consent state (audit) |
| `commerce:tax_exempt` | **Boolean** | Tax-exempt flag |
| `commerce:tags` | String | Shopify customer tags (incl. the manual VIP tag) |
| `commerce:created_at` | **Date** | Shopify created |

## Event log — `/content/commerce/events/{source}/{yyyy}/{MM}/{eventId}.json`

Writer: `commerce.Events` (ingest core).

| Property | Type | Meaning |
|---|---|---|
| `commerce:status` | String | `received` → `processed` / `error` |
| `commerce:source` | String | Backend id (e.g. `shopify`) |
| `commerce:topic` | String | Webhook topic |
| `commerce:event_id` | String | Event ID |
| `commerce:entity_type` | String | Entity/collection |
| `commerce:entity_id` | String | Entity ID |
| `commerce:received_at` | **Date** | Receipt timestamp |
| `commerce:attempts` | **Long** | Processing attempts |
| `commerce:last_error` | String | Error (on failure) |
| `commerce:gdpr_redacted` / `commerce:redacted_at` | **Boolean** / **Date** | GDPR payload-scrub markers |

## Generic entities — `/content/commerce/entities/{source}/{collection}/{id}.json`

Writer: `commerce.Events` (normalize). Topics without a bespoke workflow (fulfillments, carts, checkouts…; customers/* is bespoke now).

| Property | Type | Meaning |
|---|---|---|
| `commerce:status` | String | `received` / `deleted` |
| `commerce:source` | String | Backend id |
| `commerce:topic` | String | Webhook topic |
| `commerce:entity_type` | String | Collection |
| `commerce:entity_id` | String | Entity ID |
| `commerce:updated_at` | **Date** | Last update |
| `commerce:customer_email` | String | Best-effort customer email (dummied by redact) |
| `commerce:order_id` | String | Best-effort order ID |
| `commerce:deletedAt` | **Date** | Set on delete |

## GDPR data requests — `/content/commerce/gdpr/data-requests/{yyyy}/{MM}/request_*.json`

Writer: `commerce.Gdpr.dataRequest`.

| Property | Type | Meaning |
|---|---|---|
| `commerce:status` | String | `received` |
| `commerce:customer_id` | String | Subject customer |
| `commerce:created_at` | **Date** | Report timestamp |

## Reconciliation run reports — `/content/commerce/reconciliation/{yyyy}/{MM}/{recon|inventory}_{epochMs}.json`

One report per reconciliation run, for BOTH scopes: the status/price diff batch
(`recon_*`, scope `diff`) and the full inventory audit (`inventory_*`, scope
`inventory`, recorded off the Bulk broker's terminal transitions). Single writer:
`commerce.Reconciliation.writeRunReport` (called by `reconcile.groovy` for diff and
`Reconciliation.recordBulkAudit` for inventory). The typed properties are the row
axes of the index-backed history lister (`Reconciliation.listRuns`) — the reconcile
endpoint and the dashboard never parse the report bodies (a diff body embeds the
full diffs array). An earlier boot migration stamped/retyped these
onto reports written before the unified run history.

| Property | Type | Meaning |
|---|---|---|
| `commerce:scope` | String | `diff` (products) / `inventory` — also the "is a run report" marker (state files carry none) |
| `commerce:started_at` | **Date** | Run start — the history sort/range axis |
| `commerce:finished_at` | **Date** | Run end |
| `commerce:updated_count` | **Long** | Items the run updated (diff: refreshed products; inventory: re-mirrored items) |
| `commerce:result` | String | `success` / `error` |
| `commerce:total_diffs` | **Long** | Total field diffs found (diff scope only) |
| `commerce:products_with_drift` | **Long** | Products with ≥1 diff (diff scope only) |
| `commerce:refreshed_products` | **Long** | Products whose mirror was patched (diff scope only) |
| `commerce:created_at` | **Date** | Report timestamp |

## Outbound sync audit — `/content/commerce/sync/{yyyy}/{MM}/sync_{epochMs}.json`

Writer: `commerce.SyncAudit` (sync endpoint, `cancelOrder.groovy`, `createIncomingTransfer.groovy`,
`recordFulfillment.groovy`). One record per attempted CMS→Shopify write, carrying typed queryable
properties so the audit answers WHO / WHEN / on WHAT-TARGET /
WHAT-ACTION. Surfaced by `reports.groovy?type=operations` and the **commerce-oplog** app.

| Property | Type | Meaning |
|---|---|---|
| `commerce:status` | String | `ok` / `failed` / `dryrun` |
| `commerce:action` | String | `inventory` / `price` / `publish` / `metafields` / `order_cancel` / `incoming_transfer` / `receive_transfer` / `customer_update` / `product_update` / `media_add` / `media_delete` / `media_reorder` / `media_updateAlt` |
| `commerce:actor` | String | **WHO** — the operator/decider who caused the write. HTTP editor writes = the logged-in operator (`repositorySession.getUserID()`); BPM-service writes = the operator captured by the user-task form onto the node (`commerce:reviewed_by` / `commerce:reorder_ordered_by` / `commerce:fulfilled_by`), read by `cancelOrder.groovy` / `createIncomingTransfer.groovy` / `recordFulfillment.groovy`; falls back to `workflow`. `cms` only for true system writes (or legacy call sites) |
| `commerce:entity` | String | **WHAT-TARGET** kind: `product` / `customer` / `order` / `inventory_item` |
| `commerce:entity_id` | String | The Shopify entity id the write targeted — the queryable target axis (against what) |
| `commerce:source` | String | Integration platform — stays `cms` (reserved for a future multi-target split; not the actor) |
| `commerce:created_at` | **Date** | Timestamp |

---

## Notes for the webtop UI redesign

- **Facets / lists**: `commerce:vendor`, `commerce:product_type`, `commerce:tags`,
  `commerce:status`, `commerce:source_status` (products) are the natural facet axes.
- **Inventory list**: `commerce:available_total` (Long) supports numeric low-stock queries
  (`< N`) with pagination; `commerce:variant_id`/`commerce:product_id` on the index nodes
  give variant↔item resolution without body parsing.
- **Customers**: composite XPath works directly — e.g. subscribed subscribers created since a
  date (`@commerce:marketing_enabled = true and @commerce:created_at >= xs:dateTime(...)`); facet
  on `commerce:tags` (incl. the manual VIP tag) / `commerce:source_status` / marketing consent.
- **Multi-currency**: aggregate on the BASE-currency axis (`commerce:total_price_base` on
  orders); keep native per-currency data for audit (`commerce:total_price`+`commerce:currency`).
  Per-transaction judgments (order/refund highValue) stay native via per-currency config maps.
- **Retyped history**: an earlier boot migration converted legacy String values on
  already-written nodes, so range queries hold across old data too.
