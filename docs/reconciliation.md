# Reconciliation (CMS ↔ Shopify)

Category G, #24. Detects where the CMS mirror has drifted from Shopify's current
truth — product **status**, variant **price**, variant **inventory** — reports it,
alerts, and (only when explicitly enabled per field) heals it. Drift normally means
a missed/failed webhook (also mitigated by ingest replay #4) or a CMS-authoritative
value that was never pushed.

## Flow

```
timer:commerce-reconcile (hourly, service user)   ─┐   POST …/endpoints/reconcile.groovy
                                                    ▼   (on-demand) → direct:commerce-reconcile
reconcile.groovy
   ├─ pick a cursor-advanced batch of products (maxPerRun; whole catalog over time)
   ├─ per product: fetch Shopify (status / variant price / inventoryQuantity)
   │              compare to the CMS mirror (commerce.Reconciliation.diffProduct)
   ├─ write a drift report → /content/commerce/reconciliation/{yyyy}/{MM}/recon_*.json
   ├─ alert on drift (debounced) → commerce.Alerts → Notifications (#17)
   └─ heal ONLY fields whose autoHeal is on (per source-of-truth direction)
```

The batch advances a round-robin **cursor** (`reconciliation/state.json`) so the
whole catalog is covered over successive runs without a large API burst. Requires
the Admin API (`shopify.yml → adminApi.enabled`).

## Source of truth & healing

Each field has a configured source of truth that sets the heal direction:

| sourceOfTruth | meaning | heal |
|---|---|---|
| `cms` | the CMS value wins | **push** to Shopify (`commerce.ShopifyWrite`) |
| `shopify` | Shopify wins | **refresh** the CMS mirror |

Healing is **off by default** — reconciliation detects + reports + alerts until you
opt in per field (`autoHeal`). **Inventory is never auto-healed**: the mirror is
per-location while Shopify exposes an aggregate, so neither direction is lossless;
inventory drift is always reported (heal it by replaying the missed inventory
webhook #4, or manually).

`status` push maps the mirror status to publish (`active` → ACTIVE) / unpublish
(otherwise → DRAFT) via `productUpdate`; `price` push uses `productVariantsBulkUpdate`;
`refresh` patches the stored product JSON (`status` / variant `price`) and its
`commerce:source_status` property.

## Endpoint

```
GET  …/endpoints/reconcile.groovy     # latest drift report + cursor/run state
POST …/endpoints/reconcile.groovy     # trigger a run now (202; runs as service user)
```

## Configuration (`/etc/commerce/config/reconcile.yml`)

| Key | Meaning |
|---|---|
| `enabled` | master switch |
| `maxPerRun` | products checked per run (cursor-advanced) |
| `sourceOfTruth.{status,price,inventory}` | `cms` / `shopify` per field |
| `autoHeal.{status,price,inventory}` | enable healing per field (inventory ignored) |
| `alert` | notify (debounced) on drift |

## Relationship to #2 / #4

The write primitives are the same `commerce.ShopifyWrite` used by the outbound sync
(#2); reconciliation is the automated consumer of them. For drift caused by missed
webhooks, replaying the event (#4) is often the cleaner heal — especially for
inventory. See [bidirectional-sync.md](bidirectional-sync.md) and
[ingestion.md](ingestion.md).

## Operator UI

The **Commerce Operations** Webtop app (`webtop/src/webtop/apps/commerce-ops`)
exposes reconciliation on its **Reconcile** tab: the latest drift report (products
with drift / field diffs / auto-healed / checked, plus a per-field drift table with
CMS vs Shopify values and the heal direction), the cursor state, and a **Run now**
trigger (`POST reconcile.groovy`, confirmed). The same app's **Sync** and **Events**
tabs cover the outbound-write (#2) and replay (#4) surfaces.
