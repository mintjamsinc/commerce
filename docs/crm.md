# Customer CRM & Marketing

Category D. Builds on the ingestion platform (#1): `customers/*` and `checkouts/*`
are already normalized into the entity mirror, and orders are the authoritative
purchase record. From those, this turns raw data into customer intelligence and
marketing follow-up.

| # | Feature | What it does |
|---|---|---|
| #13 | Segmentation | roll up purchase history per customer, classify into segments |
| #14 | Abandoned cart | detect un-completed checkouts and follow up (customer email + operator summary) |
| #15 | VIP / dormant alerts | notify operators when a customer's behaviour changes |

## #13 Customer segmentation

A daily batch (`segmentCustomers.groovy`) rolls up every customer's purchase history
from the stored orders (`commerce.Customers.aggregate`) — keyed by the order's
customer id, or the email for guests — and classifies each with
`commerce.Customers.segment`:

| Segment | Rule |
|---|---|
| `new` | order count ≤ `newMaxOrders` |
| `repeat` | active, more than `newMaxOrders` orders |
| `vip` | total spent ≥ `vipMinSpend` **or** orders ≥ `vipMinOrders` |
| `at_risk` | no order in ≥ `atRiskDays` days |
| `dormant` | no order in ≥ `dormantDays` days |

The primary `segment` is chosen by priority (dormant → at_risk → vip → new →
repeat); `vip` is also kept as an orthogonal flag and `recency` as active/at_risk/
dormant, so a dormant VIP is still visible as VIP. Results are written to a CRM store,
one doc per customer:

```
/content/commerce/crm/customers/{key}.json   ( id_{id} | email_{hash} )
```

with `commerce:segment` / `commerce:vip` / `commerce:recency` / `commerce:orders` /
`commerce:total_spent` / `commerce:last_order_at` / `commerce:email` for cheap
querying. (Revenue is summed per the customer's store currency; multi-currency
customers are an edge case.)

## #15 Behaviour-change alerts

The same batch compares each customer's new classification to the stored one and
notifies operators when a customer becomes **newly VIP**, **newly at-risk** or
**newly dormant** (one summary per run, via the `notifications.yml` channels). This
is the "purchase behaviour changed" signal operators act on.

## #14 Abandoned cart follow-up

A 30-minute batch (`abandonedCheckouts.groovy`) finds checkouts that have no
`completed_at` and have been idle past `abandonedAfterMinutes`
(`commerce.Checkouts.findAbandoned`), and follows up:

- **Customer reminder email** (opt-in, **off by default** as it is outward-facing):
  when `sendToCustomer: true`, emails the customer a recovery link via the
  `notifications.yml` SMTP transport (`commerce.SmtpClient`), staged up to
  `maxReminders` with at least `reminderIntervalMinutes` between reminders. Reminder
  count + timestamp are tracked on the checkout entity (`commerce:reminders_sent` /
  `commerce:last_reminder_at`).
- **Operator summary** (always): a debounced notification of how many carts are
  abandoned (and how many reminders were sent), so abandonment is visible even when
  customer emails are disabled.

## Endpoint

```
GET  …/endpoints/crm.groovy?view=segments                        # counts by segment / recency / vip
GET  …/endpoints/crm.groovy?view=customers[&segment=vip][&limit] # CRM records, highest spend first
GET  …/endpoints/crm.groovy?view=customer&key=id_123             # one customer
GET  …/endpoints/crm.groovy?view=abandoned                       # abandoned checkouts
POST …/endpoints/crm.groovy                                      # recompute segments now (202)
```

## Configuration (`/etc/commerce/config/crm.yml`)

| Key | Meaning |
|---|---|
| `enabled` | master switch for the CRM batches |
| `segments.{vipMinSpend,vipMinOrders,newMaxOrders,atRiskDays,dormantDays}` | segment thresholds |
| `alert.enabled` | notify on VIP / at-risk / dormant transitions (#15) |
| `abandonedCart.{enabled,abandonedAfterMinutes,reminderIntervalMinutes,maxReminders}` | abandoned-cart timing |
| `abandonedCart.sendToCustomer` | email customers (off by default; operator summary always sent) |

## Shared logic

`commerce.Customers` (purchase-history rollup + segmentation + CRM store) and
`commerce.Checkouts` (abandoned detection + reminder bookkeeping). See
[commerce-shared-classes.md](commerce-shared-classes.md).
