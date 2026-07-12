# GDPR Compliance (customers/redact, customers/data_request, shop/redact)

The platform stores PII the moment an order is ingested, so GDPR handling is a
**platform obligation, not a customer-domain feature**. The three Shopify
compliance topics are handled as bespoke ingest routes (`gdpr.xml`) backed by
`commerce.Gdpr`.

## Setup (operator)

Shopify delivers compliance topics to a **separately registered compliance
webhook endpoint** (Partner dashboard → App → Compliance webhooks), NOT the
normal webhook subscriptions. Register the same adapter URL there:

```
/bin/cms.cgi/{workspace}/content/public/commerce/endpoints/shopify/webhook.groovy
```

The events arrive HMAC-verified through the normal ingest core, are logged to
the event log (replayable on failure), and dispatch to the routes below.

| Topic | Route | Handler |
|---|---|---|
| `customers/redact` | `direct:shopify-customer-redact` | `customerRedact.groovy` → `Gdpr.redactCustomer` |
| `customers/data_request` | `direct:shopify-customer-data-request` | `customerDataRequest.groovy` → `Gdpr.dataRequest` |
| `shop/redact` | `direct:shopify-shop-redact` | `shopRedact.groovy` → `Gdpr.shopRedact` |

## Redaction policy: anonymize and keep

Records are **kept** for accounting / tax / legal-claim purposes (GDPR art.
17(3) exceptions); only personally identifying fields are dummied out.

| Keep (never deleted) | Redact (dummy / clear) |
|---|---|
| Order / refund / variant / line-item ids, quantities, amounts, **taxes**, timestamps | Names (first/last), **emails** (→ `redacted_{id}@example.com`), phone numbers |
| **Country / province-level region** (tax rates, statistics) | Street-level address: `address1/2`, `city`, `zip`, `company`, coordinates |
| Non-PII stats on the customer node (orders, total_spent, segment) | Client IPs (`browser_ip`), the raw Shopify customer JSON (→ `{}`) |

The dummy email is unique per customer (`redacted_{customerId}@example.com`) to
avoid uniqueness collisions.

## PII field map (store × action)

| Store | Match | Action |
|---|---|---|
| `orders/raw/**` | `orders_to_redact[]` ids + `commerce:customer_email` query | Body: scrub `email`/`contact_email`/`phone`, reduce `customer` to `{id, dummy}`, reduce `billing/shipping_address` to region, clear `browser_ip`. Props: `commerce:customer_email` → dummy |
| `refunds/raw/**` | `commerce:order_id` of redacted orders | Body: same generic scrub (refund payloads embed order/customer fields on some API versions) |
| `backorders/**` | `commerce:customer_email` + `commerce:order_id` | Body `customer_email` + prop → dummy |
| `entities/{src}/checkouts/**` (and any entity carrying the email) | `commerce:customer_email` | Body scrub + prop → dummy |
| `entities/{src}/customers/{id}` | customer id | Body → `{}`, `commerce:status=redacted` |
| `customers/customer_{id}.json` | customer id | **Shell**: body `{}`, `commerce:status=redacted`, `commerce:redacted_at`, dummy email, `commerce:name=GDPR_REDACTED`, `marketing_enabled=false`. Stats props are kept (non-PII) |
| `events/{src}/**` (raw payloads) | contains email / customer id / order id | `payload` subtree scrubbed in place |
| `crm/customers/**` (legacy) | key | Deleted (derived data) |

Every touched node gets `commerce:gdpr_redacted` (Boolean) +
`commerce:redacted_at` (Date), which is also the **idempotency guard**: a
replayed / duplicated webhook skips already-redacted nodes, and an
already-shelled customer makes the whole run a no-op.

Known limitation (documented on purpose): free-text operator fields
(`internal_memo`, order/refund `note`) are not machine-scrubbed — operators
should not put customer PII there.

## customers/data_request

Collects the customer node, orders (requested ids + email match), refunds,
backorders and checkout mirrors into one JSON report under
`/content/commerce/gdpr/data-requests/{yyyy}/{MM}/` (kept under the non-public
`/content/commerce`, so it is not web-exposed; note: no explicit admin-only ACL is
currently set — the reports inherit the store's default access) and
notifies operators. **The merchant conveys the data to the customer** — the
platform only assembles it.

## shop/redact

Sent ~48h after app uninstall: deletes every store under `/content/commerce`
(orders, refunds, products, inventory, events, entities, customers, migrations
markers, …) plus the public catalog projection
(`/content/public/commerce/catalog`). Config under `/etc/commerce` is operator
data and stays.

## Non-goals

- No customer-facing UI: Shopify owns the request intake.
- The compliance webhooks' own event-log entries contain the request payload
  (customer id + email); `customers/redact` scrubs matching event payloads in
  the same pass, and `shop/redact` erases the log wholesale.
