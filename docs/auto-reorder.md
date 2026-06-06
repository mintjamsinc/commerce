# Auto-Reorder / Replenishment

Closes the loop on low stock: a batch proposes purchase orders for variants that
will run short, an operator approves (and can adjust the quantity), and the
approved PO is recorded and sent to the supplier — a human-task + automatic-action
combination built on BPMN.

## Flow

```
timer:commerce-reorder (daily, as service user)
        │
        ▼
proposeReorders.groovy
   ├─ SalesVelocity.loadPerDay + SalesVelocity.variants  (cached velocity + stock)
   ├─ Replenishment.suggest(perDay, stock, cfg) > 0 ?  (and not already proposed)
   ├─ record a PO proposal → /content/commerce/purchase-orders/{yyyy}/{MM}/po_*.json (review_pending)
   └─ start replenishment-flow (businessKey = "reorder:<variantId>")

replenishment-flow.bpmn
   StartEvent
     → UserTask "Approve Reorder"  (form: reorder-approval.html; notifyReorderTaskCreated → #17)
     → ServiceTask "Process Reorder" (purchaseOrder.groovy)
     → EndEvent
```

The approval form writes the decision (`reorder:decision` = approved/rejected,
`reorder:approved_qty`, `reorder:note`) onto the PO record and completes the task;
the service task acts on it — no gateway, mirroring the order-fulfillment pattern.

## Suggested quantity (`commerce.Replenishment`)

```
need = velocity * (leadTimeDays + targetCoverDays) - currentStock
qty  = roundUp( max(ceil(need), minOrderQty), roundTo )   (0 → no proposal)
```

Pure logic over plain data, so it is unit-testable; the script supplies stock +
(cached) velocity.

## Supplier delivery (`purchaseOrder.groovy`)

On approval the PO is recorded and delivered per `reorder.yml` `supplier.delivery`:

| delivery | action | terminal status |
|---|---|---|
| `none` | record only (order manually) | `approved` |
| `email` | email the PO to `supplier.email` via the **notifications.yml** SMTP transport (`commerce.SmtpClient`) | `ordered` / `order_failed` |
| `webhook` | POST the PO JSON to `supplier.webhookUrl` | `ordered` / `order_failed` |

The email transport (SMTP host, credentials, from) is reused from the
notifications.yml `email` block — only the supplier recipient lives in
reorder.yml. Delivery is best-effort: a failure records `order_failed` on the PO,
never breaks the workflow.

## Configuration (`/etc/commerce/config/reorder.yml`)

Managed from **Webtop → Commerce → Replenishment**. Disabled by default.

| Key | Meaning |
|---|---|
| `enabled` | master switch for proposals |
| `leadTimeDays` | supplier lead time |
| `targetCoverDays` | days of stock to hold beyond the lead time |
| `minOrderQty` | floor for a non-zero order |
| `roundTo` | round the order up to this multiple (case/pack size) |
| `supplier.delivery` | `none` / `email` / `webhook` |
| `supplier.email` | recipient for email delivery |
| `supplier.webhookUrl` | endpoint for webhook delivery |

## Dedup

A variant is not re-proposed while a replenishment workflow is already running for
it (engine business-key query), nor when a non-rejected PO was proposed within the
lead time (so a just-ordered item is not re-proposed before it can arrive).

## Storage & status

PO records live at `/content/commerce/purchase-orders/{yyyy}/{MM}/po_{id}.json`,
with `commerce:status` moving through `review_pending` → `approved`/`rejected` →
`ordered`/`order_failed`. The Commerce **Dashboard** Reorders card shows the count
awaiting approval and recently ordered. See
[commerce-status.md](commerce-status.md) for the reorder status lifecycle.
