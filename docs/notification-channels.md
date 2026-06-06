# Notification Channels

The commerce workflows raise human tasks (inventory review, order review, order
fulfillment, refund review). When a task is created, a Camunda `create` task
listener builds **one** channel-agnostic message and dispatches it to every
**enabled** channel in `/etc/commerce/config/notifications.yml`. Each channel
renders that single message in its own native format.

This is the platform "completion form": callers describe *what* to say (a
[`NotificationMessage`](../content/WEB-INF/classes/commerce/NotificationMessage.groovy));
channels decide *how* to deliver it. Adding a channel is purely additive — write a
[`NotificationChannel`](../content/WEB-INF/classes/commerce/NotificationChannel.groovy)
subclass and register it in
[`Notifications.registry()`](../content/WEB-INF/classes/commerce/Notifications.groovy);
no caller changes.

## Architecture

```
notify*TaskCreated.groovy
        │  builds
        ▼
NotificationMessage ──► Notifications.dispatch(log, source, config, message)
                                         │  for each enabled section in notifications.yml
                                         ▼
        ┌───────────┬───────────┬──────────┬──────────┬───────────┬──────────┐
     SlackChannel DiscordCh.  TeamsCh.  LineChannel WebhookCh. EmailChannel
       {text}     {content}  Adaptive   Messaging   structured   SMTP
       mrkdwn     markdown   Card        API push     JSON      (SmtpClient)
```

A channel is **ON unless** `enabled: false`. Delivery is best-effort: a failure is
logged and never breaks the business process.

## Configuration (`/etc/commerce/config/notifications.yml`)

All settings are managed from the Webtop **Commerce → Notifications** app (an
enabled channel with missing required fields blocks Save), or by editing the file
directly. Every leaf is a scalar.

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

Nothing else changes — `dispatch` and all four task-listener callers are untouched.
