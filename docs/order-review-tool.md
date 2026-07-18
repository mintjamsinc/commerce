# Order Processing Workflow

The order processing workflow takes every paid Shopify order from receipt to
fulfillment. It screens each order, raises a manual **Order Review** task only
when a rule matches, marks the order approved, then raises a manual **Fulfill
Order** task for the warehouse and (optionally) writes the fulfillment back to
Shopify. Orders that match no screening rule skip the review step entirely, so
operators only see the orders that actually need attention.

It is the order-side counterpart of the
[inventory alert tool](inventory-alert-tool.md) and is built from the same
pieces: an EIP route, a BPMN workflow, task-helper scripts, Tasks-app forms,
and YAML configuration.

```
Shopify (orders/paid)
   │  Webhook (HMAC-SHA256 verified)
   ▼
Groovy endpoint ──→ order-paid route ──→ JCR (store + normalize)
                                           │  start order-review-flow
                                           ▼
                                     Screen Order (rules)
                                           │  any rule matched?
                              ┌────────────┴────────────┐
                              │ No                       │ Yes
                              ▼                          ▼
                              └──────→ approved ←── Order Review task → notify
                                          │
                                          ▼
                                  Fulfill Order task → notify
                                          │  operator records tracking, marks fulfilled
                                          │  (order cancelled in Shopify? → close instead)
                              ┌───────────┴─────────────┐
                              │ fulfill                 │ close
                              ▼                         ▼
          Create Fulfillment (Shopify         cancelled (no Shopify
          write-back, gated) → fulfilled      write-back)
```

## Stage 1 — Screening

Screening rules live in `/etc/commerce/config/order-review.yml`. Each rule can
be toggled independently; an order is sent to review if **any** enabled rule
matches.

| Rule | Flags an order when… | Default |
|---|---|---|
| `highValue` | `total_price` ≥ the threshold for its currency (or `default`). | on — JPY ≥ 100,000 / USD ≥ 1,000 |
| `flaggedFinancialStatus` | Shopify `financial_status` is one of the listed states. | on — `pending`, `authorized`, `partially_paid` |
| `largeQuantity` | Any single line item quantity ≥ `maxLineQuantity`. | on — ≥ 10 |
| `newCustomer` | `customer.orders_count` ≤ `maxOrdersCount`. | off |
| `addressMismatch` | Billing and shipping countries differ. | on |

Set the master switch `enabled: false` to auto-approve every order (screening
off). Screening is **fail-open**: if the config file is missing or unparseable,
orders are auto-approved and a warning is logged rather than blocking the flow.

## Stage 2 — Order Review (only when flagged)

Operators work these in the Webtop **Tasks** app:

1. A new **Order Review** task appears (and a notice is posted to every enabled
   notification channel — Slack / Discord / Teams / LINE / webhook / email).
   Open it to see the order summary, customer and addresses, line
   items, and — highlighted at the top — the **reasons it was flagged**.
2. **Claim** the task to take ownership. Only the assignee can decide it or
   write a memo.
3. Optionally **View on Shopify**, and leave a **memo** for the audit trail.
4. Click **Approve order** to complete the review.

## Stage 3 — Fulfillment

**Reject** is also wired (both buttons on the review form): rejecting requires a
reason, and the flow then cancels the order in Shopify (Admin API Order Cancel,
restock + refund; the reason rides as the staff note). The outcome is recorded
on the order (`commerce:cancel_writeback` ok/failed, `commerce:status =
cancelled`) and in the outbound sync audit; the cancellation itself echoes back
through the normal webhook path.

Once approved (whether auto-approved or after a review), a **Fulfill Order** task
is raised for the warehouse and announced to every enabled notification channel
(Slack / Discord / Teams / LINE / webhook / email):

1. Open the task to see what to pick and where to ship it (line items and
   shipping address).
2. **Claim** the task, optionally enter a **tracking number**, **carrier** and
   **tracking URL** (all optional), and click **Mark as fulfilled**.
3. The workflow then runs **Create Fulfillment**:
   - It always records the tracking details and a fulfilled-at timestamp on the
     order (`commerce:tracking_number`, `commerce:tracking_company`,
     `commerce:fulfilled_at`).
   - When the **Admin API is configured** (`shopify.yml` → `adminApi`), it
     also creates the fulfillment in Shopify (GraphQL `fulfillmentCreateV2`),
     passing the tracking info. This is **best-effort**: a failure is recorded in
     `commerce:fulfillment_writeback` / `commerce:fulfillment_error` and logged,
     but never breaks the workflow — the operator can still fulfill manually in
     Shopify. When the Admin API is not yet configured, the write-back is `skipped`
     and the fulfillment is CMS-side only.

### Shopify-side state on the Fulfill Order form

The form header mirrors the order's Shopify-side state as badges — **Cancelled**,
**Refunded** / **Partially refunded**, **Fulfillment not required**, **Archived**,
**On hold** — read from the order mirror (refreshed on every `orders/updated`
delivery) and from the fulfillment-hold flag (`commerce:fulfillment_hold`,
mirrored by the `fulfillment_orders/placed_on_hold` / `hold_released` webhooks).
The badges change what the assignee can do:

- **On hold**: the *Mark as fulfilled* button is withheld (a notice explains why)
  until the hold is released in Shopify.
- **Cancelled**: the action becomes **Close without fulfilling**. Completing it
  records `commerce:fulfillment_decision = close`, and the flow's gateway then
  **bypasses** the Create Fulfillment write-back and ends the workflow with
  `commerce:status = cancelled`.

## Cancellation while the workflow is running

A Shopify-side cancellation (admin cancel, or our own reject-flow cancel echoing
back) arrives via `orders/updated` with `cancelled_at` set. The route's reconcile
step (`reconcileOrderCancellation.groovy`) then:

- **cancels the order's open backorders** (a cancel without a refund would
  otherwise leave them waiting on stock forever), and
- **terminates the running `order-review-flow` instance** when its open user
  task(s) are all **unassigned**, marking the order `cancelled` — nobody has
  picked the work up, so there is nothing to hand back;
- **leaves the instance running when a task is assigned**: the operator sees the
  cancelled badge on the form and confirms the close themselves (see above).

This is the one deliberate exception to the `orders/updated` mirror-only rule,
and it only ever *closes* the lifecycle, never advances it.

## Configuration

1. **Order screening rules** — edit `/etc/commerce/config/order-review.yml`
   (see the table above).
2. **Shopify write-back** — to push fulfillments back to Shopify, configure the
   Admin API in `/etc/commerce/config/shopify.yml` (`adminApi`: `shopDomain`,
   `apiVersion`, `clientID`, `clientSecret`). The app
   must be granted the fulfillment write scopes (e.g. `write_merchant_managed_fulfillment_orders`).
   By default the write-back does **not** ask Shopify to email the customer;
   set `adminApi.notifyCustomer: true` to opt into Shopify's shipping-notification
   email.
3. **Notifications** — the review task notifies under the `orders` category and
   the fulfillment task under `fulfillment`: each goes to every enabled channel
   of the channel set configured for its category (or of the default set when
   the category has none) — Slack, Discord, Teams, LINE, generic webhook, email.
   Configure them in the Webtop **Commerce** app under *Notifications*, or
   directly in `/etc/commerce/config/notifications.yml`. All channels ship
   disabled, so enable at least one to receive notices (tasks are still raised
   either way).
4. **Shopify webhooks** — ensure the `orders/paid` and `orders/updated` topics
   point at the webhook endpoint. For the hold badge / gating, also subscribe
   `fulfillment_orders/placed_on_hold` and `fulfillment_orders/hold_released`
   (the Settings one-click sync registers all of them; these two additionally
   require a fulfillment-order read scope such as
   `read_merchant_managed_fulfillment_orders`). The hold topics deliver a slim
   payload without the parent order id, so mirroring the hold also requires the
   **Admin API** to be configured (the handler resolves the parent order via
   GraphQL); without it, hold events are logged and skipped.

## Status

The order moves along the integration processing lifecycle
(`commerce:status`), kept separate from Shopify's own business status
(`commerce:source_status`, e.g. `paid`). See
[commerce-status.md](commerce-status.md) for the authoritative model.

| `commerce:status` | Meaning |
|---|---|
| `received` | Order stored; about to be screened. |
| `review_pending` | A rule matched; an Order Review task is open. |
| `approved` | Review cleared (auto or manual); queued for fulfillment. |
| `fulfillment_pending` | A Fulfill Order task is open. |
| `fulfilled` | Order fulfilled; tracking recorded (and written back to Shopify when enabled). Terminal. |
| `cancelled` | Workflow ended without fulfillment: review reject, close-without-fulfilling, or auto-termination after a Shopify-side cancel. Terminal. |
| `error` | Processing failed; see `commerce:errorMessage` / `commerce:stackTrace`. |

## Files

| Concern | Path |
|---|---|
| EIP route (starts the workflow) | `etc/eip/routes/commerce/shopify/order-paid.xml` |
| BPMN workflow | `etc/bpm/processes/commerce/shopify/order-review-flow.bpmn` |
| Screening rules | `etc/commerce/config/order-review.yml` |
| Screening script | `etc/commerce/scripts/shopify/screenOrder.groovy` |
| Status updates | `etc/commerce/scripts/shopify/setOrderWorkflowStatus.groovy` |
| Fulfillment decision (fulfill / close) | `etc/commerce/scripts/shopify/readFulfillmentDecision.groovy` |
| Fulfillment write-back | `etc/commerce/scripts/shopify/recordFulfillment.groovy` |
| Cancellation reconcile (auto-terminate) | `etc/commerce/scripts/shopify/reconcileOrderCancellation.groovy` |
| Fulfillment-hold mirror | `etc/eip/routes/commerce/shopify/fulfillment-hold.xml`, `etc/commerce/scripts/shopify/setFulfillmentHold.groovy` |
| Review notification | `etc/commerce/scripts/shopify/notifyOrderTaskCreated.groovy` |
| Fulfillment notification | `etc/commerce/scripts/shopify/notifyFulfillmentTaskCreated.groovy` |
| Order Review form | `content/commerce/forms/shopify/order-review.html` |
| Fulfill Order form | `content/commerce/forms/shopify/order-fulfillment.html` |
| Notification destinations | `etc/commerce/config/notifications.yml` |
