# Notification Channels

The commerce workflows and monitors notify operators: human tasks (inventory
review, order review, order fulfillment, refund review), backorder events, GDPR
compliance actions, and operational alerts (health monitor, task SLA). Each
caller builds **one** channel-agnostic message, tags it with its **category**,
and dispatches it. The configuration in
`/etc/commerce/config/notifications.yml` decides **where** that category is
delivered: to the **default** channel set, or to a **dedicated** channel set
configured just for that category. Every enabled channel in the chosen set
renders the same message in its own native format.

This is the platform "completion form" split three ways: callers describe *what*
to say (a
[`NotificationMessage`](../content/WEB-INF/classes/commerce/NotificationMessage.groovy))
and which category it belongs to; the configuration decides *where* it goes;
channels decide *how* to deliver it. Adding a channel is purely additive — write a
[`NotificationChannel`](../content/WEB-INF/classes/commerce/NotificationChannel.groovy)
subclass and register it in
[`Notifications.registry()`](../content/WEB-INF/classes/commerce/Notifications.groovy);
no caller changes.

## Architecture

```
notify*TaskCreated.groovy, detectBackorders.groovy, GDPR scripts, Alerts.fire()
        │  builds NotificationMessage + declares a category
        ▼
Notifications.dispatch(log, source, config, message, category)
        │  1. pick the channel set: categories.<category> if present, else default
        │  2. fan out to every enabled channel in that set
        ▼
        ┌───────────┬───────────┬──────────┬──────────┬───────────┬──────────┐
     SlackChannel DiscordCh.  TeamsCh.  LineChannel WebhookCh. EmailChannel
       {text}     {content}  Adaptive   Messaging   structured   SMTP
       mrkdwn     markdown   Card        API push     JSON      (SmtpClient)
```

A channel is **ON unless** `enabled: false`. Delivery is best-effort: a failure is
logged and never breaks the business process.

## Categories

Every notification carries one of the fixed categories (declared by the calling
code, constants on `commerce.Notifications`):

| Category | Fired by |
|---|---|
| `inventory` | Inventory alert workflow tasks (threshold setup, inventory & reorder review) |
| `orders` | Order review workflow tasks |
| `refunds` | Refund review workflow tasks |
| `fulfillment` | Order fulfillment workflow tasks |
| `backorders` | Backorder created / ready-to-release notifications |
| `compliance` | GDPR webhook actions (customer redact / data request, shop redact) |
| `operations` | Health monitor and task SLA alerts |

A category either uses the **default** set or its **own complete** set — the two
are never merged. A channel not listed in a category's set is off for that
category. This keeps the model simple: what the operator configures for a
category is exactly what is delivered.

## Configuration (`/etc/commerce/config/notifications.yml`)

All settings are managed from the Webtop **Commerce → Notifications** app, which
shows one tab for the default set and one per category (an enabled channel with
missing required fields blocks Save — in any set). Or edit the file directly:

```yaml
default:            # used by every category without an entry under `categories`
  slack:
    enabled: true
    webhookUrl: "https://hooks.slack.com/services/.../system-alerts"
  email:
    enabled: false
    # ...

categories:         # optional; only list the categories you want to separate
  inventory:        # this category delivers ONLY through the channels below
    slack:
      enabled: true
      webhookUrl: "https://hooks.slack.com/services/.../stock-alerts"
```

In the Webtop app, switching a category to *dedicated destinations* starts with
an empty form; a per-channel **Copy default** button copies the default set's
values for that channel as a starting point (the sets stay independent
afterwards).

| Channel | Key | Required when enabled | Other settings |
|---|---|---|---|
| Slack | `slack` | `webhookUrl` | — |
| Discord | `discord` | `webhookUrl` | — |
| Microsoft Teams | `teams` | `webhookUrl` | — (posts an Adaptive Card) |
| LINE | `line` | `accessToken`, `to` | `endpoint` (default: public push endpoint) |
| Generic webhook | `webhook` | `url` | `textField` (default `text`) |
| Email (SMTP) | `email` | `smtpHost`, `from`, `to` | `smtpPort` (587), `security` (`none`/`starttls`/`ssl`), `username`, `password`, `subjectPrefix` |

### Notes per channel

- **Slack / Discord** — incoming webhooks. Bold uses `*` (Slack mrkdwn) / `**`
  (Discord markdown). Payloads: `{"text": …}` / `{"content": …}`.
- **Teams** — the Workflows/connector incoming webhook URL. Posts an Adaptive Card
  (`message` → `attachments` → `AdaptiveCard` with a wrapping `TextBlock`), the
  supported successor to the retired Office 365 connector cards.
- **LINE** — Messaging API push (`POST /v2/bot/message/push`, Bearer token).
  `to` is a user/group/room ID your bot can resolve. Plain text (no markdown);
  truncated to LINE's 5000-character limit. (LINE Notify reached end of service in
  2025; the Messaging API is its successor.)
- **Generic webhook** — POSTs structured JSON so any downstream consumer can use
  it flat or field-by-field:
  ```json
  {
    "source": "notifyOrderTaskCreated",
    "title": "Order review workflow",
    "status": "Order review required",
    "summary": "Order review workflow — Order review required",
    "fields": [ { "label": "Total", "value": "5000 JPY" } ],
    "text": "…full plain-text rendering…"
  }
  ```
  `textField` renames the plain-text key (e.g. `content`, `message`) to match a
  receiver's expectation.
- **Email** — sent over SMTP by
  [`commerce.SmtpClient`](../content/WEB-INF/classes/commerce/SmtpClient.groovy),
  a JDK-socket-only client (no `jakarta.mail` / extra JAR). Supports `none` (25),
  `starttls` (587) and `ssl` (465), and SMTP AUTH (PLAIN/LOGIN, chosen from the
  server's EHLO advertisement). The message is a single UTF-8 `text/plain` part —
  Subject is RFC 2047 encoded, body is base64 — so Japanese text and emoji survive.
  `to` is comma-separated for multiple recipients; Subject is `subjectPrefix` + the
  message summary.

## Adding a channel

1. Create `commerce/MyChannel.groovy` extending `NotificationChannel`; implement
   `type()` (its config key) and `send(log, source, cfg, message)`, using the
   inherited `postJson(...)` helper or `SmtpClient` as appropriate.
2. Add `new MyChannel()` to `Notifications.registry()`.
3. Add a documented section to `notifications.yml` (and, optionally, a form section
   to the Webtop Commerce app).

Nothing else changes — `dispatch`, the category routing and all callers are
untouched.

## Adding a category

Categories are a fixed vocabulary owned by the code: add a `CAT_*` constant to
`commerce.Notifications`, pass it from the new caller, and add the category to
the Webtop Commerce app's tab list and i18n bundles. Configurations that don't
mention the new category simply deliver it through the default set.
