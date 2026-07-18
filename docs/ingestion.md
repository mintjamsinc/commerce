# Event Ingestion (all-topics · multi-backend · replay)

The intake side of the platform. Every inbound integration event — from any backend — funnels through a
single source-agnostic core, is recorded with its raw payload, and is then either
handled by a dedicated workflow or normalized into a business entity. The same store
powers replay.

## The core funnel

```
backend adapter (verifies signature, builds a source-agnostic envelope)
        │   body = raw payload
        │   event_source / event_topic / event_id / received_at
        ▼
direct:commerce-ingest                       (etc/eip/routes/commerce/ingest.xml)
   ├─ logEvent         → event log entry (raw payload, status=received, attempts++)
   ├─ derive shopify_* headers for the bespoke handlers
   ├─ dispatch by topic:
   │     bespoke  → direct:shopify-order-paid / -order-updated
   │                / -order-transaction-created                     (payment store)
   │                / -product-update / -product-delete
   │                / -refund-created / -inventory-level / -location
   │                / -customer-update / -customer-delete            (customer store)
   │                / -customer-redact / -customer-data-request / -shop-redact  (GDPR)
   │     other    → normalizeEvent → /content/commerce/entities/{source}/{collection}/{id}.json
   └─ markEvent        → event log status processed | error
```

`commerce.Events` is the storage + normalization + replay engine; the routes and
scripts stay thin. See [commerce-shared-classes.md](commerce-shared-classes.md).

## All webhook topics

The Shopify adapter forwards **every** topic (no allow-list). Topics with a
dedicated workflow keep their existing behaviour — `customers/*` now lands in the
first-class customer store ([crm.md](crm.md)) and the GDPR compliance topics have
bespoke handlers ([gdpr.md](gdpr.md)); every other topic
(`fulfillments/*`, `carts/*`, `checkouts/*`, and anything Shopify
adds later) is normalized into a current-state entity record at
`/content/commerce/entities/{source}/{collection}/{id}.json` (e.g.
`entities/shopify/customers/123.json`), latest update winning, with `commerce:*`
metadata (source, topic, entity type/id, status, cross-references like customer
email / order id). Namespacing by source + collection keeps these generic records
from ever colliding with the curated bespoke stores or across backends. These
records are the foundation later business features build on, without any new ingest
plumbing.

A `*/delete` action marks the record `deleted` (parity with products) rather than
removing it.

## Multi-backend

`direct:commerce-ingest` is **source-agnostic**: it knows nothing about Shopify
beyond a small topic→route table for the topics that currently have Shopify
workflows. A backend is just an **adapter** that:

1. verifies that backend's signature / authentication, and
2. sends a message to `direct:commerce-ingest` with the envelope:

   | Header | Meaning |
   |---|---|
   | (body) | the raw event payload (JSON) |
   | `event_source` | backend id, e.g. `shopify`, `rakuten`, `base`, `erp` |
   | `event_topic` | topic / event type, e.g. `orders/paid`, `customers/create` |
   | `event_id` | unique event id (used as the log key + idempotency key) |
   | `received_at` | ISO receipt timestamp |
   | `event_shop_domain` | (optional) source shop/host, for back-references |

The Shopify adapter is `content/public/commerce/endpoints/shopify/webhook.groovy`.
To connect Rakuten / BASE / a self-hosted store / an ERP, add a sibling adapter
endpoint (or a Camel `from` route) that produces the same envelope — **no change to
the core or the downstream handlers**. Topics that need their own workflow get a new
bespoke route + a `when` branch in `ingest.xml`; everything else is normalized for
free.

## Replay

Because the event log keeps the **raw payload** of every event, any event can be
re-run.

- **Automatic** — the `commerce-replay` timer (every 5 min) re-dispatches events
  whose status is `error`, up to `replay.maxAttempts` passes, after a
  `replay.backoffMinutes` backoff. (`etc/eip/routes/commerce/replay.xml` →
  `replayEvents.groovy`.) Event-log retention/pruning is a separate concern — see
  [retention.md](retention.md).
- **Manual** — `POST` to the events endpoint replays a single event
  (`{source,eventId}`) or every event matching a filter
  (`{status,topic,source,from,to}` — `from`/`to` are ISO-8601 instants; defaults to `status:error`).

A replay re-sends the envelope through `direct:commerce-ingest` with `replay=true`.
The backend handlers honour that flag to **reprocess** the event instead of skipping
it as a duplicate (their webhook-idempotency guard is bypassed on replay); the
per-entity workflow guards still prevent duplicate in-flight workflows.

> Scope note: a bespoke route swallows its own business errors (moving the entity to
> its `error/` folder and recording health), so the event-log status reflects
> "handler invoked", not business success. Automatic replay therefore targets
> ingest/normalization failures and transport errors; a bespoke business failure is
> visible in its `error/` folder and can be replayed manually from the events
> endpoint.

## Endpoint

```
GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/events.groovy?status=error&limit=100
POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/events.groovy   {"status":"error"}
```

- `GET` — status summary + a filtered list (status / source / topic / from-to period, ISO-8601).
- `POST` — replay (see above); returns `{matched, replayed}`.

Both live outside `/content/public`, so the CGI enforces authentication and ACLs.

## Configuration (`/etc/commerce/config/ingest.yml`)

| Key | Meaning |
|---|---|
| `enabled` | master switch for the replay batch (live ingest is always on) |
| `replay.enabled` | turn automatic replay on/off |
| `replay.maxAttempts` | stop retrying an event after this many ingest passes |
| `replay.backoffMinutes` | minimum wait since the last attempt |

Event-log **retention** (how long entries are kept) is configured separately in
`retention.yml` and applied by the housekeeping batch — see [retention.md](retention.md).

## Storage

Both the raw event log and the normalized entity records described above (e.g.
`/content/commerce/entities/{source}/{collection}/{id}.json`) live under the
platform's standard JCR content tree, denormalized into `commerce:*` metadata
for auto-indexed lookups. See [jcr-structure.md](jcr-structure.md) for the full
content-tree layout, and [commerce-status.md](commerce-status.md) for the
complete set of event/entity status values (`received`, `processed`, `error`, …).

## Operator UI

The **Commerce Events** Webtop app (`webtop/src/webtop/apps/commerce-events`)
is the event-log console: a status summary, status / source / topic / period filters over
`events.groovy`, per-event **replay**, and **replay matching** (re-dispatch every event
matching the current filter, confirmed). It is one of the four single-concern apps the former
single Commerce Operations console was split into; the outbound-write audit is now the
**commerce-oplog** app and drift the **commerce-reconcile** app.
