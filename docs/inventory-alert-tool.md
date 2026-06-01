# Inventory Alert Tool — User Manual

When a Shopify product's inventory falls below a configured threshold, this
mechanism automatically raises a manual review task for the person in charge and
sends a notification to Slack / Discord. All settings are edited together from
the **Commerce** app in Webtop.

---

## 1. Overall Flow

1. When a product is updated in Shopify, a webhook is received.
2. The product data is saved, and the inventory alert workflow starts.
3. On the **first run**, if no threshold has been set for that product, a "Set
   Inventory Threshold" task is created.
4. If the inventory is below the threshold, an "Inventory Review" task is created.
5. When a task is created, a notification is sent to the registered Slack / Discord.
6. The person in charge opens the task in Webtop's **Tasks** app to review and respond.

> If there is already a workflow in progress for the same product, a new webhook
> will not start it a second time (the latest data has already been saved, so
> nothing is missed).

---

## 2. Initial Setup (Commerce App)

Open **Commerce** from the Webtop menu (administrators only). Switch screens
using the icon in the top-left, and after editing, click 💾 (Save all) to
**save everything at once**. If there are unsaved changes, "Unsaved changes" is
shown in the status bar.

### 2-1. Shop (Shopify Connection)
Edit `etc/commerce/config/shopify.yml`. The settings are divided into two
groups: **Webhook** and **Admin API**.

**Webhook (required; independent of the Admin API)**

| Item | Description |
|---|---|
| Webhook shared secret | The webhook signing secret from Shopify Admin > Notifications > Webhooks. Used to verify incoming webhooks (HMAC). **Required regardless of whether the Admin API is ON or OFF.** |

**Admin API (optional)**

When "Use the Admin API" is turned ON, metafields are fetched from the Shopify
Admin API and attached to the product when a product webhook is received. When
OFF, the Admin API is not called at all, and the following 4 items become
**disabled (not editable)**.

| Item | Description |
|---|---|
| Shop domain | e.g. `your-store.myshopify.com` |
| API version | e.g. `2026-01` |
| Client ID / Client secret | App credentials from Shopify Partners |

> When turned ON, all 4 items above are **required** (you cannot save if they are
> left empty). Each secret item can be shown / hidden with the 👁 icon on the right.

### 2-2. Notifications (Notification Targets)
Edit `etc/commerce/config/notifications.yml` (**a separate file from the Shopify
credentials**). For each of Slack / Discord, set the enable checkbox and the
Incoming Webhook URL.

---

## 3. How to Set Up Slack

1. Open Slack's [Incoming Webhooks](https://api.slack.com/messaging/webhooks).
2. Select the workspace / channel you want to notify, then create and add the app.
3. Copy the issued Webhook URL (`https://hooks.slack.com/services/XXX/YYY/ZZZ`).
4. Paste it into Commerce app > Notifications > Slack, and turn ON "Enable Slack notifications".
5. Save with 💾.

---

## 4. How to Set Up Discord

1. Open "Settings (⚙)" > "Integrations" > "Webhooks" for the Discord channel you
   want to notify.
2. Create a "New Webhook" and "Copy Webhook URL".
   (`https://discord.com/api/webhooks/...`)
3. Paste it into Commerce app > Notifications > Discord, and turn ON "Enable Discord notifications".
4. Save with 💾.

> Even if sending a notification fails, the business process is not stopped (it is
> only recorded in the log). Channels with an unset / invalid URL are excluded
> from sending.

---

## 5. Handling Tasks (Tasks App)

When a notification arrives, open the corresponding task in Webtop's **Tasks**
app. The form automatically follows the light/dark theme. Operate on a task after
"Claim"ing it (assigning it to yourself).

### 5-1. Set Inventory Threshold
- Shown when the product does not yet have a threshold, such as on the first run.
- Enter an "alert threshold" for each variant ("Apply to all" lets you fill them
  in all at once).
- Once the inventory falls below the threshold, a review task will be raised on
  subsequent updates.
- Save and complete the task with "Save thresholds & complete".

### 5-2. Manual Inventory Check
- Shown for products whose inventory has fallen below the threshold.
- You can check the inventory count, threshold, and alert status for each variant.
- Leave notes as needed, and use "View on Shopify" to go to the admin screen.
- Once handled, complete the task with "Mark as reviewed".

---

## 6. Notes on Deployment

- Configuration, scripts, forms, and BPMN (under `etc/` and `content/`) are
  deployed to the CMS deployment paths.
- The **Commerce app** is built independently in this project (`webtop/`). The
  source under `webtop/src/webtop/apps/commerce` is self-contained — its only
  build-time dependency is the published `@mintjamsinc/ichigojs` runtime — so it
  does not require the cms0 Webtop project. Build with `npm run build`
  (development) or `npm run build:prod` (production) from `webtop/`. The output
  in `dist/webtop/apps/commerce/` (`app.js` / `index.html` / `assets` /
  `app.yml`) can be dropped straight into a deployed Webtop's `apps/` directory.
  The shared Webtop CSS and Bootstrap Icons that `index.html` references via
  `../../assets/...` belong to the Webtop core at the deploy target and are not
  part of this build.
- No additional library (JAR) is required for notifications (it uses the HTTP
  client built into the JDK).

---

## 7. Troubleshooting

| Symptom | Things to Check |
|---|---|
| No notification arrives | Is "Enable" ON in Notifications / Is the Webhook URL correct / Are there any `notifyTaskCreated` warnings in the server log |
| Review task is not raised | Is a threshold already set for that product (if not, the "Set Threshold" task comes first) / Is the inventory below the threshold |
| Many tasks pile up for the same product | Is the multiple-start guard working (look for "already running ... skipping" in the log) |
| Form says "Please open from the Tasks app" | The form is meant to be opened via the Tasks app (it does not work from a standalone URL) |
| Cannot save in the Commerce app | Is the content service available / Are there any save (multipart upload) errors in the server log / When the Admin API is ON, all four connection fields must be filled in |
