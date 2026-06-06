# Inventory Threshold Rules

Generalises the inventory alert workflow from "every product needs a manually
configured threshold" to **dynamic thresholds** resolved per variant from product
attributes, the calendar and sales velocity — while staying fully backward
compatible.

## Effective threshold precedence

For each variant the engine ([`commerce.InventoryRules`](../content/WEB-INF/classes/commerce/InventoryRules.groovy))
resolves one **effective threshold**, highest precedence first:

1. **manual override** — an explicit per-variant threshold an operator set on the
   product (the "Set Inventory Threshold" form → `inventory_level_config`). Always wins.
2. **rule** — the **first matching** rule in `inventory-rules.yml` (rules are
   evaluated top-down; order them most-specific first).
3. **default** — the config's `default`, when present.
4. **none** — no threshold; the variant is not monitored (the original behaviour
   when nothing is configured).

Every alert records *why* a threshold applied (`source` = manual / rule / default,
plus the rule name), so the notification shows e.g. `Threshold: 20 (rule: Perishable)`.

## Configuration (`/etc/commerce/config/inventory-rules.yml`)

```yaml
default: 5            # effective threshold when no rule matches (omit → unmatched
                      # variants stay unmonitored / manual-only)
rules:
  - name: "Perishable"
    match:
      productType: ["Food", "Beverage"]
    threshold: 20
  - name: "Year-end peak"
    match:
      tags: ["seasonal", "gift"]
      season: { from: "11-15", to: "12-31" }
    threshold: 30
  - name: "High velocity"
    match:
      minVelocityPerDay: 5
    threshold: 25
```

A rule matches when **all** the criteria it declares hold (a rule with no `match`
is a catch-all). Criteria:

| Criterion | Meaning |
|---|---|
| `productType` | list — Shopify `product_type` is one of these (case-insensitive) |
| `vendor` | list — Shopify `vendor` is one of these |
| `tags` | list — the product has **at least one** of these tags |
| `season` | `{ from: "MM-DD", to: "MM-DD" }` — today is within the window (may wrap the year end, e.g. `11-15`→`01-15`) |
| `minVelocityPerDay` | number — the variant's sales velocity (units/day) ≥ this |

Because it is a list structure, the file is parsed by the calling scripts with
the YAML binding and handed to the pure rule engine.

## Behavioural change (backward compatible)

- **With `inventory-rules.yml` deployed (a `default` set):** products are
  monitored **immediately** using effective thresholds — the first-run "Set
  Inventory Threshold" task is skipped (operators can still set per-variant
  overrides anytime via the form, which take precedence).
- **Without the file:** behaviour is unchanged — only variants with a manual
  threshold are monitored, and a brand-new product still routes to the manual
  setup task.

This is governed by `checkThresholdConfig.groovy`, which now reports
"configured" when an effective threshold exists from *any* source.

## Sales velocity

The `minVelocityPerDay` criterion consumes a per-variant velocity supplied by the
caller. This is now populated from the cached sales velocity
(`commerce.SalesVelocity.loadPerDay`, see [sales-velocity.md](sales-velocity.md)),
so velocity-based rules are live: a variant selling at or above the configured
rate gets that rule's threshold. (Before any velocity has been computed the map
is empty, so velocity criteria simply don't match — the engine degrades
gracefully.)

## Where it is used

| Script | Use |
|---|---|
| `checkInventoryLevel.groovy` | compares each variant's quantity against its effective threshold |
| `checkThresholdConfig.groovy` | decides whether the manual setup task is needed (effective threshold exists?) |
| `notifyTaskCreated.groovy` | shows the effective threshold and its origin in the alert |
