# Commerce Shared Classes (`commerce.*`)

This is the reference for the shared Groovy classes that back the commerce
integration scripts. It exists so that common logic lives in exactly one place,
and so that anyone adding or changing a script knows where helpers belong and how
to call them.

## Why these exist

The commerce scripts (BPMN service tasks / listeners and Camel `cms:` route
steps under `etc/commerce/scripts/shopify/`) had grown copies of the same
helpers — number formatting, refund maths, order lookup, Slack/Discord delivery,
Shopify token + GraphQL, inventory-threshold parsing, and `commerce:status`
writes. Each copy was a place for the logic to drift. The shared classes collapse
each of those into one implementation; every script now keeps only its own
business logic (rule maps, message templates, element-to-status mappings).

## Where they live and how scripts use them

| Aspect | Convention |
|---|---|
| Location | `/content/WEB-INF/classes/` — the per-workspace classpath root the CMS deploys and exposes to the Groovy script engine. In the repo: `content/WEB-INF/classes/`. |
| Package root | `commerce`. A class file is `content/WEB-INF/classes/commerce/<Name>.groovy` and declares `package commerce`. |
| Use from a script | `import commerce.Money` (etc.), then call the static methods: `Money.format(total)`. |
| Form | `.groovy` **source** — no compilation/`.jar` step. The workspace classloader compiles it on deploy. (Precedent: `api.util.JSON` / `api.util.YAML`.) |

> **Deployment note.** A changed class is picked up when the workspace deploys
> it; if a change does not seem to take effect, reload the workspace classloader
> (redeploy / restart).

## Design rules

These keep the classes safe to call from any context (service task, task/execution
listener, Camel route):

1. **All methods are `static`; classes hold no state.**
2. **No script bindings inside a class.** `repositorySession`, `log`, `context`,
   `task`, `execution`, `JSON`, `YAML` are bound only in *script* scope, not in
   class scope. Pass whatever a method needs as a parameter (e.g. `session`,
   `log`, `config`, an already-parsed map).
3. **JSON inside a class uses jackson `ObjectMapper`** (the same library
   `api.util.JSON` uses, so behaviour matches), or the method takes data already
   parsed by the caller's `JSON`/`YAML` binding. A class never assumes a JSON
   binding is present.
4. **Error policy is explicit.** A helper is either *pure* — it does the work and
   lets the caller own error handling (so different callers can keep different
   policies) — or *defensive* — it catches, logs and swallows, which is then
   documented on the method. This mirrors what each original script did.

## The classes

### `commerce.Api`
**The wire mapper** — the single normalization gate every endpoint passes its JSON
through on the way out (and applies to ids on the way in). Pure, null-tolerant.
The contract it enforces: money/quantities as
JSON numbers with money as `{currency, amount}` objects, ids as Shopify GIDs
(numeric never leaves the layer; `legacyId` peels server-side only), timestamps
as millisecond-precision UTC ISO-8601 (`Api.now()` — never
`Instant.now().toString()`, whose fraction digits drift), keys camelCase.

| Method | Purpose |
|---|---|
| `String now()` / `String instant(v)` / `Long epochMs(v)` / `Date date(v)` | The wire timestamp format (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`) — or a `java.util.Date` for typed JCR Date properties — from any Calendar/Date/Number/ISO input. |
| `Number num(v[, dflt])` / `Long count(v)` | Clean JSON numbers (strips `.0` — zero-decimal-currency safe). |
| `Map money(currency, amount)` / `List moneyList(map)` | The wire money shapes. |
| `String gid(type, id)` / `String legacyId(id)` | GID canonicalization (idempotent, token passthrough) / numeric peel (INTERNAL ONLY). The only place `gid://` strings are built. |
| `String gidType(gid)` / `String gidTypeFor(collection)` | GID type extraction / `"inventory_levels"` → `"InventoryLevel"`. |
| `String camel(key)` / `camelValue(v)` / `Object camelize(tree)` | camelCase keys (deep variant for storage-shaped rows; never applied to raw Shopify mirror bodies). |

### `commerce.Money`
Numeric / money helpers. Pure.

| Method | Purpose |
|---|---|
| `BigDecimal toNumber(Object value)` | Parse String/Number/null to BigDecimal; null if absent/unparseable. |
| `String format(Number n)` | Thousands separators; whole numbers drop decimals (`100000` → `100,000`), fractions keep two places. `""` for null. |

### `commerce.ReviewReasons`
Structured "review reason" message descriptors shared by the order/refund
screening workflow. Pure.

A reason is emitted as a descriptor — a stable `code` plus raw `params`
(numbers, currency code, status) — *not* pre-rendered text:

```json
{ "code": "highValue", "params": { "total": 133000, "currency": "JPY", "threshold": 100000 } }
```

This lets each consumer render it in its own context: the review forms map the
`code` to an i18n key and format money/numbers in the reviewer's locale, while
the notifications render operational English server-side via `render`. It is
the intended contract for any future server-produced, user-facing message
(e.g. form validation errors), so codes/params live in exactly one place.

| Method | Purpose |
|---|---|
| `Map highValue / flaggedFinancialStatus / largeQuantity / newCustomer / addressMismatch(...)` | Build an order-screening descriptor `[code, params]` (used by `screenOrder`). |
| `Map highRefundValue / fullRefund / noRestock(...)` | Build a refund-screening descriptor (used by `screenRefund`). |
| `Map descriptor(String code, Map params)` | Generic descriptor builder. |
| `List<String> renderAll(List reasons)` | Render descriptors to operational English, dropping empties; passes legacy plain-string reasons through. |
| `String render(Object reason)` | Render one descriptor (or legacy string) to English. |

> The forms do **not** call `render`; they localize each descriptor via their
> i18n bundle. `render` exists for the notifications, which have no per-user
> locale.

### `commerce.Refunds`
Interpret a parsed Shopify refund payload. Pure.

| Method | Purpose |
|---|---|
| `BigDecimal amount(refund)` | Sum of successful refund transactions; null if none. |
| `String currency(refund)` | Upper-cased transaction currency, or null. |
| `boolean isRestocked(lineItem)` | False when `restock_type` is `no_restock`/`none`/absent. |

### `commerce.Orders`
Locate and read the original order. `findResource` needs the session.

| Method | Purpose |
|---|---|
| `Object findResource(session, orderId)` | Find `order_{id}.json` under `/content/commerce/orders/raw`; null if blank id / not found. |
| `BigDecimal totalPrice(order)` | `total_price` of an already-parsed order map, or null. |

### `commerce.Notifications`
Pluggable, multi-channel notification dispatch. A caller builds **one**
channel-agnostic `NotificationMessage` and hands it here with the parsed
`/etc/commerce/config/notifications.yml`. Dispatch walks the channel registry and,
for every section that is present and enabled, lets the matching channel render +
deliver. Adding a channel is additive (subclass `NotificationChannel`, add it to
`registry()`) — the dispatch signature and every caller stay unchanged.
Defensive: each channel runs in its own try/catch and failures are only logged.

| Method | Purpose |
|---|---|
| `List<NotificationChannel> registry()` | The known channels (Slack, Discord, Teams, LINE, webhook, email), fresh per call. |
| `boolean isEnabled(channel)` | A channel section is on unless `enabled: false`. |
| `String taskVar(task, String name)` | Safely read a DelegateTask variable as String, or null. |
| `void dispatch(log, String source, config, NotificationMessage message)` | Render + deliver to each enabled/configured channel. `source` is the calling script name (log prefix). |

### `commerce.NotificationMessage`
Channel-agnostic message model + builder. Separates **what** a workflow says from
**how** each channel renders it (Slack mrkdwn `*`, Discord/Teams markdown `**`,
plain text for email/LINE, structured JSON for the generic webhook). Builder
methods are null/empty tolerant so optional content chains without guards.

| Method | Purpose |
|---|---|
| `static NotificationMessage create()` | Start a new message. |
| `title(icon, text)` / `status(icon, text)` | Leading workflow line / status headline. |
| `field(label, value)` | A "Label: value" line (skipped when value is blank). |
| `heading(text)` | A sub-heading grouping the fields that follow. |
| `bullets(heading, items)` / `lines(heading, items)` | A bold heading + a bullet/plain list (skipped when empty). |
| `text(text)` / `footer(taskName, assignee)` | Free-form paragraph / task+assignee footer. |
| `String render(bold, bullet, icons)` | Render for a markup channel. |
| `String plainText()` / `String summary()` | Plain rendering / one-line "title — status". |
| `List<Map> fields()` / `String titleText()` / `String statusText()` | Structured accessors for the webhook channel. |

### `commerce.NotificationChannel` and adapters
`NotificationChannel` is the abstract base: a channel declares its config key
(`type()`) and how to deliver (`send(log, source, channelConfig, message)`), and
inherits a shared, defensive `postJson` helper. Adapters:
`SlackChannel`, `DiscordChannel`, `TeamsChannel`, `LineChannel`, `WebhookChannel`,
`EmailChannel`. Email uses `commerce.SmtpClient`, a JDK-socket-only SMTP client
(`none` / `starttls` / `ssl`, AUTH PLAIN/LOGIN, UTF-8 subject + base64 body) so no
extra mail library is required. See `docs/notification-channels.md` for the config.

### `commerce.ShopifyWrite`
Outbound CMS → Shopify writes, built on {@link
commerce.ShopifyAdmin}. Each method builds an Admin GraphQL mutation and raises on a
transport error or a Shopify `userErrors` entry (not defensive — the sync endpoint
reports the outcome), mirroring `recordFulfillment`'s write-back policy. Ids may be
raw numeric ids or gids. See [bidirectional-sync.md](bidirectional-sync.md).

| Method | Purpose |
|---|---|
| `Map setInventory(client, endpoint, token, inventoryItemId, locationId, quantity, reason)` | Set available stock at a location (`inventorySetQuantities`). |
| `Map updatePrice(client, endpoint, token, productId, variantId, price)` | Set a variant price (`productVariantsBulkUpdate`). |
| `Map setPublished(client, endpoint, token, productId, published)` | Publish/unpublish a product (`productUpdate` status). |
| `Map setMetafields(client, endpoint, token, ownerId, metafields)` | Upsert product metafields (`metafieldsSet`) — the PIM push. |
| `Map updateCustomer(client, endpoint, token, customerId, fields)` | Update a customer (`customerUpdate` tags/note/taxExempt + `customerEmailMarketingConsentUpdate` marketing consent); enum values uppercased. Backs the customer editor's save via `endpoints/sync.groovy`. |
| `Map updateProduct(client, endpoint, token, productId, fields)` | Update a product's base fields (`productUpdate`): title / descriptionHtml / vendor / productType / tags (normalized) / handle / status (uppercased ACTIVE\|DRAFT\|ARCHIVED). Only present keys are written; skips the Shopify call when nothing changed. Backs the product editor's Overview save (product 360). |
| `Map addProductMedia(client, endpoint, token, productId, originalSource, alt)` | Add one image to a product by URL (`productCreateMedia`, `mediaContentType: IMAGE`). Add-by-URL only — no staged/local upload in v1. **Async** on Shopify's side (product 360 Media). |
| `Map deleteProductMedia(client, endpoint, token, productId, mediaIds)` | Delete media from a product (`productDeleteMedia`); `mediaIds` are MediaImage ids. Returns the deleted ids. |
| `Map reorderProductMedia(client, endpoint, token, productId, orderedMediaIds)` | Reorder a product's media to the given order (`productReorderMedia`, each id → its list index). **Async** — returns a job id; the mirror catches up via `products/update`. |
| `Map updateProductMediaAlt(client, endpoint, token, mediaId, alt)` | Edit a media's alt text via `fileUpdate` (the durable path at 2026-01; `productUpdateMedia` is deprecated). |
| `String gid(type, id)` | Normalize a numeric id to a Shopify gid. |

### `commerce.Pim`
Product Information Management: a CMS-authoritative overlay of extended
attributes (multi-language, rich descriptions, custom attributes, metafields) stored
as the `pim` property on the product node, so it is versioned / searchable /
ACL-governed with the product. Reads defensive; `write` raises. See [pim.md](pim.md).

| Method | Purpose |
|---|---|
| `Map read(session, productId)` / `Object productResource(session, productId)` | The overlay / the product node. |
| `Map write(session, log, productId, overlay, merge, editor)` | Deep-merge or replace the overlay; stamps updatedAt/By. |
| `Map view(session, productId)` | Unified Shopify base + metafields mirror + overlay. |
| `List search(session, query, limit)` | Full-text product search (`jcr:contains`). |
| `Map browse(session, opts)` | Faceted browse: filters over `commerce:*` props + facet counts (the product browser). `sort=updated\|sales\|quantity` (+ `salesFrom`/`salesTo` epoch-ms) ranks by the line-grain sales facts (`SalesQuery.salesByProduct`); ranked rows carry a `sales` object. |
| `Map titles(session, productIds)` | productId → `commerce:title` from the product mirror (labels for sales-fact aggregations). |
| `List metafieldsToPush(overlay)` | The overlay's CMS-authored metafields for Shopify (pure). |

### `commerce.Reconciliation`
Shopify → CMS drift detection + refresh (status / price; inventory is the Bulk
audit's job). Pure `diffProduct`; defensive `applyRefresh` (Shopify→CMS mirror
patch). CMS→Shopify writes are done by the caller via `ShopifyWrite`. See
[reconciliation.md](reconciliation.md).

| Method | Purpose |
|---|---|
| `List diffProduct(cmsProduct, shopifyProduct)` | Field-level diffs (status/price) with refresh direction. |
| `boolean applyRefresh(session, log, productResource, diff)` | Refresh the CMS mirror (status/price) from Shopify. |
| `def writeRunReport(session, report)` | The single run-report writer for BOTH scopes (diff / inventory): body JSON + the typed queryable row properties. Does not commit. |
| `void recordBulkAudit(session, log, job)` | Bulk-broker terminal hook: records an inventory-audit job as an "inventory" run report (no-op for other job types; exactly-once via the broker's absorbing states). |
| `List listRuns(session, opts)` | Run-history rows, newest-started first, via one index-backed XPath query over the typed report props (`scope` / `result` / `fromIso` / `limit`); shared by the reconcile endpoint and the dashboard. |
| `String numericId(gid)` | Numeric id from a Shopify gid. |

### `commerce.Reports`
Operations / audit export. Pure, defensive, index-backed. Sales reporting does
NOT live here — every sales read is `commerce.SalesQuery` over the sales facts (the
pre-fact folder walk has been removed). See [reports.md](reports.md).

| Method | Purpose |
|---|---|
| `List operations(session, actor, fromIso, toIso, statusFilter, limit)` | Outbound-write audit trail (`/content/commerce/sync`), newest first, XPath-filtered. |

### `commerce.ShopifyAdmin`
Shopify Admin API: enablement, token (Client Credentials Grant + JCR cache),
GraphQL, and **Bulk Operations** (start / status / cancel — the bulk job broker).
JSON via jackson. Token caching is best-effort — a valid token is still
returned even if it could not be persisted.

| Method | Purpose |
|---|---|
| `boolean adminApiEnabled(config)` | True when the Admin API is configured (all four `adminApi` connection fields filled) in parsed `shopify.yml`. The Admin API is required; there is no enable toggle. |
| `String endpoint(adminApi)` | GraphQL endpoint URL; throws if `shopDomain`/`apiVersion` missing. |
| `String accessToken(session, log, adminApi)` | Reuse cached token while fresh, else fetch + cache. |
| `Object graphql(client, endpoint, accessToken, payload)` | POST GraphQL; throws on non-200 or a top-level `errors` array. `payload` may be a Map or a JSON String. |
| `String startBulk(client, endpoint, accessToken, bulkQuery)` | Start a Bulk Operation query (`bulkOperationRunQuery`); returns its operation gid. |
| `boolean currentBulkRunning(client, endpoint, accessToken)` | True while the shop's current bulk query is CREATED/RUNNING — the producer lane's belt-and-suspenders singleton guard. |
| `Map bulkByGid(client, endpoint, accessToken, gid)` | Status + downloadable result URL for a bulk (2026-01 `bulkOperation(id:)`, falls back to `currentBulkOperation`); null if not this job's. |
| `Map cancelBulk(client, endpoint, accessToken, gid)` | Best-effort `bulkOperationCancel` — the watchdog's RUNNING hard cap; frees the app's single bulk slot. Never throws (returns `[error]` on failure). |

### `commerce.BulkJobs`
Durable JCR queue + guarded state machine behind the **Shopify Bulk job broker**, with
**domain-based** serialization. Each job is a doc under
`/content/commerce/jobs/shopify/{jobId}.json` carrying the data **domains** it touches
(`targetDomains`, e.g. `["inventory"]`), so bulk work is serialized *per domain* across two
independent lanes — a Shopify **producer** singleton and a domain-parallel CMS **consumer** — rather
than through one global lane. Defensive throughout: a bookkeeping failure never breaks a route. See
[reconciliation.md](reconciliation.md) and [clustering.md](clustering.md).

State machine: `QUEUED → RUNNING` (Shopify bulk running) `→ READY` (Shopify COMPLETED, awaiting a CMS
ingest slot) `→ PROCESSING` (CMS downloading/reconciling) `→ COMPLETED | FAILED | CANCELED |
TIMED_OUT`; `isActive = QUEUED || RUNNING || READY || PROCESSING`. **Every transition is a guarded
compare-and-set** (`patchIf`): it writes only from the expected source state and returns a `boolean`
(true iff applied), so a duplicate at-least-once `bulk_operations/finish` webhook or a watchdog race
can't resurrect a job into a double reconcile — the four terminal states are **absorbing**. A job
whose `targetDomains` is missing/empty is a **wildcard** that conservatively overlaps every domain.

| Method | Purpose |
|---|---|
| `String create(session, log, type, targetDomains)` / `create(session, log, type)` | Create a QUEUED job carrying its domains (2-arg form = wildcard `[]`). Callers enforce idempotency via `hasActive`. |
| `List<Map> list(session)` | All job docs. |
| `boolean hasActive(session, type)` | Any QUEUED/RUNNING/READY/PROCESSING job of this type (enqueue idempotency guard). |
| `boolean hasRunning(session)` / `laneBusy(session)` | Any job RUNNING (Shopify singleton busy) / RUNNING-or-PROCESSING. |
| `Map nextQueued(session)` | Oldest QUEUED job (FIFO by jobId). |
| `List<Map> running / ready / processing(session)` | Jobs in that status (watchdog + CMS consumer lane). |
| `Map findByGid(session, gid)` | Job for a Shopify bulk-operation gid (finish-webhook correlation). |
| `Set<String> domainsOf(Map job)` | A job's domains; **empty Set = wildcard**. |
| `Set<String> domainsInStatuses(session, statuses, excludeJobId = null)` | Union of domains over jobs in those statuses; collapses to the `ALL_DOMAINS` (`"*"`) sentinel if a wildcard job is present. |
| `boolean overlaps(Set a, Set b)` | Does a candidate's domains conflict with an active-domain set (conservative = serialize)? |
| `boolean markRunning / markReady / markProcessing / markCompleted(...)` | Guarded forward transitions; each true iff applied. |
| `boolean markFailed / markTimedOut / markCanceled(...)` | Guarded any-active → terminal transitions (release the job's domains). |
| `int incrementReconcileAttempts(session, log, jobId)` | Bump + return the bounded reconcile-retry counter (not status-guarded). |

### `commerce.BulkQueries`
The Shopify Bulk Operation query strings plus the **single-source `TYPES` registry** that pairs each
bulk job type with both its query **and** the data domains it touches, so `forType` and
`domainsForType` cannot drift apart. Adding a future backfill type (products/orders/customers) is one
row. Pure. Used with `BulkJobs` by the broker.

| Method / field | Purpose |
|---|---|
| `static final String INVENTORY_FULL` | Full inventory snapshot query — every inventory item → per-location `available`; un-paginated, streamed as JSONL. |
| `static final Map TYPES` | Single source of truth: `type → [ query, domains ]` (today `"inventory-full" → [INVENTORY_FULL, ["inventory"]]`). |
| `String forType(String type)` | The bulk query for a type; throws for an unknown type. |
| `List<String> domainsForType(String type)` | The domains a type touches; `[]` (wildcard) for an unknown/mis-typed type. |

### `commerce.Health`
Integration health monitor: records operational metrics to JCR and raises alerts
(through {@link commerce.Notifications}) when a threshold in `health.yml` is
breached. Best-effort — a recording failure is swallowed, never thrown. See
[health-monitor.md](health-monitor.md).

| Method | Purpose |
|---|---|
| `void count(session, log, group, metric, by = 1)` | Increment a counter (e.g. `webhook`.`hmac_failure`) and evaluate its alert rule. |
| `void outcome(session, log, group, name, ok, latencyMs = null)` | Record success/error (+ latency) for a bucket (e.g. `route`.`orders/paid`) and evaluate. |
| `Object timeApi(session, log, label, Closure call)` | Time a call, record its `api` outcome+latency, return its result; the original exception propagates. Call only when `session` has no uncommitted business changes. |
| `Map snapshot(session, days = 7)` | Aggregate the last N days into a snapshot with per-bucket `error_rate` / `latency_avg`. |

### `commerce.Dashboard`
Read-only sales and inventory aggregations for the Commerce dashboard. Sales money
figures come from the sales facts (`commerce.SalesQuery`, uncapped); the lifecycle
byStatus breakdown is a facet COUNT over the raw order store's typed props (no folder
walk). Defensive — a read error is skipped, not thrown. See
[commerce-dashboard.md](commerce-dashboard.md).

| Method | Purpose |
|---|---|
| `Map inventorySummary(session)` | Total products + breakdown by `commerce:status` (+ a low-stock count = products in `review_pending`). |
| `Map salesSummary(session, days = 30)` | Orders / native revenue / base rollup / metrics over the last N days by `ordered_at` (SalesQuery.salesRange) + the lifecycle byStatus facet count. |

### Sales facts (`commerce.Sales` / `SalesFacts` / `SalesQuery` / `SalesFactBackfill` / `RefundMirror`)

The sales re-implementation. Raw components are stored as
typed, index-backed facts and every metric is composed at READ TIME by server-side `facet accumulate` — no
pre-selected "sales" number (operator sovereignty). A single cluster-guarded drainer is the only fact
writer; all other paths (including the backfill chain) only ENQUEUE. See `docs/commerce-properties.md`
(Sales facts) and `docs/clustering.md` (the guarded tasks).

**`commerce.Sales`** — PURE component computer (no JCR/bindings; never mutates the body). `compute(order,
refundBodies)` → order-grain + line-grain native+base components + `components_complete` + `recon_delta`.
`foldReturns` / `hasOrderDecomposition` / `nativeAmt` / `baseOrNative` are the shared component helpers.

**`commerce.SalesFacts`** — the pending queue + the SINGLE cluster-guarded writer (modeled on the
inventory-total materialize). Defensive JCR; `pickBody` is pure.

| Method | Purpose |
|---|---|
| `void markPending / boolean writePending(session, log, orderId)` | Enqueue an order (commit / stage-only). |
| `List pendingOrderIds(session)` / `void clearPending(...)` | Queue read / delete-before-evaluate. |
| `void recompute(session, log, orderId)` | THE fact writer: resolve body (`pickBody` prefers complete), fold refunds, `Sales.compute`, upsert + prune both grains, never downgrade a complete fact. Drainer-only. |
| `Map effectiveProps(props, complete)` | Strip the money decomposition when incomplete (keep total_price + dims). |

**`commerce.SalesQuery`** — the index-backed READER: composes metrics from the facts via
`facet accumulate`, delegating the SUM to the platform (single pass, no 5000 cap). Defensive (degrades to
zeros). The dimension-text helpers (`sumDim`/`statsDim`/`pctDim`…) are the one place the facet dimension
strings are formed (prop name == dimension text).

| Method | Purpose |
|---|---|
| `Map config(session)` / `Map defaults(cfg)` / `Map resolveOpts(cfg, overrides)` | sales.yml — the population/returns-basis defaults, merged with per-request overrides. |
| `Map salesRange(session, fromMs, toMs, opts)` | Full report: component sums (base) + native by currency + net/total synthesis + daily timeseries + stats/percentiles. `daily:false` opt skips the timeseries. |
| `List topProducts(...)` / `List byCustomer(...)` | Top-N by base gross (real product_id) / by base total (customer_id). |
| `Map salesByProduct(session, fromMs, toMs, opts)` | product_id → { quantity, gross, discounts, returns, net } in ONE grouped pass (line grain) — backs the product browse's sales sorts. |
| `Map spendByCustomer(session, fromMs, toMs, opts)` | customer_id → { orders, totalPrice, gross, discounts, returns, net } in ONE grouped pass (order grain) — backs the CRM spend sort + min-spend period filter. |
| `Map pop(session, fromMs, toMs, opts)` | Period-over-period (current vs preceding equal window). |
| `String populationPredicate(opts)` | financialStatus / includeCancelled → XPath predicate. |
| `String baseCurrencyOf(session, fromMs, toMs, opts)` | The window's base currency (first order fact; best-effort). |

**`commerce.SalesFactBackfill`** — resumable, enqueue-only history seed. `seed(session,
log)` walks the whole order mirror, dedups by order id, `SalesFacts.writePending` in 300-marker batches;
`progress(session)` backs the GET endpoint. NEVER writes a fact node. Run via
`seedSalesFactBackfill.groovy` (cluster-guarded), CHAINED off a completed orders backfill
(`importBulkResult.groovy` kicks it); the `sales-backfill.groovy` admin endpoint is GET-only progress.

**`commerce.RefundMirror`** — the historical refund MIRROR writer used by the orders backfill import
(`importBulkResult.groovy`). Two-step fetch: the orders bulk export only FLAGS refund-bearing orders
(`refunds { id }` — Bulk rejects a connection field inside a list field), so the detail is fetched per
candidate order via the foreground Admin API (`refundsQuery`/`fetchRefundNodes`), and only when a flagged
refund id is not mirrored yet. `toRestRefund` (PURE) maps a GraphQL refund node to the REST refund body;
missing refunds are mirrored into the refund raw store with the SAME typed props as the webhook path (via
`commerce.Refunds`). Already-mirrored refunds are skipped (a webhook-delivered refund's lifecycle is never
reset). No review-flow / no order-summary mutation / idempotent.

| Method | Purpose |
|---|---|
| `String refundsQuery(orderId)` / `List fetchRefundNodes(client, endpoint, token, orderId)` | Per-order foreground refunds fetch (live-verified field shapes). |
| `Map toRestRefund(gqlRefundNode, orderId)` | PURE GraphQL→REST refund body mapper (the reviewable/testable piece). |
| `Object findRefundResource(session, refundId)` | Name-based existence query across the month-nested refund store. |
| `void storeRefund(session, rest, existing)` | Stage one refund body + the webhook-parity typed props (caller commits). |

### `commerce.TaskSla`
Task SLA evaluation: decides when an open human task has breached a rule
(`overdue` / `unclaimed` / `open`, per `sla.yml`) and should be escalated. Pure
logic over plain task data (no Camunda dependency); the scanner script gathers
live tasks and applies engine-side actions. Escalations are debounced per
task+rule via {@link commerce.Alerts}. See [task-sla.md](task-sla.md).

| Method | Purpose |
|---|---|
| `List<Map> evaluate(session, log, cfg, tasks, nowMs)` | Evaluate tasks, fire escalation alerts for breaches (respecting cooldown), return the fired list. |
| `String status(cfg, task, nowMs)` | Read-only breached-rule name (or null) for a task — used by the tasks endpoint. |
| `void prune(session, log, openTaskIds)` | Drop cooldown state for tasks no longer open. |

### `commerce.Alerts`
Shared alert dispatch with per-key cooldown, used by the operational monitors
(health, task SLA). Centralises the debounce and delivery through
{@link commerce.Notifications}; the cooldown is armed before sending so a delivery
failure cannot cause a storm. Defensive — never throws.

| Method | Purpose |
|---|---|
| `boolean fire(session, log, statePath, key, cooldownMs, message)` | Send an alert for `key` unless it fired within `cooldownMs`; returns whether it was sent. |
| `void pruneState(session, log, statePath, Closure keep)` | Drop cooldown entries whose key fails `keep(key)`. |

### `commerce.Jcr`
Small JCR helpers for reading/writing JSON documents under /content (getOrCreate
file with mkdir-p, parse/serialize), shared by the operational features.

| Method | Purpose |
|---|---|
| `getOrCreateFile(session, path)` | Resolve/create a file and its parent folders. |
| `Map readMap(session, path)` / `safeGet(session, path)` | Read+parse a JSON doc to a Map (empty if absent) / resolve a resource or null. |
| `String toJson(value)` / `Map parseMap(json)` | Serialize / parse JSON. |

### `commerce.SimpleYaml`
Dependency-free reader for the controlled two-level config files (top-level
scalars + one nested level), the server-side counterpart of the Webtop app's
`parseSimpleYaml`. Lets classes under WEB-INF/classes read config without the
script `YAML` binding. Coercion: true/false → Boolean, integers → Long, decimals
→ Double, quoted strings unquoted.

| Method | Purpose |
|---|---|
| `Map parse(String text)` | Parse YAML text into a nested Map (empty map for null/blank). |

### `commerce.Inventory`
Read per-variant alert thresholds from a product's `inventory_level_config`
property. *Pure* — a malformed config throws, so each caller keeps its own policy
(`sweepInventoryAlerts` isolates it per item in its sweep loop; `checkThresholdConfig` /
`notifyTaskCreated` catch and treat as unconfigured).

| Method | Purpose |
|---|---|
| `Map thresholdsByVariantId(resource)` | `{ variantId(String): threshold(int) }`; empty map if the property is absent. |
| `boolean hasThresholdConfig(resource)` | True if at least one variant has a usable threshold. |

### `commerce.Planning`
Planning layer (replaces the retired `InventoryRules` rule engine): resolves the
single per-variant reorder **`threshold`** (a plain unit count, the fixed reorder
point) as
`per-variant (pim.planning) → legacy manual override (inventory_level_config) →
planning.yml default (defaults.threshold) → none`. It is a pure lookup — the system
never derives or rewrites the threshold; an operator sets any per-variant value
explicitly. See [planning.md](planning.md).

| Method | Purpose |
|---|---|
| `Map config(session)` / `Integer defaultThreshold(cfg)` | Parsed planning.yml / the global default `threshold`. |
| `Map resolve(resource, variantId, cfg)` | Threshold → `[value, source]` (variant/manual/default/none). |
| `Integer value(resolved)` / `boolean hasEffectiveThreshold(resource, variantIds, cfg)` | Convenience accessor / onboarding gate. |
| `Map setValues(session, log, productId, byVariant, editor)` | Merge operator-set thresholds into `pim.planning` (operator action only). |

### `commerce.Locations`
Multi-location inventory access: per-location stock levels (from the
`inventory_levels/update` webhook) and location metadata. Defensive JSON reads.
See [multi-location.md](multi-location.md).

| Method | Purpose |
|---|---|
| `Map levels(session, inventoryItemId)` | locationId → available. |
| `int aggregate(session, inventoryItemId)` | total across locations. |
| `String locationName(session, locationId)` | name, or the id when no metadata. |
| `List breakdown(session, productJson)` | per-variant, per-location breakdown. |

### `commerce.Allocation`
Cross-location allocation planning (advisory). Pure logic.

| Method | Purpose |
|---|---|
| `Map plan(availableByLocation, qtyNeeded, cfg)` | Plan (`allocations`/`allocated`/`shortfall`) by strategy (`most_stock`/`priority`), holding back safety stock. |

### `commerce.Backorders`
Backorder / pre-order management. The pure `detect` decides which order lines
need a backorder (shortfall against tracked stock, or a pre-order tag) from maps the
caller resolves; the JCR methods (create / find / mark / summary) are *defensive*
so a bookkeeping failure never breaks the webhook route or the release workflow. See
[backorders.md](backorders.md).

| Method | Purpose |
|---|---|
| `List<Map> detect(order, variantToItem, availableByItem, preorderItemIds)` | Pure: backordered lines (reason `shortfall`/`preorder`, awaited qty). |
| `boolean create(session, log, descriptor)` | Idempotent persist of a `backordered` record (per order+line). |
| `boolean exists(session, orderId, lineItemId)` | Has a record already (this/last month)? |
| `List findOpenForItem(session, inventoryItemId)` | Open `backordered` records for an item, oldest-first (FIFO release order). |
| `List findForOrder(session, orderId, statuses)` | Records for an order in the given statuses. |
| `int cancelOpenForOrder(session, log, orderId, reason)` | Cancel an order's still-waiting backorders. |
| `boolean markCancelled / markReleased(...)` | Set the terminal `cancelled` / stamp `released_at`. |
| `Map summary(session)` / `List list(session, statuses, limit)` | Counts by status + awaited units / recent records (dashboard + endpoint). |

### `commerce.Events`
Source-agnostic event ingestion. The storage + normalization + replay engine behind the ingest core
(`direct:commerce-ingest`): it logs every inbound event with its raw payload to the
event log, normalizes generic topics into current-state entity records, and finds /
re-dispatches / prunes events for replay. The topic→collection mapping is pure; the
JCR methods are defensive. See [ingestion.md](ingestion.md).

| Method | Purpose |
|---|---|
| `String collectionFor(topic)` / `String actionFor(topic)` / `boolean isBespoke(topic)` | Pure topic parsing + bespoke-vs-generic test. |
| `String logEvent(session, log, source, topic, eventId, receivedAt, payloadJson)` | Record/re-record an event (status received, attempts++); returns its path. |
| `void setStatus(session, log, path, status, error)` | Stamp an event `processed` / `error`. |
| `boolean normalize(session, log, source, topic, eventId, payloadJson)` | Store the generic entity record at `/content/commerce/entities/{source}/{collection}/{id}.json`. |
| `Map find / findRecent(session, source, eventId)` | Locate an event-log entry (scan / deterministic current-month). |
| `List list(session, statuses, source, topic, fromMs, toMs, limit)` | Filtered event list (newest first) for the endpoint; `fromMs`/`toMs` bound the received_at range (0 = unbounded). |
| `List findReplayable(session, maxAttempts, backoffMs, nowMs)` | Failed events eligible for auto-replay (oldest first). |
| `String payloadJson(session, path)` | The stored raw payload, for re-dispatch. |
| `int prune(session, log, retentionMs, nowMs)` / `Map summary(session)` | Drop old processed events / counts by status. |

### `commerce.Customers`
First-class customer store (the customer domain). Body = the raw Shopify customer
JSON; typed properties for lifecycle/profile. A single writer — the customers/*
webhook upsert — sets them (and stamps the customer MIME); the mirror is
display-only, and edits go to Shopify via `ShopifyWrite.updateCustomer` rather than
back onto these props. There is no self-computed wallet or auto-segmentation; VIP is
a manual Shopify tag. GDPR redaction is handled by `commerce.Gdpr`. See
[crm.md](crm.md).

| Method | Purpose |
|---|---|
| `String keyFor(customerId, email)` / `String pathFor(key)` | Store keys (member / guest). |
| `String upsertFromWebhook(session, log, payloadJson, customer)` | customers/* upsert (profile + consent + MIME stamp; guest merge). |
| `boolean marketingEnabled(session, email)` / `findByEmail(session, email)` | Consent lookup / locate a record by email. |
| `Map read(session, key)` | One record (mirror body + profile props). |
| `List search(session, query, limit)` | Partial match on name / email / id (the customer browser search). |
| `void markDeleted(session, log, customerId)` | customers/delete lifecycle marker. |

### `commerce.Catalog`
Public storefront catalog projection. Builds sanitized public card/detail
objects from a product + its PIM overlay + per-item availability; the publish script
does the JCR IO. Pure builders (customer-safe fields only). See
[storefront.md](storefront.md).

| Method | Purpose |
|---|---|
| `Map detail(product, pimOverlay, availByItem)` | Full public product detail (variants, images, localized). |
| `Map card(detail)` | Lightweight catalog card (price range, image, aggregate availability, item ids). |
| `String toJson(value)` | Serialize for the publisher. |

### (removed) `commerce.Pages` / `commerce.InventoryRules`
Retired in the re-implementation: block-LP publishing gave way to the embed
toolkit ([storefront.md](storefront.md)); the threshold rule engine gave way to
the planning layer ([planning.md](planning.md)). New classes: `Planning`,
`Gdpr`, `SyncAudit`, `Migrations` (+ `CustomersMigration`,
`ProductMimeTypeMigration`, `CustomerMimeTypeMigration`,
`StorefrontEmbedMigration`, `PropertyTypeMigration`).

| Method | Purpose |
|---|---|
| `Map publicPage(source, allCards)` | Resolve a source page (product blocks → cards); null when draft/invalid. |
| `Map indexEntry(publicPage)` | Entry for the published-pages index. |

### `commerce.WorkflowStatus`
Shared mechanics for the `set*WorkflowStatus` scripts. Each script keeps its own
element-to-status mapping; this class does the rest. `write` is defensive (a
status-update failure never breaks the workflow). See
[commerce-status.md](commerce-status.md) for the status values themselves.

| Method | Purpose |
|---|---|
| `String elementId(task, execution)` | User-task definition key, else execution activity id, else null. |
| `Object pathVariable(context, task, execution, String name)` | Resolve a path variable: `inputs` attribute → task var → execution var. |
| `void write(session, log, String source, String path, String status, String elementId = null)` | Set `commerce:status`, commit; logs `<source>: <path> commerce:status -> <status>[ (element <id>)]`. Errors are rolled back, logged, swallowed. |

### `commerce.NamespaceMigration`
One-time data migration that renames legacy, non-namespaced commerce metadata
(`product_id`, `title`, `status`, …) on the mirrored product / order / refund nodes
to the canonical `commerce:` namespace. Needed for data ingested before the Shopify
routes were corrected to write `commerce:*` (they previously used
`includes=commerce_~`, which strips the prefix). Driven by the
`endpoints/migrate-namespace.groovy` endpoint. Idempotent and type-preserving; only
the allow-listed legacy names are touched.

| Method | Purpose |
|---|---|
| `Map run(session, log, boolean dryRun)` | Migrate every area; returns `{dryRun, areas:[…], totals:{…}}`. `dryRun` reports without writing; otherwise each area is committed independently. |

## Which script uses what

| Script | Shared classes |
|---|---|
| `screenOrder.groovy` | Money |
| `screenRefund.groovy` | Money, Refunds, Orders |
| `recordRefund.groovy` | Money, Refunds, Orders |
| `recordFulfillment.groovy` | ShopifyAdmin, Health |
| `endpoints/sync.groovy` | ShopifyAdmin, ShopifyWrite, Pim, Health, Jcr |
| `endpoints/product-media.groovy` | ShopifyAdmin, ShopifyWrite |
| `reconcile.groovy` | Api, ShopifyAdmin, Reconciliation, Health, Jcr |
| `enqueueBulkJob.groovy` | BulkJobs, BulkQueries |
| `runBulkLane.groovy` | BulkJobs, BulkQueries, ShopifyAdmin |
| `runBulkCmsLane.groovy` | BulkJobs |
| `onBulkFinish.groovy` | BulkJobs |
| `reconcileBulkResult.groovy` | BulkJobs, Locations, InventoryAlert, Reconciliation, ShopifyAdmin |
| `importBulkResult.groovy` | BulkJobs, Jcr, Money, Orders, Reconciliation, RefundMirror, SalesFacts, ShopifyAdmin |
| `watchdogBulkJobs.groovy` | BulkJobs, ShopifyAdmin |
| `endpoints/pim.groovy` | Pim |
| `endpoints/reconcile.groovy` | Api, Jcr |
| `endpoints/reports.groovy` | Reports, SalesQuery |
| `sweepSalesFacts.groovy` | SalesFacts |
| `markSalesPending.groovy` | SalesFacts |
| `seedSalesFactBackfill.groovy` | SalesFactBackfill, Jcr |
| `endpoints/sales-backfill.groovy` | SalesFactBackfill, Api |
| `endpoints/crm.groovy` | Customers |
| `publishCatalog.groovy` | Catalog, Pim, Locations, Jcr |
| `publishInventory.groovy` | Catalog, Locations, Jcr |
| `getAccessToken.groovy` | ShopifyAdmin |
| `getMetafields.groovy` | ShopifyAdmin, Health |
| `recordHealth.groovy` | Health |
| `scanTaskSla.groovy` | SimpleYaml, TaskSla |
| `checkAdminApiEnabled.groovy` | ShopifyAdmin |
| `sweepInventoryAlerts.groovy` | Planning, Locations, InventoryAlert |
| `checkThresholdConfig.groovy` | Planning, InventoryAlert |
| `createIncomingTransfer.groovy` / `cancelOrder.groovy` | ShopifyAdmin, ShopifyWrite, SyncAudit |
| `recordCustomer.groovy` | Customers |
| `customerRedact.groovy` / `customerDataRequest.groovy` / `shopRedact.groovy` | Gdpr, Notifications |
| `runMigrations.groovy` | Migrations |
| `recordInventoryLevel.groovy` | Jcr |
| `recordLocation.groovy` | Jcr |
| `notifyOrderTaskCreated.groovy` | Notifications, NotificationMessage |
| `notifyFulfillmentTaskCreated.groovy` | Notifications, NotificationMessage |
| `notifyRefundTaskCreated.groovy` | Money, Refunds, Orders, Notifications, NotificationMessage |
| `notifyTaskCreated.groovy` | Planning, Locations, Notifications, NotificationMessage |
| `setOrderWorkflowStatus.groovy` | WorkflowStatus |
| `setRefundWorkflowStatus.groovy` | WorkflowStatus |
| `setWorkflowStatus.groovy` | WorkflowStatus |
| `setBackorderWorkflowStatus.groovy` | WorkflowStatus |
| `detectBackorders.groovy` | Backorders, Locations, Notifications, NotificationMessage |
| `releaseBackorders.groovy` | Backorders, Locations |
| `cancelBackorders.groovy` | Backorders |
| `recordBackorderRelease.groovy` | Backorders |
| `notifyBackorderReady.groovy` | Notifications, NotificationMessage |
| `logEvent.groovy` | Events |
| `markEvent.groovy` | Events |
| `normalizeEvent.groovy` | Events |
| `replayEvents.groovy` | Events, SimpleYaml |

## Adding shared code

When the same logic appears in two or more scripts, extract it:

1. Add a `static` method to the right `commerce.*` class (or a new
   `commerce/<Name>.groovy` with `package commerce`).
2. Keep script-specific parts (screening rules, message wording, element-to-status
   maps) in the script — share only the mechanism.
3. Take bindings as parameters (`session`, `log`, `config`, …); don't reach for
   them inside the class.
4. Decide and document the error policy (pure vs. defensive).
5. `import commerce.<Name>` in the scripts and call the static method.
