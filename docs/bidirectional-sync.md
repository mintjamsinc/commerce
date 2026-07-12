# Bidirectional Sync (CMS → Shopify)

The outbound half of the integration. The platform already
receives from Shopify (webhooks → ingestion) and writes a fulfillment back at the
end of the order workflow; this lets operators / tooling push the three corrections
they most need from the CMS side, through the Shopify Admin API.

| Action | What it does | Shopify mutation |
|---|---|---|
| `inventory` | set a variant's available quantity at a location (stock correction) | `inventorySetQuantities` |
| `price` | set a variant's price | `productVariantsBulkUpdate` |
| `publish` | publish / unpublish a product (status ACTIVE / DRAFT) | `productUpdate` |

## Surface

```
GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/sync.groovy      # capability/status
POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/sync.groovy      # perform an action
```

```jsonc
// stock correction
{ "action": "inventory", "inventoryItemId": 123, "locationId": 456, "quantity": 10 }
// price update
{ "action": "price", "productId": 1, "variantId": 2, "price": "19.99" }
// publish / unpublish
{ "action": "publish", "productId": 1, "published": true }
// add "dryRun": true to any of the above to validate + echo the plan (no Shopify call)
```

Ids may be raw numeric ids or full gids — they are normalized to gids. The endpoint
lives outside `/content/public`, so the CGI enforces authentication and ACLs. It is
the programmatic surface a Webtop app / form / ichigo.js storefront calls.

## Gating, safety & audit

- **Enablement** — requires the Admin API to be configured in `shopify.yml` (the same as
  metafield enrichment and fulfillment write-back). When it is not configured the endpoint
  returns `409` and never calls Shopify.
- **Dry run** — `"dryRun": true` validates the request and returns the exact
  target (gids + values) that *would* be written, without calling Shopify. Safe for
  staged rollout and for wiring up UIs.
- **Health** — each live call is timed and recorded as an `api` outcome
  (`sync:{action}`) via `commerce.Health`, so the connection-health monitor
  covers outbound calls too.
- **Audit** — every attempt (including dry runs and failures) is written to
  `/content/commerce/sync/{yyyy}/{MM}/sync_{ts}.json` with the request, outcome and
  any error.

## Shared logic

The mutations live in `commerce.ShopifyWrite` (see
[commerce-shared-classes.md](commerce-shared-classes.md)), built on the existing
`commerce.ShopifyAdmin` (token + GraphQL). Unlike the defensive ingest helpers,
these raise on a transport error or a Shopify `userErrors` entry so the endpoint can
report the outcome — mirroring `recordFulfillment`'s write-back policy.

## Relationship to reconciliation

These are the write primitives a future reconciliation job calls to auto-refresh
detected Shopify→CMS drift (stock / price / status). They are deliberately small and
composable so both an operator action and an automated reconciler use the same path.

## Operator UI

Since the single **Commerce Operations** console was split into four single-concern apps
(reports / oplog / import / events), this endpoint is driven from the **entity editors**, not
a standalone action console. The **Commerce Product** editor
(`webtop/src/webtop/apps/commerce-product`) writes a product's base fields / status
(publish–unpublish) / variant price / stock / media through `sync.groovy`, and the **Commerce
Customer** editor writes customer updates — each with a **Dry run** toggle (validate + echo the
plan without calling Shopify) and an Admin-API capability banner; real (non-dry-run) writes
are confirmed first.

Every attempt (dry runs and failures included) is audited, and the audit is its own
observation-only app: **commerce-oplog** (`webtop/src/webtop/apps/commerce-oplog`) — a
searchable / filterable list of recent outbound writes from `reports.groovy?type=operations`
(status / date range + text search over action / actor / entity / target), with CSV export and
the Admin-API status banner. Drift is now the **commerce-reconcile** app and event
replay the **commerce-events** app.
