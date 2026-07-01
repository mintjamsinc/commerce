# Product Information Management (PIM)

Category G, #23. A CMS-authoritative overlay of extended attributes on top of the
Shopify product mirror — multi-language titles/descriptions, rich descriptions, and
custom attributes / metafields — managed centrally so it inherits the platform's
JCR strengths: **version history**, **full-text search** and **ACLs**.

## Model

Each Shopify product is mirrored at `/content/commerce/products/product_{id}.json`
(raw JSON + `commerce:*` metadata + a metafields mirror when the Admin API is on).
PIM adds one JSON property, **`pim`**, on that same node — the same co-location
pattern as the forms' `inventory_level_config` / `fulfillment` properties — so the
overlay is versioned, searchable and ACL-governed *with* the product.

Overlay shape (free-form; these are conventions):

```jsonc
{
  "attributes": { "material": "cotton", "care": "machine wash" },
  "localized":  { "ja": { "title": "…", "description_html": "…" },
                  "en": { "title": "…", "description_html": "…" } },
  "metafields": [ { "namespace": "custom", "key": "care", "type": "single_line_text_field", "value": "…" } ],
  "updatedAt": "…", "updatedBy": "…"
}
```

`commerce.Pim.view` composes **Shopify base + metafields mirror + PIM overlay** into
one unified product view that downstream consumers (the ichigo.js storefront #20/#22,
exports) read.

## Endpoint

```
GET  …/endpoints/pim.groovy?q=keyword[&limit=50]      # full-text product search
GET  …/endpoints/pim.groovy?productId=123             # unified view
GET  …/endpoints/pim.groovy?productId=123&raw=true    # raw PIM overlay only
POST …/endpoints/pim.groovy  {productId, pim:{…}, merge:true}   # write overlay
```

`POST` deep-merges by default (a partial edit keeps other fields); `merge:false`
replaces the overlay. Writes are stamped `updatedAt` / `updatedBy` and committed on
the product node, so the change enters the node's JCR version history. Lives outside
`/content/public`, so the CGI enforces authentication and the product node's ACL.

## Full-text search

`GET ?q=` runs a JCR `jcr:contains` query over the product files
(`/jcr:root/content/commerce/products//element(*, nt:file)[jcr:contains(., '…')]`),
which indexes the stored Shopify JSON *and* the `pim` overlay — so a search matches
titles, descriptions, tags and any CMS-authored attribute. Returns
`{ productId, title, handle, status, path }` rows.

Each row is built from the denormalized `commerce:*` node metadata, falling back to
the node name (which encodes the id, `product_{id}.json`) and the stored JSON body
(`title`/`handle`/`status`) whenever that metadata is absent — e.g. products imported
outside the webhook route, or mirrored before those properties existed. So a row is
always populated and consistent with `view()`, never `#null`.

## Pushing to Shopify

CMS-authored metafields in the overlay are written back to Shopify through the
outbound sync (#2):

```
POST …/endpoints/sync.groovy  {"action":"metafields","productId":123}
```

This reads `pim.metafields` (via `commerce.Pim.metafieldsToPush`) and applies them
with the Admin `metafieldsSet` mutation (`commerce.ShopifyWrite.setMetafields`),
requires the Admin API to be configured, with `dryRun` and the same audit trail as every other
outbound write. Multi-language fields are served from the unified view today;
pushing them to Shopify translations is a future extension.

## Operator UI

A dedicated Webtop application, **Commerce PIM** (`webtop/src/webtop/apps/commerce-pim`),
gives operators a point-and-click editor over the overlay — no JSON by hand:

- **Search** the mirrored catalog (full-text, via `GET ?q=`) and pick a product.
- **Edit** the overlay in three sections — *Localized content* (per-locale title +
  HTML description), *Custom attributes* (key/value), and *Metafields*
  (namespace/key/type/value). The Shopify base (title/handle/status/variants) shows
  read-only for reference.
- **Save** writes the overlay with `merge:false` (so removals take effect), while
  preserving any unmanaged overlay keys and per-locale subfields it doesn't surface.
- **Push metafields to Shopify** from the toolbar (`POST sync.groovy
  {action:"metafields"}`), enabled only when the Admin API is on.

Unsaved edits are guarded on product switch and window close. The app is
self-contained (only depends on the published ichigo.js runtime) and builds with the
other Commerce apps via `webtop/rollup.config.js` (`makeApp('commerce-pim')`).

## Shared logic

`commerce.Pim` (overlay read/write/merge, unified view, search, metafield payload)
and `commerce.ShopifyWrite.setMetafields`. See
[commerce-shared-classes.md](commerce-shared-classes.md).
