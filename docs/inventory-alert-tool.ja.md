# 在庫アラートツール 利用マニュアル

Shopify の在庫が設定した閾値を下回ったら、担当者の手動レビュータスクを
自動で起こし、登録済みの通知チャネル（Slack / Discord / Teams / LINE / Email /
汎用 Webhook）へ知らせる仕組みです。設定は Webtop の **Commerce** アプリから
まとめて編集します（設定ファイルを直接編集することもできます）。

> **在庫評価は `inventory_levels/update` Webhook を起点**とし、判定には
> **全ロケーションの在庫合計（ローカルミラー）** を用います。商品 Webhook
> （`products/*`）は商品データの保存・逆引き索引の構築・閾値のオンボーディングを担います。

---

## 1. 全体の流れ

在庫評価（アラート）と商品オンボーディング（閾値設定）は別系統で動きます。

**商品オンボーディング系（`products/create` / `products/update`）**

1. 商品 Webhook が届くと、商品データを保存し、`inventory_item_id → 商品/バリアント`
   の逆引き索引を構築する。
2. その商品にまだ閾値が無い場合、**未設定閾値ポリシー**（後述）に従って
   「在庫閾値の設定（Set Inventory Threshold）」タスクを作成する（既定 = `prompt`）。

**在庫アラート系（`inventory_levels/update`）**

3. 在庫 Webhook が届くと、ロケーション別の在庫ミラーを **newest-wins** で更新し、
   その品目を「評価待ち（pending）」としてマークする。
4. 短周期スイープ（約 15 秒ごと）が pending を評価する。
   - 全ロケーションの在庫を合計（ミラー総量）し、バリアント別の閾値と比較。
   - **エッジトリガ**: 「割れた瞬間（ok → low）」でのみ「在庫レビュー
     （Manual Inventory Check）」タスクを起票する。割れ続けている間（low → low）は
     再起票しない。回復（low → ok）後に再び割れたら、また鳴る。
5. タスク作成時に、有効化済みの通知チャネルへ通知が飛ぶ。
6. 担当者は Webtop の **Tasks** アプリでタスクを開き、確認・対応する。

**取りこぼし防止（バックストップ）**

7. 突合バッチ（既定: 日次 00:00 の在庫フル監査 / Bulk）が Shopify の権威在庫と
   ミラーを突合し、Webhook を取りこぼした品目を再評価に回す。

> 同じ商品について処理中のワークフローがある場合、新たな Webhook では二重に
> 起動しません（再入ガード。businessKey = 商品 ID）。最新データはミラーに保存済みなので
> 取りこぼしはありません。

---

## 2. 初期設定（Commerce アプリ）

Webtop のメニューから **Commerce** を開きます（管理者のみ）。左のナビで画面を
切り替え、編集後に 💾（Save all）で**一括保存**します。未保存の変更があると
ステータスバーに「Unsaved changes」と表示されます。
（各設定ファイルを Text Editor で直接編集しても構いません。配置先は
`/etc/commerce/config/*.yml`。）

> **既定では在庫アラートは休止状態です。** ルート・タイマー・BPMN・突合バッチは
> デプロイ時から常駐していますが、出荷時は **Shopify 接続が未設定**（Webhook
> シークレットはプレースホルダ、Admin API 4 項目は空）で、**通知チャネルもすべて
> 無効**のため、何も起票・通知しません。使う場合は以下を設定して有効化します。

### 2-1. Shop（Shopify 接続）
`etc/commerce/config/shopify.yml` を編集します。設定は **Webhook** と
**Admin API** の 2 グループに分かれています。

**Webhook（必須・Admin API とは独立）**

| 項目 | 説明 |
|---|---|
| Webhook shared secret | Shopify 管理画面 > 通知 > Webhook の署名シークレット。受信 Webhook の検証（HMAC-SHA256）に使用します。**これが未設定だと Webhook を一切受信できません。** |

**Admin API（必須）**

Admin API は**必須依存**です（ON/OFF トグルはありません）。下記 4 項目が
すべて埋まった時点で有効になり、未設定の間は関連機能
（初見補完・突合での在庫ミラー権威更新・メタフィールド付与・Fulfillment 書き戻し）が
「Admin API not configured」の警告付きでスキップされます。

| 項目 | 説明 |
|---|---|
| Shop domain | 例: `your-store.myshopify.com` |
| API version | 例: `2026-01` |
| Client ID / Client secret | Shopify Partners のアプリ資格情報 |

> 4 項目は**すべて入力必須**です（部分的な入力では保存できません）。
> 各秘匿項目は右側の 👁 アイコンで表示／非表示を切り替えられます。
> Webhook 受信〜ミラー更新自体は Admin API 無しでも動きますが、初見補完と
> 突合には Admin API が必要です。

### 2-2. Shopify 側の Webhook 購読
Shopify 管理画面（またはアプリのスコープ設定）で、以下のトピックを購読します。

| トピック | 用途 |
|---|---|
| `inventory_levels/update` | **在庫アラートの主トリガー**（在庫評価） |
| `products/create` / `products/update` | 商品保存・逆引き索引・閾値オンボーディング |
| `products/delete` | 逆引き索引の掃除 |
| `locations/create` / `locations/update` | ロケーション情報 |
| `bulk_operations/finish` | 突合（在庫フル監査）の完了通知 |

### 2-3. Notifications（通知先）
`etc/commerce/config/notifications.yml` を編集します（**Shopify の資格情報とは別ファイル**）。
対応チャネルは **Slack / Discord / Teams / LINE / Email / 汎用 Webhook** の 6 種類です。
**出荷時はすべて無効（`enabled: false`）** なので、使うチャネルを有効化し、
接続先（Incoming Webhook URL / トークン / SMTP 等）を設定してください（最低 1 つ）。
1 つのタスク作成イベントから、有効なすべてのチャネルへ同報されます。

### 2-4. 閾値の方針（任意）
- `etc/commerce/config/inventory-rules.yml`: バリアント別の**有効閾値**を解決するルール。
  優先順位は **手動上書き → ルール → default → なし（未監視）**。出荷時は `default: 5` と
  サンプルルールを同梱しています（＝最初から閾値が解決されます）。
- `etc/commerce/config/inventory-alert.yml`:
  - `unconfiguredPolicy` — 有効閾値が解決できない品目の扱い。
    `prompt`（既定: 「在庫閾値の設定」タスクを起票）/ `default`（`defaultThreshold` で監視）/
    `silent`（監視しない）。
  - `sweepDebounceSeconds` — スイープのデバウンス秒（既定 0 ＝ 約 15 秒のハートビートごと）。

---

## 3. Slack の設定方法

1. Slack の [Incoming Webhooks](https://api.slack.com/messaging/webhooks) を開く。
2. 通知したいワークスペース／チャンネルを選び、アプリを作成・追加する。
3. 発行された Webhook URL（`https://hooks.slack.com/services/XXX/YYY/ZZZ`）をコピー。
4. Commerce アプリ > Notifications > Slack に貼り付け、「Enable Slack notifications」を ON。
5. 💾 で保存。

（Discord / Teams / LINE / Email / 汎用 Webhook も同様に、各チャネルの
接続先を設定して有効化します。）

> 通知の送信に失敗しても業務プロセスは止まりません（ログにのみ記録）。URL が未設定／
> 無効のチャネルは送信対象外です。

---

## 4. タスクの対応（Tasks アプリ）

通知が届いたら Webtop の **Tasks** アプリで該当タスクを開きます。フォームは
light/dark テーマに自動追従します。タスクは「Claim（自分に割当）」してから操作します。

### 4-1. 在庫閾値の設定（Set Inventory Threshold）
- 初回など、その商品にまだ閾値が無い場合に表示されます（`unconfiguredPolicy: prompt` 時）。
- variant ごとに「アラート閾値」を入力します（「Apply to all」で一括入力も可）。
- 在庫（全ロケーション合計）が閾値を下回ると、以降の評価でレビュータスクが起こるようになります。
- 「Save thresholds & complete」で保存し、タスクを完了します。

### 4-2. 在庫レビュー（Manual Inventory Check）
- 在庫が閾値を下回った商品で表示されます。
- variant ごとの在庫数（**全ロケーション合計**）・有効閾値・アラート状況を確認できます。
- 必要に応じてメモを残し、「View on Shopify」で管理画面へ移動できます。
- 対応が済んだら「Mark as reviewed」でタスクを完了します。

---

## 5. デプロイに関する補足

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

## 6. トラブルシューティング

| 症状 | 確認ポイント |
|---|---|
| そもそも何も起きない | Shop の Webhook secret / Admin API 4 項目が設定済みか / Shopify 側で `inventory_levels/update`・`products/*` を購読済みか |
| 通知が来ない | Notifications で対象チャネルが「Enable」ON か / 接続先（URL 等）が正しいか / サーバログに `notifyTaskCreated` の警告が無いか |
| レビュータスクが起きない | その商品に閾値が解決されるか（`inventory-rules.yml` の default / ルール / 手動）/ 在庫（全ロケーション合計）が閾値を下回っているか / 直近で既に low 判定済みでないか（エッジトリガ） |
| 同じ商品でタスクが乱立 | 再入ガードが効いているか（ログに "already running ... not starting another"） |
| フォームが「Tasks アプリから開いてください」 | フォームは Tasks アプリ経由で開く前提（単体 URL では動作しません） |
| Commerce アプリで保存できない | content サービスが利用可能か / サーバログに保存（マルチパートアップロード）のエラーが無いか / Admin API の接続 4 項目がすべて入力済みか（部分構成は保存不可） |
