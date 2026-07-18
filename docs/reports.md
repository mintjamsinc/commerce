# Reports & Audit Export

Operator-facing reports over the data the platform keeps in JCR,
as JSON or CSV.

| Report | Source | Contents |
|---|---|---|
| `occurrence` | the index-backed sales facts (`/content/commerce/sales/orders/index`) + the refund store (`/content/commerce/refunds/raw`) + the payment store (`/content/commerce/payments/raw`) | occurrence-date summary: new orders / full cancels / payments / refunds, each counted on its OWN event date — the view the Commerce Reports app shows |
| `operations` | outbound-write audit (`/content/commerce/sync`) | every CMS → Shopify write: when, action, status, error |

## Endpoint

```
GET /bin/cms.cgi/{ws}/content/commerce/endpoints/reports.groovy
      ?type=occurrence&days=30
      ?type=occurrence&from=2026-01-01&to=2026-01-31   # explicit range (inclusive) wins over days
      ?type=operations&days=30[&status=ok|failed|dryrun]
      [&format=json|csv]
```

Lives outside `/content/public`, so authentication + ACLs are enforced by the CGI.

The former `type=sales` report (order-date P/L + refund-date cash-out views, with
population/groupBy/compare params and per-view CSVs) has been RETIRED end to end —
the endpoint, its webtop tabs, the dashboard Sales card and the P/L read layer
(`SalesQuery.salesRange` / `commerce.SalesLadder`) are all gone. The fact WRITE
path is untouched: the drainer still decomposes and reconciles every order and
refund, so the P/L reading can be rebuilt from the same facts if it is ever
needed again.

## Occurrence-date summary (`type=occurrence`)

The report the **Commerce Reports** webtop app shows (its only view). Every
metric is counted on the date its OWN event happened, via
`SalesQuery.occurrenceSummary`:

- **new orders** — count + base amount by `ordered_at`. No population filter:
  every created order counts on its creation day, whatever its
  `financial_status`, even if it is cancelled later (the cancellation is its
  own column on its own day).
- **cancellations** — count by `cancelled_at` (full cancels only — Shopify sets
  `cancelled_at` only on a full cancel).
- **payments** — count + base cash IN by `paid_at`, over the payment store
  (successful `sale`/`capture` transactions: the initial charge AND later
  surcharge captures / bank transfers marked paid, each on the day the money
  moved). `paymentAmount` is reported POSITIVE (cash in).
- **refunds** — count + base cash by `refunded_at` (all refunds, incl.
  partial-cancel reductions). `refundAmount` is reported NEGATIVE (cash out).

`confirmedSales = paymentAmount + refundAmount` — the PAYMENT basis: only money
that actually moved (cash in minus cash out), so unpaid orders, pre-payment
cancellations and partial payments all reconcile with no special-casing.
`newOrderAmount` stays a column of its own (order intake); receivables =
`newOrderAmount − paymentAmount` is composable by the reader. The payment store
must be backfilled (re-run the orders backfill) for historical windows to read
true. All money is base-currency
(`baseCurrency` rides the response). A closed month never changes, because each
event's date is fixed. Params: the shared window (`from`/`to` instants win over
the rolling `days`), `tz` (IANA id — see below) and `format=json|csv` only — no
population params.

The day rows are formed **at query time**: each pass declares one `range()`
facet bucket per local day of the `tz` parameter (sent by the client from the
user's Preferences timezone; UTC when absent — never the server default)
directly on the absolute Date props. No baked day-string props exist, so the
same data viewed from another timezone regroups on that viewer's day
boundaries, and the report is fully server-timezone independent. Windows longer
than `SalesQuery.MAX_DAY_BUCKETS` days truncate and flag `truncated: true`.

The dashboard's sales-trend hero reads the SAME aggregation (confirmed sales /
new-order count / AOV = newOrderAmount ÷ newOrderCount), so the dashboard
headline and the report can never disagree.

## The sales-fact store: index-backed facet aggregation (the ONLY sales read path)

Every sales read goes through `commerce.SalesQuery` over the index-backed sales
facts (`/content/commerce/sales/{orders,lines}/index`, written by the
`commerce.SalesFacts` drainer): the full component breakdown (gross / discounts /
tax / shipping / tips / duties / returns / returns_tax / returns_shipping) with
`net`/`total` synthesized at read time (operator sovereignty — the system stores
no pre-selected "sales" number). Aggregation is server-side `facet accumulate`
(cms0) — one pass, exact, no 5000-row cap. The pre-fact per-order folder walk
and its `salesSource` switch have been REMOVED: there is one source of truth.

Boolean (and date) aggregates carry their type IN the query with the same `xs:`
cast syntax the order-by clause takes: `sum(xs:boolean(@commerce:components_complete))`
(`SalesQuery.sumBoolExpr`). The search index stores boolean/date doc values raw
(0/1 / epoch ms) while numbers are sortable-double encoded; without the cast the
facet aggregator decodes with the numeric default and a boolean sum collapses to
~0 (which mislabelled every order "not decomposable"). The result dimension stays
`sum(commerce:components_complete)` — a cast never changes addressing. Numbers
need no cast; a new boolean/date aggregate must use one.

Where the fact store is read today (all read-time, all facet-backed, nothing
precomputed onto the entity mirrors):

- reports `type=occurrence` + the dashboard sales-trend hero
  (`SalesQuery.occurrenceSummary`),
- dashboard `salesTrend.topProducts` — line-grain top-N by base gross
  (`SalesQuery.topProducts`),
- product browse `sort=sales|quantity` (`SalesQuery.salesByProduct` via `Pim.browse`),
- customer browse `sort=spend` / `minSpend` period filter
  (`SalesQuery.spendByCustomer` via crm.groovy),
- order browse `productId=` drill-down (line facts resolve the order-id set).

## Design rationale — the load-bearing pieces (do NOT "simplify" these away)

These notes protect the fact WRITE path (`commerce.Sales` / `commerce.Refunds` /
`commerce.SalesFacts` / `commerce.SalesReconcile`), which stays in service behind
every reader above.

- **`restocking_fee_income_base` is long ON PURPOSE.** The Shopify `refund_discrepancy` is stored
  POSITIVE (the store received it). Named `restocking_fee` alone, someone reads the persisted prop
  and ADDS it as part of the refund — the exact class of error (income mixed into a refund/return)
  this work existed to fix. The `_income` in the name is load-bearing. The occurrence report
  subtracts it from the returned value so `refundAmount` is the actual CASH refunded.
- **A (order) / A′ (refund) recon are drainer WARN, not throw.** A hard throw would DROP the fact
  of a broken order/refund — the worst outcome (the broken data vanishes from the report). They
  warn and still write.
- **Money-decomposition components are OMITTED on `components_complete=false` facts.** A lossy
  historical order contributes to the order count and `total_price` but NOTHING to gross/net —
  "not decomposable", never a silent fake zero.

## Historical backfill (surfaced in the Commerce Import app)

The orders backfill (`endpoints/backfill.groovy`) is the whole historical sales import: the bulk
export flags refund-bearing orders (`refunds { id }` — Bulk cannot carry the nested refund
detail), the import fetches those orders' refunds via the foreground Admin API and mirrors the
missing ones (`commerce.RefundMirror`), and on completion it chains the all-history fact seed
(`commerce.SalesFactBackfill`), which enqueues every order through the single-writer drainer.
The bulk filter always carries an explicit `(status:open OR status:closed OR status:cancelled)`
clause: Shopify's order search does not match cancelled orders by default, and without it a
cancelled order vanishes from the export — its mirror never learns `cancelled_at` and its refunds
are never flagged for the detail phase, so the report misses both the cancellation and the refund
(`status:any` is REST-only vocabulary, hence the spelled-out OR).
`endpoints/sales-backfill.groovy` (GET only) reports the chained seed's progress; there are no
separate refund/seed triggers.

**60-day order access (deployment-time decision).** Without the `read_all_orders` access scope,
the Shopify Admin API silently limits every order read — the bulk export included — to orders
created in the last 60 days, so a full-range backfill only ever returns a 60-day rolling window
and its reported order count shrinks as the oldest orders age out. This does NOT lose data
already ingested: the backfill never deletes mirror entries, the chained fact seed walks the
whole mirror (not the export), and day-to-day ingestion is webhook-driven (webhooks are
event-driven and unaffected by the historical read limit). What the missing scope does limit is
recovery and initial import: a missed webhook on an order older than 60 days cannot be repaired
by a backfill, a rebuilt environment can only re-import the last 60 days from Shopify, and an
app installed on a store with pre-existing history older than 60 days cannot import that history.
Decide at deployment whether the store needs pre-60-day order history (request `read_all_orders`
access for the app in the Shopify Dev Dashboard before the initial backfill) or the 60-day window
suffices (webhooks capture everything from install time onward). Any future prune/reconcile
feature that compares the mirror against a bulk export MUST require `read_all_orders` first —
under the 60-day window it would mistake old, perfectly valid orders for deleted ones.

## Multi-currency

The occurrence report (and the dashboard trend it feeds) is **base-currency
only**, from Shopify's own conversion (`total_price_set.shop_money` / line
`price_set.shop_money` — no external FX). The native per-currency amounts stay
audit-true on the stored facts and mirrors (nothing converts or discards them);
no current screen sums them, but any future per-currency reader aggregates the
same facts.
