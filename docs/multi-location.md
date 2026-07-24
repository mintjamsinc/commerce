# Multi-Location Inventory

Ingests Shopify's per-location stock, aggregates it per variant, and provides
cross-location **allocation** decision support — which locations to draw from to
fulfil a quantity. Advisory only: it does not override Shopify's own fulfillment
routing.

## Ingestion

| Webhook topic | Route | Stored |
|---|---|---|
| `inventory_levels/update` | `direct:shopify-inventory-level` → `recordInventoryLevel.groovy` | per-item, per-location available |
| `locations/create`, `locations/update` | `direct:shopify-location` → `recordLocation.groovy` | location metadata (name) |

Storage:

```
/content/commerce/inventory/
├── levels/{inventory_item_id}.json
│       { inventory_item_id, locations: { "<location_id>": { available, updatedAt } } }
└── locations/{location_id}.json          # raw Shopify location payload (name, …)
```

A variant links to its levels through `inventory_item_id` (carried on the product
JSON's variants). `recordInventoryLevel` merges each update into the item's file,
**newest update winning** (an out-of-order redelivery cannot overwrite a fresher
value). Both routes record processing health like the other webhooks.

### Location metadata backfill (initial import / self-heal)

The `locations/*` webhooks only fire on a **create or edit** in Shopify. A shop's
locations are almost always set up *before* the app is installed (and rarely edited
after), so no webhook ever fires and the location-metadata mirror stays **empty** —
which leaves the reorder review's destination picker with nothing to pick and
per-location names falling back to raw ids. To close that gap, `commerce.Locations`
has an Admin-API backfill:

| Path | Trigger | Effect |
|---|---|---|
| `Locations.backfillFromAdmin(session, log, client, endpoint, token)` | called FIRST by `reconcileBulkResult.groovy` on every full inventory pull | pages the Admin GraphQL `locations` and writes each to `locations/{id}.json` in the same REST shape `recordLocation` writes |

Both full-inventory paths land in `reconcileBulkResult` and therefore refresh the
location mirror first:

- the scheduled **inventory reconcile** (`inventory-full` Bulk, `inventory` scope), and
- the operator-triggered **Inventory backfill** (`inventory-backfill` Bulk, from
  **Commerce Import → Full import**).

So the mirror self-heals on the next reconcile cycle, and an operator can force it
immediately by running an Inventory backfill. The backfill is idempotent (a location
already mirrored is simply overwritten with the current payload).

## Access (`commerce.Locations`)

| Method | Purpose |
|---|---|
| `Map levels(session, inventoryItemId)` | locationId → available |
| `int aggregate(session, inventoryItemId)` | total across locations (sums the levels) |
| `Integer readTotal(session, inventoryItemId)` | **materialized** total from the index property, or null |
| `int materializeTotal(session, log, inventoryItemId)` | recompute the total and persist it (sweep only) |
| `boolean writeTotal(session, log, inventoryItemId, total)` | write the total onto the index node |
| `String locationName(session, locationId)` | name, or the id when no metadata |
| `List breakdown(session, productJson)` | per-variant, per-location breakdown (+ total) |

### Materialized total (fast 1:1 read)

Summing the per-location levels on every screen render is O(products × variants) reads, which
gets heavy as the catalog grows. Instead the total is **pre-computed and stored on the variant's
index node** as the `commerce:available_total` JCR property (+ `commerce:available_total_at`), so
callers read it 1:1 with `readTotal` — no per-location summation.

- **Single writer**: the inventory-alert sweep (`sweepInventoryAlerts.groovy`) is the only writer.
  It already aggregates for the alert decision; it now also persists that total for every live
  indexed item it drains (`materializeTotal`), guarded by its task lock so exactly one execution
  writes at a time — no write conflicts under concurrent webhooks.
- **All write paths converge**: webhook (`recordInventoryLevel` → `markPending`) and the bulk
  inventory audit (`writeLevels` → `writePending`) both feed the same pending queue, so any
  path that changes stock refreshes the total the same way. (The reconcile diff pass no longer
  touches inventory — the Bulk `inventory` audit owns stock recovery.)
- **Recompute-from-source**: the total is re-summed from the levels each time (never `+= delta`),
  so it converges regardless of update order or coalescing.
- **Near-immediate**: `inventory_levels/update` kicks the sweep asynchronously
  (`direct:commerce-inventory-alert-sweep`), so the total refreshes within milliseconds; the 15 s
  timer is the backstop.
- **Fallback**: when the property is absent (item not indexed, or not yet swept) `readTotal`
  returns null and callers fall back to `aggregate`. Correctness never depends on the cache.
- **List views**: the platform JCR auto-indexes every property, so `commerce:available_total`
  is XPath-queryable the moment it is written — large inventory lists are served by a normal
  indexed query with pagination (no rollup file, no per-row aggregation).

## Allocation (`commerce.Allocation`)

Pure logic. Given available-by-location and a quantity, returns a plan
(`{ requested, allocated, shortfall, allocations: [{locationId, qty}, …] }`) per
the strategy:

| Strategy | Behaviour |
|---|---|
| `most_stock` | draw from the location with the most stock first |
| `priority` | draw from the configured priority locations first (in order), then the rest by most stock |

A per-location `defaultSafetyStock` is held back (not allocatable).

## Configuration (`/etc/commerce/config/locations.yml`)

Managed from **Webtop → Commerce → Locations**. Flat (editor-friendly):

| Key | Meaning |
|---|---|
| `strategy` | `most_stock` / `priority` |
| `priorityOrder` | comma-separated location IDs (for the `priority` strategy) |
| `defaultSafetyStock` | quantity held back at each location |

## Reading it

```
GET /bin/cms.cgi/{workspace}/content/commerce/endpoints/inventory-locations.groovy?productId=123
GET ...?productId=123&variantId=456&qty=10        # also returns an allocation plan
```

`content/commerce/endpoints/inventory-locations.groovy` returns each variant's
per-location stock (with names); with `variantId` + `qty` it adds the allocation
plan. The Commerce **Dashboard** Locations card shows the location count, tracked
items, and how many (item, location) pairs are out (≤ safety stock).

## Relationship to the threshold engine

The inventory alert workflow judges low stock on the **multi-location mirror
total** — `commerce.Locations` summed across locations (`aggregate` / `levels`).
`sweepInventoryAlerts.groovy` (the timer-driven evaluation) and `notifyTaskCreated.groovy`
(the alert display) share this source of truth; `notifyTaskCreated` falls back to the
product webhook's aggregate `inventory_quantity` only until the mirror has been populated
for an item. Per-location low-stock alerting (a threshold per location,
rather than on the total) can still build on `commerce.Locations` in a future iteration.
