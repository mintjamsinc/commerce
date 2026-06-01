# MintJams Commerce (commerce)

A headless-commerce **orchestration layer** built on
[cms0](https://github.com/mintjamsinc/cms0)
(JCR 2.0 + an EIP integration engine + a BPMN workflow engine). It connects an external commerce
platform (currently Shopify) to MintJams CMS: webhooks are received and
verified, normalized into the JCR repository, and driven through BPMN
workflows that raise human tasks and fire notifications when an operator
needs to step in.

- **Integration assets** — Groovy webhook endpoint, EIP integration routes,
  BPMN workflow processes, task-helper scripts, and YAML configuration. Sources
  under [`content/`](content/) and [`etc/`](etc/).
- **Commerce app** — a "Commerce" virtual-desktop app for the Webtop that
  centralizes connection and notification settings (admin-only). Source under
  [`webtop/`](webtop/).

These artifacts are **bundled, pre-deployed, into the same `mintjams/cms`
Docker image as cms0**. When you start MintJams CMS, the Commerce tooling is
already in place — there is nothing extra to install, only to configure.

> Status: **public preview.** APIs, JCR layouts, configuration keys, and the
> bundled app may change before 1.0.

---

## Quick start

Commerce has no image of its own. Its assets travel inside the `mintjams/cms`
image, so the quick start *is* the cms0 quick start — run the CMS container and
the Commerce tooling comes up with it.

```bash
docker run --rm \
  -p 8080:8080 \
  -e CMS_PUBLIC_BASE_URL=http://localhost:8080 \
  -v cms-repository:/data/repository \
  -v cms-secrets:/data/secrets \
  --tmpfs /opt/felix/tmp:size=512m,mode=0700 \
  mintjams/cms:latest
```

See the [cms0 README](https://github.com/mintjamsinc/cms0) for the full set of
environment variables, volumes, first-login flow, and `docker compose` example.

Once the CMS is up, open <http://localhost:8080/>, log in as `admin`, and open
the **Commerce** app from the Webtop menu to configure the integration (see
[Configuration](#configuration)).

---

## What ships today: the Shopify inventory alert tool

The current workflow watches Shopify stock levels. When a product's inventory
falls below a per-variant threshold, it raises a manual review task for an
operator and announces it to Slack / Discord — going beyond a fire-and-forget
alert by tracking *who* owns the follow-up.

```
Shopify (product updated / order paid)
   │  Webhook (HMAC-SHA256 verified)
   ▼
Groovy endpoint ──→ EIP route ──→ JCR (store + normalize)
                                    │  signal "process this"
                                    ▼
                              BPMN workflow
                                    │  threshold set? stock low?
                                    ▼
                          Human task ──→ Slack / Discord notice
```

Two task types make up the flow:

1. **Set Inventory Threshold** — raised the first time a product is seen with
   no threshold yet. The operator decides, per variant, "how few units before
   we warn."
2. **Manual Inventory Check** — raised when a variant drops below its
   threshold. The operator reviews the situation and marks it reviewed.

Operators work these in the Webtop **Tasks** app; notifications are posted as
each task is created. A re-entrancy guard prevents a product with an in-flight
workflow from launching a duplicate (the latest data is still stored, so
nothing is lost).

For the end-to-end operator manual, see
[`docs/inventory-alert-tool.md`](docs/inventory-alert-tool.md).

---

## Status model

Entity status is modelled on **two independent axes** so that "what the record
is in the source system" never gets conflated with "how far our pipeline has
processed it." This is a platform invariant — see
[`docs/commerce-status.md`](docs/commerce-status.md) for the authoritative,
closed enumeration.

| Property | Axis | Owner |
|---|---|---|
| `commerce:status` | Integration processing lifecycle (`received`, `threshold_pending`, `review_pending`, `monitored`, `error`, `deleted`) | This pipeline (EIP + BPMN) |
| `commerce:source_status` | Source-system business status, mirrored verbatim from Shopify | Shopify |

The runtime JCR paths these properties live on (orders, products, error
handling) are described in [`docs/jcr-structure.md`](docs/jcr-structure.md).

---

## Configuration

All settings are edited from the **Commerce** Webtop app (admin-only) and
persisted as YAML in the repository. Connection and notification settings are
kept in **separate files** on purpose, so notification destinations can be
managed without touching API secrets.

### `etc/commerce/config/shopify.yml` — Shop

| Group | Field | Required | Purpose |
|---|---|---|---|
| Webhook | `webhookSecret` | **yes** | Shared secret from Shopify Admin → Notifications → Webhooks. Verifies incoming webhooks (HMAC-SHA256). Required regardless of the Admin API setting. |
| Admin API | `adminApi.enabled` | no | When `true`, product webhooks are enriched with metafields fetched from the Shopify Admin API (GraphQL). When `false`, no Admin API calls are made and the fields below are ignored. |
| Admin API | `adminApi.shopDomain` / `apiVersion` / `clientID` / `clientSecret` | yes *(when enabled)* | Connection and OAuth credentials from Shopify Partners. All four are required once the Admin API is enabled. |

### `etc/commerce/config/notifications.yml` — Notifications

| Channel | Fields | Purpose |
|---|---|---|
| Slack | `slack.enabled`, `slack.webhookUrl` | Slack [incoming webhook](https://api.slack.com/messaging/webhooks). |
| Discord | `discord.enabled`, `discord.webhookUrl` | Discord [incoming webhook](https://support.discord.com/hc/en-us/articles/228383668-Intro-to-Webhooks). |

Notification delivery is best-effort: a failed or unconfigured channel is
logged and skipped, and never blocks the business process. Notifications use
the JDK's built-in HTTP client — no extra JARs are required.

---

## Repository layout

```
content/    JCR content deployed into the repository
            public/commerce/endpoints/shopify/webhook.groovy  Shopify webhook receiver
            commerce/forms/shopify/                            Task UI forms (threshold / review)
etc/        Server-side integration assets
            commerce/config/      shopify.yml, notifications.yml  (managed by the Commerce app)
            commerce/scripts/shopify/                            Groovy task/route helpers
            eip/routes/commerce/shopify/                         EIP integration routes
            bpm/processes/commerce/shopify/                      BPMN workflow processes
webtop/     Commerce Webtop app source (TypeScript + Rollup)
docs/       Reference docs: status model, JCR runtime structure, operator manual
```

---

## Building from source

The integration assets under `content/` and `etc/` are plain JCR
content/configuration: they are deployed to the matching repository paths and
require no compilation. The published `mintjams/cms` image already includes
them in its seed.

The **Commerce** Webtop app is the only buildable component. It is
self-contained — its only build-time dependency is the published
[`@mintjamsinc/ichigojs`](https://github.com/mintjamsinc/ichigojs) runtime, so
it builds independently of cms0:

```bash
cd webtop
npm install
npm run build        # development: unminified, inline sourcemaps
npm run build:prod   # production:  minified JS + CSS, external sourcemaps
```

The output mirrors the cms0 Webtop layout (`dist/webtop/apps/commerce/`), so
`app.js` / `index.html` / `assets/` / `app.yml` can be dropped straight into a
deployed Webtop's `apps/` directory.

---

## License

MIT. See [`LICENSE`](LICENSE).

## Links

- Source: <https://github.com/mintjamsinc/commerce>
- Runtime platform (cms0): <https://github.com/mintjamsinc/cms0>
- UI framework (ichigojs): <https://github.com/mintjamsinc/ichigojs>
- Vendor: <https://www.mintjams.jp/>
</content>
</invoke>
