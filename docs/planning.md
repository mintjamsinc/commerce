# Planning Layer (per-variant reorder threshold)

Replaces the retired attribute/season **rule engine**
(`InventoryRules` / `inventory-rules.yml`) and the standalone reorder batch.

## Principles

- **Per-variant explicit values only**. No category/tag rule matching —
  "set by category" is a bulk-apply of explicit values, not a rule.
- **The system never derives or rewrites the threshold**. There is no
  proposal batch and no computed value; operators set the threshold themselves
  (product editor / onboarding form).
- **threshold = the reorder point (ROP)**: a plain unit count — a fixed
  number, not a formula — that fires the ONE unified "Inventory & Reorder
  Review" task when stock crosses below it.

## Data model

The planning layer resolves exactly ONE value per variant, `threshold`
(a plain unit count). Per-variant values live on the product node's PIM overlay,
`pim.planning` (variantId → `{ threshold }`). Resolution:

```
per-variant value (pim.planning)  →  planning.yml defaults.threshold  →  none (not monitored)
```

`commerce.Planning` is the resolver; `planning.yml` holds the global default
`defaults.threshold`. An unset threshold means the variant is **not monitored**.

## The unified review (stock < threshold)

`sweepInventoryAlerts.groovy` (event-driven, edge-triggered) materializes the
multi-location total onto the inventory index node (`commerce:available_total`)
and, on the ok→low transition (`total < threshold`), starts ONE
`inventory-alert-flow` per product.

`inventory-alert-flow.bpmn`:

```
Start → UserTask (form inventory-level-review.html)
      → ServiceTask createIncomingTransfer.groovy → End
```

Process variables: `productID`, `productPath`, `inventoryItemId`, `variantId`,
`variantTitle`, `availableTotal`, `threshold`.

The form shows the current stock (summed across all locations), the fixed
threshold, and the **previous order** (date + qty) for reference. There is **no
system-suggested quantity** — the operator enters the order quantity manually
(blank by default). The previous-order reference is stored per inventory item in
a JSON map property `commerce:reorder_last_orders` on the product node:

```
commerce:reorder_last_orders = { "<itemId>": { at: ISO8601, qty: N, receivedAt: ISO8601 } }
```

`createIncomingTransfer.groovy` writes it on completion. The operator purchases
through their own channel, enters the confirmed quantity + destination, and
completion records it in Shopify as **incoming stock**
(`ShopifyWrite.createIncomingTransfer`; Admin API required; audited in
`/content/commerce/sync/`). A quantity of 0 writes nothing ("reviewed, no
reorder"). Receiving happens in the Shopify admin and flows back via
`inventory_levels/update` — one-way data flow.

The supplier ledger, MOQ, lot rounding and PO email/webhook delivery were
removed; reorder is a manual operator action.

## Task landscape (2 kinds)

| Task | Trigger | Purpose |
|---|---|---|
| Set Inventory Threshold | new product, nothing configured (`unconfiguredPolicy: prompt`) | onboarding: operator sets the fixed threshold |
| **Inventory & Reorder Review** | stock < threshold (event-driven sweep) | stock check + confirmed reorder → Shopify incoming |

## Config (`/etc/commerce/config/planning.yml`)

`defaults.threshold` — the global fallback threshold, used when a variant has no
per-variant value. The legacy `reorder.yml` and `inventory-rules.yml` were
retired.

## Live-verify notes

groovy cannot run locally; smoke-test on CMS + Shopify:
- the Inventory Transfers mutation shape (`inventoryTransferCreateAsReadyToShip`)
  against the configured API version, and its idempotency.
