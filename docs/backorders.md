# Backorder / Pre-order Management

Tracks order lines that cannot be — or should not yet be — fulfilled from on-hand
stock, and releases them when stock arrives. A backorder is a
line-level record with its own `commerce:status` lifecycle, modelled exactly like
refunds and purchase orders so operators read it the same way.

Two causes:

- **shortfall** — a stock-tracked variant's ordered quantity exceeds the aggregate
  available stock at the moment the order is received.
- **preorder** — the product is tagged as a pre-order (configurable), so its lines
  are held until stock arrives regardless of current availability.

## Lifecycle

```
order paid ─→ detectBackorders ─→ backordered ──┐ (one record per short / pre-order line)
                                                 │
inventory_levels/update ─→ releaseBackorders ───┘
   (arriving stock covers it, oldest-first FIFO)
        │
        ▼
   backorder-release-flow.bpmn
     StartEvent
       → UserTask "Release Backorder" (form: backorder-release.html; setBackorderWorkflowStatus → ready; notifyBackorderReady)
       → ServiceTask "Record Release"  (recordBackorderRelease.groovy → released_at)
       → EndEvent                       (setBackorderWorkflowStatus → released)

refunds/create ─→ cancelBackorders ─→ cancelled   (only records still merely awaiting stock)
```

See [commerce-status.md](commerce-status.md) for the status vocabulary
(`backordered` → `ready` → `released`, or `cancelled`).

## Detection (`detectBackorders.groovy`, on `orders/paid`)

Runs as a step in the order-paid route after the order is stored. For each line it
resolves the variant to its inventory item (read from the mirrored product JSON),
reads the aggregate stock we hold for that item, and whether the product is a
pre-order, then records a backorder for any short or pre-order line. The decision
itself is the pure, unit-testable `commerce.Backorders.detect`; the script only
gathers the JCR inputs and persists/notifies.

**No false floods.** A shortfall is only raised for **stock-tracked** items — those
we actually hold inventory levels for (from the `inventory_levels/update` webhook).
Items with no levels are treated as untracked and never backordered on a shortfall,
so a shop that has not wired inventory webhooks is never flooded with records.
Pre-order detection is independent (tag-based) and applies regardless of stock.

Records are idempotent: keyed by `order + line`, so a redelivered order webhook does
not create duplicates. The whole step is defensive — a failure never moves the order
to the error folder.

## Release (`releaseBackorders.groovy`, on `inventory_levels/update`)

Runs as a step in the inventory-level route after the new level is recorded. It
finds the open backorders waiting on the affected inventory item and, while the
current aggregate available stock covers them, raises each as a **Release
Backorder** task (starting `backorder-release-flow`). Allocation is **FIFO**: the
oldest backorder is served first and the loop stops at the first one the arriving
stock cannot cover, so an older, larger backorder is never jumped by a newer,
smaller one. A backorder that already has a running release workflow is skipped
(engine business-key dedup).

## Cancellation (`cancelBackorders.groovy`, on `refunds/create`)

A refund unwinds the order, so its still-waiting backorders should not linger. The
refund route cancels every record for the order that is still `backordered`.
Records already `ready` are left untouched — an operator is actively releasing them
through a task, and cancelling out from under that workflow would leave dangling
engine state.

## Configuration (`/etc/commerce/config/backorder.yml`)

| Key | Meaning |
|---|---|
| `enabled` | master switch for detection + release |
| `preorderTags` | product tags (case-insensitive) that mark a pre-order; empty disables pre-order detection |
| `notify.onCreated` | notify operators (one summary per order) when items go on backorder |
| `notify.onReady` | notify when a backorder has stock and a release task is raised |

Notifications go out under the `backorders` category — the channel set
configured for it in `notifications.yml`, or the default set when none is
([notification-channels.md](notification-channels.md)).

## Storage & visibility

Records live at `/content/commerce/backorders/{yyyy}/{MM}/backorder_{orderId}_{lineItemId}.json`
(UTC month folders — the shared storage fold rule)
([jcr-structure.md](jcr-structure.md)). The Commerce **Dashboard** Backorders card
shows lines awaiting stock, ready-to-release count and total units awaited; the
admin endpoint exposes the book directly:

```
GET /bin/cms.cgi/{workspace}/content/commerce/endpoints/backorders.groovy?limit=50
```

The **Release Backorder** task is a first-class human task: it participates in Task
SLA management alongside the order / refund / product review tasks.

## Shared logic

All of the above sits on `commerce.Backorders`
([commerce-shared-classes.md](commerce-shared-classes.md)): the pure `detect`
decision plus defensive create / find / cancel / release / summary helpers, so the
route scripts stay thin and the decision logic is reusable across backends (the
platform is not Shopify-specific — any backend that feeds the same order and
inventory shape gets backorders for free).
