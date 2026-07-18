# Integration Health Monitor

Observes the health of the Shopify → CMS integration and raises alerts when
something is going wrong, using the same pluggable channels as the workflow
notifications (see [notification-channels.md](notification-channels.md)).

## What it measures

| Source | Signal | Where it is recorded |
|---|---|---|
| Webhook endpoint | `received`, `hmac_failure`, `verify_error`, `unhandled`, `dispatch_error` counts | `webhook.groovy` → `direct:commerce-health` → `recordHealth.groovy` |
| Camel routes | per-topic `success` / `error` and processing latency (receipt → completion) | each route → `cms:/…/recordHealth.groovy` |
| Shopify Admin API | per-label `success` / `error` and call latency | callers wrap `ShopifyAdmin.graphql` in `Health.timeApi` |

"Processing latency" is the time from HTTP receipt (the `received_at` header set
by the webhook endpoint) to route completion — the most actionable signal of
pipeline health (queue backlog, slow steps). Shopify does not send a reliable
"event sent at" header, so delivery age is intentionally not used.

## Architecture

```
webhook.groovy ──(IntegrationAPI, async)──► direct:commerce-health ──► recordHealth.groovy
Camel routes  ──(cms:script, runAs service)─────────────────────────► recordHealth.groovy
API callers   ──(Health.timeApi on the caller's session)───────────► commerce.Health
                                                                          │
                                              records metrics (JCR) ──────┤
                                              evaluates alert rules ──────┤
                                              fires alerts ──► Notifications.dispatch
```

`recordHealth.groovy` is the single writer for webhook/route metrics and always
runs in its own session (as the `commerce-service-user`), so health recording
never shares a session with — or interferes with — business processing. The
public webhook endpoint runs unauthenticated, so it cannot write metrics itself;
it emits a fire-and-forget event to the privileged `direct:commerce-health` route.

Metrics are **best-effort**: a recording failure is swallowed and logged, so
monitoring can never break the process it observes. Counter updates use an
optimistic read-modify-write with a short retry loop (accurate at webhook
volumes).

## Storage (JCR)

```
/content/commerce/health/
├── metrics/{yyyy}/{MM}/{yyyy-MM-dd}.json   # daily counters (UTC days)
└── state.json                              # per-alert cooldown timestamps
```

Daily buckets fold on the **UTC** day — the shared storage fold rule, so the
metric files line up with the raw mirror folders and never depend on the
server's timezone.

Daily document shape:

```json
{
  "date": "2026-06-02",
  "webhook": { "received": 120, "hmac_failure": 2, "verify_error": 0, "unhandled": 1, "dispatch_error": 0 },
  "route":   { "orders/paid": { "success": 100, "error": 3,
                                "latency_sum": 50000, "latency_count": 100, "latency_max": 1200 } },
  "api":     { "getMetafields": { "success": 50, "error": 1,
                                  "latency_sum": 8000, "latency_count": 51, "latency_max": 900 } }
}
```

## Alerting

Governed by `/etc/commerce/config/health.yml` (managed from **Webtop → Commerce →
Health**). Metrics are always recorded; this file only controls alerting.
Alerting is event-driven — each rule reacts to the event that just happened,
with no daily aggregation, thresholds, or sample-size gating.

| Rule | Fires when | Key settings |
|---|---|---|
| `hmacFailures` | a single HMAC verification fails; repeats are suppressed for `cooldownMinutes` (state in `state.json`) | `cooldownMinutes` |
| `apiErrors` | every Admin API error, notified individually with its error detail | — |
| `routeErrors` | every route processing error, notified individually with its error detail | — |

`enabled` is the master switch; each rule also has its own `enabled`.
The HMAC cooldown is armed before the notification is sent, so a notification
failure cannot cause an alert storm. `apiErrors` / `routeErrors` have no
cooldown by design: every error carries a distinct detail, so each one is
delivered.

Alerts are delivered as a `NotificationMessage` (title "Integration health")
under the `operations` notification category — every enabled channel of the
channel set configured for that category in `notifications.yml`, or of the
default set when the category has none. The cooldown + dispatch is provided
by the shared `commerce.Alerts` helper (also used by the task SLA monitor);
the per-error notifications go through its cooldown-less `send`.

Error details reach the monitor as follows: `Health.timeApi` captures the
thrown exception's message, and the Camel routes pass `${exception.message}`
as the `health_error` header to `recordHealth.groovy` from their error
handlers.

## Reading the data

```
GET /bin/cms.cgi/{workspace}/content/commerce/endpoints/health.groovy?days=7
```

`content/commerce/endpoints/health.groovy` returns an aggregated JSON snapshot
(last `days` days, default 7, max 90) with per-bucket `error_rate` and
`latency_avg`. It lives outside `/content/public`, so the CGI enforces
authentication and ACLs (admin-only). This is the data surface for the future
Commerce dashboard.

## Security note

The HMAC-failure counter is incremented for unauthenticated requests, so a flood
of bad requests could inflate it — which is by design (we *want* to alert on
that), and the cooldown caps the resulting notifications. Recording is async and
bounded; treat repeated HMAC-failure alerts as a signal to check the webhook
secret and the source of traffic.

HMAC verification runs with elevated privilege, not in the public endpoint. The
endpoint is unauthenticated, so its session cannot read the webhook secret
(`/etc/commerce/config/shopify.yml` is `jcr:all` = deny for anonymous). It calls
the privileged route `direct:commerce-webhook-verify` synchronously, which reads
the secret and checks the signature as the `commerce-service-user` and returns a
verdict; the endpoint gates its response on it, so unverified payloads never reach
the ingest core. The two failure counters are distinct: `hmac_failure` is a
signature mismatch (a `401`, the caller's fault), while `verify_error` is a
server-side condition — the secret is missing or unreadable (a `500`) — so the
secret being unconfigured is never silently mistaken for an unauthorized caller.
