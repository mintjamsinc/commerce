# Commerce Status Model

This is the authoritative reference for the status properties used across all
commerce entities (products, orders, and any future entity). It exists so that
every developer, operator, and downstream consumer reads status the same way and
never has to guess.

## Two independent axes

A common pitfall is to overload a single `status` field with two unrelated
meanings — "what the record is in the source system" and "how far our pipeline
has processed it". On a platform these MUST stay separate, because they answer
different questions and change at different times. We model them as two
properties:

| Property | Axis | Question it answers | Owner |
|---|---|---|---|
| `commerce:status` | **Integration processing lifecycle** | Is our handling done, waiting on someone, or broken? | Our pipeline (Camel routes + BPMN) |
| `commerce:source_status` | **Source-system business status** | What state is this record in inside Shopify? | Shopify (mirrored verbatim) |

> The EIP/Camel console shows the last `commerce:status` header set by the route.
> Because that is now the *processing* status (`received`), a finished ingestion
> no longer looks like the ambiguous Shopify `active`.

## `commerce:status` — processing lifecycle

A **closed enumeration**. Every value below is the complete set; do not introduce
new values without updating this list.

| Value | Meaning | Set by | Terminal? |
|---|---|---|---|
| `received` | Webhook received, raw JSON and metadata stored. | Camel route (product-update / order-paid) | No |
| `threshold_pending` | Workflow raised the "Set Inventory Threshold" task; waiting on an operator. | BPMN `create` task listener (`setWorkflowStatus.groovy`) | No |
| `review_pending` | A manual review task is open, waiting on an operator. **Products:** a variant dropped below its threshold ("Manual Inventory Check"). **Orders:** a screening rule matched ("Order Review"). **Refunds:** a screening rule matched ("Refund Review"). | BPMN `create` task listener (`setWorkflowStatus.groovy` / `setOrderWorkflowStatus.groovy` / `setRefundWorkflowStatus.groovy`) | No |
| `monitored` | Product workflow finished: thresholds are configured and stock is OK, or the review was completed. The product is now under routine monitoring. | BPMN end-event execution listener (`setWorkflowStatus.groovy`) | Yes |
| `approved` | Order review cleared (auto-approved or a manual review was completed); the order is queued for fulfillment. | BPMN service task `ServiceTask_approveOrder` (`setOrderWorkflowStatus.groovy`) | No |
| `fulfillment_pending` | Order workflow raised the "Fulfill Order" task; waiting on a fulfiller to pick, pack and ship. | BPMN `create` task listener (`setOrderWorkflowStatus.groovy`) | No |
| `fulfilled` | Order workflow finished: the order was fulfilled (tracking recorded, and written back to Shopify when the Admin API is enabled). | BPMN end-event execution listener (`setOrderWorkflowStatus.groovy`) | Yes |
| `resolved` | Refund workflow finished: the refund was screened, optionally reviewed, and recorded (the order's refund summary updated). A refund is already executed in Shopify, so this is the terminal audit state. | BPMN end-event execution listener (`setRefundWorkflowStatus.groovy`) | Yes |
| `backordered` | A paid order line could not be fulfilled from stock (shortfall) or is sold ahead as a pre-order; a line-level backorder record is waiting for stock. **Backorder-scoped.** | Camel route (order-paid → `detectBackorders.groovy`) | No |
| `ready` | The awaited stock for a backorder has arrived; the "Release Backorder" task is open, waiting on an operator. **Backorder-scoped.** | BPMN `create` task listener (`setBackorderWorkflowStatus.groovy`) | No |
| `released` | Backorder workflow finished: the operator released the in-stock line to normal fulfilment. **Backorder-scoped.** | BPMN end-event execution listener (`setBackorderWorkflowStatus.groovy`) | Yes |
| `cancelled` | A backorder was cancelled before release (e.g. the order was refunded while it still awaited stock). `commerce:cancelled_at` / `commerce:cancel_reason` carry the detail. **Backorder-scoped.** | Camel route (refund-created → `cancelBackorders.groovy`) | Yes |
| `processed` | An event-log entry was handled (forwarded to its workflow, or normalized). `commerce:attempts` counts ingest passes. **Event-log-scoped.** | Ingest core (`markEvent.groovy`) | No (replayable) |
| `ok` / `failed` / `dryrun` | Outcome of a CMS → Shopify outbound write (#2): applied / rejected / validated-only. **Sync-audit-scoped.** | `endpoints/sync.groovy` | Yes |
| `error` | Processing failed; for entities `commerce:errorMessage` / `commerce:stackTrace`, for event-log entries `commerce:last_error`, carry details. | Camel route error handlers / ingest core | Yes (until reprocessed) |
| `deleted` | The record was deleted in Shopify. `commerce:deletedAt` carries the timestamp. | Camel route (product-delete) | Yes |

### Product lifecycle transitions

```
received ─┬─ (no threshold yet) ─→ threshold_pending ─→ … ─┐
          └─ (threshold exists) ───────────────────────────┤
                                                            ↓
                                              (stock below threshold?)
                                              ├─ No ───────────────────→ monitored
                                              └─ Yes → review_pending ─→ monitored

any state ─→ error      (on processing failure)
any state ─→ deleted    (on Shopify product deletion)
```

### Order lifecycle transitions

```
received ─→ (screen order against review rules)
              ├─ no rule matched ──────────────────────┐
              └─ rule matched → review_pending ────────┤
                                                        ↓
                                                     approved
                                                        ↓
                                              fulfillment_pending
                                                        ↓
                                           (record tracking + Shopify write-back)
                                                        ↓
                                                    fulfilled

any state ─→ error      (on processing failure)
```

Orders share the vocabulary with products: `received`, `review_pending`,
`error` are common. The order flow then continues through `approved` →
`fulfillment_pending` → `fulfilled` (the order equivalent of the product's
terminal `monitored`).

### Refund lifecycle transitions

```
received ─→ (screen refund against review rules)
              ├─ no rule matched ──────────────────────┐
              └─ rule matched → review_pending ────────┤
                                                        ↓
                                            (record refund + update order summary)
                                                        ↓
                                                    resolved

any state ─→ error      (on processing failure)
```

A refund is its own resource with its own `commerce:status`. Recording a refund
also updates the **order's** `commerce:source_status` to `refunded` /
`partially_refunded` (the order's business status mirror), keeping the two axes
distinct: the refund's processing status is `resolved`, while the order's
business status reflects how much of it was refunded.

### Reorder (purchase order) lifecycle transitions

The replenishment workflow (#7) creates purchase-order records under
`/content/commerce/purchase-orders/`, each its own resource with its own
`commerce:status`. See [auto-reorder.md](auto-reorder.md).

```
review_pending ─→ (operator decision)
                    ├─ rejected
                    └─ approved ─→ (supplier delivery)
                                     ├─ none ───────────────→ approved   (order manually)
                                     ├─ email/webhook ok ───→ ordered
                                     └─ email/webhook fails →  order_failed
```

These values are scoped to the reorder entity (`review_pending` and `approved`
are shared with the order vocabulary; `ordered` / `rejected` / `order_failed` are
reorder-specific). Routing/ops decisions for reorders read this status off the PO
record.

### Backorder lifecycle transitions

The backorder/pre-order feature (#12) creates line-level backorder records under
`/content/commerce/backorders/`, each its own resource with its own
`commerce:status`. See [backorders.md](backorders.md).

```
backordered ─→ (stock arrives and covers it, FIFO)
                 └─ ready ─→ (operator releases) ─→ released

backordered ─→ cancelled    (order refunded while still awaiting stock)
any state   ─→ error
```

`backordered` is set by the order-paid route when a line is short on stock or is a
pre-order; `ready` (task raised) and `released` (terminal) are driven by the
backorder-release workflow once arriving stock covers it. `cancelled` is set by the
refund route for backorders that were still merely waiting on stock. These values
are scoped to the backorder entity (`ready` / `released` / `cancelled` are
backorder-specific; `backordered` is shared with nothing else).

### Event-log lifecycle transitions

The ingestion core (#1/#3/#4) records every inbound event under
`/content/commerce/events/`, each its own resource with its own `commerce:status`.
See [ingestion.md](ingestion.md).

```
received ─→ (handler ran) ─→ processed
received ─→ (handling failed) ─→ error ─→ (replay) ─→ received ─→ …
```

`received` is shared with the entity vocabulary; `processed` is event-log-specific;
`error` is shared. `commerce:attempts` tracks ingest passes; automatic replay
re-runs `error` events up to a configured limit.

## `commerce:source_status` — business status (Shopify mirror)

A faithful mirror of the source system's own status. The value set is whatever
Shopify sends, so the enumeration is owned by Shopify, not by us.

| Entity | Source field | Typical values |
|---|---|---|
| Product | `product.status` | `active`, `archived`, `draft` |
| Order | `order.financial_status` | `paid`, `pending`, `refunded`, `partially_refunded`, … |

This property is informational/mirror data. Routing and operational decisions are
driven by `commerce:status`, not by `commerce:source_status`.

## Where each property is written

| File | Writes |
|---|---|
| `etc/eip/routes/commerce/shopify/product-update.xml` | `commerce:status = received`, `commerce:source_status = $.status` |
| `etc/eip/routes/commerce/shopify/product-delete.xml` | `commerce:status = deleted` |
| `etc/eip/routes/commerce/shopify/order-paid.xml` | `commerce:status = received`, `commerce:source_status = $.financial_status` |
| `etc/commerce/scripts/shopify/setWorkflowStatus.groovy` | `commerce:status = threshold_pending` / `review_pending` / `monitored` (products) |
| `etc/commerce/scripts/shopify/setOrderWorkflowStatus.groovy` | `commerce:status = review_pending` / `approved` / `fulfillment_pending` / `fulfilled` (orders) |
| `etc/commerce/scripts/shopify/recordFulfillment.groovy` | `commerce:tracking_number` / `commerce:tracking_company` / `commerce:fulfilled_at` / `commerce:fulfillment_writeback` / `commerce:fulfillment_id` (orders) |
| `etc/commerce/scripts/shopify/setRefundWorkflowStatus.groovy` | `commerce:status = review_pending` / `resolved` (refunds) |
| `etc/commerce/scripts/shopify/recordRefund.groovy` | `commerce:refund_amount` / `commerce:currency` / `commerce:restocked` (refunds); `commerce:refunded_amount` / `commerce:refund_count` / `commerce:source_status` (orders) |
| `etc/commerce/scripts/shopify/detectBackorders.groovy` (via `commerce.Backorders`) | `commerce:status = backordered` + backorder facts (backorders) |
| `etc/commerce/scripts/shopify/setBackorderWorkflowStatus.groovy` | `commerce:status = ready` / `released` (backorders) |
| `etc/commerce/scripts/shopify/cancelBackorders.groovy` (via `commerce.Backorders`) | `commerce:status = cancelled` / `commerce:cancelled_at` / `commerce:cancel_reason` (backorders) |
| `etc/commerce/scripts/shopify/recordBackorderRelease.groovy` (via `commerce.Backorders`) | `commerce:released_at` (backorders) |
| `etc/commerce/scripts/commerce/logEvent.groovy` (via `commerce.Events`) | `commerce:status = received` + event metadata (event log) |
| `etc/commerce/scripts/commerce/markEvent.groovy` (via `commerce.Events`) | `commerce:status = processed` / `error` (event log) |
| `etc/commerce/scripts/commerce/normalizeEvent.groovy` (via `commerce.Events`) | `commerce:status = received` / `deleted` + entity metadata (generic entities) |
| all route error handlers | `commerce:status = error` |
