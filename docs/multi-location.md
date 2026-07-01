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
value). Both routes record processing health like the other webhooks (#18).

## Access (`commerce.Locations`)

| Method | Purpose |
|---|---|
| `Map levels(session, inventoryItemId)` | locationId → available |
| `int aggregate(session, inventoryItemId)` | total across locations |
| `String locationName(session, locationId)` | name, or the id when no metadata |
| `List breakdown(session, productJson)` | per-variant, per-location breakdown (+ total) |

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

The inventory alert workflow (#5) judges low stock on the **multi-location mirror
total** — `commerce.Locations` summed across locations (`aggregate` / `levels`).
`sweepInventoryAlerts.groovy` (the timer-driven evaluation) and `notifyTaskCreated.groovy`
(the alert display) share this source of truth; `notifyTaskCreated` falls back to the
product webhook's aggregate `inventory_quantity` only until the mirror has been populated
for an item. Per-location low-stock alerting (a threshold per location,
rather than on the total) can still build on `commerce.Locations` in a future iteration.
