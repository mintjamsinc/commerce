# Headless Storefront

Category F. A customer-facing storefront built with ichigo.js (#20) with real-time
inventory badges (#21), served entirely from public, sanitized catalog data — the
admin product store is never exposed.

## Architecture

```
admin data (NOT public)                     public projection (anonymous-readable)
/content/commerce/products  ─┐              /content/public/commerce/catalog/
+ PIM overlay (#23)          │  publish       ├─ index.json        (cards, for list/search)
+ inventory levels (#6)      ├──────────────▶ ├─ products/{id}.json (full detail)
                             │  (service user) ├─ inventory.json    (itemId → available, #21)
                             ┘                 └─ store.json        (name, shopDomain, currency, lowStock)
                                                        ▲
                                                        │ fetch (relative, anonymous)
                              /content/public/commerce/storefront/index.html  (ichigo.js SPA)
```

- **Why a projection** — an anonymous visitor cannot read `/content/commerce`. Rather
  than loosen ACLs or run a public script as a privileged user, a publisher (service
  user) writes a *sanitized* copy under `/content/public/commerce/catalog/` with only
  customer-safe fields (no `commerce:*` admin metadata, costs, or internal PIM
  attributes — only the localized marketing overlay). The storefront reads those
  files directly. `commerce.Catalog` builds the projection objects; `publishCatalog.groovy`
  does the IO.
- **Freshness** — the `commerce-catalog-publish` timer rebuilds the catalog every 5
  minutes (and prunes removed products). **Inventory is near-real-time**: the
  `inventory_levels/update` route calls `publishInventory.groovy` to update
  `inventory.json` within seconds of a stock change.
- **Only active products** are published (`commerce:source_status == active`, not
  deleted).

## Storefront app (#20)

A single ichigo.js page (`storefront/index.html`, no build step — ESM from CDN, like
the task forms) with hash-routed views:

- **Catalog** `#/` — card grid, client-side search (title / vendor / type / tags) and
  sort (name / price), with availability badges.
- **Product** `#/product/{handle}` — gallery, variant selector, price (with
  compare-at), description, availability badge, add-to-cart.
- **Cart** `#/cart` — line items (persisted in `localStorage`), quantity edit,
  remove, total, checkout.

## Checkout (#20)

Checkout redirects to **Shopify's hosted checkout** via a cart permalink —
`https://{shopDomain}/cart/{variantId}:{qty},…` — so there is **no Storefront API
token or secret**, and payment/checkout stays on Shopify. The shop domain comes from
`shopify.yml` (`adminApi.shopDomain`), published into `store.json`.

## Real-time inventory (#21)

The storefront polls `inventory.json` every 30s; the polled quantity overrides the
published snapshot, and badges recompute live:

| available | badge |
|---|---|
| `null` (untracked) | none — treated as purchasable |
| `0` | **Sold out** (add-to-cart disabled) |
| `≤ lowStock` | **Only N left** |
| `> lowStock` | In stock |

(A public SSE channel is not available to anonymous pages, so polling is used; the
client abstracts availability so SSE can replace polling later without UI changes.)

## URLs

```
Storefront : /bin/cms.cgi/{workspace}/content/public/commerce/storefront/index.html
Catalog    : /bin/cms.cgi/{workspace}/content/public/commerce/catalog/{index|store|inventory}.json
Admin      : GET/POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/storefront.groovy  (status / rebuild)
```

## Configuration (`/etc/commerce/config/storefront.yml`)

| Key | Meaning |
|---|---|
| `enabled` | master switch for catalog publishing |
| `storeName` | storefront title |
| `currency` | fallback display currency |
| `lowStock` | "only a few left" threshold (0 → sold out) |

## Content-commerce landing pages (#22)

Editorial landing pages that mix prose with product showcases — articles × products
on one page — built on the same publish-projection model.

```
admin (authored)                          public projection
/content/commerce/pages/{slug}.json  ──▶  /content/public/commerce/pages/{slug}.json
  block document                  publishPages.groovy   resolved (product blocks → cards)
                                  (service user)        + pages/index.json
                                                                 ▲ fetch (anonymous)
                              /content/public/commerce/landing/index.html?slug=…  (ichigo.js)
```

A page is a **block document** authored in the CMS (versioned, ACL-governed):

| Block `type` | Renders |
|---|---|
| `hero` | a banner (title / subtitle / image / CTA) |
| `heading` | a section heading |
| `markdown` | prose (a minimal, dependency-free markdown renderer) |
| `html` | trusted author HTML |
| `products` | a product grid, by `productIds` (explicit, ordered) or `tag` (+`limit`) |

`commerce.Pages.publicPage` resolves the `products` blocks against the published
catalog cards (#20), so embedded cards carry the same image / price / handle and
**live inventory** (#21, polled) and link into the storefront
(`../storefront/index.html#/product/{handle}`). `publishPages.groovy` runs on the
`commerce-pages-publish` timer (after the catalog) and on demand; it prunes removed
pages.

The landing renderer (`landing/index.html`) reads `?slug=` (default `welcome`). A
seed page ships at `/content/commerce/pages/welcome.json`.

### URLs

```
Landing : /bin/cms.cgi/{workspace}/content/public/commerce/landing/index.html?slug=welcome
Pages   : /bin/cms.cgi/{workspace}/content/public/commerce/pages/{index|{slug}}.json
```

## Operator UI

The **Commerce Publishing** Webtop app (`webtop/src/webtop/apps/commerce-publish`)
drives both publishing and landing-page authoring:

- **Publish tab** — catalog / inventory / page publish status (counts + timestamps)
  from `storefront.groovy`, a **Rebuild now** trigger, and quick links to the public
  storefront / landing / catalog JSON.
- **Pages tab** — a CRUD block editor for the landing documents over a dedicated
  admin endpoint, `pages.groovy`:

  ```
  GET  …/endpoints/pages.groovy            # list source pages (+ which are live)
  GET  …/endpoints/pages.groovy?slug=…     # one source page document
  POST …/endpoints/pages.groovy {slug, page:{…}}   # create / replace (stamped)
  POST …/endpoints/pages.groovy {slug, delete:true} # delete
  ```

  Blocks are added/reordered/removed with type-specific fields (hero / heading /
  markdown / html / products), with status (published/draft) and slug validation.
  Saves persist the source document; a **Rebuild** then projects it to the public
  storefront. Unsaved edits are guarded on switch and window close; deletes are
  confirmed.
