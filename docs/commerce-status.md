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
| `review_pending` | A variant dropped below its threshold; "Manual Inventory Check" task raised, waiting on an operator. | BPMN `create` task listener (`setWorkflowStatus.groovy`) | No |
| `monitored` | Workflow finished: thresholds are configured and stock is OK, or the review was completed. The product is now under routine monitoring. | BPMN end-event execution listener (`setWorkflowStatus.groovy`) | Yes |
| `error` | Processing failed; `commerce:errorMessage` / `commerce:stackTrace` carry details. | Camel route error handler | Yes (until reprocessed) |
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

Orders currently use only `received` and `error` — the same vocabulary — so the
two entities stay consistent.

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
| `etc/commerce/scripts/shopify/setWorkflowStatus.groovy` | `commerce:status = threshold_pending` / `review_pending` / `monitored` |
| all route error handlers | `commerce:status = error` |
