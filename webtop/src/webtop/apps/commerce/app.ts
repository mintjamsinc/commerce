// Commerce — Webtop application for editing the headless-commerce configuration.
//
// Every config file under /etc/commerce/config is edited as a section (grouped in a
// sidebar) in memory and persisted together with a single "Save" (the schema-manager
// model): shopify, notifications, ingest, reconcile, locations, inventory-alert,
// planning, backorder, order-review, refund-review, sla, health.
// Each file keeps its own concern (e.g. notification destinations are stored
// separately from API secrets) so they can be managed independently.
//
// The original sections use parseSimpleYaml (top-level + one nesting level); the
// deeper files (nested maps, block lists, flow arrays — including notifications
// with its per-category channel sets) use parseYaml. Each section has a
// hand-written serialize<Section> emitting the file (with its comments).
//
// Repository IO uses the Webtop content service exposed on the application
// instance (instance.api.content), mirroring how schema-manager reads and
// writes JSON config files under /etc:
//   • read  : getNode(path) → fetch(node.downloadUrl)
//   • write : initiateMultipartUpload → appendMultipartUploadChunk → completeMultipartUpload (overwrite)

import { VDOM } from '@mintjamsinc/ichigojs';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from '../../composables/use-localization.js';
import { utcTimeToZone, zoneTimeToUtc } from '../../composables/wire-datetime.js';

// Type-only: avoid a hard import so the source stays self-contained. The shell
// passes a fully-featured ApplicationInstance at launch.
type AnyInstance = any;

// --- Repository locations --------------------------------------------------
const CONFIG_DIR = '/etc/commerce/config';
const SHOPIFY_FILE = 'shopify.yml';
const NOTIF_FILE = 'notifications.yml';
const HEALTH_FILE = 'health.yml';
const SLA_FILE = 'sla.yml';
const PLANNING_FILE = 'planning.yml';
const LOCATIONS_FILE = 'locations.yml';
const RECONCILE_FILE = 'reconcile.yml';
const INGEST_FILE = 'ingest.yml';
const RETENTION_FILE = 'retention.yml';
const BACKORDER_FILE = 'backorder.yml';
const ORDER_REVIEW_FILE = 'order-review.yml';
const REFUND_REVIEW_FILE = 'refund-review.yml';
const INVENTORY_ALERT_FILE = 'inventory-alert.yml';
const SHOPIFY_PATH = CONFIG_DIR + '/' + SHOPIFY_FILE;
const NOTIF_PATH = CONFIG_DIR + '/' + NOTIF_FILE;
const HEALTH_PATH = CONFIG_DIR + '/' + HEALTH_FILE;
const SLA_PATH = CONFIG_DIR + '/' + SLA_FILE;
const PLANNING_PATH = CONFIG_DIR + '/' + PLANNING_FILE;
const LOCATIONS_PATH = CONFIG_DIR + '/' + LOCATIONS_FILE;
const RECONCILE_PATH = CONFIG_DIR + '/' + RECONCILE_FILE;
const INGEST_PATH = CONFIG_DIR + '/' + INGEST_FILE;
const RETENTION_PATH = CONFIG_DIR + '/' + RETENTION_FILE;
const BACKORDER_PATH = CONFIG_DIR + '/' + BACKORDER_FILE;
const ORDER_REVIEW_PATH = CONFIG_DIR + '/' + ORDER_REVIEW_FILE;
const REFUND_REVIEW_PATH = CONFIG_DIR + '/' + REFUND_REVIEW_FILE;
const INVENTORY_ALERT_PATH = CONFIG_DIR + '/' + INVENTORY_ALERT_FILE;
const YAML_MIME = 'application/x-yaml';

// Admin endpoint that create-or-updates the required Shopify webhook subscriptions
// (idempotent: re-running with a changed URL updates the existing subscriptions'
// callbackUrl instead of creating duplicates). The "Webhooks" section is an ACTION
// panel — it calls this endpoint directly over the cgi base URL and does NOT
// participate in the config Save-all flow.
const WEBHOOKS_SCRIPT = '/content/commerce/endpoints/webhooks.groovy';
const RETENTION_PURGE_SCRIPT = '/content/commerce/endpoints/retention-purge.groovy';
// GDPR compliance topics are configured in the app's compliance webhook settings
// (Partner Dashboard), NOT creatable via webhookSubscriptionCreate — shown to the
// operator as an informational reminder, never registered from here.
const WEBHOOK_COMPLIANCE_TOPICS = ['customers/redact', 'customers/data_request', 'shop/redact'];

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

# Admin API integration (REQUIRED).
# Used to enrich products with metafields, reconcile the catalog mirror (status / price)
# and audit inventory via bulk operations, and write fulfillments back to Shopify. There
# is no on/off toggle: the Admin API is active once the four connection fields below are
# filled in.
adminApi:
  # Shop domain
  shopDomain: "${esc(a.shopDomain)}"
  # API version
  apiVersion: "${esc(a.apiVersion)}"
  # OAuth credentials (from Shopify Partners > App > Client credentials)
  clientID: "${esc(a.clientID)}"
  clientSecret: "${esc(a.clientSecret)}"
`;
}

// Notification categories (fixed vocabulary, mirrored by commerce.Notifications).
// Every notification carries one; the config maps each category to the default
// channel set or to a dedicated one.
const NOTIF_CATEGORIES = ['inventory', 'orders', 'refunds', 'fulfillment', 'backorders', 'compliance', 'operations'];

// A blank channel set: every channel off with empty connection fields. Used for
// the default set before load and as the starting point when a category is
// switched to dedicated destinations (the operator fills it in or copies the
// default values per channel).
function emptyNotifSet(): any {
	return {
		slack: { enabled: false, webhookUrl: '' },
		discord: { enabled: false, webhookUrl: '' },
		teams: { enabled: false, webhookUrl: '' },
		line: { enabled: false, accessToken: '', to: '' },
		webhook: { enabled: false, url: '', textField: 'text' },
		email: {
			enabled: false, smtpHost: '', smtpPort: 587, security: 'starttls',
			username: '', password: '', from: '', to: '', subjectPrefix: '[Commerce] ',
		},
	};
}

// Map one parsed channel set (a `default` or category section of
// notifications.yml) into the edit model, coercing every field.
function readNotifSet(y: any): any {
	const src = (y && typeof y === 'object') ? y : {};
	const sec = (key: string) => (src[key] && typeof src[key] === 'object') ? src[key] : {};
	const slack = sec('slack'), discord = sec('discord'), teams = sec('teams');
	const line = sec('line'), webhook = sec('webhook'), email = sec('email');
	return {
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
}

// Emit one channel set at the given indent (used for `default` and for each
// dedicated category set).
function emitNotifSet(set: any, indent: string): string {
	const s = set || {};
	const slack = s.slack || {}, discord = s.discord || {}, teams = s.teams || {};
	const line = s.line || {}, webhook = s.webhook || {}, email = s.email || {};
	const i = indent;
	return `${i}slack:
${i}  enabled: ${slack.enabled === true}
${i}  webhookUrl: "${esc(slack.webhookUrl)}"
${i}discord:
${i}  enabled: ${discord.enabled === true}
${i}  webhookUrl: "${esc(discord.webhookUrl)}"
${i}teams:
${i}  enabled: ${teams.enabled === true}
${i}  webhookUrl: "${esc(teams.webhookUrl)}"
${i}line:
${i}  enabled: ${line.enabled === true}
${i}  accessToken: "${esc(line.accessToken)}"
${i}  to: "${esc(line.to)}"
${i}webhook:
${i}  enabled: ${webhook.enabled === true}
${i}  url: "${esc(webhook.url)}"
${i}  textField: "${esc(webhook.textField || 'text')}"
${i}email:
${i}  enabled: ${email.enabled === true}
${i}  smtpHost: "${esc(email.smtpHost)}"
${i}  smtpPort: ${Number(email.smtpPort) || 587}
${i}  security: "${esc(email.security || 'starttls')}"
${i}  username: "${esc(email.username)}"
${i}  password: "${esc(email.password)}"
${i}  from: "${esc(email.from)}"
${i}  to: "${esc(email.to)}"
${i}  subjectPrefix: "${esc(email.subjectPrefix)}"
`;
}

function serializeNotifications(n: any): string {
	const sets = n.sets || {};
	const customCats = NOTIF_CATEGORIES.filter((c) => sets[c] && typeof sets[c] === 'object');
	let out = `# Notification destinations for the commerce workflows
# Deploy to: /etc/commerce/config/notifications.yml
# Managed by the Commerce app (Webtop > Commerce > Notifications).
# Kept separate from shopify.yml so notification settings carry no API secrets.
#
# Every notification carries a category (inventory / orders / refunds /
# fulfillment / backorders / compliance / operations). The "default" channel set
# is used for every category that has no entry under "categories"; a listed
# category uses its own channel set exactly as written (channels not listed in
# a category set are off for that category). A channel is ON unless enabled is
# false. Each enabled channel renders the same workflow message in its own
# format. See /content/WEB-INF/classes/commerce/.

# Channels: Slack / Discord incoming webhooks; Teams Workflows webhook (Adaptive
# Card); LINE Messaging API push; generic webhook (structured JSON, textField
# renames the plain-text key); email over SMTP (security: none | starttls | ssl,
# to is comma-separated).
default:
${emitNotifSet(sets.default, '  ')}`;
	if (customCats.length) {
		out += `
# Per-category channel sets (categories not listed use "default").
categories:
`;
		for (const c of customCats) {
			out += `  ${c}:\n${emitNotifSet(sets[c], '    ')}`;
		}
	}
	return out;
}

function num(v: any, dflt: number): number {
	const n = Number(v);
	return Number.isFinite(n) ? n : dflt;
}

function serializeHealth(h: any): string {
	const hmac = h.hmacFailures || {};
	const api = h.apiErrors || {};
	const route = h.routeErrors || {};
	return `# Integration health monitor
# Deploy to: /etc/commerce/config/health.yml
# Managed by the Commerce app (Webtop > Commerce > Health).
# Metrics are always recorded; this file governs ALERTING only. Alerts are sent
# through the enabled channels in notifications.yml.

# Master switch for alerting (false = record metrics without alerting).
enabled: ${h.enabled === true}

# A single failed HMAC verification alerts; repeats are suppressed for
# cooldownMinutes.
hmacFailures:
  enabled: ${hmac.enabled === true}
  cooldownMinutes: ${num(hmac.cooldownMinutes, 30)}

# Every Shopify Admin API error is notified with its error detail.
apiErrors:
  enabled: ${api.enabled === true}

# Every webhook processing error is notified with its error detail.
routeErrors:
  enabled: ${route.enabled === true}
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

function serializePlanning(p: any): string {
	const d = p.defaults || {};
	const thresholdLine = (String(d.threshold ?? '').trim() !== '' && Number.isFinite(Number(d.threshold)))
		? `  threshold: ${Number(d.threshold)}       # fixed reorder threshold fallback; remove to keep "not configured -> onboarding task"\n`
		: `  # threshold: 5          # fixed reorder threshold fallback; unset = not monitored until configured\n`;
	return `# Planning layer — the per-variant fixed reorder threshold
# Deploy to: /etc/commerce/config/planning.yml
# Managed by the Commerce app (Webtop > Commerce > Planning).
#
# The reorder threshold is a FIXED unit count, EXPLICIT per variant (product editor /
# onboarding form, stored on the product's pim.planning overlay). This file holds only
# the GLOBAL DEFAULT a variant falls back to. The system never derives or rewrites it
# (no velocity, no proposal).
#
# When the materialized stock total drops below the threshold, the event-driven sweep
# raises ONE "Stock Check + Reorder" task. Leave threshold unset to keep the "not configured ->
# onboarding task" behaviour (the inventory alert configuration's unconfigured-threshold
# policy: prompting to set one, rather than staying silent).

defaults:
${thresholdLine}`;
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

function serializeReconcile(r: any): string {
	const schedules = (r.schedules || []).filter((s: any) => String(s.at || '').trim());
	let body = `# Shopify -> CMS reconciliation
# Deploy to: /etc/commerce/config/reconcile.yml
# Managed by the Commerce app (Webtop > Commerce > Reconciliation).

# Master switch for the reconciliation batch.
enabled: ${r.enabled === true}

# Diff page size: products fetched per page when scanning Shopify for changes.
maxPerRun: ${num(r.maxPerRun, 50)}

# Shopify is the single source of truth: reconciliation only refreshes the CMS mirror
# FROM Shopify (status / price) and records every run — there is no CMS->Shopify push.
# Inventory is not part of the diff scope; the full inventory audit is the Bulk job
# broker (inventory schedule scope).

# Diff throttle: leave reserveBudgetPercent of the cost bucket free for foreground ops; an
# optional fixed floor (ms) between per-product calls.
reserveBudgetPercent: ${num(r.reserveBudgetPercent, 50)}
minDelayMsPerCall: ${num(r.minDelayMsPerCall, 0)}

# Bulk job broker watchdog timeouts (minutes). Lower for testing.
bulkWatchdogTimeoutMinutes: ${num(r.bulkWatchdogTimeoutMinutes, 90)}
bulkProcessingTimeoutMinutes: ${num(r.bulkProcessingTimeoutMinutes, 180)}

# Additional wall-clock passes (HH:mm in UTC, fixed — independent of the server's
# timezone). scope: diff = products changed in Shopify since the last pass (status/price;
# cheap); inventory = a full inventory audit via the Bulk job broker. Empty = none.
schedules:`;
	if (!schedules.length) { body += ' []\n'; return body; }
	body += '\n';
	for (const s of schedules) {
		const scope = String(s.scope) === 'inventory' ? 'inventory' : 'diff';
		body += `  - at: "${esc(String(s.at).trim())}"\n    scope: ${scope}\n`;
	}
	return body;
}

function serializeIngest(g: any): string {
	const rep = g.replay || {};
	return `# Event ingestion (all-topics intake, multi-backend, replay)
# Deploy to: /etc/commerce/config/ingest.yml
# Managed by the Commerce app (Webtop > Commerce > Ingestion).

# Master switch for the replay batch (live ingestion is always on; this only governs
# automatic replay of failed events). Event-log retention/pruning lives in retention.yml.
enabled: ${g.enabled === true}

# Automatic replay of failed events.
replay:
  enabled: ${rep.enabled === true}
  maxAttempts: ${num(rep.maxAttempts, 5)}
  backoffMinutes: ${num(rep.backoffMinutes, 15)}
`;
}

function serializeRetention(r: any): string {
	// Each value is a number of days; 0 means keep forever (no pruning).
	return `# Data retention (automatic pruning of accumulated history stores)
# Deploy to: /etc/commerce/config/retention.yml
# Managed by the Commerce app (Webtop > Commerce > Maintenance > Retention).
#
# Each value is a number of DAYS; records older than that are pruned by the periodic
# housekeeping batch (commerce-housekeeping). 0 means keep forever (no pruning).
# Business data (orders / payments / refunds) is NOT auto-pruned here — it is removed
# only by the explicit, audited manual purge action in the same section.

# Master switch for the housekeeping batch. false = nothing is auto-pruned.
enabled: ${r.enabled === true}

eventLog: ${num(r.eventLog, 0)}
webhookMarkers: ${num(r.webhookMarkers, 0)}
bulkJobs: ${num(r.bulkJobs, 0)}
reconciliation: ${num(r.reconciliation, 0)}
health: ${num(r.health, 0)}
`;
}

function serializeBackorder(b: any): string {
	const notify = b.notify || {};
	return `# Backorder / pre-order management
# Deploy to: /etc/commerce/config/backorder.yml
# Managed by the Commerce app (Webtop > Commerce > Backorders).

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
# Managed by the Commerce app (Webtop > Commerce > Order review).

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
# Managed by the Commerce app (Webtop > Commerce > Refund review).
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

function serializeInventoryAlert(ia: any): string {
	const policy = String(ia.unconfiguredPolicy) === 'silent' ? 'silent' : 'prompt';
	return `# Inventory alert behaviour.
# Managed by the Commerce app (Webtop > Commerce > Inventory alert).
#
# unconfiguredPolicy — how to treat a variant that resolves to NO effective threshold
# (no per-variant planning value, no legacy manual override, and no default in planning.yml):
#   prompt  : raise the "Set Inventory Threshold" task so an operator sets it
#             (default; matches the public commerce.gsp promise). No alert until set.
#   silent  : do not monitor — no task, no alert.
# For a blanket baseline instead, set \`defaults.threshold\` in planning.yml.
unconfiguredPolicy: ${policy}

# Debounce window for the alert sweep, in SECONDS. Bursts of inventory_levels/update for the
# same item that arrive within the window collapse into a single evaluation.
#   0 : evaluate on every sweep heartbeat (~15s, the sweep's default period).
#   N : leave at least N seconds between sweeps. Values at or below the heartbeat behave like 0.
sweepDebounceSeconds: ${Math.max(0, num(ia.sweepDebounceSeconds, 0))}
`;
}

// UTF-8 safe base64 for multipart upload chunks.
function toBase64(text: string): string {
	const bytes = new TextEncoder().encode(text);
	let binary = '';
	for (const b of bytes) binary += String.fromCharCode(b);
	return btoa(binary);
}

// Grouped sidebar navigation. Each item key matches a section template + a dirty flag.
// Labels are i18n keys resolved at render time via the navGroups computed (see below).
const NAV_GROUPS = [
	{ labelKey: 'app.commerce.nav.group.connection', items: [
		{ key: 'shop', labelKey: 'app.commerce.nav.shop', icon: 'bi-shop' },
		{ key: 'notifications', labelKey: 'app.commerce.nav.notifications', icon: 'bi-bell' },
		{ key: 'webhooks', labelKey: 'app.commerce.nav.webhooks', icon: 'bi-broadcast' },
	] },
	{ labelKey: 'app.commerce.nav.group.intakeSync', items: [
		{ key: 'ingestion', labelKey: 'app.commerce.nav.ingestion', icon: 'bi-inbox' },
		{ key: 'reconciliation', labelKey: 'app.commerce.nav.reconciliation', icon: 'bi-arrow-left-right' },
	] },
	{ labelKey: 'app.commerce.nav.group.inventory', items: [
		{ key: 'locations', labelKey: 'app.commerce.nav.locations', icon: 'bi-geo-alt' },
		{ key: 'inventoryAlert', labelKey: 'app.commerce.nav.inventoryAlert', icon: 'bi-exclamation-triangle' },
		{ key: 'planning', labelKey: 'app.commerce.nav.planning', icon: 'bi-rulers' },
		{ key: 'backorders', labelKey: 'app.commerce.nav.backorders', icon: 'bi-hourglass-split' },
	] },
	{ labelKey: 'app.commerce.nav.group.workflows', items: [
		{ key: 'orderReview', labelKey: 'app.commerce.nav.orderReview', icon: 'bi-clipboard-check' },
		{ key: 'refundReview', labelKey: 'app.commerce.nav.refundReview', icon: 'bi-receipt' },
		{ key: 'tasks', labelKey: 'app.commerce.nav.tasks', icon: 'bi-list-check' },
	] },
	{ labelKey: 'app.commerce.nav.group.monitoring', items: [
		{ key: 'health', labelKey: 'app.commerce.nav.health', icon: 'bi-heart-pulse' },
	] },
	{ labelKey: 'app.commerce.nav.group.maintenance', items: [
		{ key: 'retention', labelKey: 'app.commerce.nav.retention', icon: 'bi-clock-history' },
	] },
];
// Section key → the dirty computed that tracks it (for the nav unsaved markers).
const SECTION_DIRTY: Record<string, string> = {
	shop: 'shopDirty', notifications: 'notifDirty', health: 'healthDirty', tasks: 'slaDirty',
	planning: 'planningDirty', locations: 'locationsDirty',
	ingestion: 'ingestDirty', reconciliation: 'reconcileDirty', retention: 'retentionDirty',
	inventoryAlert: 'inventoryAlertDirty',
	backorders: 'backorderDirty', orderReview: 'orderReviewDirty', refundReview: 'refundReviewDirty',
};

const App = {
	data() {
		return {
			instance: null as AnyInstance,
			content: null as AnyInstance,
			// Reactive localization snapshot — drives every t() binding so the app
			// repaints when the user switches language or a bundle is hot-reloaded.
			localization: createLocalizationSnapshot(),

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
			// Collapsible nav groups: group labelKey → expanded. All open by default.
			navGroupExpanded: NAV_GROUPS.reduce((acc, g) => { acc[g.labelKey] = true; return acc; }, {} as Record<string, boolean>),

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
				adminApi: { shopDomain: '', apiVersion: '', clientID: '', clientSecret: '' },
			},
			// Notification destinations (notifications.yml): the default channel set
			// plus an optional dedicated set per category (null = the category uses
			// the default set). notifCat is the set currently shown in the editor.
			notif: {
				sets: NOTIF_CATEGORIES.reduce((acc, c) => { acc[c] = null; return acc; },
					{ default: emptyNotifSet() } as Record<string, any>),
			},
			notifCat: 'default',
			// Channel whose "copy default" button is currently showing the
			// post-click checkmark ('' = none). Only one at a time.
			notifCopied: '',

			// Integration health monitor alert rules (health.yml).
			health: {
				enabled: true,
				hmacFailures: { enabled: true, cooldownMinutes: 30 },
				apiErrors: { enabled: true },
				routeErrors: { enabled: true },
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

			// Planning layer: the global default reorder threshold (planning.yml).
			// Per-variant thresholds live on each product (pim.planning) and are edited
			// in the product editor; the system never derives or rewrites them.
			planning: {
				defaults: { threshold: '' as any },
			},

			// Multi-location inventory & allocation (locations.yml).
			locations: {
				strategy: 'most_stock',
				priorityOrder: '',
				defaultSafetyStock: 0,
			},

			// Shopify -> CMS reconciliation (reconcile.yml).
			reconcile: {
				enabled: true,
				maxPerRun: 50,
				reserveBudgetPercent: 50,
				minDelayMsPerCall: 0,
				bulkWatchdogTimeoutMinutes: 90,
				bulkProcessingTimeoutMinutes: 180,
				schedules: [] as any[],
			},

			// Event ingestion replay (ingest.yml). Retention/pruning lives in retention.yml.
			ingest: { enabled: true, replay: { enabled: true, maxAttempts: 5, backoffMinutes: 15 } },

			// Data retention — automatic pruning of history stores (retention.yml). Each
			// value is a number of days; 0 means keep forever. Business data (orders /
			// payments / refunds) is NOT here — it is handled by the manual purge below.
			retention: { enabled: true, eventLog: 30, webhookMarkers: 30, bulkJobs: 90, reconciliation: 365, health: 400 },

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

			// Inventory alert behaviour (inventory-alert.yml): unconfigured-threshold
			// policy and the sweep debounce window (seconds).
			inventoryAlert: { unconfiguredPolicy: 'prompt', sweepDebounceSeconds: 0 },

			// Webhook registration — an ACTION panel, NOT a saved config section. The
			// operator pastes the public callback URL and clicks Register; the endpoint
			// create-or-updates every required Shopify topic subscription idempotently.
			// State here is the endpoint's GET snapshot (current subscriptions +
			// suggested URL + Admin-API gate) plus the last POST's per-topic results.
			webhooks: {
				loaded: false,
				checked: false,           // the first status GET has completed (success OR failure)
				loading: false,
				running: false,
				enabled: false,           // Admin API configured (from the endpoint)
				shopDomain: '',
				apiVersion: '',
				callbackUrl: '',          // v-model — edited by the operator
				suggestedCallbackUrl: '', // placeholder / prefill from the endpoint
				current: [] as any[],     // [{ topic, subscribed, callbackUrl }]
				results: [] as any[],     // [{ topic, action, message }]
				compliance: WEBHOOK_COMPLIANCE_TOPICS.slice(),
				ran: false,
				error: '',
			},

			// Manual business-data purge — an ACTION panel, NOT a saved config section.
			// The operator enters a day count, previews the affected orders/payments/
			// refunds, then confirms an irreversible, audited delete. State mirrors the
			// endpoint's GET preview + history; the actual delete is a fire-and-forget POST.
			retentionPurge: {
				loaded: false,
				days: 3650,               // v-model — the cutoff age in days
				previewing: false,
				preview: null as any,     // { orders, payments, refunds, cutoff } or null
				running: false,
				loadingHistory: false,
				history: [] as any[],     // [{ at, actor, days, orders, payments, refunds, status }]
				error: '',
				confirm: { visible: false, orders: 0, payments: 0, refunds: 0, cutoff: '' },
			},

			// Snapshots for dirty detection (the in-memory edit model).
			_origShop: '',
			_origNotif: '',
			_origHealth: '',
			_origSla: '',
			_origPlanning: '',
			_origLocations: '',
			_origReconcile: '',
			_origIngest: '',
			_origRetention: '',
			_origBackorder: '',
			_origOrderReview: '',
			_origRefundReview: '',
			_origInventoryAlert: '',
			_base: '' as string,
			_messageListener: null as any,
			_toastTimer: null as any,
			_notifCopiedTimer: null as any,
		};
	},

	computed: {
		shopDirty(): boolean { return JSON.stringify(this.shop) !== this._origShop; },
		notifDirty(): boolean { return JSON.stringify(this.notif) !== this._origNotif; },
		healthDirty(): boolean { return JSON.stringify(this.health) !== this._origHealth; },
		slaDirty(): boolean { return JSON.stringify(this.sla) !== this._origSla; },
		planningDirty(): boolean { return JSON.stringify(this.planning) !== this._origPlanning; },
		locationsDirty(): boolean { return JSON.stringify(this.locations) !== this._origLocations; },
		reconcileDirty(): boolean { return JSON.stringify(this.reconcile) !== this._origReconcile; },
		ingestDirty(): boolean { return JSON.stringify(this.ingest) !== this._origIngest; },
		retentionDirty(): boolean { return JSON.stringify(this.retention) !== this._origRetention; },
		backorderDirty(): boolean { return JSON.stringify(this.backorder) !== this._origBackorder; },
		orderReviewDirty(): boolean { return JSON.stringify(this.orderReview) !== this._origOrderReview; },
		refundReviewDirty(): boolean { return JSON.stringify(this.refundReview) !== this._origRefundReview; },
		inventoryAlertDirty(): boolean { return JSON.stringify(this.inventoryAlert) !== this._origInventoryAlert; },
		hasChanges(): boolean {
			return this.shopDirty || this.notifDirty || this.healthDirty || this.slaDirty || this.planningDirty || this.locationsDirty
				|| this.reconcileDirty || this.ingestDirty || this.retentionDirty || this.backorderDirty
				|| this.orderReviewDirty || this.refundReviewDirty || this.inventoryAlertDirty;
		},

		// The Admin API is required, but "not configured yet" (all four fields empty) is
		// allowed — the integration degrades with a warning until it is filled in. Only a
		// PARTIAL config (some fields filled, not all) is invalid. Drives the inline field
		// markers, the save guard and the status hint.
		adminApiInvalid(): boolean {
			const a = this.shop.adminApi;
			const filled = [a.shopDomain, a.apiVersion, a.clientID, a.clientSecret]
				.filter((v: any) => String(v == null ? '' : v).trim() !== '').length;
			return filled > 0 && filled < 4;
		},
		// An enabled channel must have its required connection fields — checked in
		// the default set AND every dedicated category set. Drives the inline
		// markers, the save guard and the status hint (mis-config guard).
		notifInvalid(): boolean {
			const blank = (v: any) => !String(v == null ? '' : v).trim();
			const bad = (n: any) => {
				if (!n) return false;
				if (n.slack.enabled && blank(n.slack.webhookUrl)) return true;
				if (n.discord.enabled && blank(n.discord.webhookUrl)) return true;
				if (n.teams.enabled && blank(n.teams.webhookUrl)) return true;
				if (n.line.enabled && (blank(n.line.accessToken) || blank(n.line.to))) return true;
				if (n.webhook.enabled && blank(n.webhook.url)) return true;
				if (n.email.enabled && (blank(n.email.smtpHost) || blank(n.email.from) || blank(n.email.to))) return true;
				return false;
			};
			const sets = this.notif.sets || {};
			if (bad(sets.default)) return true;
			for (const c of NOTIF_CATEGORIES) {
				if (bad(sets[c])) return true;
			}
			return false;
		},
		canSave(): boolean { return this.hasChanges && !this.adminApiInvalid && !this.notifInvalid && !this.saving; },
		// Expose the static nav structure to the template. Labels are NOT resolved
		// here: the template renders them with `t(g.labelKey)` / `t(s.labelKey)`
		// directly, so they repaint reactively on a language change like every
		// other binding, and the expand-state map stays keyed by the stable,
		// language-independent labelKey.
		navGroups(): any {
			return NAV_GROUPS;
		},
	},

	methods: {
		// ---- i18n ------------------------------------------------------------
		// Reactive translation: reading the localization snapshot inside translate()
		// subscribes every `{{ t(...) }}` binding so the UI repaints instantly when
		// the user switches language or a bundle hot-reloads.
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},

		// ---- Lifecycle -------------------------------------------------------
		onMounted() {
			const vm = this;

			// The shell pushes theme changes and localization events to the iframe via
			// postMessage; fold locale/bundle changes into the snapshot first so the
			// UI relocalizes live, then handle other messages.
			vm._messageListener = (event: MessageEvent) => {
				const data: any = event.data || {};
				// Fold locale/timezone/currency changes and i18n bundle hot-reloads
				// into the reactive snapshot so the UI re-localizes live.
				if (handleLocalizationMessage(data.type, vm.localization, vm.instance)) return;
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

				// Snapshot the effective Localization preference so the first paint
				// is already in the user's language / region.
				refreshLocalization(vm.localization, vm.instance);

				try { instance.windowTitle = vm.t('app.commerce.title', undefined, 'Commerce'); } catch (_) {}

				// Warn before discarding unsaved edits on window close, using the
				// shared Webtop dialog (same look as the cms0 text-editor).
				if (typeof instance.setBeforeCloseCallback === 'function') {
					instance.setBeforeCloseCallback(async () => vm.confirmClose());
				}

				await vm.loadUiState();
				// Resolve the cgi base URL up front so the Webhooks action panel can
				// reach its endpoint the moment the operator opens that section.
				await vm.resolveBase();
				await vm.loadAll();

				vm.$nextTick(() => { try { instance.notifyLaunched(); } catch (_) {} });
			};
		},

		onUnmount() {
			if (this._messageListener) window.removeEventListener('message', this._messageListener);
			if (this._toastTimer) clearTimeout(this._toastTimer);
			if (this._notifCopiedTimer) clearTimeout(this._notifCopiedTimer);
			// Detach any in-flight sidebar resize listeners.
			if (this._boundSidebarResizeMove) document.removeEventListener('mousemove', this._boundSidebarResizeMove);
			if (this._boundSidebarResizeUp) document.removeEventListener('mouseup', this._boundSidebarResizeUp);
		},

		selectSection(section: string) {
			this.section = section;
			// The Webhooks panel is an action console, not a config file: lazy-load its
			// current-state snapshot the first time it is opened.
			if (section === 'webhooks' && !this.webhooks.loaded && !this.webhooks.loading) {
				this.loadWebhooks();
			}
			// The manual purge panel is an action console too: lazy-load its recent
			// purge history the first time Retention is opened.
			if (section === 'retention' && !this.retentionPurge.loaded && !this.retentionPurge.loadingHistory) {
				this.loadPurgeHistory();
			}
			// Reset the main panel to the top after the new section renders, so a
			// tab switch always starts at the top (the .content-main island is the
			// scroll container).
			this.$nextTick(() => {
				const el = document.querySelector('.content-main');
				if (el) el.scrollTop = 0;
			});
		},

		// ---- Left sidebar (toggle / collapse / resize) -----------------------
		toggleSidebar() {
			this.sidebarVisible = !this.sidebarVisible;
			this.persistUiState();
		},
		toggleNavGroup(labelKey: string) {
			this.navGroupExpanded[labelKey] = !this.navGroupExpanded[labelKey];
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
			addSchedule() { this.reconcile.schedules.push({ at: '00:00', scope: 'diff' }); },
			removeSchedule(i: number) { this.reconcile.schedules.splice(i, 1); },
			// Schedule times are stored/evaluated in UTC (scheduleReconcile.groovy) but shown
			// and edited in the operator's effective Preferences time zone — like the
			// commerce-events date filters — so `at` is converted to the display zone for the
			// `<input type="time">` and converted back to UTC on change. Falls back to the
			// browser zone when Preferences hasn't loaded.
			effectiveTimeZone(): string {
				let zone = this.localization.timeZone;
				if (!zone) {
					try { zone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; } catch (_) { zone = 'UTC'; }
				}
				return zone;
			},
			scheduleDisplayTime(at: string): string {
				return utcTimeToZone(at, this.effectiveTimeZone());
			},
			onScheduleTimeChange(s: any, value: string) {
				s.at = zoneTimeToUtc(value, this.effectiveTimeZone());
			},

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
			if (!this.content) throw new Error(this.t('app.commerce.error.contentUnavailable', undefined, 'Content service is unavailable.'));
			const info: any = await this.content.initiateMultipartUpload();
			const uploadID = info?.uploadId ?? info?.uploadID ?? info?.id ?? info;
			await this.content.appendMultipartUploadChunk(uploadID, toBase64(text));
			await this.content.completeMultipartUpload(uploadID, dir, file, YAML_MIME, true);
		},

		async loadAll() {
			try {
				const [shopText, notifText, healthText, slaText, planningText, locationsText,
					reconcileText, ingestText, retentionText, backorderText, orderReviewText, refundReviewText, inventoryAlertText] = await Promise.all([
					this.readText(SHOPIFY_PATH),
					this.readText(NOTIF_PATH),
					this.readText(HEALTH_PATH),
					this.readText(SLA_PATH),
					this.readText(PLANNING_PATH),
					this.readText(LOCATIONS_PATH),
					this.readText(RECONCILE_PATH),
					this.readText(INGEST_PATH),
					this.readText(RETENTION_PATH),
					this.readText(BACKORDER_PATH),
					this.readText(ORDER_REVIEW_PATH),
					this.readText(REFUND_REVIEW_PATH),
					this.readText(INVENTORY_ALERT_PATH),
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
						shopDomain: String(a.shopDomain ?? s.shopDomain ?? ''),
						apiVersion: String(a.apiVersion ?? s.apiVersion ?? ''),
						clientID: String(a.clientID ?? s.clientID ?? ''),
						clientSecret: String(a.clientSecret ?? s.clientSecret ?? ''),
					},
				};

				// Notifications: a `default` channel set plus optional per-category
				// sets under `categories` (nested one level deeper than the simple
				// files, hence parseYaml).
				const nRaw = parseYaml(notifText || '');
				const n = (nRaw && typeof nRaw === 'object' && !Array.isArray(nRaw)) ? nRaw : {};
				const nCats = (n.categories && typeof n.categories === 'object') ? n.categories : {};
				const nSets: Record<string, any> = { default: readNotifSet(n.default) };
				for (const c of NOTIF_CATEGORIES) {
					nSets[c] = (nCats[c] && typeof nCats[c] === 'object') ? readNotifSet(nCats[c]) : null;
				}
				this.notif = { sets: nSets };

				const h = parseSimpleYaml(healthText || '');
				const hsec = (key: string) => (h[key] && typeof h[key] === 'object') ? h[key] : {};
				const hmac = hsec('hmacFailures'), api = hsec('apiErrors');
				const route = hsec('routeErrors');
				// A missing health.yml defaults to the recommended rules (on).
				const hasHealth = !!healthText;
				this.health = {
					enabled: hasHealth ? (h.enabled === true) : true,
					hmacFailures: {
						enabled: hasHealth ? (hmac.enabled === true) : true,
						cooldownMinutes: Number(hmac.cooldownMinutes) || 30,
					},
					apiErrors: {
						enabled: hasHealth ? (api.enabled === true) : true,
					},
					routeErrors: {
						enabled: hasHealth ? (route.enabled === true) : true,
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

				const pl = parseSimpleYaml(planningText || '');
				const plsec = (key: string) => ((pl as any)[key] && typeof (pl as any)[key] === 'object') ? (pl as any)[key] : {};
				const plDefaults = plsec('defaults');
				this.planning = {
					defaults: {
						threshold: (plDefaults.threshold == null || plDefaults.threshold === '') ? '' : Number(plDefaults.threshold),
					},
				};

				const loc = parseSimpleYaml(locationsText || '');
				this.locations = {
					strategy: String(loc.strategy || 'most_stock'),
					priorityOrder: String(loc.priorityOrder || ''),
					defaultSafetyStock: Number(loc.defaultSafetyStock) || 0,
				};

				const obj = (v: any) => (v && typeof v === 'object' && !Array.isArray(v)) ? v : {};

				const rc = parseYaml(reconcileText || '');
				const hasRc = !!reconcileText;
				this.reconcile = {
					enabled: hasRc ? (rc.enabled === true) : true,
					maxPerRun: Number(rc.maxPerRun) || 50,
					reserveBudgetPercent: Number.isFinite(Number(rc.reserveBudgetPercent)) ? Number(rc.reserveBudgetPercent) : 50,
					minDelayMsPerCall: Number.isFinite(Number(rc.minDelayMsPerCall)) ? Number(rc.minDelayMsPerCall) : 0,
					bulkWatchdogTimeoutMinutes: Number(rc.bulkWatchdogTimeoutMinutes) || 90,
					bulkProcessingTimeoutMinutes: Number(rc.bulkProcessingTimeoutMinutes) || 180,
					schedules: (Array.isArray(rc.schedules) ? rc.schedules : []).map((s: any) => ({
						at: String((s && s.at) || '').trim(),
						scope: (s && String(s.scope) === 'inventory') ? 'inventory' : 'diff',
					})),
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
					},
				};

				// Retention (days per store; 0/absent = keep forever). A missing file falls
				// back to the shipped defaults so a fresh install prunes sensibly.
				const rt = parseYaml(retentionText || '');
				const hasRt = !!retentionText;
				const rtNum = (v: any, dflt: number) => Number.isFinite(Number(v)) ? Math.max(0, Math.trunc(Number(v))) : dflt;
				this.retention = {
					enabled: hasRt ? (rt.enabled === true) : true,
					eventLog: rtNum(rt.eventLog, 30),
					webhookMarkers: rtNum(rt.webhookMarkers, 30),
					bulkJobs: rtNum(rt.bulkJobs, 90),
					reconciliation: rtNum(rt.reconciliation, 365),
					health: rtNum(rt.health, 400),
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

				const ia = parseSimpleYaml(inventoryAlertText || '');
				const iaPolicy = String(ia.unconfiguredPolicy || 'prompt').trim().toLowerCase();
				this.inventoryAlert = {
					unconfiguredPolicy: (iaPolicy === 'silent') ? 'silent' : 'prompt',
					sweepDebounceSeconds: Math.max(0, Number(ia.sweepDebounceSeconds) || 0),
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
				this.status = this.t('app.commerce.status.adminApiInvalid', undefined, 'Enter the shop domain, API version, client ID and client secret, or turn off the Admin API.');
				this.statusKind = 'err';
				this.showToast(this.status, true);
				return;
			}

			// Same guard for notification channels: never persist an enabled
			// channel that is missing its required connection fields.
			if (this.notifInvalid) {
				this.section = 'notifications';
				this.status = this.t('app.commerce.status.notifInvalid', undefined, 'Complete the required fields for each enabled channel, or turn it off.');
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
				if (this.planningDirty) {
					await this.writeText(CONFIG_DIR, PLANNING_FILE, serializePlanning(this.planning));
				}
				if (this.locationsDirty) {
					await this.writeText(CONFIG_DIR, LOCATIONS_FILE, serializeLocations(this.locations));
				}
				if (this.reconcileDirty) {
					await this.writeText(CONFIG_DIR, RECONCILE_FILE, serializeReconcile(this.reconcile));
				}
				if (this.ingestDirty) {
					await this.writeText(CONFIG_DIR, INGEST_FILE, serializeIngest(this.ingest));
				}
				if (this.retentionDirty) {
					await this.writeText(CONFIG_DIR, RETENTION_FILE, serializeRetention(this.retention));
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
				if (this.inventoryAlertDirty) {
					await this.writeText(CONFIG_DIR, INVENTORY_ALERT_FILE, serializeInventoryAlert(this.inventoryAlert));
				}
				this.snapshot();
				this.status = this.t('app.commerce.status.saved', undefined, 'All changes saved.');
				this.statusKind = 'ok';
			} catch (e: any) {
				const msg = (e && e.message) ? e.message : String(e);
				this.status = this.t('app.commerce.status.saveFailed', { message: msg }, 'Save failed: {message}');
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
			this._origPlanning = JSON.stringify(this.planning);
			this._origLocations = JSON.stringify(this.locations);
			this._origReconcile = JSON.stringify(this.reconcile);
			this._origIngest = JSON.stringify(this.ingest);
			this._origRetention = JSON.stringify(this.retention);
			this._origBackorder = JSON.stringify(this.backorder);
			this._origOrderReview = JSON.stringify(this.orderReview);
			this._origRefundReview = JSON.stringify(this.refundReview);
			this._origInventoryAlert = JSON.stringify(this.inventoryAlert);
		},

		showToast(message: string, isError: boolean) {
			this.toast = message || '';
			this.toastError = !!isError;
			if (this._toastTimer) clearTimeout(this._toastTimer);
			this._toastTimer = setTimeout(() => { this.toast = ''; }, 4000);
		},

		// ---- Webhook registration (action panel) -----------------------------
		// This section talks to a cgi endpoint directly (not the content service
		// used by the config editors), so it needs the cgi base URL + small JSON
		// fetch helpers — mirroring the commerce-import console. Kept entirely out
		// of the config Save flow.
		async resolveBase() {
			let ws: string | null = null;
			try { ws = this.instance?.api?.workspace || null; } catch (_) {}
			if (ws) { this._base = `/bin/cms.cgi/${ws}`; return; }
			try {
				const node = await this.instance.api.content.getNode('/content');
				const m = String(node?.downloadUrl || '').match(/\/bin\/[^/]*cgi\/([^/?#]+)/);
				if (m) { this._base = `/bin/cms.cgi/${m[1]}`; return; }
			} catch (_) {}
			this._base = '';
		},
		async getJson(path: string): Promise<any> {
			const res = await fetch(`${this._base}${path}`, { headers: { Accept: 'application/json' }, credentials: 'same-origin' });
			if (!res.ok) throw new Error(`Request failed (${res.status})`);
			return res.json();
		},
		async postJson(path: string, body: any): Promise<{ status: number; json: any }> {
			const res = await fetch(`${this._base}${path}`, {
				method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
				credentials: 'same-origin', body: JSON.stringify(body),
			});
			const json = await res.json().catch(() => ({}));
			return { status: res.status, json };
		},

		// GET the current subscription snapshot + suggested callback URL. Lazy —
		// called the first time the Webhooks section is opened (and by Refresh).
		async loadWebhooks() {
			const vm = this;
			vm.webhooks.loading = true;
			vm.webhooks.error = '';
			try {
				if (!vm._base) await vm.resolveBase();
				const j = await vm.getJson(WEBHOOKS_SCRIPT);
				vm.webhooks.enabled = j.enabled === true;
				// The Admin API can be enabled but the live subscription LISTING still fail
				// (e.g. the app's token lacks the read_webhooks scope) — surface that real
				// error instead of the misleading "Admin API not configured" banner.
				if (j.listError) vm.webhooks.error = String(j.listError);
				vm.webhooks.shopDomain = String(j.shopDomain || '');
				vm.webhooks.apiVersion = String(j.apiVersion || '');
				vm.webhooks.suggestedCallbackUrl = String(j.suggestedCallbackUrl || '');
				vm.webhooks.current = vm.$markRaw(Array.isArray(j.operational) ? j.operational : []);
				if (Array.isArray(j.compliance) && j.compliance.length) vm.webhooks.compliance = vm.$markRaw(j.compliance);
				// Prefill the URL once (an already-registered callbackUrl wins over the
				// suggestion); never clobber an edit the operator has already made.
				if (!vm.webhooks.callbackUrl) {
					vm.webhooks.callbackUrl = String(j.callbackUrl || j.suggestedCallbackUrl || '');
				}
				vm.webhooks.loaded = true;
			} catch (e: any) {
				vm.webhooks.error = (e && e.message) ? e.message : String(e);
			} finally {
				vm.webhooks.loading = false;
				vm.webhooks.checked = true;
			}
		},

		// POST the callback URL → the endpoint create-or-updates every required topic
		// subscription idempotently and returns per-topic results + the refreshed
		// current state.
		async runWebhookRegister() {
			const vm = this;
			if (vm.webhooks.running) return;
			if (!vm.webhooks.enabled) {
				vm.showToast(vm.t('app.commerce.webhooks.adminApiOff', undefined, 'Configure the Shopify Admin API first (Shop section).'), true);
				return;
			}
			const url = String(vm.webhooks.callbackUrl || '').trim();
			if (!url) {
				vm.showToast(vm.t('app.commerce.webhooks.urlRequired', undefined, 'Enter the callback URL.'), true);
				return;
			}
			vm.webhooks.running = true;
			vm.status = '';
			vm.statusKind = '';
			try {
				if (!vm._base) await vm.resolveBase();
				const { status, json } = await vm.postJson(WEBHOOKS_SCRIPT, { callbackUrl: url });
				if (status === 200 || status === 202) {
					vm.webhooks.results = vm.$markRaw(Array.isArray(json.results) ? json.results : []);
					vm.webhooks.ran = true;
					// The POST returns per-topic results + summary (not the list); refresh the
					// current-state table from a fresh GET so the "subscribed" column updates.
					await vm.loadWebhooks();
					const errors = vm.webhooks.results.filter((r: any) => String(r && r.action) === 'error').length;
					if (errors > 0) {
						vm.status = vm.t('app.commerce.webhooks.doneWithErrors', { count: errors }, 'Completed with {count} error(s).');
						vm.statusKind = 'err';
						vm.showToast(vm.status, true);
					} else {
						vm.status = vm.t('app.commerce.webhooks.done', undefined, 'Webhook subscriptions registered / updated.');
						vm.statusKind = 'ok';
						vm.showToast(vm.status, false);
					}
				} else {
					const msg = (json && (json.message || json.error)) ? (json.message || json.error) : String(status);
					vm.showToast(vm.t('app.commerce.webhooks.failed', { message: msg }, 'Registration failed: {message}'), true);
				}
			} catch (e: any) {
				vm.showToast(e?.message || vm.t('app.commerce.webhooks.failed', { message: '?' }, 'Registration failed.'), true);
			} finally {
				vm.webhooks.running = false;
			}
		},

		// Map a per-topic action to the shared status-pill palette / a localized label.
		webhookActionClass(action: any): string {
			const v = String(action || '').toLowerCase();
			if (v === 'created') return 'st-ok';
			if (v === 'updated') return 'st-received';
			if (v === 'error') return 'st-error';
			return 'st-report'; // skipped / unknown
		},
		webhookActionLabel(action: any): string {
			const v = String(action || '').toLowerCase();
			if (v === 'created') return this.t('app.commerce.webhooks.action.created', undefined, 'Created');
			if (v === 'updated') return this.t('app.commerce.webhooks.action.updated', undefined, 'Updated');
			if (v === 'skipped') return this.t('app.commerce.webhooks.action.skipped', undefined, 'Skipped');
			if (v === 'error') return this.t('app.commerce.webhooks.action.error', undefined, 'Error');
			return String(action || '—');
		},

		// ---- Manual business-data purge (action panel) -----------------------
		// Mirrors the Webhooks console: it talks to a cgi endpoint directly (never
		// the config Save flow). GET ?days=N previews the affected counts and
		// returns recent purge history; POST { days } triggers the irreversible,
		// audited delete as the operator.
		purgeDaysValid(): boolean {
			const n = Number(this.retentionPurge.days);
			return Number.isFinite(n) && Math.trunc(n) >= 1;
		},
		async loadPurgeHistory() {
			const vm = this;
			vm.retentionPurge.loadingHistory = true;
			vm.retentionPurge.error = '';
			try {
				if (!vm._base) await vm.resolveBase();
				const j = await vm.getJson(RETENTION_PURGE_SCRIPT);
				vm.retentionPurge.history = Array.isArray(j?.history) ? j.history : [];
				vm.retentionPurge.loaded = true;
			} catch (e: any) {
				vm.retentionPurge.error = (e && e.message) ? e.message : String(e);
			} finally {
				vm.retentionPurge.loadingHistory = false;
			}
		},
		// Fetch the affected counts for the current day count without deleting anything.
		async previewPurge() {
			const vm = this;
			if (!vm.purgeDaysValid()) {
				vm.showToast(vm.t('app.commerce.retention.purge.invalidDays', undefined, 'Enter a number of days (1 or more).'), true);
				return;
			}
			vm.retentionPurge.previewing = true;
			vm.retentionPurge.error = '';
			try {
				if (!vm._base) await vm.resolveBase();
				const days = Math.trunc(Number(vm.retentionPurge.days));
				const j = await vm.getJson(`${RETENTION_PURGE_SCRIPT}?days=${days}`);
				vm.retentionPurge.preview = {
					orders: Number(j?.orders) || 0,
					payments: Number(j?.payments) || 0,
					refunds: Number(j?.refunds) || 0,
					cutoff: String(j?.cutoff || ''),
				};
			} catch (e: any) {
				vm.retentionPurge.error = (e && e.message) ? e.message : String(e);
				vm.retentionPurge.preview = null;
			} finally {
				vm.retentionPurge.previewing = false;
			}
		},
		// Open the confirm modal — always preview first so the operator sees the
		// exact cutoff date and counts before committing to an irreversible delete.
		async openPurgeConfirm() {
			const vm = this;
			if (!vm.purgeDaysValid()) {
				vm.showToast(vm.t('app.commerce.retention.purge.invalidDays', undefined, 'Enter a number of days (1 or more).'), true);
				return;
			}
			await vm.previewPurge();
			if (!vm.retentionPurge.preview) return; // preview failed; error already shown
			const p = vm.retentionPurge.preview;
			vm.retentionPurge.confirm = {
				visible: true,
				orders: p.orders, payments: p.payments, refunds: p.refunds, cutoff: p.cutoff,
			};
		},
		cancelPurgeConfirm() {
			this.retentionPurge.confirm.visible = false;
		},
		// Fire the delete. The endpoint returns the deleted counts (synchronous —
		// the purge runs to completion so the operator gets a real tally).
		async confirmPurge() {
			const vm = this;
			vm.retentionPurge.confirm.visible = false;
			if (!vm.purgeDaysValid()) return;
			vm.retentionPurge.running = true;
			vm.retentionPurge.error = '';
			try {
				if (!vm._base) await vm.resolveBase();
				const days = Math.trunc(Number(vm.retentionPurge.days));
				const { status, json } = await vm.postJson(RETENTION_PURGE_SCRIPT, { days });
				if (status < 200 || status >= 300) {
					throw new Error((json && json.error) ? json.error : `Request failed (${status})`);
				}
				vm.showToast(vm.t('app.commerce.retention.purge.done', {
					orders: Number(json?.orders) || 0,
					payments: Number(json?.payments) || 0,
					refunds: Number(json?.refunds) || 0,
				}, 'Purge complete.'), false);
				vm.retentionPurge.preview = null;
				await vm.loadPurgeHistory();
			} catch (e: any) {
				const msg = (e && e.message) ? e.message : String(e);
				vm.showToast(vm.t('app.commerce.retention.purge.failed', { message: msg }, 'Purge failed.'), true);
			} finally {
				vm.retentionPurge.running = false;
			}
		},

		// ---- wt-select popups (shell menu anchored to the trigger) -----------
		// House convention: the shell apps have no native <select>. Each opener
		// mirrors content-browser's Date filter (openFilterDateDropdown) — it
		// anchors a shell popup to the trigger's rect and writes the chosen id
		// back to the same model the <select> used. A paired *Label helper
		// renders the current value inside the trigger button.
		// --- Notification category set editing -------------------------------
		// The display label for a channel-set key ("default" or a category).
		notifCatLabel(key: string): string {
			if (key === 'default') return this.t('app.commerce.notifications.set.default');
			return this.t('app.commerce.notifications.category.' + key);
		},
		// Choose the channel set shown in the editor: the default set or any
		// category. Categories that carry their own dedicated set are flagged
		// with a secondary line in the list (mirrors the trigger's dot).
		async openNotifCatMenu(event: MouseEvent) {
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !this.instance) return;
			const rect = trigger.getBoundingClientRect();
			const cur = this.notifCat;
			const mark = this.t('app.commerce.notifications.category.customMark');
			const items = [
				{ id: 'default', label: this.t('app.commerce.notifications.set.default'), selected: cur === 'default' },
				...NOTIF_CATEGORIES.map((c) => ({
					id: c,
					label: this.notifCatLabel(c),
					description: this.notif.sets[c] ? mark : undefined,
					selected: cur === c,
				})),
			];
			const handle = this.instance.popup.open({ anchor: rect, placement: 'bottom-start', minWidth: rect.width, items });
			const result = await handle.result;
			if (result == null) return;
			this.notifCat = String(result);
		},
		// Switch the current category between "use the default set" (null) and
		// "use a dedicated set". The dedicated set starts EMPTY (all channels off)
		// — the operator fills it in, or copies the default values per channel.
		setNotifCustom(on: boolean) {
			if (this.notifCat === 'default') return;
			this.notif.sets[this.notifCat] = on ? emptyNotifSet() : null;
		},
		// Copy one channel's settings from the default set into the current
		// category's dedicated set (explicit action; sets stay independent).
		copyNotifDefault(type: string) {
			const set = this.notif.sets[this.notifCat];
			if (this.notifCat === 'default' || !set) return;
			set[type] = JSON.parse(JSON.stringify(this.notif.sets.default[type]));
			// Confirm the copy on the clicked button: its icon becomes a
			// checkmark until the timer expires. Re-arming the timer keeps a
			// rapid second click from cutting the first one's feedback short.
			if (this._notifCopiedTimer) clearTimeout(this._notifCopiedTimer);
			this.notifCopied = type;
			this._notifCopiedTimer = setTimeout(() => {
				this.notifCopied = '';
				this._notifCopiedTimer = null;
			}, 1500);
		},

		async openEmailSecurityMenu(event: MouseEvent) {
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !this.instance) return;
			const set = this.notif.sets[this.notifCat];
			if (!set) return;
			const rect = trigger.getBoundingClientRect();
			const cur = set.email.security;
			const items = [
				{ id: 'starttls', label: this.t('app.commerce.notifications.email.security.starttls'), selected: cur === 'starttls' },
				{ id: 'ssl', label: this.t('app.commerce.notifications.email.security.ssl'), selected: cur === 'ssl' },
				{ id: 'none', label: this.t('app.commerce.notifications.email.security.none'), selected: cur === 'none' },
			];
			const handle = this.instance.popup.open({ anchor: rect, placement: 'bottom-start', minWidth: rect.width, items });
			const result = await handle.result;
			if (result == null) return;
			set.email.security = String(result);
		},
		emailSecurityLabel(v: string): string {
			switch (v) {
				case 'ssl': return this.t('app.commerce.notifications.email.security.ssl');
				case 'none': return this.t('app.commerce.notifications.email.security.none');
				default: return this.t('app.commerce.notifications.email.security.starttls');
			}
		},

		async openStrategyMenu(event: MouseEvent) {
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !this.instance) return;
			const rect = trigger.getBoundingClientRect();
			const cur = this.locations.strategy;
			const items = [
				{ id: 'most_stock', label: this.t('app.commerce.locations.strategy.mostStock'), selected: cur === 'most_stock' },
				{ id: 'priority', label: this.t('app.commerce.locations.strategy.priority'), selected: cur === 'priority' },
			];
			const handle = this.instance.popup.open({ anchor: rect, placement: 'bottom-start', minWidth: rect.width, items });
			const result = await handle.result;
			if (result == null) return;
			this.locations.strategy = String(result);
		},
		strategyLabel(v: string): string {
			switch (v) {
				case 'priority': return this.t('app.commerce.locations.strategy.priority');
				default: return this.t('app.commerce.locations.strategy.mostStock');
			}
		},

		async openScopeMenu(event: MouseEvent, s: any) {
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !this.instance) return;
			const rect = trigger.getBoundingClientRect();
			const cur = s.scope;
			const items = [
				{ id: 'diff', label: this.t('app.commerce.reconciliation.schedules.scope.diff'), selected: cur === 'diff' },
				{ id: 'inventory', label: this.t('app.commerce.reconciliation.schedules.scope.inventory'), selected: cur === 'inventory' },
			];
			const handle = this.instance.popup.open({ anchor: rect, placement: 'bottom-start', minWidth: rect.width, items });
			const result = await handle.result;
			if (result == null) return;
			s.scope = String(result);
		},
		scopeLabel(v: string): string {
			switch (v) {
				case 'inventory': return this.t('app.commerce.reconciliation.schedules.scope.inventory');
				default: return this.t('app.commerce.reconciliation.schedules.scope.diff');
			}
		},

		async openUnconfiguredPolicyMenu(event: MouseEvent) {
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !this.instance) return;
			const rect = trigger.getBoundingClientRect();
			const cur = this.inventoryAlert.unconfiguredPolicy;
			const items = [
				{ id: 'prompt', label: this.t('app.commerce.inventoryAlert.unconfiguredPolicy.prompt'), selected: cur === 'prompt' },
				{ id: 'silent', label: this.t('app.commerce.inventoryAlert.unconfiguredPolicy.silent'), selected: cur === 'silent' },
			];
			const handle = this.instance.popup.open({ anchor: rect, placement: 'bottom-start', minWidth: rect.width, items });
			const result = await handle.result;
			if (result == null) return;
			this.inventoryAlert.unconfiguredPolicy = String(result);
		},
		unconfiguredPolicyLabel(v: string): string {
			switch (v) {
				case 'silent': return this.t('app.commerce.inventoryAlert.unconfiguredPolicy.silent');
				default: return this.t('app.commerce.inventoryAlert.unconfiguredPolicy.prompt');
			}
		},
	},
};

VDOM.createApp(App).mount('#app');
