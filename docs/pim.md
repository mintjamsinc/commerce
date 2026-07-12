# Product Information Management (PIM)

A CMS-authoritative overlay of extended attributes on top of the
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
one unified product view that downstream consumers (the ichigo.js storefront,
exports) read.

## Endpoint

```
GET  …/endpoints/pim.groovy?q=keyword[&limit=50]      # full-text product search
GET  …/endpoints/pim.groovy?view=browse[&q=&vendor=&productType=&tag=
     &sourceStatus=&status=&limit=&offset=
     &sort=updated|sales|quantity&salesFrom=&salesTo=]  # faceted browse + counts (+ sales-fact sorts)
GET  …/endpoints/pim.groovy?productId=123             # unified view
GET  …/endpoints/pim.groovy?productId=123&raw=true    # raw PIM overlay only
POST …/endpoints/pim.groovy  {productId, pim:{…}, merge:true}   # write overlay
```

`view=browse` backs the product facet browser: filters run over the
auto-indexed `commerce:*` properties (vendor / product type / tags / Shopify
status / processing status, plus `jcr:contains` for `q`); one pass collects the
requested page and the facet counts, so counts always reflect the current
drill-down (soft-deleted mirrors excluded; scan capped at 5000, flagged via
`capped:true`).

**Sales sorts**: `sort=sales` (base gross) / `sort=quantity` (units sold) rank
the same filtered match set by the per-product figures from ONE grouped facet
pass over the line-grain sales facts (`commerce.SalesQuery.salesByProduct` —
uncapped, exact, real `product_id` axis) over the `salesFrom`/`salesTo` window
(ISO instants; absent = all time). Ranked rows carry a `sales` object
(`quantity / gross / discounts / returns / net / baseCurrency`). With no filter
active the ranking comes straight from the fact aggregation (exact and uncapped —
products with sales in the window, resolved by direct id lookup); with filters it
re-ranks the filtered scan window (capped, products without sales rank last). The
product mirror stores NO derived sales figures.

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
outbound sync:

```
POST …/endpoints/sync.groovy  {"action":"metafields","productId":123}
```

This reads `pim.metafields` (via `commerce.Pim.metafieldsToPush`) and applies them
with the Admin `metafieldsSet` mutation (`commerce.ShopifyWrite.setMetafields`),
requires the Admin API to be configured, with `dryRun` and the same audit trail as every other
outbound write. Multi-language fields are served from the unified view today;
pushing them to Shopify translations is a future extension.

The base-field (`action:"product"`) and media (`action:"media"`, ops
`add`/`delete`/`reorder`/`updateAlt`) writes described above ride the same
`sync.groovy` outbound path — Admin-API-gated, `dryRun`-capable, and recorded in the
`SyncAudit` trail (audit actions `product_update` / `media_add` / `media_delete` /
`media_reorder` / `media_updateAlt`; see
[commerce-properties.md](commerce-properties.md)).

## Operator UI

The **Commerce Product** editor (`webtop/src/webtop/apps/commerce-product`) is the
"product 360": everything about ONE product in one place, and the single write hub
toward Shopify (it replaced the former Commerce PIM app). It mirrors the shipped
customer editor's pattern — **display the mirror, edit the Admin-API-editable
fields** — under a strict one-way dataflow: writes go to the Shopify Admin API and
the CMS mirror follows via the `products/update` webhook. Six sections:

- **Overview** — the Shopify base facts (**title, description, vendor, product
  type, tags, handle, status**), now **editable** inputs + a description textarea +
  a **status** dropdown (`ACTIVE` / `DRAFT` / `ARCHIVED`) + a **Save to Shopify**
  button. Save posts only the changed fields —
  `POST sync.groovy {action:"product", productId, fields:{…}}` →
  `ShopifyWrite.updateProduct` (`productUpdate`; status uppercased, tags normalized,
  the Shopify call skipped when nothing changed). The old boolean publish / unpublish
  toggle was **folded into the status dropdown** (so `ARCHIVED` is now reachable;
  the standalone `action:"publish"` toggle was removed from the editor). Also shows
  the primary thumbnail from the mirror (`Pim.view` now carries an `images` key).
- **Variants & stock** — per-variant price and per-location quantities with Apply
  buttons; writes go to Shopify via the Admin API (`action:"price"` /
  `action:"inventory"`), the CMS mirror follows through the webhook round-trip.
- **Localized content** — the multi-language overlay (per-locale title + HTML
  description).
- **Metafields** — CMS-authored metafields; **Push to Shopify**
  (`POST sync.groovy {action:"metafields"}`) after saving.
- **Media** — product image management (v1). Because the REST/webhook mirror
  carries image `{src, alt}` but **not** the `MediaImage` gids the editor needs, the
  Media section **live-reads** media (with ids) from the dedicated
  `product-media.groovy` endpoint:
  `GET ?productId` → `{ productId, enabled, media:[{ id, alt, status, url, width,
  height }] }` (image-only; Admin-API-gated, degrades to `{enabled:false, media:[]}`).
  Each operation posts `sync.groovy {action:"media", op:…}`: **add by image URL**
  (`op:"add"`, `originalSource` — **async** Shopify processing, re-fetched shortly),
  **delete** (`op:"delete"`, `mediaIds`), **reorder** up/down (`op:"reorder"`,
  `orderedMediaIds` — **async** job; the optimistic order is kept), **alt edit**
  (`op:"updateAlt"`, `mediaId` + `alt`). **No local file upload in v1** (add-by-URL
  only; staged uploads deferred).
- **Planning** — the per-variant planning value (`pim.planning`, threshold = the
  fixed reorder point), edited directly per variant; empty inputs fall back to the
  `planning.yml` defaults (see [planning.md](planning.md)).

Two write channels. **Base fields (Overview) and Media are "channel B"** — an
immediate Shopify write (`productUpdate` / the media mutations), *not* the overlay
dirty-save; a discard guard prompts on leave / product switch when base edits are
unsaved. The **overlay sections** (Localized content, Metafields, Planning) use the
CMS dirty-save: it writes the `pim` overlay with `merge:false` (so removals take
effect) while preserving unmanaged overlay keys — including the retired free-form
`attributes`, which pass through untouched. Unsaved edits are guarded on product
switch and window close.

The editor registers for the product mirror's MIME type
(`application/vnd.mintjams.commerce.product+json`, `editor: true` +
`contentTypes` in `app.yml`), so a double-click in the Content Browser opens it.
The **Commerce Products** browser (`webtop/src/webtop/apps/commerce-products`)
is the faceted catalog view over `view=browse` (vendor / product type / tags /
status facets with live counts + full-text search) and launches the same editor
through the shell's MIME association. Both build with the other Commerce apps
via `webtop/rollup.config.js`.

## Shared logic

`commerce.Pim` (overlay read/write/merge, unified view, search, metafield payload)
and `commerce.ShopifyWrite` — `setMetafields` (metafields push), `updateProduct`
(Overview base fields), and the media mutations `addProductMedia` /
`deleteProductMedia` / `reorderProductMedia` (async) / `updateProductMediaAlt`
(`fileUpdate`). The live media read is the `product-media.groovy` endpoint. See
[commerce-shared-classes.md](commerce-shared-classes.md).
