# Reports & Audit Export

Operator-facing reports over the data the platform keeps in JCR,
as JSON or CSV.

| Report | Source | Contents |
|---|---|---|
| `sales` | the index-backed sales facts (`/content/commerce/sales/{orders,lines}/index`, written by the `commerce.SalesFacts` drainer) | daily series + native per-currency revenue + base-currency rollup + the raw component breakdown and synthesized metrics |
| `operations` | outbound-write audit (`/content/commerce/sync`) | every CMS → Shopify write: when, action, status, error |

## Endpoint

```
GET /bin/cms.cgi/{ws}/content/commerce/endpoints/reports.groovy
      ?type=sales&days=30
      ?type=sales&from=2026-01-01&to=2026-01-31        # explicit range (inclusive) wins over days
      ?type=operations&days=30[&status=ok|failed|dryrun]
      [&format=json|csv]
```

Lives outside `/content/public`, so authentication + ACLs are enforced by the CGI.

## Sales: index-backed facet aggregation (the ONLY source)

Every sales read goes through `commerce.SalesQuery` over the index-backed sales facts:
the full component breakdown (gross / discounts / tax / shipping / tips / duties /
returns / returns_tax / returns_shipping) with `net`/`total` synthesized at read time
(operator sovereignty — the system stores no pre-selected "sales" number), plus
`stats`/`percentiles` and product/customer/PoP groupings. Aggregation is server-side
`facet accumulate` (cms0) — one pass, exact, no 5000-row cap. The pre-fact per-order
folder walk and its `salesSource` switch have been REMOVED: there is one source of
truth.

Boolean (and date) aggregates carry their type IN the query with the same `xs:`
cast syntax the order-by clause takes: `sum(xs:boolean(@commerce:components_complete))`
(`SalesQuery.sumBoolExpr`). The search index stores boolean/date doc values raw
(0/1 / epoch ms) while numbers are sortable-double encoded; without the cast the
facet aggregator decodes with the numeric default and a boolean sum collapses to
~0 (which mislabelled every order "not decomposable"). The result dimension stays
`sum(commerce:components_complete)` — a cast never changes addressing. Numbers
need no cast; a new boolean/date aggregate must use one.

`type=sales` params (defaults from `etc/commerce/config/sales.yml`):

| Param | Meaning |
|---|---|
| `financialStatus=paid,partially_refunded,…` | Population by Shopify financial_status (empty = all). |
| `includeCancelled=true\|false` | Keep cancelled orders in the population. |
| `returnsBasis=order\|refund` | Returns attributed to the order date (default) or the refund store by `refunded_at`. Response is labelled with the basis. |
| `groupBy=product\|customer` + `top=N` | Add top-N products (real `product_id`) / customers. |
| `compare=1` | Add period-over-period (current vs preceding equal window). |

The sales CSV is one row per day with the full component columns
(`date,orders,baseCurrency,baseRevenue,gross,discounts,returns,tax,shipping,tips,duties,net,total`,
base currency, camelCase headers per the wire convention), so the operator can slice
gross/net/returns/shipping/fees freely in a spreadsheet. Per-day native-by-currency does
not exist in the facet report (a 2-dim grouping); the native per-currency totals ride the
JSON report (`totals.revenue`). Under `returnsBasis=refund` the range-level returns figure
is refund-period while the daily rows stay order-cohort — the response labels both
(`returnsBasis` / `dailyReturnsBasis`).

Orders whose mirror body lacks the money decomposition (`components_complete=false`)
count toward `orders`/`totalPrice` but contribute NOTHING to the components — the
report carries `totals.incompleteOrders` so a partial breakdown is visible, never a
silent fake zero.

## The sales report is a P/L; the refund list is cash-flow

The `sales` report returns TWO views, deliberately kept apart, because they answer
different questions and cannot be added together.

- **`totals.pl` / `daily[].pl` — the P/L, ORDER-date basis.** "What did each order finally
  come to." Tax-exclusive, composed ADDITIVELY: `grossSales − discounts − returns = netSales`,
  then `+ shipping + otherIncome = totalRevenue`, then `+ tax + duties = totalCharged`.
  `totalCharged` equals the actual net cash charged. This is the Shopify Analytics "net sales"
  axis. `otherIncome` is split into `tips` (order cohort) and `restockingFees` (moves with the
  returns basis) so each stays single-cohort.
- **`totals.refunds` / `refunds.daily[]` — the refund list, REFUND-date cash-out.** "What cash
  went out, on which day." NOT a P/L (no ladder). `cashOut` is the real cash refunded
  (`refund_amount`, the transactions), and `cashOut == goods + tax + shipping − restockingFeeIncome`
  (i.e. `refundOutflow − restockingFeeIncome`).

**Why they are never in one table.** A P/L row (order date) and a refund row (refund date) come
from different cohorts. A single refund appears in BOTH — on a DIFFERENT day and for a DIFFERENT
amount — and that is correct:

    Order 7/8:   goods 30,000 + tax 3,000 + shipping 1,000 = 34,000 charged
    Refund 7/11: 29,000 refunded (a 5,000 restocking fee was kept)

    P/L (order date)    → the 7/8 row moves: returns 34,000; the 5,000 restocking fee is booked
                          as other income (the store RECEIVED it — it is not a discount on returns).
    Refund list (7/11)  → the 7/11 row shows cashOut 29,000.

`refunds.daily[].crossPeriod` flags a refund day whose refunds include an order from OUTSIDE the
window — the signal a monthly close needs ("a refund landed in July against a June order").
Adding the `refunds` block moves NO number in `pl` (`totalCharged` stays put) — cash-flow and
P/L are structurally separate; that separation is what keeps `dayTotalDrift` at 0.

## Design rationale — the load-bearing pieces (do NOT "simplify" these away)

Every one of these looks removable and is not. Several review rounds were spent discovering that
the "redundant" field was the only tripwire (the report once dropped `Sales (Base)` as "the same
number twice" — it was in fact the sole place a components/total mismatch would have shown).

- **`metrics` AND `pl` both exist, on purpose.** `metrics` (tax-INCLUSIVE `totalSales` /
  `returnsTotal`) is the legacy axis other screens read; `pl` is the additive tax-EXCLUSIVE P/L
  new screens read. `metrics.returns` (tax-inclusive) and `pl.returns` / `components.returns`
  (tax-exclusive goods) are DIFFERENT numbers sharing a name — new code reads `pl` ONLY. Do not
  merge them.
- **`netSales` is composed UP, never `totalCharged − tax − shipping`.** Back-subtraction silently
  absorbs any unmodelled income (tips, duties, a restocking fee) into net sales — the original
  bug. The additive form makes that impossible to write.
- **`diagnostics` counts what it could NOT place, instead of returning a silent partial:**
  `lossyOrders` / `lossyRevenue` (orders with no decomposition), `unclassifiedRefundAdjustments`
  (refund money with no P/L home), `unreconciledRefunds` / `refunds.unmigratedRefunds` (not yet
  re-drained — the figure is INCOMPLETE while > 0), `dayTotalDrift` (Σ daily.pl − totals.pl, 0
  unless a cohort / day-boundary disagreement crept in). A zero here means "checked and clean,"
  never "not looked at." Do not delete a diagnostic because it "is always 0."
- **`restocking_fee_income_base` is long ON PURPOSE.** The Shopify `refund_discrepancy` is stored
  POSITIVE (the store received it). Named `restocking_fee` alone, someone reads the persisted prop
  and ADDS it as part of the refund — the exact class of error (income mixed into a refund/return)
  this work existed to fix. The `_income` in the name is load-bearing. The read layer dual-reads
  the pre-rename name during migration so `pl.restockingFees` never silently dips to 0.
- **A (order) / A′ (refund) recon are drainer WARN, not throw.** A hard throw would DROP the fact
  of a broken order/refund — the worst outcome (the broken data vanishes from the report). They
  warn and still write; the report surfaces the residual. Throwing is reserved for CI / self-tests.

### returnsBasis and the P/L

`returnsBasis` (order/refund) affects the legacy `metrics` figure only. **`pl.basis` is always
`order`** (the P/L is a net-sales axis, not a cash view); a `returnsBasis=refund` request is echoed
as `pl.basisRequested` so the mismatch is visible, and the refund-date view is served by the
separate `refunds` block above rather than by bending `pl`.

## Sales-fact aggregation elsewhere

The same fact stores back the other operator-facing sales axes (all read-time,
all facet-backed, nothing precomputed onto the entity mirrors):

- dashboard `salesTrend.topProducts` — line-grain top-N by base gross (`SalesQuery.topProducts`),
- product browse `sort=sales|quantity` (`SalesQuery.salesByProduct` via `Pim.browse`),
- customer browse `sort=spend` / `minSpend` period filter (`SalesQuery.spendByCustomer` via crm.groovy),
- order browse `productId=` drill-down (line facts resolve the order-id set).

## Historical backfill (surfaced in the Commerce Import app)

The orders backfill (`endpoints/backfill.groovy`) is the whole historical sales import: the bulk
export flags refund-bearing orders (`refunds { id }` — Bulk cannot carry the nested refund
detail), the import fetches those orders' refunds via the foreground Admin API and mirrors the
missing ones (`commerce.RefundMirror`), and on completion it chains the all-history fact seed
(`commerce.SalesFactBackfill`), which enqueues every order through the single-writer drainer.
`endpoints/sales-backfill.groovy` (GET only) reports the chained seed's progress; there are no
separate refund/seed triggers.

## Multi-currency

The per-currency breakdown stays native (audit-true). On top, every sales
report carries a **base-currency rollup** from Shopify's own conversion
(`total_price_set.shop_money` / line `price_set.shop_money` — no external FX):
`totals.baseRevenue`/`totals.baseCurrency`, per-day `baseRevenue`, per-product
`baseRevenue`. The sales CSV has the matching `baseCurrency`,`baseRevenue`
columns (one row per day).
