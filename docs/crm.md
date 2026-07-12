# Customers

Customers are a **first-class store**:
`/content/commerce/customers/customer_{id}.json` (guests:
`customer_email_{hash}.json`), body = the raw Shopify customer JSON, everything
else in typed JCR properties (see commerce-properties.md). The legacy
order-derived `crm/customers` rollup store and the dormant
`entities/*/customers` mirror were migrated and removed by the
`customers-store` migration.

The customer domain follows the same philosophy as product-360: the CMS **mirrors
what Shopify owns and lets an operator edit it through the Admin API** — it does
not compute its own view of the customer. There is no self-maintained wallet, no
automatic segmentation, and no recency/VIP scoring. **VIP is simply a manual
Shopify customer tag**; anything the shop wants to track about a customer is a
tag, a note, or a Shopify-native field.

## Ingestion

```
customers/create|update|enable|disable ──▶ recordCustomer.groovy
                                              └─ Customers.upsertFromWebhook
customers/delete ─────────────────────────▶ deleteCustomer.groovy
                                              └─ Customers.markDeleted
```

`recordCustomer.groovy` stores the raw Shopify customer JSON as the node **body**
(the product-mirror convention) and promotes profile / lifecycle fields to typed,
auto-indexed JCR properties:

| Property | Type | Source |
|---|---|---|
| `commerce:status` | String | `received` (lifecycle; `deleted` after `customers/delete`) |
| `commerce:customer_id` | String | Shopify customer id |
| `commerce:source_status` | String | Shopify account state (`enable`/`disable` arrive as the same customer object) |
| `commerce:created_at` / `commerce:updated_at` | Date | Shopify timestamps |
| `commerce:email` | String | customer email |
| `commerce:name` | String | first + last name |
| `commerce:marketing_consent` | String | Shopify `email_marketing_consent.state` |
| `commerce:marketing_enabled` | Boolean | `true` when consent state is `subscribed` (the compliance gate) |
| `commerce:tags` | String | comma-separated Shopify tags (VIP lives here) |
| `commerce:tax_exempt` | Boolean | Shopify `tax_exempt` |

The node is also stamped with `jcr:mimeType =
application/vnd.mintjams.commerce.customer+json` (raw `+`; `%2B` only inside
`cms:store` URLs). That MIME is what launches the customer editor from a node.

`customers/delete` sets `commerce:status = deleted` (+ `commerce:deletedAt`) —
parity with `products/delete`, distinct from a GDPR redact. Guest records fold
into the member record when the email matches (basic identity merge), and no
stats are recomputed at ingestion — the body **is** the source of truth.

## Endpoint — `crm.groovy` (READ-ONLY)

`GET` only; edits go through the sync endpoint (below). Backed by the customer
store; the string filters run as one XPath query over the auto-indexed
`commerce:*` properties. Lives outside `/content/public`, so the CGI enforces
authentication and ACLs.

```
GET crm.groovy?view=browse[&q=&tag=&marketing=&sourceStatus=&page=&limit=]
                 [&sort=updated|spend&spendFrom=&spendTo=&minSpend=&spendMetric=totalPrice|gross|net]
                                     paginated member-customer list + live facets
                                     (facets: tags / marketing / sourceStatus)
GET crm.groovy?view=search&q=partial[&limit]   partial match on name / email / id
GET crm.groovy?view=customer&id=123             one customer (Shopify body + props)
GET crm.groovy?view=customer&key=customer_123[.json]   (id / key interchangeable)
```

`view=browse` lists only member customers (`customer_{id}.json`) — the ones the
editor can open (it derives the id from the filename); guests
(`customer_email_{hash}.json`, no Shopify id) are excluded by design. Facet
counts reflect the current drill-down, and the list is ordered by
`commerce:updated_at` descending.

**Spend axis** (operator sovereignty — metric / window / threshold are all
request-chosen): `sort=spend` ranks by per-customer purchase amount, and
`minSpend` keeps only customers whose chosen metric ≥ the base-currency amount
over the `spendFrom`/`spendTo` window (ISO instants; absent = all time) — the
"customers who spent at least a given amount over a specified period" filter. `spendMetric` picks the figure
(`totalPrice` default / `gross` / `net`). The per-customer figures come from ONE
grouped facet pass over the order-grain sales facts
(`commerce.SalesQuery.spendByCustomer` — uncapped, exact); the customer mirror
stores NO derived spend. Matching rows carry a `spend` object and the response a
`spendWindow` echo. With no other filter active, `sort=spend` ranks straight from
the fact aggregation (exact and uncapped — purchasers only, resolved by direct id
lookup on the flat store); with filters it re-ranks the filtered scan window
(capped at 5000 like the rest of the browse, flagged via `capped`).

## Editing — Admin API

Operator edits are pushed to Shopify (the mirror follows on the webhook
round-trip), never written straight into the CMS body. They go through the
outbound sync endpoint's `customer` action:

```
POST sync.groovy
  {"action":"customer","customerId":123,
   "fields":{"tags":["vip"],"note":"...","taxExempt":true,
             "marketingConsent":{"state":"subscribed"}}}
  (add "dryRun":true to validate + echo the plan without calling Shopify)
```

- Gated on `adminApi.enabled` (the same switch as metafield enrichment and
  fulfillment write-back); supports `dryRun` for safe rollout; audited under
  the distinct action name `customer_update`.
- `ShopifyWrite.updateCustomer(...)` runs the Admin GraphQL `customerUpdate`
  (tags / note / taxExempt) and, when `marketingConsent` is present,
  `customerEmailMarketingConsentUpdate`. Shopify's marketing enums are
  case-sensitive UPPERCASE (`SUBSCRIBED`, `SINGLE_OPT_IN`, …); the writer
  normalizes the lowercase webhook/mirror casing up.

## Operator UI — two Webtop apps

The old "customer 360" console is split into a browser and an editor, so the
editor is the single write hub:

- **Commerce Customer** (`webtop/src/webtop/apps/commerce-customer`) — the
  singular **editor** (`editor: true`, `contentTypes:
  application/vnd.mintjams.commerce.customer+json`). MIME-launched by
  double-clicking a `customer_{id}.json` node (from the Content Browser or the
  browser below). Displays the Shopify mirror — profile, `orders_count`,
  `total_spent` (shop currency, straight from Shopify), addresses, marketing
  consent — and edits the small set of shop-curated fields: tags (VIP is just a
  tag), the internal note, tax exemption and email-marketing consent. Addresses
  are display-only in v1.
- **Commerce Customers** (`webtop/src/webtop/apps/commerce-customers`) — a
  read-only **browser** over `crm.groovy?view=browse`: full-text search plus
  facets (tags / marketing consent / Shopify source status). A `singleton`.
  Opening a row hands the customer node to the editor through the same MIME
  association a Content-Browser double-click uses. (The Commerce Product editor
  likewise lost its standalone search sidebar — search lives only in the
  browsers.)

## GDPR

`customers/redact` reduces the customer node to a shell and anonymizes PII across
all stores; `customers/data_request` assembles the held data; `shop/redact`
erases everything. See [gdpr.md](gdpr.md).

## Migrations

- `customers-store` — seeds the profile properties on existing customer
  nodes, verifies them, and hard-deletes the legacy `crm/customers` and
  `entities/*/customers` paths.
- `customer-mimetype` (`CustomerMimeTypeMigration`) — retro-stamps existing
  customer nodes with the customer MIME so the editor association covers records
  ingested before the MIME was introduced.
