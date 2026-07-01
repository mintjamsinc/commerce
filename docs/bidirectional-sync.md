# Bidirectional Sync (CMS → Shopify)

The outbound half of the integration (category A, #2). The platform already
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
- **Dry run** (#28) — `"dryRun": true` validates the request and returns the exact
  target (gids + values) that *would* be written, without calling Shopify. Safe for
  staged rollout and for wiring up UIs.
- **Health** — each live call is timed and recorded as an `api` outcome
  (`sync:{action}`) via `commerce.Health`, so the connection-health monitor (#18)
  covers outbound calls too.
- **Audit** (#25) — every attempt (including dry runs and failures) is written to
  `/content/commerce/sync/{yyyy}/{MM}/sync_{ts}.json` with the request, outcome and
  any error.

## Shared logic

The mutations live in `commerce.ShopifyWrite` (see
[commerce-shared-classes.md](commerce-shared-classes.md)), built on the existing
`commerce.ShopifyAdmin` (token + GraphQL). Unlike the defensive ingest helpers,
these raise on a transport error or a Shopify `userErrors` entry so the endpoint can
report the outcome — mirroring `recordFulfillment`'s write-back policy.

## Relationship to reconciliation (#24)

These are the write primitives a future reconciliation job (#24) calls to auto-heal
detected CMS↔Shopify drift (stock / price / status). They are deliberately small and
composable so both an operator action and an automated reconciler use the same path.

## Operator UI

The **Commerce Operations** Webtop app (`webtop/src/webtop/apps/commerce-ops`) drives
this endpoint from its **Sync** tab: an action form (inventory / price / publish /
metafields) with a **Dry run** toggle (validate + echo the plan without calling
Shopify, #28), an Admin-API capability banner, and a recent-outbound-writes table
from `reports.groovy?type=operations`. Real (non-dry-run) writes are confirmed first.
The app's **Reconcile** and **Events** tabs cover drift (#24) and replay (#4).
