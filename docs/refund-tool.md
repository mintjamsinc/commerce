# Refund Review Workflow

The refund review workflow watches refunds as they happen in Shopify, screens
each one, and raises a manual **Refund Review** task only when a rule matches. It
is an audit / fraud-monitoring tool: a refund is already executed in Shopify by
the time the `refunds/create` webhook fires, so this workflow records and triages
refunds — **it never moves money and never writes anything back to Shopify.**

It is the refund-side counterpart of the [order processing workflow](order-review-tool.md)
and reuses the same building blocks: an EIP route, a BPMN workflow, screening and
helper scripts, a Tasks-app form, and YAML configuration.

```
Shopify (refunds/create)
   │  Webhook (HMAC-SHA256 verified, shared receiver)
   ▼
webhook.groovy ──→ refund-created route ──→ JCR (store refund) ──→ refund-review-flow
                                                                      │  screen refund
                                                       ┌──────────────┴──────────────┐
                                                       │ No rule matched              │ Rule matched
                                                       ▼                              ▼
                                                       └────→ record refund ←── Refund Review task → Slack/Discord
                                                                  │  update order's refund summary
                                                                  ▼
                                                              resolved
```

## Stage 1 — Screening

Screening rules live in `/etc/commerce/config/refund-review.yml`. Each rule can
be toggled independently; a refund is sent to review if **any** enabled rule
matches.

| Rule | Flags a refund when… | Default |
|---|---|---|
| `highRefundValue` | The refunded amount ≥ the threshold for its currency (or `default`). | on — JPY ≥ 50,000 / USD ≥ 500 |
| `fullRefund` | The refunded amount ≥ the original order's `total_price`. Skipped if the order can't be located. | on |
| `noRestock` | The refund returns line items but restocks none of them (all `restock_type: no_restock`). | on |

Set the master switch `enabled: false` to auto-acknowledge every refund
(screening off). Screening is **fail-open**: if the config file is missing or
unparseable, refunds are auto-acknowledged and a warning is logged rather than
blocking the flow.

The refunded amount is computed by summing the refund's successful `refund`
transactions; the currency is taken from those transactions.

## Stage 2 — Refund Review (only when flagged)

Operators work these in the Webtop **Tasks** app:

1. A new **Refund Review** task appears (and a notice is posted to Slack /
   Discord). Open it to see the refund amount, the order it belongs to, the
   refunded line items, whether inventory was restocked, the refund note, and —
   highlighted at the top — the **reasons it was flagged**.
2. **Claim** the task to take ownership. Only the assignee can acknowledge it or
   write a memo.
3. Optionally **View on Shopify** (opens the order), and leave a **memo** for the
   audit trail.
4. Click **Acknowledge refund** to complete the review.

## Stage 3 — Recording

Whether the refund was auto-acknowledged or reviewed, the workflow runs **Record
Refund** before finishing:

- It persists the computed facts on the refund resource: `commerce:refund_amount`,
  `commerce:currency`, `commerce:restocked`, `commerce:line_item_count` and the
  refund note.
- It updates the **original order** (best-effort, when locatable by its
  `order_{id}.json` node): adds to `commerce:refunded_amount`, bumps
  `commerce:refund_count`, and sets the order's `commerce:source_status` to
  `refunded` (when the cumulative refund covers the order total) or
  `partially_refunded`. A guard property on the refund (`commerce:order_updated`)
  ensures the order summary is applied at most once.

The refund's own `commerce:status` ends at `resolved`.

## Configuration

1. **Refund screening rules** — edit `/etc/commerce/config/refund-review.yml`
   (see the table above).
2. **Notifications** — the Refund Review task posts to the same destinations as
   the order and inventory tools. Configure them in the Webtop **Commerce** app
   under *Notifications*, or directly in `/etc/commerce/config/notifications.yml`.
3. **Shopify webhook** — subscribe the `refunds/create` topic to the shared
   webhook endpoint (the same receiver used by `orders/paid` and the product
   topics). No Admin API access is required.

## Status

The refund moves along the integration processing lifecycle (`commerce:status`),
kept separate from the order's business status (`commerce:source_status`). See
[commerce-status.md](commerce-status.md) for the authoritative model.

| `commerce:status` | Meaning |
|---|---|
| `received` | Refund stored; about to be screened. |
| `review_pending` | A rule matched; a Refund Review task is open. |
| `resolved` | Refund screened, optionally reviewed, and recorded. Terminal. |
| `error` | Processing failed; see `commerce:errorMessage` / `commerce:stackTrace`. |

## Files

| Concern | Path |
|---|---|
| Webhook receiver (topic routing) | `content/public/commerce/endpoints/shopify/webhook.groovy` |
| EIP route (starts the workflow) | `etc/eip/routes/commerce/shopify/refund-created.xml` |
| BPMN workflow | `etc/bpm/processes/commerce/shopify/refund-review-flow.bpmn` |
| Screening rules | `etc/commerce/config/refund-review.yml` |
| Screening script | `etc/commerce/scripts/shopify/screenRefund.groovy` |
| Status updates | `etc/commerce/scripts/shopify/setRefundWorkflowStatus.groovy` |
| Recording / order summary | `etc/commerce/scripts/shopify/recordRefund.groovy` |
| Review notification | `etc/commerce/scripts/shopify/notifyRefundTaskCreated.groovy` |
| Refund Review form | `content/commerce/forms/shopify/refund-review.html` |
| Notification destinations | `etc/commerce/config/notifications.yml` |
