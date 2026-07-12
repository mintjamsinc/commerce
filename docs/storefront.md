# Self-Hosted Storefront = Product Data + a Thin JS Client

The platform does **not** ship a storefront — it exposes a small,
sanitized **product read endpoint** plus a tiny **data-only JS client**. You build
your own promotion / feature pages as ordinary same-origin CMS pages and pull product
data in with the client; **you render and style everything yourself**. Purchase goes to
Shopify's hosted checkout via a cart permalink.

History: the fixed ichigo.js SPA and the block-page LP publisher were removed
by the storefront-embed migration; the pre-built public catalog projection, the server GSP
templates, the declarative SDK widgets and the commerce-publish app were removed by the
2026-07-03 simplification. The storefront-retire migration hard-deletes the leftover projection data.

## How it works

```
Shopify (source of truth) ──webhook/Admin API──▶ /content/commerce/products (admin mirror)
                                                      ▲ read directly (everyone has /content READ)
public page (same-origin) ─fetch─▶ /content/public/commerce/endpoints/catalog.groovy (anonymous)
                                     ▼
                                 commerce.Catalog.detail / card (sanitize)
                                     → customer-safe JSON (Shopify-active products only)
```

There is **no pre-built projection** any more: the endpoint reads the admin mirror on
demand and sanitizes per request, so the data is always fresh. Every session — including
the anonymous one the public endpoint runs as — has repository READ on `/content`
(granted to everyone; only `/etc` is denied), so the endpoint reads the mirror **directly**
with no privileged delegation. Sanitizing to a customer-safe subset (`Catalog.detail`) is
what keeps the raw admin JSON from leaking, and only `commerce:source_status = active`
products are returned.

## Read endpoint

`GET /content/public/commerce/endpoints/catalog.groovy` (same-origin; returns JSON):

| Query | Returns |
|---|---|
| `?id={id}` / `?handle={handle}` | one sanitized product detail (with live stock) |
| `?view=list[&tag=&type=&vendor=&q=&limit=]` | sanitized product cards (bounded; cards omit live stock) |

Handles resolve through the auto-indexed `commerce:handle` property. Sanitized fields:
`id / handle / title / bodyHtml / vendor / productType / tags / images / options /
variants(id, title, sku, price, compareAtPrice, available) / localized` (the multi-
language PIM overlay). Admin metadata, cost, and internal PIM attributes are never
emitted. The response is cacheable (`Cache-Control: public, max-age=30`).

## Client SDK — data only

`/content/public/commerce/sdk/commerce.js` (v2): a tiny, dependency-free **data client**
— no rendering, no cart, no widgets (you design the page). Configure it on the tag (the
endpoint is same-origin; the shop domain is a public value used to build checkout links):

```html
<script src="/content/public/commerce/sdk/commerce.js"
        data-commerce-endpoint="/bin/cms.cgi/{workspace}/content/public/commerce/endpoints/catalog.groovy"
        data-commerce-shop-domain="your-shop.myshopify.com"
        data-commerce-currency="JPY"></script>
```

JS API:

- `Commerce.product(idOrHandle)` — one sanitized product detail (Promise; numeric → id, else handle)
- `Commerce.products({tag, type, vendor, q, limit})` — sanitized product cards (Promise)
- `Commerce.checkoutUrl(variantId, qty)` — Shopify cart permalink (string)
- `Commerce.formatMoney(amount, currency?)`

Vanilla JS, no dependencies; version through the URL (`commerce.js?v=2`).

## Checkout

Shopify hosted checkout via cart permalink
(`https://{shopDomain}/cart/{variantId}:{qty}`) — no Storefront API token.
`Commerce.checkoutUrl(variantId, qty)` builds it from `data-commerce-shop-domain` (the
public shop domain you set on the script tag). You render the buy button.

## Notes

- **Same-origin**: pages that embed the SDK are served from this CMS host, so the
  endpoint sends no CORS headers, and the read runs as the page visitor's session.
- **Never touches `/etc`**: the endpoint reads only `/content/commerce` (product mirror,
  PIM overlay, inventory levels). The checkout shop domain is configured on the client,
  so no Admin API config is read.
- **No admin UI**: there is no publish / rebuild console (the commerce-publish app was
  removed; webtop apps 7 → 6). Nothing is published — reads are on demand.
