# 在庫アラートツール 利用マニュアル

Shopify の商品在庫が設定した閾値を下回ったら、担当者の手動レビュータスクを
自動で起こし、Slack / Discord に通知する仕組みです。設定は Webtop の
**Commerce** アプリからまとめて編集します。

---

## 1. 全体の流れ

1. Shopify で商品が更新されると Webhook が届く。
2. 商品データを保存し、在庫アラートのワークフローが起動する。
3. **初回**でその商品に閾値が未設定なら、「在庫閾値の設定」タスクが作成される。
4. 在庫が閾値を下回っていれば、「在庫レビュー」タスクが作成される。
5. タスク作成時に、登録済みの Slack / Discord へ通知が飛ぶ。
6. 担当者は Webtop の **Tasks** アプリでタスクを開き、確認・対応する。

> 同じ商品について処理中のワークフローがある場合、新たな Webhook では二重に
> 起動しません（最新データは保存済みなので取りこぼしはありません）。

---

## 2. 初期設定（Commerce アプリ）

Webtop のメニューから **Commerce** を開きます（管理者のみ）。
左上のアイコンで画面を切り替え、編集後に 💾（Save all）で**一括保存**します。
未保存の変更があるとステータスバーに「Unsaved changes」と表示されます。

### 2-1. Shop（Shopify 接続）
`etc/commerce/config/shopify.yml` を編集します。設定は **Webhook** と
**Admin API** の 2 グループに分かれています。

**Webhook（必須・Admin API とは独立）**

| 項目 | 説明 |
|---|---|
| Webhook shared secret | Shopify 管理画面 > 通知 > Webhook の署名シークレット。受信 Webhook の検証（HMAC）に使用します。**Admin API の ON/OFF に関わらず必須**です。 |

**Admin API（任意）**

「Use the Admin API」を ON にすると、商品 Webhook の受信時に Shopify Admin API から
メタフィールド（metafields）を取得して商品に付与します。OFF の場合は Admin API を
一切呼び出さず、以下の 4 項目は**非活性（編集不可）**になります。

| 項目 | 説明 |
|---|---|
| Shop domain | 例: `your-store.myshopify.com` |
| API version | 例: `2026-01` |
| Client ID / Client secret | Shopify Partners のアプリ資格情報 |

> ON にした場合、上記 4 項目はすべて**入力必須**です（未入力では保存できません）。
> 各秘匿項目は右側の 👁 アイコンで表示／非表示を切り替えられます。

### 2-2. Notifications（通知先）
`etc/commerce/config/notifications.yml` を編集します（**Shopify の資格情報とは別ファイル**）。
Slack / Discord それぞれについて、有効化のチェックと Incoming Webhook URL を設定します。

---

## 3. Slack の設定方法

1. Slack の [Incoming Webhooks](https://api.slack.com/messaging/webhooks) を開く。
2. 通知したいワークスペース／チャンネルを選び、アプリを作成・追加する。
3. 発行された Webhook URL（`https://hooks.slack.com/services/XXX/YYY/ZZZ`）をコピー。
4. Commerce アプリ > Notifications > Slack に貼り付け、「Enable Slack notifications」を ON。
5. 💾 で保存。

---

## 4. Discord の設定方法

1. 通知したい Discord チャンネルの「設定（⚙）」>「連携サービス」>「ウェブフック」を開く。
2. 「新しいウェブフック」を作成し、「ウェブフックURLをコピー」。
   （`https://discord.com/api/webhooks/...`）
3. Commerce アプリ > Notifications > Discord に貼り付け、「Enable Discord notifications」を ON。
4. 💾 で保存。

> 通知の送信に失敗しても業務プロセスは止まりません（ログにのみ記録）。URL が未設定／
> 無効のチャネルは送信対象外です。

---

## 5. タスクの対応（Tasks アプリ）

通知が届いたら Webtop の **Tasks** アプリで該当タスクを開きます。フォームは
light/dark テーマに自動追従します。タスクは「Claim（自分に割当）」してから操作します。

### 5-1. 在庫閾値の設定（Set Inventory Threshold）
- 初回など、その商品にまだ閾値が無い場合に表示されます。
- variant ごとに「アラート閾値」を入力します（「Apply to all」で一括入力も可）。
- 在庫数が閾値を下回ると、以降の更新でレビュータスクが起こるようになります。
- 「Save thresholds & complete」で保存し、タスクを完了します。

### 5-2. 在庫レビュー（Manual Inventory Check）
- 在庫が閾値を下回った商品で表示されます。
- variant ごとの在庫数・閾値・アラート状況を確認できます。
- 必要に応じてメモを残し、「View on Shopify」で管理画面へ移動できます。
- 対応が済んだら「Mark as reviewed」でタスクを完了します。

---

## 6. デプロイに関する補足

- 設定・スクリプト・フォーム・BPMN（`etc/`・`content/`配下）は CMS の配置パスへ
  デプロイします。
- **Commerce アプリ**は本プロジェクト（`webtop/`）内で独立してビルドします。
  `webtop/src/webtop/apps/commerce` のソースは自己完結しており、ビルド時の依存は
  公開済みの `@mintjamsinc/ichigojs` ランタイムのみのため、cms0 の Webtop
  プロジェクトは不要です。`webtop/` で `npm run build`（開発）または
  `npm run build:prod`（本番）を実行してください。成果物
  `dist/webtop/apps/commerce/`（`app.js` / `index.html` / `assets` / `app.yml`）
  を、デプロイ先 Webtop の `apps/` ディレクトリにそのまま配置できます。
  `index.html` が `../../assets/...` で参照する共有 Webtop CSS や Bootstrap Icons
  はデプロイ先の Webtop コア側に属し、本ビルドには含まれません。
- 通知に追加ライブラリ（JAR）は不要です（JDK 内蔵の HTTP クライアントを使用）。

---

## 7. トラブルシューティング

| 症状 | 確認ポイント |
|---|---|
| 通知が来ない | Notifications で「Enable」ON か / Webhook URL が正しいか / サーバログに `notifyTaskCreated` の警告が無いか |
| レビュータスクが起きない | その商品に閾値が設定済みか（未設定だと先に「閾値の設定」タスク）/ 在庫が閾値を下回っているか |
| 同じ商品でタスクが乱立 | 多重起動ガードが効いているか（ログに "already running ... skipping"） |
| フォームが「Tasks アプリから開いてください」 | フォームは Tasks アプリ経由で開く前提（単体 URL では動作しません） |
| Commerce アプリで保存できない | content サービスが利用可能か / サーバログに保存（マルチパートアップロード）のエラーが無いか / Admin API が ON の場合は接続 4 項目がすべて入力済みか |
