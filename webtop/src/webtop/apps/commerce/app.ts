// Commerce — Webtop application for editing the headless-commerce configuration.
//
// Every config file under /etc/commerce/config is edited as a section (grouped in a
// sidebar) in memory and persisted together with a single "Save" (the schema-manager
// model): shopify, notifications, ingest, reconcile, locations, inventory-rules,
// velocity, reorder, backorder, order-review, refund-review, sla, storefront, crm,
// health. Each file keeps its own concern (e.g. notification destinations are stored
// separately from API secrets) so they can be managed independently.
//
// The original sections use parseSimpleYaml (top-level + one nesting level); the
// newer files (nested maps, block lists, flow arrays) use parseYaml. Each section
// has a hand-written serialize<Section> emitting the file (with its comments).
//
// Repository IO uses the Webtop content service exposed on the application
// instance (instance.api.content), mirroring how schema-manager reads and
// writes JSON config files under /etc:
//   • read  : getNode(path) → fetch(node.downloadUrl)
//   • write : initiateMultipartUpload → appendMultipartUploadChunk → completeMultipartUpload (overwrite)

import { VDOM } from '@mintjamsinc/ichigojs';

// Type-only: avoid a hard import so the source stays self-contained. The shell
// passes a fully-featured ApplicationInstance at launch.
type AnyInstance = any;

// --- Repository locations --------------------------------------------------
const CONFIG_DIR = '/etc/commerce/config';
const SHOPIFY_FILE = 'shopify.yml';
const NOTIF_FILE = 'notifications.yml';
const HEALTH_FILE = 'health.yml';
const SLA_FILE = 'sla.yml';
const VELOCITY_FILE = 'velocity.yml';
const REORDER_FILE = 'reorder.yml';
const LOCATIONS_FILE = 'locations.yml';
const STOREFRONT_FILE = 'storefront.yml';
const CRM_FILE = 'crm.yml';
const RECONCILE_FILE = 'reconcile.yml';
const INGEST_FILE = 'ingest.yml';
const BACKORDER_FILE = 'backorder.yml';
const ORDER_REVIEW_FILE = 'order-review.yml';
const REFUND_REVIEW_FILE = 'refund-review.yml';
const INVENTORY_RULES_FILE = 'inventory-rules.yml';
const SHOPIFY_PATH = CONFIG_DIR + '/' + SHOPIFY_FILE;
const NOTIF_PATH = CONFIG_DIR + '/' + NOTIF_FILE;
const HEALTH_PATH = CONFIG_DIR + '/' + HEALTH_FILE;
const SLA_PATH = CONFIG_DIR + '/' + SLA_FILE;
const VELOCITY_PATH = CONFIG_DIR + '/' + VELOCITY_FILE;
const REORDER_PATH = CONFIG_DIR + '/' + REORDER_FILE;
const LOCATIONS_PATH = CONFIG_DIR + '/' + LOCATIONS_FILE;
const STOREFRONT_PATH = CONFIG_DIR + '/' + STOREFRONT_FILE;
const CRM_PATH = CONFIG_DIR + '/' + CRM_FILE;
const RECONCILE_PATH = CONFIG_DIR + '/' + RECONCILE_FILE;
const INGEST_PATH = CONFIG_DIR + '/' + INGEST_FILE;
const BACKORDER_PATH = CONFIG_DIR + '/' + BACKORDER_FILE;
const ORDER_REVIEW_PATH = CONFIG_DIR + '/' + ORDER_REVIEW_FILE;
const REFUND_REVIEW_PATH = CONFIG_DIR + '/' + REFUND_REVIEW_FILE;
const INVENTORY_RULES_PATH = CONFIG_DIR + '/' + INVENTORY_RULES_FILE;
const YAML_MIME = 'application/x-yaml';

// --- Minimal YAML helpers --------------------------------------------------
// Purpose-built for the controlled two-level structure of these config files
// (top-level scalars + one level of nesting). Not a general YAML parser.
function coerce(raw: string): string | boolean {
	let v = (raw || '').trim();
	if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
		v = v.slice(1, -1);
	}
	if (v === 'true') return true;
	if (v === 'false') return false;
	return v;
}

function parseSimpleYaml(text: string): Record<string, any> {
	const root: Record<string, any> = {};
	let parent: Record<string, any> | null = null;
	for (const rawLine of String(text || '').split(/\r?\n/)) {
		const trimmed = rawLine.trim();
		if (!trimmed || trimmed.startsWith('#')) continue;
		const m = trimmed.match(/^([A-Za-z0-9_.-]+)\s*:\s*(.*)$/);
		if (!m) continue;
		const key = m[1];
		const val = m[2];
		const indent = rawLine.length - rawLine.replace(/^\s+/, '').length;
		if (indent === 0) {
			if (val === '') { root[key] = {}; parent = root[key]; }
			else { root[key] = coerce(val); parent = null; }
		} else if (parent) {
			parent[key] = coerce(val);
		}
	}
	return root;
}

// Richer indentation-based parser for the newer config files, which carry nested
// maps and block lists (of scalars and of maps) plus inline flow arrays — beyond
// what parseSimpleYaml handles. Still purpose-built for these controlled files, not
// a general YAML parser. (parseSimpleYaml is kept for the original sections.)
function coerceScalar(raw: string): any {
	let v = (raw || '').trim();
	if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) return v.slice(1, -1).replace(/\\(.)/g, '$1');
	if (v === 'true') return true;
	if (v === 'false') return false;
	if (v === '') return '';
	if (/^-?\d+(\.\d+)?$/.test(v)) return Number(v);
	return v;
}
function coerceYaml(raw: string): any {
	const v = (raw || '').trim();
	if (v.startsWith('[') && v.endsWith(']')) {
		const inner = v.slice(1, -1).trim();
		return inner ? inner.split(',').map((x) => coerceScalar(x)) : [];
	}
	return coerceScalar(v);
}
const YAML_KV = /^([A-Za-z0-9_.-]+)\s*:\s*(.*)$/;
function parseYaml(text: string): any {
	const lines: Array<{ indent: number; content: string }> = [];
	for (const r of String(text || '').split(/\r?\n/)) {
		let line = r.replace(/^(\s*)#.*$/, '$1'); // full-line comment
		line = line.replace(/\s+#.*$/, '');       // trailing comment
		if (!line.trim()) continue;
		lines.push({ indent: line.length - line.replace(/^\s+/, '').length, content: line.trim() });
	}
	const build = (block: Array<{ indent: number; content: string }>): any => {
		if (block.length === 0) return {};
		const result: any = block[0].content.startsWith('- ') ? [] : {};
		let idx = 0;
		while (idx < block.length) {
			const line = block[idx];
			let j = idx + 1;
			while (j < block.length && block[j].indent > line.indent) j++;
			const children = block.slice(idx + 1, j);
			if (line.content.startsWith('- ')) {
				const rest = line.content.slice(2);
				const m = rest.match(YAML_KV);
				if (m) result.push(build([{ indent: line.indent + 2, content: rest }, ...children]));
				else result.push(coerceYaml(rest));
			} else {
				const m = line.content.match(YAML_KV);
				if (m) result[m[1]] = (m[2] === '') ? (children.length ? build(children) : {}) : coerceYaml(m[2]);
			}
			idx = j;
		}
		return result;
	};
	return build(lines);
}

function esc(v: any): string {
	return String(v == null ? '' : v).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}

function serializeShopify(s: any): string {
	const a = s.adminApi || {};
	return `# Shopify configuration
# Deploy to: /etc/commerce/config/shopify.yml
# Managed by the Commerce app (Webtop > Commerce > Shop).

# Webhook shared secret (from Shopify Admin > Notifications > Webhooks).
# Used to verify incoming Shopify webhooks (HMAC-SHA256). Required to receive
# webhooks and kept INDEPENDENT of the Admin API credentials below.
webhookSecret: "${esc(s.webhookSecret)}"

# Admin API integration (optional).
# When enabled, product webhooks are enriched with metafields from the Shopify
# Admin API (GraphQL). When disabled, no Admin API calls are made and the
# connection fields below are ignored. The four fields are required when enabled.
adminApi:
  enabled: ${a.enabled === true}
  # Shop domain
  shopDomain: "${esc(a.shopDomain)}"
  # API version
  apiVersion: "${esc(a.apiVersion)}"
  # OAuth credentials (from Shopify Partners > App > Client credentials)
  clientID: "${esc(a.clientID)}"
  clientSecret: "${esc(a.clientSecret)}"
`;
}

function serializeNotifications(n: any): string {
	const slack = n.slack || {};
	const discord = n.discord || {};
	const teams = n.teams || {};
	const line = n.line || {};
	const webhook = n.webhook || {};
	const email = n.email || {};
	return `# Notification destinations for the commerce workflows
# Deploy to: /etc/commerce/config/notifications.yml
# Managed by the Commerce app (Webtop > Commerce > Notifications).
# Kept separate from shopify.yml so notification settings carry no API secrets.
# A channel is ON unless enabled is false. Each enabled channel renders the same
# workflow message in its own format. See /content/WEB-INF/classes/commerce/.

# Slack incoming webhook — https://api.slack.com/messaging/webhooks
slack:
  enabled: ${slack.enabled === true}
  webhookUrl: "${esc(slack.webhookUrl)}"

# Discord incoming webhook — https://support.discord.com/hc/en-us/articles/228383668
discord:
  enabled: ${discord.enabled === true}
  webhookUrl: "${esc(discord.webhookUrl)}"

# Microsoft Teams incoming webhook (Workflows / connector URL) — posts an Adaptive Card
teams:
  enabled: ${teams.enabled === true}
  webhookUrl: "${esc(teams.webhookUrl)}"

# LINE Messaging API push — https://developers.line.biz/en/reference/messaging-api/
line:
  enabled: ${line.enabled === true}
  accessToken: "${esc(line.accessToken)}"
  to: "${esc(line.to)}"

# Generic outbound webhook — posts structured JSON { source, title, status, summary, fields, <textField> }
webhook:
  enabled: ${webhook.enabled === true}
  url: "${esc(webhook.url)}"
  textField: "${esc(webhook.textField || 'text')}"

# Email over SMTP — security: none | starttls (587) | ssl (465); to is comma-separated
email:
  enabled: ${email.enabled === true}
  smtpHost: "${esc(email.smtpHost)}"
  smtpPort: ${Number(email.smtpPort) || 587}
  security: "${esc(email.security || 'starttls')}"
  username: "${esc(email.username)}"
  password: "${esc(email.password)}"
  from: "${esc(email.from)}"
  to: "${esc(email.to)}"
  subjectPrefix: "${esc(email.subjectPrefix)}"
`;
}

function num(v: any, dflt: number): number {
	const n = Number(v);
	return Number.isFinite(n) ? n : dflt;
}

function serializeHealth(h: any): string {
	const hmac = h.hmacFailures || {};
	const api = h.apiErrorRate || {};
	const route = h.routeErrorRate || {};
	const lat = h.processingLatency || {};
	return `# Integration health monitor
# Deploy to: /etc/commerce/config/health.yml
# Managed by the Commerce app (Webtop > Commerce > Health).
# Metrics are always recorded; this file governs ALERTING only. Alerts are sent
# through the enabled channels in notifications.yml.

# Master switch for alerting (false = record metrics without alerting).
enabled: ${h.enabled === true}

# Minimum minutes between repeat alerts of the same kind (debounce).
cooldownMinutes: ${num(h.cooldownMinutes, 30)}

# Burst of failed HMAC verifications today.
hmacFailures:
  enabled: ${hmac.enabled === true}
  threshold: ${num(hmac.threshold, 5)}

# Shopify Admin API error rate today (errors / calls), gated by minSample.
apiErrorRate:
  enabled: ${api.enabled === true}
  minSample: ${num(api.minSample, 10)}
  threshold: ${num(api.threshold, 0.2)}

# Webhook processing error rate today, gated by minSample.
routeErrorRate:
  enabled: ${route.enabled === true}
  minSample: ${num(route.minSample, 10)}
  threshold: ${num(route.threshold, 0.2)}

# Slow webhook processing: receipt -> completion latency over maxMs.
processingLatency:
  enabled: ${lat.enabled === true}
  maxMs: ${num(lat.maxMs, 30000)}
`;
}

function serializeSla(s: any): string {
	const unclaimed = s.unclaimed || {};
	const open = s.open || {};
	const overdue = s.overdue || {};
	const escal = s.escalation || {};
	const priorityLine = escal.bumpPriority
		? `  priority: ${num(escal.priority, 75)}\n`
		: '';
	return `# Task SLA monitor
# Deploy to: /etc/commerce/config/sla.yml
# Managed by the Commerce app (Webtop > Commerce > Tasks).
# A periodic scan escalates open human tasks that breach the rules below: an
# alert through notifications.yml plus the optional escalation action.

# Master switch for SLA escalation (false = scan does nothing).
enabled: ${s.enabled === true}

# Minimum minutes between repeat escalations of the same task+rule.
cooldownMinutes: ${num(s.cooldownMinutes, 120)}

# Task left unassigned for longer than minutes.
unclaimed:
  enabled: ${unclaimed.enabled === true}
  minutes: ${num(unclaimed.minutes, 60)}

# Task still open (claimed or not) for longer than minutes.
open:
  enabled: ${open.enabled === true}
  minutes: ${num(open.minutes, 1440)}

# Task past its due date by more than graceMinutes (only when a due date is set).
overdue:
  enabled: ${overdue.enabled === true}
  graceMinutes: ${num(overdue.graceMinutes, 0)}

# Optional engine-side escalation on breached tasks (in addition to the alert).
escalation:
${priorityLine}  candidateGroup: "${esc(escal.candidateGroup)}"
`;
}

function serializeVelocity(v: any): string {
	const stockout = v.stockout || {};
	return `# Sales velocity & stockout forecast
# Deploy to: /etc/commerce/config/velocity.yml
# Managed by the Commerce app (Webtop > Commerce > Forecast).
# A periodic batch computes per-variant velocity (units/day) from order history,
# caches it for the inventory rules, and alerts on imminent stockouts via the
# Notifications channels.

# Master switch (false = the batch does nothing).
enabled: ${v.enabled === true}

# Averaging window for velocity, in days.
windowDays: ${num(v.windowDays, 30)}

# Minimum minutes between repeat stockout alerts for the same variant.
cooldownMinutes: ${num(v.cooldownMinutes, 720)}

stockout:
  enabled: ${stockout.enabled === true}
  # Alert when a variant is predicted to run out within this many days.
  warnDays: ${num(stockout.warnDays, 7)}
`;
}

function serializeReorder(r: any): string {
	const supplier = r.supplier || {};
	return `# Auto-reorder / replenishment
# Deploy to: /etc/commerce/config/reorder.yml
# Managed by the Commerce app (Webtop > Commerce > Replenishment).
# A batch proposes purchase orders for variants that will not cover the lead time
# + target cover at the current velocity; an operator approves, then the PO is
# sent to the supplier. The email transport is reused from notifications.yml.

# Master switch (false = no reorder proposals are created).
enabled: ${r.enabled === true}

leadTimeDays: ${num(r.leadTimeDays, 7)}
targetCoverDays: ${num(r.targetCoverDays, 14)}
minOrderQty: ${num(r.minOrderQty, 1)}
roundTo: ${num(r.roundTo, 1)}

# Where an approved PO is sent. delivery: none | email | webhook
supplier:
  delivery: "${esc(supplier.delivery || 'none')}"
  email: "${esc(supplier.email)}"
  webhookUrl: "${esc(supplier.webhookUrl)}"
`;
}

function serializeLocations(l: any): string {
	return `# Multi-location inventory & allocation
# Deploy to: /etc/commerce/config/locations.yml
# Managed by the Commerce app (Webtop > Commerce > Locations).
# Per-location stock is ingested from Shopify inventory_levels/update; this file
# governs the cross-location allocation decision support (advisory only).

# strategy: most_stock | priority
strategy: "${esc(l.strategy || 'most_stock')}"

# Ordered, comma-separated Shopify location IDs (used by the "priority" strategy).
priorityOrder: "${esc(l.priorityOrder)}"

# Quantity held back at each location (not allocatable).
defaultSafetyStock: ${num(l.defaultSafetyStock, 0)}
`;
}

// Split a comma-separated editing string into a trimmed, non-empty list.
function csvList(s: any): string[] {
	return String(s == null ? '' : s).split(',').map((x) => x.trim()).filter(Boolean);
}
// Emit a YAML block list of quoted scalars under a key, or `[]` when empty.
function blockList(items: string[], indent: string): string {
	if (!items.length) return ' []';
	return '\n' + items.map((it) => `${indent}- "${esc(it)}"`).join('\n');
}
// Emit a YAML inline flow array of quoted scalars.
function flowList(items: string[]): string {
	return '[' + items.map((it) => `"${esc(it)}"`).join(', ') + ']';
}

function serializeStorefront(s: any): string {
	return `# Headless storefront (category F: #20 storefront, #21 realtime inventory)
# Deploy to: /etc/commerce/config/storefront.yml
# Managed by the Commerce app (Webtop > Commerce > Storefront).
#
# The publisher builds a sanitized public catalog projection that the ichigo.js
# storefront reads; checkout redirects to Shopify's hosted checkout. See docs/storefront.md.

# Master switch for catalog publishing.
enabled: ${s.enabled === true}

# Store presentation.
storeName: "${esc(s.storeName)}"

# Fallback display currency (when a product carries none).
currency: "${esc(s.currency)}"

# "Low stock" threshold: at/below this available quantity the storefront shows a
# "only a few left" badge; 0 shows "sold out".
lowStock: ${num(s.lowStock, 5)}
`;
}

function serializeCrm(c: any): string {
	const seg = c.segments || {};
	const ac = c.abandonedCart || {};
	return `# Customer CRM & marketing (category D: #13 segmentation, #14 abandoned cart, #15 alerts)
# Deploy to: /etc/commerce/config/crm.yml
# Managed by the Commerce app (Webtop > Commerce > CRM). See docs/crm.md.

# Master switch for the CRM batches.
enabled: ${c.enabled === true}

# Segment thresholds (#13).
segments:
  vipMinSpend: ${num(seg.vipMinSpend, 100000)}
  vipMinOrders: ${num(seg.vipMinOrders, 10)}
  newMaxOrders: ${num(seg.newMaxOrders, 1)}
  atRiskDays: ${num(seg.atRiskDays, 60)}
  dormantDays: ${num(seg.dormantDays, 120)}

# Operator alerts on behaviour changes (#15): newly VIP / at-risk / dormant.
alert:
  enabled: ${(c.alert || {}).enabled === true}

# Abandoned cart follow-up (#14).
abandonedCart:
  enabled: ${ac.enabled === true}
  abandonedAfterMinutes: ${num(ac.abandonedAfterMinutes, 60)}
  reminderIntervalMinutes: ${num(ac.reminderIntervalMinutes, 1440)}
  maxReminders: ${num(ac.maxReminders, 2)}
  # Customer-facing reminder emails (outward-facing). OFF by default.
  sendToCustomer: ${ac.sendToCustomer === true}
`;
}

function serializeReconcile(r: any): string {
	const sot = r.sourceOfTruth || {};
	const heal = r.autoHeal || {};
	return `# CMS <-> Shopify reconciliation (category G, #24)
# Deploy to: /etc/commerce/config/reconcile.yml
# Managed by the Commerce app (Webtop > Commerce > Reconciliation). See docs/reconciliation.md.

# Master switch for the reconciliation batch.
enabled: ${r.enabled === true}

# Products checked per run (a round-robin cursor covers the catalog over time).
maxPerRun: ${num(r.maxPerRun, 50)}

# Which side wins per field ("cms" or "shopify") — the heal direction when enabled.
sourceOfTruth:
  status: "${esc(sot.status || 'cms')}"
  price: "${esc(sot.price || 'cms')}"
  inventory: "${esc(sot.inventory || 'shopify')}"

# Automatic healing, per field. OFF by default. Inventory is never auto-healed.
autoHeal:
  status: ${heal.status === true}
  price: ${heal.price === true}
  inventory: ${heal.inventory === true}

# Send a (debounced) notification when drift is detected.
alert: ${r.alert === true}
`;
}

function serializeIngest(g: any): string {
	const rep = g.replay || {};
	return `# Event ingestion (category A: all-topics intake, multi-backend, replay)
# Deploy to: /etc/commerce/config/ingest.yml
# Managed by the Commerce app (Webtop > Commerce > Ingestion). See docs/ingestion.md.

# Master switch for the replay/housekeeping batch (live ingestion is always on; this
# only governs automatic replay + pruning).
enabled: ${g.enabled === true}

# Automatic replay of failed events.
replay:
  enabled: ${rep.enabled === true}
  maxAttempts: ${num(rep.maxAttempts, 5)}
  backoffMinutes: ${num(rep.backoffMinutes, 15)}
  retentionDays: ${num(rep.retentionDays, 30)}
`;
}

function serializeBackorder(b: any): string {
	const notify = b.notify || {};
	return `# Backorder / pre-order management (feature #12)
# Deploy to: /etc/commerce/config/backorder.yml
# Managed by the Commerce app (Webtop > Commerce > Backorders). See docs/backorders.md.

# Master switch (false = no backorders are detected or released).
enabled: ${b.enabled === true}

# Products tagged with any of these (case-insensitive) are treated as pre-orders.
# Leave empty to disable pre-order detection (shortfall detection still applies).
preorderTags:${blockList(csvList(b.preorderTags), '  ')}

# Operator notifications (delivered through the channels in notifications.yml).
notify:
  onCreated: ${notify.onCreated === true}
  onReady: ${notify.onReady === true}
`;
}

// Currency-keyed threshold rows → YAML map lines.
function thresholdLines(rows: any[], indent: string): string {
	const ok = (rows || []).filter((r) => String(r.currency || '').trim());
	if (!ok.length) return `${indent}{}`;
	return ok.map((r) => `${indent}${esc(String(r.currency).trim())}: ${num(r.value, 0)}`).join('\n');
}

function serializeOrderReview(o: any): string {
	const hv = o.highValue || {}, ff = o.flaggedFinancialStatus || {}, lq = o.largeQuantity || {}, nc = o.newCustomer || {}, am = o.addressMismatch || {};
	return `# Order review (screening) rules for the Shopify order-review workflow
# Deploy to: /etc/commerce/config/order-review.yml
# Managed by the Commerce app (Webtop > Commerce > Order review). See docs/order-review-tool.md.

# Master switch. When false, every order is auto-approved (no screening).
enabled: ${o.enabled === true}

rules:
  # Flag orders whose total_price meets/exceeds a per-currency threshold.
  highValue:
    enabled: ${hv.enabled === true}
    thresholds:
${thresholdLines(hv.thresholds, '      ')}
    default: ${num(hv.default, 1000)}

  # Flag orders whose Shopify financial_status is one of these.
  flaggedFinancialStatus:
    enabled: ${ff.enabled === true}
    statuses:${blockList(csvList(ff.statuses), '      ')}

  # Flag orders where any single line quantity meets/exceeds this count.
  largeQuantity:
    enabled: ${lq.enabled === true}
    maxLineQuantity: ${num(lq.maxLineQuantity, 10)}

  # Flag orders from first-time / new customers.
  newCustomer:
    enabled: ${nc.enabled === true}
    maxOrdersCount: ${num(nc.maxOrdersCount, 1)}

  # Flag orders whose billing and shipping countries differ.
  addressMismatch:
    enabled: ${am.enabled === true}
`;
}

function serializeRefundReview(r: any): string {
	const hv = r.highRefundValue || {}, fr = r.fullRefund || {}, nr = r.noRestock || {};
	return `# Refund review (screening) rules for the Shopify refund-review workflow
# Deploy to: /etc/commerce/config/refund-review.yml
# Managed by the Commerce app (Webtop > Commerce > Refund review). See docs/refund-tool.md.
#
# A refund is already executed in Shopify by the time this fires; review here is for
# audit / fraud-monitoring, NOT for issuing money. Nothing is written back to Shopify.

# Master switch. When false, every refund is auto-acknowledged (no screening).
enabled: ${r.enabled === true}

rules:
  # Flag refunds whose total refunded amount meets/exceeds a per-currency threshold.
  highRefundValue:
    enabled: ${hv.enabled === true}
    thresholds:
${thresholdLines(hv.thresholds, '      ')}
    default: ${num(hv.default, 500)}

  # Flag refunds that return the full order value.
  fullRefund:
    enabled: ${fr.enabled === true}

  # Flag refunds that restock no inventory (goodwill / write-off signal).
  noRestock:
    enabled: ${nr.enabled === true}
`;
}

function serializeInventoryRules(ir: any): string {
	let body = `# Inventory threshold rules
# Deploy to: /etc/commerce/config/inventory-rules.yml
# Managed by the Commerce app (Webtop > Commerce > Inventory rules). See docs/inventory-rules.md.
#
# Effective threshold precedence: manual override > first matching rule > default > none.
`;
	if (String(ir.default ?? '').trim() !== '' && Number.isFinite(Number(ir.default))) {
		body += `\n# Effective threshold when no rule matches. Remove to leave unmatched variants unmonitored.\ndefault: ${num(ir.default, 5)}\n`;
	}
	body += `\nrules:`;
	const rules = ir.rules || [];
	if (!rules.length) { body += ' []\n'; return body; }
	body += '\n';
	for (const r of rules) {
		body += `  - name: "${esc(r.name || '')}"\n`;
		const crit: string[] = [];
		const pt = csvList(r.productType), vn = csvList(r.vendor), tg = csvList(r.tags);
		if (pt.length) crit.push(`      productType: ${flowList(pt)}`);
		if (vn.length) crit.push(`      vendor: ${flowList(vn)}`);
		if (tg.length) crit.push(`      tags: ${flowList(tg)}`);
		const from = String(r.seasonFrom || '').trim(), to = String(r.seasonTo || '').trim();
		if (from || to) crit.push(`      season:\n        from: "${esc(from)}"\n        to: "${esc(to)}"`);
		if (String(r.minVelocityPerDay ?? '').trim() !== '') crit.push(`      minVelocityPerDay: ${num(r.minVelocityPerDay, 0)}`);
		if (crit.length) body += `    match:\n${crit.join('\n')}\n`;
		body += `    threshold: ${num(r.threshold, 0)}\n`;
	}
	return body;
}

// UTF-8 safe base64 for multipart upload chunks.
function toBase64(text: string): string {
	const bytes = new TextEncoder().encode(text);
	let binary = '';
	for (const b of bytes) binary += String.fromCharCode(b);
	return btoa(binary);
}

// Grouped sidebar navigation. Each item key matches a section template + a dirty flag.
const NAV_GROUPS = [
	{ label: 'Connection', items: [
		{ key: 'shop', label: 'Shop', icon: 'bi-shop' },
		{ key: 'notifications', label: 'Notifications', icon: 'bi-bell' },
	] },
	{ label: 'Intake & sync', items: [
		{ key: 'ingestion', label: 'Ingestion', icon: 'bi-inbox' },
		{ key: 'reconciliation', label: 'Reconciliation', icon: 'bi-arrow-left-right' },
	] },
	{ label: 'Inventory', items: [
		{ key: 'locations', label: 'Locations', icon: 'bi-geo-alt' },
		{ key: 'inventoryRules', label: 'Inventory rules', icon: 'bi-sliders' },
		{ key: 'forecast', label: 'Forecast', icon: 'bi-graph-down-arrow' },
		{ key: 'replenishment', label: 'Replenishment', icon: 'bi-cart-plus' },
		{ key: 'backorders', label: 'Backorders', icon: 'bi-hourglass-split' },
	] },
	{ label: 'Workflows', items: [
		{ key: 'orderReview', label: 'Order review', icon: 'bi-clipboard-check' },
		{ key: 'refundReview', label: 'Refund review', icon: 'bi-receipt' },
		{ key: 'tasks', label: 'Task SLA', icon: 'bi-list-check' },
	] },
	{ label: 'Storefront', items: [
		{ key: 'storefront', label: 'Storefront', icon: 'bi-shop-window' },
		{ key: 'crm', label: 'Customers (CRM)', icon: 'bi-people' },
	] },
	{ label: 'Monitoring', items: [
		{ key: 'health', label: 'Integration health', icon: 'bi-heart-pulse' },
	] },
];
// Section key → the dirty computed that tracks it (for the nav unsaved markers).
const SECTION_DIRTY: Record<string, string> = {
	shop: 'shopDirty', notifications: 'notifDirty', health: 'healthDirty', tasks: 'slaDirty',
	forecast: 'velocityDirty', replenishment: 'reorderDirty', locations: 'locationsDirty',
	ingestion: 'ingestDirty', reconciliation: 'reconcileDirty', inventoryRules: 'inventoryRulesDirty',
	backorders: 'backorderDirty', orderReview: 'orderReviewDirty', refundReview: 'refundReviewDirty',
	storefront: 'storefrontDirty', crm: 'crmDirty',
};

const App = {
	data() {
		return {
			instance: null as AnyInstance,
			content: null as AnyInstance,

			section: 'shop' as string,
			view: 'loading' as 'loading' | 'error' | 'ready',
			errorMessage: '',

			// Left navigation sidebar (Island-less). Mirrors the content-browser
			// sidebar: a top "Toggle Sidebar" button hides it, a drag handle on its
			// right edge resizes it, and each nav group can be collapsed. The width
			// is applied inline so it survives resize without touching the CSS.
			sidebarVisible: true,
			sidebarWidth: 224,        // 14rem default — matches the prior fixed width
			sidebarMinWidth: 180,
			sidebarMaxWidth: 400,
			sidebarResizing: false,
			sidebarResizeStartX: 0,
			sidebarResizeStartWidth: 0,
			_boundSidebarResizeMove: null as ((e: MouseEvent) => void) | null,
			_boundSidebarResizeUp: null as (() => void) | null,
			// Collapsible nav groups: group label → expanded. All open by default.
			navGroupExpanded: NAV_GROUPS.reduce((acc, g) => { acc[g.label] = true; return acc; }, {} as Record<string, boolean>),

			saving: false,
			status: '',
			statusKind: '' as '' | 'ok' | 'err',
			toast: '',
			toastError: false,

			// Unsaved-changes prompt shown on window close (Save / Don't Save /
			// Cancel), mirroring the cms0 text-editor dialog instead of the
			// native window.confirm().
			closeConfirmDialog: {
				visible: false,
				resolve: null as null | ((result: 'save' | 'discard' | 'cancel') => void),
			},

			// Per-field reveal state for the Shop + Notification secrets (eye toggles).
			reveal: {
				clientID: false, clientSecret: false, webhookSecret: false,
				lineToken: false, emailPassword: false,
			},

			// The webhook shared secret is independent of the Admin API; the
			// Admin API connection settings are grouped (and gated) under adminApi.
			shop: {
				webhookSecret: '',
				adminApi: { enabled: false, shopDomain: '', apiVersion: '', clientID: '', clientSecret: '' },
			},
			notif: {
				slack: { enabled: false, webhookUrl: '' },
				discord: { enabled: false, webhookUrl: '' },
				teams: { enabled: false, webhookUrl: '' },
				line: { enabled: false, accessToken: '', to: '' },
				webhook: { enabled: false, url: '', textField: 'text' },
				email: {
					enabled: false, smtpHost: '', smtpPort: 587, security: 'starttls',
					username: '', password: '', from: '', to: '', subjectPrefix: '[Commerce] ',
				},
			},

			// Integration health monitor alert thresholds (health.yml).
			health: {
				enabled: true,
				cooldownMinutes: 30,
				hmacFailures: { enabled: true, threshold: 5 },
				apiErrorRate: { enabled: true, minSample: 10, threshold: 0.2 },
				routeErrorRate: { enabled: true, minSample: 10, threshold: 0.2 },
				processingLatency: { enabled: true, maxMs: 30000 },
			},

			// Task SLA monitor (sla.yml).
			sla: {
				enabled: true,
				cooldownMinutes: 120,
				unclaimed: { enabled: true, minutes: 60 },
				open: { enabled: true, minutes: 1440 },
				overdue: { enabled: true, graceMinutes: 0 },
				escalation: { bumpPriority: true, priority: 75, candidateGroup: '' },
			},

			// Sales velocity & stockout forecast (velocity.yml).
			velocity: {
				enabled: true,
				windowDays: 30,
				cooldownMinutes: 720,
				stockout: { enabled: true, warnDays: 7 },
			},

			// Auto-reorder / replenishment (reorder.yml).
			reorder: {
				enabled: false,
				leadTimeDays: 7,
				targetCoverDays: 14,
				minOrderQty: 1,
				roundTo: 1,
				supplier: { delivery: 'none', email: '', webhookUrl: '' },
			},

			// Multi-location inventory & allocation (locations.yml).
			locations: {
				strategy: 'most_stock',
				priorityOrder: '',
				defaultSafetyStock: 0,
			},

			// Headless storefront / catalog publishing (storefront.yml).
			storefront: { enabled: true, storeName: '', currency: '', lowStock: 5 },

			// Customer CRM & marketing (crm.yml).
			crm: {
				enabled: true,
				segments: { vipMinSpend: 100000, vipMinOrders: 10, newMaxOrders: 1, atRiskDays: 60, dormantDays: 120 },
				alert: { enabled: true },
				abandonedCart: { enabled: true, abandonedAfterMinutes: 60, reminderIntervalMinutes: 1440, maxReminders: 2, sendToCustomer: false },
			},

			// CMS <-> Shopify reconciliation (reconcile.yml).
			reconcile: {
				enabled: true,
				maxPerRun: 50,
				sourceOfTruth: { status: 'cms', price: 'cms', inventory: 'shopify' },
				autoHeal: { status: false, price: false, inventory: false },
				alert: true,
			},

			// Event ingestion replay / housekeeping (ingest.yml).
			ingest: { enabled: true, replay: { enabled: true, maxAttempts: 5, backoffMinutes: 15, retentionDays: 30 } },

			// Backorder / pre-order management (backorder.yml). preorderTags edited as CSV.
			backorder: { enabled: true, preorderTags: '', notify: { onCreated: true, onReady: true } },

			// Order-review screening rules (order-review.yml). thresholds edited as rows.
			orderReview: {
				enabled: true,
				highValue: { enabled: true, thresholds: [] as any[], default: 1000 },
				flaggedFinancialStatus: { enabled: true, statuses: '' },
				largeQuantity: { enabled: true, maxLineQuantity: 10 },
				newCustomer: { enabled: false, maxOrdersCount: 1 },
				addressMismatch: { enabled: true },
			},

			// Refund-review screening rules (refund-review.yml).
			refundReview: {
				enabled: true,
				highRefundValue: { enabled: true, thresholds: [] as any[], default: 500 },
				fullRefund: { enabled: true },
				noRestock: { enabled: true },
			},

			// Inventory threshold rules (inventory-rules.yml). Dynamic rule list; list
			// criteria edited as CSV, season as from/to.
			inventoryRules: { default: 5 as any, rules: [] as any[] },

			// Snapshots for dirty detection (the in-memory edit model).
			_origShop: '',
			_origNotif: '',
			_origHealth: '',
			_origSla: '',
			_origVelocity: '',
			_origReorder: '',
			_origLocations: '',
			_origStorefront: '',
			_origCrm: '',
			_origReconcile: '',
			_origIngest: '',
			_origBackorder: '',
			_origOrderReview: '',
			_origRefundReview: '',
			_origInventoryRules: '',
			_messageListener: null as any,
			_toastTimer: null as any,
		};
	},

	computed: {
		shopDirty(): boolean { return JSON.stringify(this.shop) !== this._origShop; },
		notifDirty(): boolean { return JSON.stringify(this.notif) !== this._origNotif; },
		healthDirty(): boolean { return JSON.stringify(this.health) !== this._origHealth; },
		slaDirty(): boolean { return JSON.stringify(this.sla) !== this._origSla; },
		velocityDirty(): boolean { return JSON.stringify(this.velocity) !== this._origVelocity; },
		reorderDirty(): boolean { return JSON.stringify(this.reorder) !== this._origReorder; },
		locationsDirty(): boolean { return JSON.stringify(this.locations) !== this._origLocations; },
		storefrontDirty(): boolean { return JSON.stringify(this.storefront) !== this._origStorefront; },
		crmDirty(): boolean { return JSON.stringify(this.crm) !== this._origCrm; },
		reconcileDirty(): boolean { return JSON.stringify(this.reconcile) !== this._origReconcile; },
		ingestDirty(): boolean { return JSON.stringify(this.ingest) !== this._origIngest; },
		backorderDirty(): boolean { return JSON.stringify(this.backorder) !== this._origBackorder; },
		orderReviewDirty(): boolean { return JSON.stringify(this.orderReview) !== this._origOrderReview; },
		refundReviewDirty(): boolean { return JSON.stringify(this.refundReview) !== this._origRefundReview; },
		inventoryRulesDirty(): boolean { return JSON.stringify(this.inventoryRules) !== this._origInventoryRules; },
		hasChanges(): boolean {
			return this.shopDirty || this.notifDirty || this.healthDirty || this.slaDirty || this.velocityDirty || this.reorderDirty || this.locationsDirty
				|| this.storefrontDirty || this.crmDirty || this.reconcileDirty || this.ingestDirty || this.backorderDirty
				|| this.orderReviewDirty || this.refundReviewDirty || this.inventoryRulesDirty;
		},

		// When the Admin API is enabled, all four connection fields are required.
		// Drives the inline field markers, the save guard and the status hint.
		adminApiInvalid(): boolean {
			const a = this.shop.adminApi;
			if (!a.enabled) return false;
			return !String(a.shopDomain).trim()
				|| !String(a.apiVersion).trim()
				|| !String(a.clientID).trim()
				|| !String(a.clientSecret).trim();
		},
		// An enabled channel must have its required connection fields. Drives the
		// inline markers, the save guard and the status hint (mis-config guard).
		notifInvalid(): boolean {
			const n = this.notif;
			const blank = (v: any) => !String(v == null ? '' : v).trim();
			if (n.slack.enabled && blank(n.slack.webhookUrl)) return true;
			if (n.discord.enabled && blank(n.discord.webhookUrl)) return true;
			if (n.teams.enabled && blank(n.teams.webhookUrl)) return true;
			if (n.line.enabled && (blank(n.line.accessToken) || blank(n.line.to))) return true;
			if (n.webhook.enabled && blank(n.webhook.url)) return true;
			if (n.email.enabled && (blank(n.email.smtpHost) || blank(n.email.from) || blank(n.email.to))) return true;
			return false;
		},
		canSave(): boolean { return this.hasChanges && !this.adminApiInvalid && !this.notifInvalid && !this.saving; },
		navGroups(): any { return NAV_GROUPS; },
	},

	methods: {
		// ---- Lifecycle -------------------------------------------------------
		onMounted() {
			const vm = this;

			// The shell pushes theme changes to the iframe via postMessage; mirror
			// the value onto <html data-theme> exactly like the built-in apps.
			vm._messageListener = (event: MessageEvent) => {
				const data: any = event.data || {};
				if (data.type === 'theme-changed' && data.theme) {
					document.documentElement.dataset.theme = data.theme;
				}
			};
			window.addEventListener('message', vm._messageListener);

			window.appLaunch = async (instance: AnyInstance) => {
				vm.instance = vm.$markRaw(instance);
				try { vm.content = vm.$markRaw(instance.api.content); } catch (_) { vm.content = null; }

				try {
					const theme = instance.api.theme.currentTheme || 'light';
					document.documentElement.dataset.theme = theme;
				} catch (_) { /* theme service unavailable */ }

				try { instance.windowTitle = 'Commerce'; } catch (_) {}

				// Warn before discarding unsaved edits on window close, using the
				// shared Webtop dialog (same look as the cms0 text-editor).
				if (typeof instance.setBeforeCloseCallback === 'function') {
					instance.setBeforeCloseCallback(async () => vm.confirmClose());
				}

				await vm.loadUiState();
				await vm.loadAll();

				vm.$nextTick(() => { try { instance.notifyLaunched(); } catch (_) {} });
			};
		},

		onUnmount() {
			if (this._messageListener) window.removeEventListener('message', this._messageListener);
			if (this._toastTimer) clearTimeout(this._toastTimer);
			// Detach any in-flight sidebar resize listeners.
			if (this._boundSidebarResizeMove) document.removeEventListener('mousemove', this._boundSidebarResizeMove);
			if (this._boundSidebarResizeUp) document.removeEventListener('mouseup', this._boundSidebarResizeUp);
		},

		selectSection(section: string) { this.section = section; },

		// ---- Left sidebar (toggle / collapse / resize) -----------------------
		toggleSidebar() {
			this.sidebarVisible = !this.sidebarVisible;
			this.persistUiState();
		},
		toggleNavGroup(label: string) {
			this.navGroupExpanded[label] = !this.navGroupExpanded[label];
			this.persistUiState();
		},
		onSidebarResizeStart(event: MouseEvent) {
			const vm = this;
			event.preventDefault();
			vm.sidebarResizing = true;
			vm.sidebarResizeStartX = event.clientX;
			vm.sidebarResizeStartWidth = vm.sidebarWidth;
			vm._boundSidebarResizeMove = (e: MouseEvent) => {
				const delta = e.clientX - vm.sidebarResizeStartX;
				let newWidth = vm.sidebarResizeStartWidth + delta;
				newWidth = Math.max(vm.sidebarMinWidth, Math.min(vm.sidebarMaxWidth, newWidth));
				vm.sidebarWidth = newWidth;
			};
			vm._boundSidebarResizeUp = () => {
				vm.sidebarResizing = false;
				if (vm._boundSidebarResizeMove) document.removeEventListener('mousemove', vm._boundSidebarResizeMove);
				if (vm._boundSidebarResizeUp) document.removeEventListener('mouseup', vm._boundSidebarResizeUp);
				vm._boundSidebarResizeMove = null;
				vm._boundSidebarResizeUp = null;
				vm.persistUiState();
			};
			document.addEventListener('mousemove', vm._boundSidebarResizeMove);
			document.addEventListener('mouseup', vm._boundSidebarResizeUp);
		},

		// Persist the sidebar layout per user (visibility, width, collapsed
		// groups), mirroring how content-browser stores its panel state. Guarded
		// so a shell without the db service simply skips persistence.
		async persistUiState() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				// JSON round-trip strips the reactive Proxy from navGroupExpanded;
				// IndexedDB's structured clone rejects Proxy objects otherwise.
				await db.setUserSetting(userID, 'commerce', 'sidebar', JSON.parse(JSON.stringify({
					visible: vm.sidebarVisible,
					width: vm.sidebarWidth,
					groupExpanded: vm.navGroupExpanded,
				})));
			} catch (_) { /* non-critical: ignore persistence errors */ }
		},
		async loadUiState() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				const state = await db.getUserSetting(userID, 'commerce', 'sidebar');
				if (state) {
					vm.sidebarVisible = state.visible ?? true;
					vm.sidebarWidth = state.width ?? vm.sidebarWidth;
					if (state.groupExpanded) Object.assign(vm.navGroupExpanded, state.groupExpanded);
				}
			} catch (_) { /* non-critical: ignore */ }
		},

		// Per-section unsaved marker for the sidebar.
		isDirty(key: string): boolean { const f = SECTION_DIRTY[key]; return !!f && (this as any)[f] === true; },

		// Dynamic rows for the rule editors.
		addThreshold(rows: any[]) { rows.push({ currency: '', value: 0 }); },
		removeThreshold(rows: any[], i: number) { rows.splice(i, 1); },
		addRule() { this.inventoryRules.rules.push({ name: '', productType: '', vendor: '', tags: '', seasonFrom: '', seasonTo: '', minVelocityPerDay: '', threshold: 0 }); },
		removeRule(i: number) { this.inventoryRules.rules.splice(i, 1); },

		// ---- Window controls -------------------------------------------------
		onMinimizeWindow() { this.instance?.minimize(); },
		onToggleMaximizeWindow() { this.instance?.toggleMaximize(); },
		onCloseWindow() { this.instance?.requestClose(); },

		// ---- Close confirmation ----------------------------------------------
		// Resolves true when the window may close, false to keep it open. When the
		// user chooses Save, persist first and only close if the save succeeded
		// (a failed save or invalid Admin API leaves hasChanges set → stay open).
		async confirmClose(): Promise<boolean> {
			const vm = this;
			if (!vm.hasChanges) return true;

			const result = await vm.showCloseConfirmDialog();
			if (result === 'cancel') return false;
			if (result === 'discard') return true;

			await vm.saveAll();
			return !vm.hasChanges;
		},

		showCloseConfirmDialog(): Promise<'save' | 'discard' | 'cancel'> {
			const vm = this;
			vm.closeConfirmDialog.visible = true;
			return new Promise((resolve) => {
				vm.closeConfirmDialog.resolve = resolve;
			});
		},

		onCloseConfirmDialogAction(action: 'save' | 'discard' | 'cancel') {
			const vm = this;
			if (vm.closeConfirmDialog.resolve) {
				vm.closeConfirmDialog.resolve(action);
			}
			vm.closeConfirmDialog.visible = false;
			vm.closeConfirmDialog.resolve = null;
		},

		// ---- Repository IO ---------------------------------------------------
		async readText(path: string): Promise<string | null> {
			if (!this.content) return null;
			try {
				const node = await this.content.getNode(path);
				if (!node || !node.downloadUrl) return null;
				const res = await fetch(node.downloadUrl);
				if (!res.ok) return null;
				return await res.text();
			} catch (_) {
				return null; // treat a missing file as "use defaults"
			}
		},

		async writeText(dir: string, file: string, text: string): Promise<void> {
			if (!this.content) throw new Error('Content service is unavailable.');
			const info: any = await this.content.initiateMultipartUpload();
			const uploadID = info?.uploadId ?? info?.uploadID ?? info?.id ?? info;
			await this.content.appendMultipartUploadChunk(uploadID, toBase64(text));
			await this.content.completeMultipartUpload(uploadID, dir, file, YAML_MIME, true);
		},

		async loadAll() {
			try {
				const [shopText, notifText, healthText, slaText, velocityText, reorderText, locationsText,
					storefrontText, crmText, reconcileText, ingestText, backorderText, orderReviewText, refundReviewText, inventoryRulesText] = await Promise.all([
					this.readText(SHOPIFY_PATH),
					this.readText(NOTIF_PATH),
					this.readText(HEALTH_PATH),
					this.readText(SLA_PATH),
					this.readText(VELOCITY_PATH),
					this.readText(REORDER_PATH),
					this.readText(LOCATIONS_PATH),
					this.readText(STOREFRONT_PATH),
					this.readText(CRM_PATH),
					this.readText(RECONCILE_PATH),
					this.readText(INGEST_PATH),
					this.readText(BACKORDER_PATH),
					this.readText(ORDER_REVIEW_PATH),
					this.readText(REFUND_REVIEW_PATH),
					this.readText(INVENTORY_RULES_PATH),
				]);

				const s = parseSimpleYaml(shopText || '');
				// Admin API settings live under `adminApi`. Legacy flat files kept
				// the four fields at the top level — fall back to them so existing
				// values survive until the next save migrates the file. A flat file
				// has no enabled flag, so the integration defaults to off.
				const a = (s.adminApi && typeof s.adminApi === 'object') ? s.adminApi : {};
				this.shop = {
					webhookSecret: String(s.webhookSecret || ''),
					adminApi: {
						enabled: a.enabled === true,
						shopDomain: String(a.shopDomain ?? s.shopDomain ?? ''),
						apiVersion: String(a.apiVersion ?? s.apiVersion ?? ''),
						clientID: String(a.clientID ?? s.clientID ?? ''),
						clientSecret: String(a.clientSecret ?? s.clientSecret ?? ''),
					},
				};

				const n = parseSimpleYaml(notifText || '');
				const sec = (key: string) => (n[key] && typeof n[key] === 'object') ? n[key] : {};
				const slack = sec('slack'), discord = sec('discord'), teams = sec('teams');
				const line = sec('line'), webhook = sec('webhook'), email = sec('email');
				this.notif = {
					slack: { enabled: slack.enabled === true, webhookUrl: String(slack.webhookUrl || '') },
					discord: { enabled: discord.enabled === true, webhookUrl: String(discord.webhookUrl || '') },
					teams: { enabled: teams.enabled === true, webhookUrl: String(teams.webhookUrl || '') },
					line: {
						enabled: line.enabled === true,
						accessToken: String(line.accessToken || ''),
						to: String(line.to || ''),
					},
					webhook: {
						enabled: webhook.enabled === true,
						url: String(webhook.url || ''),
						textField: String(webhook.textField || 'text'),
					},
					email: {
						enabled: email.enabled === true,
						smtpHost: String(email.smtpHost || ''),
						smtpPort: Number(email.smtpPort) || 587,
						security: String(email.security || 'starttls'),
						username: String(email.username || ''),
						password: String(email.password || ''),
						from: String(email.from || ''),
						to: String(email.to || ''),
						subjectPrefix: String(email.subjectPrefix ?? '[Commerce] '),
					},
				};

				const h = parseSimpleYaml(healthText || '');
				const hsec = (key: string) => (h[key] && typeof h[key] === 'object') ? h[key] : {};
				const hmac = hsec('hmacFailures'), api = hsec('apiErrorRate');
				const route = hsec('routeErrorRate'), lat = hsec('processingLatency');
				// A missing health.yml defaults to the recommended thresholds (on).
				const hasHealth = !!healthText;
				this.health = {
					enabled: hasHealth ? (h.enabled === true) : true,
					cooldownMinutes: Number(h.cooldownMinutes) || 30,
					hmacFailures: {
						enabled: hasHealth ? (hmac.enabled === true) : true,
						threshold: Number(hmac.threshold) || 5,
					},
					apiErrorRate: {
						enabled: hasHealth ? (api.enabled === true) : true,
						minSample: Number(api.minSample) || 10,
						threshold: Number(api.threshold) || 0.2,
					},
					routeErrorRate: {
						enabled: hasHealth ? (route.enabled === true) : true,
						minSample: Number(route.minSample) || 10,
						threshold: Number(route.threshold) || 0.2,
					},
					processingLatency: {
						enabled: hasHealth ? (lat.enabled === true) : true,
						maxMs: Number(lat.maxMs) || 30000,
					},
				};

				const sla = parseSimpleYaml(slaText || '');
				const ssec = (key: string) => (sla[key] && typeof sla[key] === 'object') ? sla[key] : {};
				const unclaimed = ssec('unclaimed'), open = ssec('open');
				const overdue = ssec('overdue'), escal = ssec('escalation');
				const hasSla = !!slaText;
				this.sla = {
					enabled: hasSla ? (sla.enabled === true) : true,
					cooldownMinutes: Number(sla.cooldownMinutes) || 120,
					unclaimed: {
						enabled: hasSla ? (unclaimed.enabled === true) : true,
						minutes: Number(unclaimed.minutes) || 60,
					},
					open: {
						enabled: hasSla ? (open.enabled === true) : true,
						minutes: Number(open.minutes) || 1440,
					},
					overdue: {
						enabled: hasSla ? (overdue.enabled === true) : true,
						graceMinutes: Number(overdue.graceMinutes) || 0,
					},
					escalation: {
						bumpPriority: escal.priority != null && escal.priority !== '',
						priority: Number(escal.priority) || 75,
						candidateGroup: String(escal.candidateGroup || ''),
					},
				};

				const vel = parseSimpleYaml(velocityText || '');
				const vstockout = (vel.stockout && typeof vel.stockout === 'object') ? vel.stockout : {};
				const hasVel = !!velocityText;
				this.velocity = {
					enabled: hasVel ? (vel.enabled === true) : true,
					windowDays: Number(vel.windowDays) || 30,
					cooldownMinutes: Number(vel.cooldownMinutes) || 720,
					stockout: {
						enabled: hasVel ? (vstockout.enabled === true) : true,
						warnDays: Number(vstockout.warnDays) || 7,
					},
				};

				const ro = parseSimpleYaml(reorderText || '');
				const rsupplier = (ro.supplier && typeof ro.supplier === 'object') ? ro.supplier : {};
				this.reorder = {
					enabled: ro.enabled === true,
					leadTimeDays: Number(ro.leadTimeDays) || 7,
					targetCoverDays: Number(ro.targetCoverDays) || 14,
					minOrderQty: Number(ro.minOrderQty) || 1,
					roundTo: Number(ro.roundTo) || 1,
					supplier: {
						delivery: String(rsupplier.delivery || 'none'),
						email: String(rsupplier.email || ''),
						webhookUrl: String(rsupplier.webhookUrl || ''),
					},
				};

				const loc = parseSimpleYaml(locationsText || '');
				this.locations = {
					strategy: String(loc.strategy || 'most_stock'),
					priorityOrder: String(loc.priorityOrder || ''),
					defaultSafetyStock: Number(loc.defaultSafetyStock) || 0,
				};

				const obj = (v: any) => (v && typeof v === 'object' && !Array.isArray(v)) ? v : {};

				const sf = parseYaml(storefrontText || '');
				const hasSf = !!storefrontText;
				this.storefront = {
					enabled: hasSf ? (sf.enabled === true) : true,
					storeName: String(sf.storeName || ''),
					currency: String(sf.currency || ''),
					lowStock: Number(sf.lowStock) || 0,
				};

				const cr = parseYaml(crmText || '');
				const seg = obj(cr.segments), crAc = obj(cr.abandonedCart);
				const hasCrm = !!crmText;
				this.crm = {
					enabled: hasCrm ? (cr.enabled === true) : true,
					segments: {
						vipMinSpend: Number(seg.vipMinSpend) || 0,
						vipMinOrders: Number(seg.vipMinOrders) || 0,
						newMaxOrders: Number(seg.newMaxOrders) || 0,
						atRiskDays: Number(seg.atRiskDays) || 0,
						dormantDays: Number(seg.dormantDays) || 0,
					},
					alert: { enabled: hasCrm ? (obj(cr.alert).enabled === true) : true },
					abandonedCart: {
						enabled: hasCrm ? (crAc.enabled === true) : true,
						abandonedAfterMinutes: Number(crAc.abandonedAfterMinutes) || 60,
						reminderIntervalMinutes: Number(crAc.reminderIntervalMinutes) || 1440,
						maxReminders: Number(crAc.maxReminders) || 2,
						sendToCustomer: crAc.sendToCustomer === true,
					},
				};

				const rc = parseYaml(reconcileText || '');
				const rcSot = obj(rc.sourceOfTruth), rcHeal = obj(rc.autoHeal);
				const hasRc = !!reconcileText;
				this.reconcile = {
					enabled: hasRc ? (rc.enabled === true) : true,
					maxPerRun: Number(rc.maxPerRun) || 50,
					sourceOfTruth: {
						status: String(rcSot.status || 'cms'),
						price: String(rcSot.price || 'cms'),
						inventory: String(rcSot.inventory || 'shopify'),
					},
					autoHeal: { status: rcHeal.status === true, price: rcHeal.price === true, inventory: rcHeal.inventory === true },
					alert: hasRc ? (rc.alert === true) : true,
				};

				const ig = parseYaml(ingestText || '');
				const igRep = obj(ig.replay);
				const hasIg = !!ingestText;
				this.ingest = {
					enabled: hasIg ? (ig.enabled === true) : true,
					replay: {
						enabled: hasIg ? (igRep.enabled === true) : true,
						maxAttempts: Number(igRep.maxAttempts) || 5,
						backoffMinutes: Number(igRep.backoffMinutes) || 15,
						retentionDays: Number(igRep.retentionDays) || 30,
					},
				};

				const bo = parseYaml(backorderText || '');
				const boNotify = obj(bo.notify);
				const hasBo = !!backorderText;
				this.backorder = {
					enabled: hasBo ? (bo.enabled === true) : true,
					preorderTags: Array.isArray(bo.preorderTags) ? bo.preorderTags.join(', ') : '',
					notify: { onCreated: hasBo ? (boNotify.onCreated === true) : true, onReady: hasBo ? (boNotify.onReady === true) : true },
				};

				const thresholdRows = (m: any) => { const o = obj(m); return Object.keys(o).map((k) => ({ currency: k, value: Number(o[k]) || 0 })); };
				const orv = parseYaml(orderReviewText || ''), orRules = obj(orv.rules);
				const orHv = obj(orRules.highValue), orFf = obj(orRules.flaggedFinancialStatus), orLq = obj(orRules.largeQuantity), orNc = obj(orRules.newCustomer), orAm = obj(orRules.addressMismatch);
				const hasOr = !!orderReviewText;
				this.orderReview = {
					enabled: hasOr ? (orv.enabled === true) : true,
					highValue: { enabled: orHv.enabled === true, thresholds: thresholdRows(orHv.thresholds), default: Number(orHv.default) || 0 },
					flaggedFinancialStatus: { enabled: orFf.enabled === true, statuses: Array.isArray(orFf.statuses) ? orFf.statuses.join(', ') : '' },
					largeQuantity: { enabled: orLq.enabled === true, maxLineQuantity: Number(orLq.maxLineQuantity) || 10 },
					newCustomer: { enabled: orNc.enabled === true, maxOrdersCount: Number(orNc.maxOrdersCount) || 1 },
					addressMismatch: { enabled: orAm.enabled === true },
				};

				const rrv = parseYaml(refundReviewText || ''), rrRules = obj(rrv.rules);
				const rrHv = obj(rrRules.highRefundValue), rrFr = obj(rrRules.fullRefund), rrNr = obj(rrRules.noRestock);
				const hasRr = !!refundReviewText;
				this.refundReview = {
					enabled: hasRr ? (rrv.enabled === true) : true,
					highRefundValue: { enabled: rrHv.enabled === true, thresholds: thresholdRows(rrHv.thresholds), default: Number(rrHv.default) || 0 },
					fullRefund: { enabled: rrFr.enabled === true },
					noRestock: { enabled: rrNr.enabled === true },
				};

				const ivr = parseYaml(inventoryRulesText || '');
				this.inventoryRules = {
					default: (ivr.default == null || ivr.default === '') ? '' : Number(ivr.default),
					rules: (Array.isArray(ivr.rules) ? ivr.rules : []).map((r: any) => {
						const match = obj(r.match), season = obj(match.season);
						const csv = (v: any) => Array.isArray(v) ? v.join(', ') : (v == null ? '' : String(v));
						return {
							name: String(r.name || ''),
							productType: csv(match.productType),
							vendor: csv(match.vendor),
							tags: csv(match.tags),
							seasonFrom: String(season.from || ''),
							seasonTo: String(season.to || ''),
							minVelocityPerDay: (match.minVelocityPerDay == null || match.minVelocityPerDay === '') ? '' : Number(match.minVelocityPerDay),
							threshold: Number(r.threshold) || 0,
						};
					}),
				};

				this.snapshot();
				this.view = 'ready';
			} catch (e: any) {
				this.errorMessage = (e && e.message) ? e.message : String(e);
				this.view = 'error';
			}
		},

		async saveAll() {
			if (this.saving || !this.hasChanges) return;

			// Enforce the required Admin API fields before persisting so we never
			// write an enabled-but-unconfigured integration.
			if (this.adminApiInvalid) {
				this.section = 'shop';
				this.status = 'Enter the shop domain, API version, client ID and client secret, or turn off the Admin API.';
				this.statusKind = 'err';
				this.showToast(this.status, true);
				return;
			}

			// Same guard for notification channels: never persist an enabled
			// channel that is missing its required connection fields.
			if (this.notifInvalid) {
				this.section = 'notifications';
				this.status = 'Complete the required fields for each enabled channel, or turn it off.';
				this.statusKind = 'err';
				this.showToast(this.status, true);
				return;
			}

			this.saving = true;
			this.status = '';
			this.statusKind = '';
			try {
				if (this.shopDirty) {
					await this.writeText(CONFIG_DIR, SHOPIFY_FILE, serializeShopify(this.shop));
				}
				if (this.notifDirty) {
					await this.writeText(CONFIG_DIR, NOTIF_FILE, serializeNotifications(this.notif));
				}
				if (this.healthDirty) {
					await this.writeText(CONFIG_DIR, HEALTH_FILE, serializeHealth(this.health));
				}
				if (this.slaDirty) {
					await this.writeText(CONFIG_DIR, SLA_FILE, serializeSla(this.sla));
				}
				if (this.velocityDirty) {
					await this.writeText(CONFIG_DIR, VELOCITY_FILE, serializeVelocity(this.velocity));
				}
				if (this.reorderDirty) {
					await this.writeText(CONFIG_DIR, REORDER_FILE, serializeReorder(this.reorder));
				}
				if (this.locationsDirty) {
					await this.writeText(CONFIG_DIR, LOCATIONS_FILE, serializeLocations(this.locations));
				}
				if (this.storefrontDirty) {
					await this.writeText(CONFIG_DIR, STOREFRONT_FILE, serializeStorefront(this.storefront));
				}
				if (this.crmDirty) {
					await this.writeText(CONFIG_DIR, CRM_FILE, serializeCrm(this.crm));
				}
				if (this.reconcileDirty) {
					await this.writeText(CONFIG_DIR, RECONCILE_FILE, serializeReconcile(this.reconcile));
				}
				if (this.ingestDirty) {
					await this.writeText(CONFIG_DIR, INGEST_FILE, serializeIngest(this.ingest));
				}
				if (this.backorderDirty) {
					await this.writeText(CONFIG_DIR, BACKORDER_FILE, serializeBackorder(this.backorder));
				}
				if (this.orderReviewDirty) {
					await this.writeText(CONFIG_DIR, ORDER_REVIEW_FILE, serializeOrderReview(this.orderReview));
				}
				if (this.refundReviewDirty) {
					await this.writeText(CONFIG_DIR, REFUND_REVIEW_FILE, serializeRefundReview(this.refundReview));
				}
				if (this.inventoryRulesDirty) {
					await this.writeText(CONFIG_DIR, INVENTORY_RULES_FILE, serializeInventoryRules(this.inventoryRules));
				}
				this.snapshot();
				this.status = 'All changes saved.';
				this.statusKind = 'ok';
			} catch (e: any) {
				this.status = 'Save failed: ' + ((e && e.message) ? e.message : String(e));
				this.statusKind = 'err';
				this.showToast(this.status, true);
			} finally {
				this.saving = false;
			}
		},

		snapshot() {
			this._origShop = JSON.stringify(this.shop);
			this._origNotif = JSON.stringify(this.notif);
			this._origHealth = JSON.stringify(this.health);
			this._origSla = JSON.stringify(this.sla);
			this._origVelocity = JSON.stringify(this.velocity);
			this._origReorder = JSON.stringify(this.reorder);
			this._origLocations = JSON.stringify(this.locations);
			this._origStorefront = JSON.stringify(this.storefront);
			this._origCrm = JSON.stringify(this.crm);
			this._origReconcile = JSON.stringify(this.reconcile);
			this._origIngest = JSON.stringify(this.ingest);
			this._origBackorder = JSON.stringify(this.backorder);
			this._origOrderReview = JSON.stringify(this.orderReview);
			this._origRefundReview = JSON.stringify(this.refundReview);
			this._origInventoryRules = JSON.stringify(this.inventoryRules);
		},

		showToast(message: string, isError: boolean) {
			this.toast = message || '';
			this.toastError = !!isError;
			if (this._toastTimer) clearTimeout(this._toastTimer);
			this._toastTimer = setTimeout(() => { this.toast = ''; }, 4000);
		},
	},
};

VDOM.createApp(App).mount('#app');
