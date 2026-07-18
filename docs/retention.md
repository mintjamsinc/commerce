# Data retention

Every accumulating history store has a retention policy in one place: the Commerce
app's **Maintenance > Retention** section. It has two parts — automatic pruning of
operational history (a saved config), and a manual, audited purge of business data.

## Automatic pruning (`retention.yml`)

The housekeeping batch (`commerce-housekeeping`, hourly) reads
`/etc/commerce/config/retention.yml` and deletes records older than each store's
window. Each value is a number of **days**; `0` (or a missing key) means keep
forever. The master `enabled` switch turns the whole batch off.

| Key | Store | Path | Default |
|---|---|---|---|
| `eventLog` | Event log (raw payloads) | `/content/commerce/events` | 30 |
| `webhookMarkers` | Webhook idempotency markers | `/content/commerce/history/webhooks` | 30 |
| `bulkJobs` | Bulk job records | `/content/commerce/jobs/shopify` | 90 |
| `reconciliation` | Reconciliation run reports | `/content/commerce/reconciliation` | 365 |
| `health` | Health daily metrics | `/content/commerce/health/metrics` | 400 |

Notes:

- **Event log is status-independent.** Pruning removes `processed` and `error`
  entries alike, so the log always covers the last N days and "when did ingestion
  last succeed / start failing" stays answerable. (An entry that ages past retention
  is terminal anyway — replay exhausts `maxAttempts × backoff` in minutes.)
- Reconciliation keeps its live cursor (`state.json`); only dated run reports prune.
- EIP execution history (`/var/eip/history`) is a platform concern handled outside
  this app; version history (JCR `mi:history`) is bounded by CMS versioning, not days.

`commerce-housekeeping` is cluster-leased (one node per tick) and swallows its own
errors (`etc/eip/routes/commerce/housekeeping.xml` → `houseKeeping.groovy`).

## Manual purge (business data)

Orders, payments and refunds are **never auto-deleted** — they are accounting data.
They are removed only by the explicit purge action in the same section: enter a day
count, preview the affected counts, then confirm an irreversible delete. Every purge
is recorded (`MaintenanceAudit`) and shown in the panel's history list.

Each store is filtered by its own index-backed business-date axis:

| Store | Path | Date axis |
|---|---|---|
| Orders | `/content/commerce/orders/raw` | `commerce:ordered_at` |
| Payments | `/content/commerce/payments/raw` | `commerce:paid_at` |
| Refunds | `/content/commerce/refunds/raw` | `commerce:refunded_at` |

Only the raw mirror stores are purged; the derived sales-fact index is a recomputed
aggregate and is left intact.

### Endpoint

```
GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/retention-purge.groovy            # recent purge history
GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/retention-purge.groovy?days=N     # count-only preview
POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/retention-purge.groovy  {"days":N} # perform the purge
```

The purge runs synchronously as the calling operator (writes carry that identity)
and returns the deleted counts. It lives outside `/content/public`, so the CGI
enforces authentication and ACLs. Deletes commit in batches, so partial progress
persists even if a very large purge is interrupted.

### Audit

One record per purge under `/content/commerce/maintenance/{yyyy}/{MM}/purge_{ts}.json`
with typed, queryable properties: `commerce:status`, `commerce:action`,
`commerce:actor` (who), `commerce:created_at` (when), `commerce:purge_days`, and the
deleted counts per store.
