# Inventory Alert Tool — User Manual

When a Shopify product's inventory falls below a configured threshold, this
mechanism automatically raises a manual review task for the person in charge and
notifies the registered channels (Slack / Discord / Teams / LINE / Email /
generic webhook). All settings are edited together from the **Commerce** app in
Webtop (you can also edit the configuration files directly).

> **Inventory is evaluated from the `inventory_levels/update` webhook**, and the
> decision uses the **sum of stock across all locations (the local mirror)**.
> Product webhooks (`products/*`) handle saving product data, building the
> reverse index, and threshold onboarding.

---

## 1. Overall Flow

Inventory evaluation (alerts) and product onboarding (threshold setup) run as two
separate paths.

**Product onboarding path (`products/create` / `products/update`)**

1. When a product webhook arrives, the product data is saved and a reverse index
   `inventory_item_id → product/variant` is built.
2. If the product has no threshold yet, a "Set Inventory Threshold" task is
   created according to the **unconfigured-threshold policy** (default = `prompt`).

**Inventory alert path (`inventory_levels/update`)**

3. When an inventory webhook arrives, the per-location inventory mirror is updated
   **newest-wins**, and the item is marked "pending" for evaluation.
4. A short-period sweep (about every 15 seconds) evaluates the pending items.
   - It sums stock across all locations (the mirror total) and compares it with
     the per-variant threshold.
   - **Edge trigger**: an "Inventory & Reorder Review" task is filed only at the
     moment stock crosses below the threshold — the **fixed reorder point** —
     (ok → low). While it stays below
     (low → low), it does not re-file. After recovery (low → ok), it can fire again.
5. When the task is created, a notification is sent to the configured
   notification channels (the `inventory` category's channel set, or the default
   set — see 2-3).
6. The person in charge opens the task in Webtop's **Tasks** app to review and respond.

**Backstop (nothing missed)**

7. A reconciliation batch (default: a daily full inventory audit at 00:00 via the
   Bulk job broker) compares Shopify's authoritative stock with the mirror and
   re-queues any item whose inventory webhook was missed.

> If there is already a workflow in progress for the same product, a new webhook
> will not start it a second time (re-entry guard; businessKey = product ID). The
> latest data is already saved in the mirror, so nothing is missed.

---

## 2. Initial Setup (Commerce App)

Open **Commerce** from the Webtop menu (administrators only). Switch screens using
the left-hand nav, and after editing, click 💾 (Save all) to **save everything at
once**. If there are unsaved changes, "Unsaved changes" is shown in the status
bar. (You may also edit each configuration file directly with the Text Editor;
they live at `/etc/commerce/config/*.yml`.)

> **By default the inventory alert is dormant.** The routes, timer, BPMN, and
> reconciliation batch are resident from deployment, but the shipped
> configuration has **no Shopify connection** (the webhook secret is a placeholder
> and the four Admin API fields are empty) and **all notification channels are
> disabled**, so nothing is filed or notified. To use it, configure the following.

### 2-1. Shop (Shopify Connection)
Edit `etc/commerce/config/shopify.yml`. The settings are divided into two groups:
**Webhook** and **Admin API**.

**Webhook (required; independent of the Admin API)**

| Item | Description |
|---|---|
| Webhook shared secret | The webhook signing secret from Shopify Admin > Notifications > Webhooks. Used to verify incoming webhooks (HMAC-SHA256). **Without it, no webhooks can be received at all.** |

**Admin API (required)**

The Admin API is a **required dependency** (there is no ON/OFF toggle). It becomes
active once all four fields below are filled in; until then the related features
(first-seen resolution, authoritative mirror refresh during reconciliation,
metafield enrichment, fulfillment write-back) are skipped with an "Admin API not
configured" warning.

| Item | Description |
|---|---|
| Shop domain | e.g. `your-store.myshopify.com` |
| API version | e.g. `2026-01` |
| Client ID / Client secret | App credentials from Shopify Partners |

> All four items are **required** (you cannot save a partial configuration). Each
> secret can be shown / hidden with the 👁 icon on the right. Webhook receipt and
> mirror updates work without the Admin API, but first-seen resolution and
> reconciliation require it.

### 2-2. Shopify Webhook Subscriptions
In the Shopify admin (or your app's scopes), subscribe to the following topics.

| Topic | Purpose |
|---|---|
| `inventory_levels/update` | **Primary trigger for inventory alerts** (evaluation) |
| `products/create` / `products/update` | Save products, build reverse index, threshold onboarding |
| `products/delete` | Clean up the reverse index |
| `locations/create` / `locations/update` | Location metadata |
| `bulk_operations/finish` | Completion signal for the reconciliation (full inventory audit) |

### 2-3. Notifications (Notification Targets)
Edit `etc/commerce/config/notifications.yml` (**a separate file from the Shopify
credentials**). The supported channels are **Slack / Discord / Teams / LINE /
Email / generic webhook** (6 total). **All are disabled (`enabled: false`) out of
the box**, so enable the channels you want and set their destinations (Incoming
Webhook URL / token / SMTP, etc.) — at least one. Inventory-alert tasks notify
under the `inventory` category: a single task-created event is delivered to
every enabled channel of the `default` set, or — to separate stock alerts from
other notices — of a dedicated set configured for `inventory` under
`categories:` (see [notification-channels.md](notification-channels.md)).

### 2-4. Threshold Policy (optional)
- The threshold is a **fixed per-variant reorder point** — a stock count the
  operator registers, never derived by the system — resolved by the planning layer
  ([planning.md](planning.md)): **per-variant planning value → `planning.yml`
  `defaults.threshold` → none (unmonitored)**. Ships
  with the default unset, so operators configure each product before monitoring
  starts (set `defaults.threshold` in `planning.yml` for a blanket baseline).
- `etc/commerce/config/inventory-alert.yml`:
  - `unconfiguredPolicy` — how to treat an item with no resolvable threshold.
    `prompt` (default: raise the "Set Inventory Threshold" task) / `silent` (do not
    monitor).
  - `sweepDebounceSeconds` — debounce window for the sweep in seconds (default 0 =
    every ~15s heartbeat).

---

## 3. How to Set Up Slack

1. Open Slack's [Incoming Webhooks](https://api.slack.com/messaging/webhooks).
2. Select the workspace / channel you want to notify, then create and add the app.
3. Copy the issued Webhook URL (`https://hooks.slack.com/services/XXX/YYY/ZZZ`).
4. Paste it into Commerce app > Notifications > Slack, and turn ON "Enable Slack notifications".
5. Save with 💾.

(Discord / Teams / LINE / Email / generic webhook are configured the same way — set
each channel's destination and enable it.)

> Even if sending a notification fails, the business process is not stopped (it is
> only logged). Channels with an unset / invalid URL are excluded from sending.

---

## 4. Handling Tasks (Tasks App)

When a notification arrives, open the corresponding task in Webtop's **Tasks** app.
The form automatically follows the light/dark theme. Operate on a task after
"Claim"ing it (assigning it to yourself).

### 4-1. Set Inventory Threshold
- Shown when the product does not yet have a threshold, such as on the first run
  (when `unconfiguredPolicy: prompt`).
- Enter an "alert threshold" for each variant ("Apply to all" fills them in at once).
- Once inventory (summed across all locations) falls below the threshold, a review
  task will be raised on subsequent evaluations.
- Save and complete the task with "Save thresholds & complete".

### 4-2. Inventory & Reorder Review
- Shown for products whose inventory has fallen below the threshold.
- For each variant you can see the current stock (**summed across all
  locations**), the fixed threshold, and the **previous order** (date + quantity)
  for reference.
- Enter the reorder quantity for each variant. The field defaults to blank —
  there is **no system-suggested quantity**; the operator decides. Use "View on
  Shopify" to open the admin screen.
- On completion the entered quantity is recorded as **incoming stock** in Shopify
  (Admin API required); a quantity of 0 writes nothing. Received stock later flows
  back in via the `inventory_levels/update` webhook.

---

## 5. Notes on Deployment

- Configuration, scripts, forms, and BPMN (under `etc/` and `content/`) are
  deployed to the CMS deployment paths.
- The **Commerce app** is built independently in this project (`webtop/`). The
  source under `webtop/src/webtop/apps/commerce` is self-contained — its only
  build-time dependency is the published `@mintjamsinc/ichigojs` runtime — so it
  does not require the cms0 Webtop project. Build with `npm run build`
  (development) or `npm run build:prod` (production) from `webtop/`. The output in
  `dist/webtop/apps/commerce/` (`app.js` / `index.html` / `assets` / `app.yml`)
  can be dropped straight into a deployed Webtop's `apps/` directory. The shared
  Webtop CSS and Bootstrap Icons that `index.html` references via `../../assets/...`
  belong to the Webtop core at the deploy target and are not part of this build.
- No additional library (JAR) is required for notifications (it uses the HTTP
  client built into the JDK).

---

## 6. Troubleshooting

| Symptom | Things to Check |
|---|---|
| Nothing happens at all | Are the Shop webhook secret and the 4 Admin API fields set / Are `inventory_levels/update` and `products/*` subscribed on the Shopify side |
| No notification arrives | Is the target channel "Enable" ON in Notifications / Is the destination (URL, etc.) correct / Any `notifyTaskCreated` warnings in the server log |
| Review task is not raised | Does the product resolve a threshold (per-variant planning value / `planning.yml` default) / Is inventory (summed across locations) below the threshold / Was it already judged low recently (edge trigger) |
| Many tasks pile up for the same product | Is the re-entry guard working (look for "already running ... not starting another" in the log) |
| Form says "Please open from the Tasks app" | The form is meant to be opened via the Tasks app (it does not work from a standalone URL) |
| Cannot save in the Commerce app | Is the content service available / Any save (multipart upload) errors in the server log / Are all four Admin API connection fields filled in (a partial configuration cannot be saved) |
