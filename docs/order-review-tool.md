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
                              └──────→ approved ←── Order Review task → Slack/Discord
                                          │
                                          ▼
                                  Fulfill Order task → Slack/Discord
                                          │  operator records tracking, marks fulfilled
                                          ▼
                          Create Fulfillment (Shopify write-back, gated) → fulfilled
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

1. A new **Order Review** task appears (and a notice is posted to Slack /
   Discord). Open it to see the order summary, customer and addresses, line
   items, and — highlighted at the top — the **reasons it was flagged**.
2. **Claim** the task to take ownership. Only the assignee can approve it or
   write a memo.
3. Optionally **View on Shopify**, and leave a **memo** for the audit trail.
4. Click **Approve order** to complete the review.

## Stage 3 — Fulfillment

Once approved (whether auto-approved or after a review), a **Fulfill Order** task
is raised for the warehouse and announced to Slack / Discord:

1. Open the task to see what to pick and where to ship it (line items and
   shipping address).
2. **Claim** the task, optionally enter a **tracking number**, **carrier** and
   **tracking URL** (all optional), and click **Mark as fulfilled**.
3. The workflow then runs **Create Fulfillment**:
   - It always records the tracking details and a fulfilled-at timestamp on the
     order (`commerce:tracking_number`, `commerce:tracking_company`,
     `commerce:fulfilled_at`).
   - When the **Admin API is enabled** (`shopify.yml` → `adminApi.enabled`), it
     also creates the fulfillment in Shopify (GraphQL `fulfillmentCreateV2`),
     passing the tracking info. This is **best-effort**: a failure is recorded in
     `commerce:fulfillment_writeback` / `commerce:fulfillment_error` and logged,
     but never breaks the workflow — the operator can still fulfill manually in
     Shopify. When the Admin API is disabled, the write-back is `skipped` and the
     fulfillment is CMS-side only.

## Configuration

1. **Order screening rules** — edit `/etc/commerce/config/order-review.yml`
   (see the table above).
2. **Shopify write-back** — to push fulfillments back to Shopify, enable the
   Admin API in `/etc/commerce/config/shopify.yml` (`adminApi.enabled: true`)
   and provide `shopDomain`, `apiVersion`, `clientID`, `clientSecret`. The app
   must be granted the fulfillment write scopes (e.g. `write_merchant_managed_fulfillment_orders`).
   By default the write-back does **not** ask Shopify to email the customer;
   set `adminApi.notifyCustomer: true` to opt into Shopify's shipping-notification
   email.
3. **Notifications** — both order tasks post to the same destinations as the
   inventory alert tool. Configure them in the Webtop **Commerce** app under
   *Notifications*, or directly in `/etc/commerce/config/notifications.yml`.
4. **Shopify webhook** — ensure the `orders/paid` topic points at the webhook
   endpoint.

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
| `error` | Processing failed; see `commerce:errorMessage` / `commerce:stackTrace`. |

## Files

| Concern | Path |
|---|---|
| EIP route (starts the workflow) | `etc/eip/routes/commerce/shopify/order-paid.xml` |
| BPMN workflow | `etc/bpm/processes/commerce/shopify/order-review-flow.bpmn` |
| Screening rules | `etc/commerce/config/order-review.yml` |
| Screening script | `etc/commerce/scripts/shopify/screenOrder.groovy` |
| Status updates | `etc/commerce/scripts/shopify/setOrderWorkflowStatus.groovy` |
| Fulfillment write-back | `etc/commerce/scripts/shopify/recordFulfillment.groovy` |
| Review notification | `etc/commerce/scripts/shopify/notifyOrderTaskCreated.groovy` |
| Fulfillment notification | `etc/commerce/scripts/shopify/notifyFulfillmentTaskCreated.groovy` |
| Order Review form | `content/commerce/forms/shopify/order-review.html` |
| Fulfill Order form | `content/commerce/forms/shopify/order-fulfillment.html` |
| Notification destinations | `etc/commerce/config/notifications.yml` |
