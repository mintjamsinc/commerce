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

### `commerce.Money`
Numeric / money helpers. Pure.

| Method | Purpose |
|---|---|
| `BigDecimal toNumber(Object value)` | Parse String/Number/null to BigDecimal; null if absent/unparseable. |
| `String format(Number n)` | Thousands separators; whole numbers drop decimals (`100000` → `100,000`), fractions keep two places. `""` for null. |

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
Outbound CMS → Shopify writes (#2 bidirectional sync), built on {@link
commerce.ShopifyAdmin}. Each method builds an Admin GraphQL mutation and raises on a
transport error or a Shopify `userErrors` entry (not defensive — the sync endpoint
reports the outcome), mirroring `recordFulfillment`'s write-back policy. Ids may be
raw numeric ids or gids. See [bidirectional-sync.md](bidirectional-sync.md).

| Method | Purpose |
|---|---|
| `Map setInventory(client, endpoint, token, inventoryItemId, locationId, quantity, reason)` | Set available stock at a location (`inventorySetQuantities`). |
| `Map updatePrice(client, endpoint, token, productId, variantId, price)` | Set a variant price (`productVariantsBulkUpdate`). |
| `Map setPublished(client, endpoint, token, productId, published)` | Publish/unpublish a product (`productUpdate` status). |
| `Map setMetafields(client, endpoint, token, ownerId, metafields)` | Upsert product metafields (`metafieldsSet`) — the PIM push (#23). |
| `String gid(type, id)` | Normalize a numeric id to a Shopify gid. |

### `commerce.Pim`
Product Information Management (#23): a CMS-authoritative overlay of extended
attributes (multi-language, rich descriptions, custom attributes, metafields) stored
as the `pim` property on the product node, so it is versioned / searchable /
ACL-governed with the product. Reads defensive; `write` raises. See [pim.md](pim.md).

| Method | Purpose |
|---|---|
| `Map read(session, productId)` / `Object productResource(session, productId)` | The overlay / the product node. |
| `Map write(session, log, productId, overlay, merge, editor)` | Deep-merge or replace the overlay; stamps updatedAt/By. |
| `Map view(session, productId)` | Unified Shopify base + metafields mirror + overlay. |
| `List search(session, query, limit)` | Full-text product search (`jcr:contains`). |
| `List metafieldsToPush(overlay)` | The overlay's CMS-authored metafields for Shopify (pure). |

### `commerce.Reconciliation`
CMS ↔ Shopify drift detection + healing (#24). Pure `diffProduct`; defensive
`applyRefresh` (Shopify→CMS mirror patch). CMS→Shopify healing is done by the caller
via `ShopifyWrite`. See [reconciliation.md](reconciliation.md).

| Method | Purpose |
|---|---|
| `List diffProduct(cmsProduct, cmsInvByItem, shopifyProduct, sourceOfTruth)` | Field-level diffs (status/price/inventory) with heal direction. |
| `boolean applyRefresh(session, log, productResource, diff)` | Refresh the CMS mirror (status/price) from Shopify. |
| `String numericId(gid)` | Numeric id from a Shopify gid. |

### `commerce.Reports`
Reporting & audit export (#25). Pure, defensive JCR traversal. See
[reports.md](reports.md).

| Method | Purpose |
|---|---|
| `Map sales(session, days)` | Daily orders + revenue per currency + top products. |
| `List operations(session, days, statusFilter, limit)` | Outbound-write audit trail (`/content/commerce/sync`). |

### `commerce.ShopifyAdmin`
Shopify Admin API: enablement, token (Client Credentials Grant + JCR cache) and
GraphQL. JSON via jackson. Token caching is best-effort — a valid token is still
returned even if it could not be persisted.

| Method | Purpose |
|---|---|
| `boolean adminApiEnabled(config)` | True only when `adminApi.enabled == true` in parsed `shopify.yml`. |
| `String endpoint(adminApi)` | GraphQL endpoint URL; throws if `shopDomain`/`apiVersion` missing. |
| `String accessToken(session, log, adminApi)` | Reuse cached token while fresh, else fetch + cache. |
| `Object graphql(client, endpoint, accessToken, payload)` | POST GraphQL; throws on non-200 or a top-level `errors` array. `payload` may be a Map or a JSON String. |

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
Read-only sales and inventory aggregations for the Commerce dashboard, derived
from the stored order and product resources (pure JCR traversal). Defensive — a
read error on one resource is skipped, not thrown. See
[commerce-dashboard.md](commerce-dashboard.md).

| Method | Purpose |
|---|---|
| `Map inventorySummary(session)` | Total products + breakdown by `commerce:status` (+ lowStock = review_pending). |
| `Map salesSummary(session, days = 30)` | Orders, revenue per currency and by-status over the last N days (by ingestion time). |

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
(`checkInventoryLevel` lets it propagate; `checkThresholdConfig` /
`notifyTaskCreated` catch and treat as unconfigured).

| Method | Purpose |
|---|---|
| `Map thresholdsByVariantId(resource)` | `{ variantId(String): threshold(int) }`; empty map if the property is absent. |
| `boolean hasThresholdConfig(resource)` | True if at least one variant has a usable threshold. |

### `commerce.InventoryRules`
Inventory threshold rule engine. Resolves an effective per-variant threshold from
a manual override (wins), the first matching rule in `inventory-rules.yml`, or a
default — generalising the manual-per-product model to dynamic thresholds
(category / tag / vendor / season / velocity). Pure logic over plain data (the
caller parses the YAML, a list structure); velocity is injected by the caller. See
[inventory-rules.md](inventory-rules.md).

| Method | Purpose |
|---|---|
| `Map resolve(product, rulesConfig, manualByVariantId, velocityByVariantId = [:], today = null)` | Per variant → `[threshold, source, rule]` (source: manual/rule/default/none). |
| `boolean hasEffectiveThreshold(resolved)` | True when at least one variant resolved to a threshold. |

### `commerce.SalesVelocity`
Sales velocity & stockout prediction. Computes per-variant velocity (units/day)
from order history, caches it for cheap reuse, and forecasts which variants will
run out. Defensive JCR traversal (jackson for order/product bodies). See
[sales-velocity.md](sales-velocity.md).

| Method | Purpose |
|---|---|
| `Map computeByVariant(session, log, windowDays)` | variantId → `[units, perDay]` from order line items in the window. |
| `void writeCache(session, windowDays, byVariant)` | Persist to `/content/commerce/analytics/velocity.json`. |
| `Map loadPerDay(session)` | Cached variantId → perDay (cheap; fed to InventoryRules). |
| `Double daysToStockout(qty, perDay)` | Days until stockout, or null (no risk). |
| `List variants(session, perDayByVariant)` | Every (non-deleted) variant with stock + velocity. |
| `List forecast(session, perDayByVariant, warnDays)` | At-risk variants (soonest first). |

### `commerce.Replenishment`
Auto-reorder math: the suggested order quantity to cover the lead time + target
cover at the current velocity, minus stock, floored at the minimum and rounded to
the order multiple. Pure logic. See [auto-reorder.md](auto-reorder.md).

| Method | Purpose |
|---|---|
| `int suggest(perDay, currentStock, cfg)` | Suggested reorder qty (0 when covered / velocity unknown). |
| `Double coverDays(currentStock, perDay)` | Days of stock cover remaining (null when unknown). |

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
Backorder / pre-order management (#12). The pure `detect` decides which order lines
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
Source-agnostic event ingestion (category A: #1 all-topics, #3 multi-backend, #4
replay). The storage + normalization + replay engine behind the ingest core
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
| `List list(session, statuses, source, topic, sinceMs, limit)` | Filtered event list (newest first) for the endpoint. |
| `List findReplayable(session, maxAttempts, backoffMs, nowMs)` | Failed events eligible for auto-replay (oldest first). |
| `String payloadJson(session, path)` | The stored raw payload, for re-dispatch. |
| `int prune(session, log, retentionMs, nowMs)` / `Map summary(session)` | Drop old processed events / counts by status. |

### `commerce.Customers`
Customer normalization + segmentation (#13/#15). Rolls up purchase history from
orders, classifies each customer, and persists a CRM record. `segment` is pure;
traversal/persistence is defensive. See [crm.md](crm.md).

| Method | Purpose |
|---|---|
| `Map aggregate(session)` | customerKey → purchase-history stats from all orders. |
| `Map segment(stats, cfg, nowMs)` | Pure: `{ segment, vip, recency }` from thresholds. |
| `Map write(session, log, stats, classification)` | Persist the CRM record; returns the previous `{segment,vip,recency}` (for transition alerts). |
| `Map read(session, key)` / `Map summary(session)` / `List list(session, segment, limit)` | One record / counts / filtered list. |

### `commerce.Checkouts`
Abandoned checkout detection (#14) over the normalized checkout entities. Defensive.
See [crm.md](crm.md).

| Method | Purpose |
|---|---|
| `List findAbandoned(session, abandonedAfterMs, nowMs)` | Idle, un-completed checkouts (with reminder bookkeeping). |
| `boolean markReminded(session, log, path, nowMs)` | Bump `commerce:reminders_sent` + timestamp. |
| `Map summary(session, abandonedAfterMs, nowMs)` | Abandoned + reminded counts. |

### `commerce.Catalog`
Public storefront catalog projection (#20/#21). Builds sanitized public card/detail
objects from a product + its PIM overlay + per-item availability; the publish script
does the JCR IO. Pure builders (customer-safe fields only). See
[storefront.md](storefront.md).

| Method | Purpose |
|---|---|
| `Map detail(product, pimOverlay, availByItem)` | Full public product detail (variants, images, localized). |
| `Map card(detail)` | Lightweight catalog card (price range, image, aggregate availability, item ids). |
| `String toJson(value)` | Serialize for the publisher. |

### `commerce.Pages`
Content-commerce landing pages (#22). Resolves a CMS-authored block page into its
public projection, replacing `products` blocks with catalog cards. Pure (given the
catalog cards); the publish script does the IO. See [storefront.md](storefront.md).

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
| `reconcile.groovy` | ShopifyAdmin, ShopifyWrite, Reconciliation, Locations, Health, Jcr, Alerts, NotificationMessage |
| `endpoints/pim.groovy` | Pim |
| `endpoints/reconcile.groovy` | Jcr |
| `endpoints/reports.groovy` | Reports |
| `segmentCustomers.groovy` | Customers, Notifications, NotificationMessage |
| `abandonedCheckouts.groovy` | Checkouts, Notifications, NotificationMessage, Alerts, SmtpClient |
| `endpoints/crm.groovy` | Customers, Checkouts |
| `publishCatalog.groovy` | Catalog, Pim, Locations, Jcr |
| `publishInventory.groovy` | Catalog, Locations, Jcr |
| `publishPages.groovy` | Pages, Catalog, Jcr |
| `getAccessToken.groovy` | ShopifyAdmin |
| `getMetafields.groovy` | ShopifyAdmin, Health |
| `recordHealth.groovy` | Health |
| `scanTaskSla.groovy` | SimpleYaml, TaskSla |
| `checkAdminApiEnabled.groovy` | ShopifyAdmin |
| `checkInventoryLevel.groovy` | Inventory, InventoryRules, SalesVelocity |
| `checkThresholdConfig.groovy` | Inventory, InventoryRules, SalesVelocity |
| `computeVelocity.groovy` | SalesVelocity, Alerts, NotificationMessage, SimpleYaml |
| `proposeReorders.groovy` | SalesVelocity, Replenishment, SimpleYaml, Jcr |
| `purchaseOrder.groovy` | SmtpClient, Replenishment |
| `notifyReorderTaskCreated.groovy` | Notifications, NotificationMessage |
| `recordInventoryLevel.groovy` | Jcr |
| `recordLocation.groovy` | Jcr |
| `notifyOrderTaskCreated.groovy` | Notifications, NotificationMessage |
| `notifyFulfillmentTaskCreated.groovy` | Notifications, NotificationMessage |
| `notifyRefundTaskCreated.groovy` | Money, Refunds, Orders, Notifications, NotificationMessage |
| `notifyTaskCreated.groovy` | Inventory, InventoryRules, SalesVelocity, Notifications, NotificationMessage |
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
