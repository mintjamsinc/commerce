// Commerce Dashboard — a read-only, real-time operational overview for the
// headless-commerce integration: sales, inventory, tasks/SLA and integration
// health.
//
// Data comes from a single server-side aggregation endpoint
// (/content/commerce/endpoints/dashboard.groovy) which combines commerce.Dashboard
// (sales + inventory), commerce.Health (integration health) and the BPMN engine
// + commerce.TaskSla (open-task / SLA counts).
//
// "Real time": the app subscribes to content changes under /content/commerce via
// the Webtop event hub (SSE-backed GraphQL subscriptions) and refetches, and
// also polls on a slow interval to pick up task/SLA changes (which live in the
// BPMN engine, not JCR).

import { VDOM } from '@mintjamsinc/ichigojs';

// Type-only: the shell passes a fully-featured ApplicationInstance at launch.
type AnyInstance = any;

const DASHBOARD_SCRIPT = '/content/commerce/endpoints/dashboard.groovy';
// Commerce Operations app (app.yml `identifier`) — the operator console the
// dashboard drills into for the outbound-sync / reconciliation / event surfaces.
const OPS_APP_ID = '2a8f6d14-9c73-4b51-8e0a-6d2f4c7b9a38';
const WATCH_PATH = '/content/commerce';
const POLL_INTERVAL_MS = 60000;
const WATCH_DEBOUNCE_MS = 800;

// --- Formatting helpers ----------------------------------------------------
function humanizeStatus(key: string): string {
	const s = String(key || '').replace(/_/g, ' ');
	return s.charAt(0).toUpperCase() + s.slice(1);
}

function formatAmount(value: string): string {
	const n = Number(value);
	if (!Number.isFinite(n)) return String(value);
	return new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 }).format(n);
}

function formatLatency(ms: number): string {
	if (!Number.isFinite(ms) || ms <= 0) return '—';
	if (ms < 1000) return `${Math.round(ms)} ms`;
	return `${(ms / 1000).toFixed(1)} s`;
}

function ratePill(rate: number): string {
	if (!(rate > 0)) return 'ok';
	if (rate >= 0.2) return 'danger';
	return 'warn';
}

function formatPct(rate: number): string {
	if (!Number.isFinite(rate)) return '0%';
	return `${(Math.round(rate * 1000) / 10)}%`;
}

// Sum success/error (+ latency) across the buckets of a health group.
function aggregateGroup(group: Record<string, any> | undefined): { rate: number; latency: number } {
	let success = 0, error = 0, latSum = 0, latCount = 0;
	for (const name in (group || {})) {
		const b = group![name] || {};
		success += Number(b.success) || 0;
		error += Number(b.error) || 0;
		latSum += Number(b.latency_sum) || 0;
		latCount += Number(b.latency_count) || 0;
	}
	const total = success + error;
	return { rate: total > 0 ? error / total : 0, latency: latCount > 0 ? latSum / latCount : 0 };
}

function statusRows(byStatus: Record<string, any> | undefined): Array<{ label: string; value: number }> {
	const out: Array<{ label: string; value: number }> = [];
	for (const k in (byStatus || {})) {
		out.push({ label: humanizeStatus(k), value: Number(byStatus![k]) || 0 });
	}
	out.sort((a, b) => b.value - a.value);
	return out;
}

const App = {
	data() {
		return {
			instance: null as AnyInstance,
			view: 'loading' as 'loading' | 'error' | 'ready',
			errorMessage: '',
			snapshot: null as any,
			lastUpdated: '',
			refreshing: false,
			connected: false,
			salesDays: 30,

			_base: '' as string,
			_watchUnsub: null as null | (() => void),
			_pollTimer: null as any,
			_debounceTimer: null as any,
			_messageListener: null as any,
		};
	},

	computed: {
		sales(): any {
			const s = (this.snapshot && this.snapshot.sales) || {};
			const rev = s.revenue || {};
			const revenueRows = Object.keys(rev).map((cur) => ({ label: cur, value: formatAmount(rev[cur]) }));
			return {
				orders: Number(s.orders) || 0,
				days: Number(s.days) || 0,
				revenueRows,
				statusRows: statusRows(s.byStatus),
			};
		},
		inventory(): any {
			const inv = (this.snapshot && this.snapshot.inventory) || {};
			const by = inv.byStatus || {};
			// Show every status except the low-stock one already surfaced above.
			const rows = statusRows(by).filter((r) => r.label.toLowerCase() !== 'review pending');
			return {
				total: Number(inv.total) || 0,
				lowStock: Number(inv.lowStock) || 0,
				statusRows: rows,
			};
		},
		forecast(): any {
			const f = (this.snapshot && this.snapshot.forecast) || {};
			const top = (f.top || []).map((t: any) => {
				const named = t.variantTitle && t.variantTitle !== 'Default Title';
				return {
					label: named ? `${t.title} — ${t.variantTitle}` : (t.title || 'Variant'),
					days: Math.round(Number(t.days) || 0),
				};
			});
			return { atRisk: Number(f.atRisk) || 0, warnDays: Number(f.warnDays) || 0, top };
		},
		reorders(): any {
			const r = (this.snapshot && this.snapshot.reorders) || {};
			return { pendingApproval: Number(r.pendingApproval) || 0, ordered: Number(r.ordered) || 0 };
		},
		backorders(): any {
			const b = (this.snapshot && this.snapshot.backorders) || {};
			return {
				backordered: Number(b.backordered) || 0,
				ready: Number(b.ready) || 0,
				openUnits: Number(b.openUnits) || 0,
			};
		},
		events(): any {
			const e = (this.snapshot && this.snapshot.events) || {};
			return {
				total: Number(e.total) || 0,
				received: Number(e.received) || 0,
				processed: Number(e.processed) || 0,
				error: Number(e.error) || 0,
			};
		},
		crm(): any {
			const c = (this.snapshot && this.snapshot.crm) || {};
			return {
				customers: Number(c.customers) || 0,
				vip: Number(c.vip) || 0,
				atRisk: Number(c.atRisk) || 0,
				dormant: Number(c.dormant) || 0,
				abandoned: Number(c.abandoned) || 0,
			};
		},
		salesTrend(): any {
			const s = (this.snapshot && this.snapshot.salesTrend) || {};
			const pts: any[] = Array.isArray(s.points) ? s.points : [];
			const W = 240, H = 56, pad = 4;
			let max = 0;
			for (const p of pts) max = Math.max(max, Number(p.revenue) || 0);
			const n = pts.length;
			let line = '', area = '';
			if (n > 0 && max > 0) {
				const coords = pts.map((p, i) => {
					const x = n === 1 ? W / 2 : (i / (n - 1)) * W;
					const y = H - pad - ((Number(p.revenue) || 0) / max) * (H - pad * 2);
					return [x, y];
				});
				line = coords.map((c) => `${c[0].toFixed(1)},${c[1].toFixed(1)}`).join(' ');
				area = `${coords[0][0].toFixed(1)},${H} ${line} ${coords[n - 1][0].toFixed(1)},${H}`;
			}
			const cur = s.primaryCurrency || '';
			return {
				days: Number(s.days) || 0,
				currency: cur,
				totalRevenue: formatAmount(String(s.totalRevenue ?? 0)),
				totalOrders: Number(s.totalOrders) || 0,
				aov: formatAmount(String(s.aov ?? 0)),
				hasData: n > 0 && max > 0,
				W, H, line, area,
				topProducts: (s.topProducts || []).map((t: any) => ({
					title: t.title || t.sku || t.key || 'item',
					qty: Number(t.quantity) || 0,
					revenue: formatAmount(String(t.revenue ?? 0)),
					currency: t.currency || cur,
				})),
			};
		},
		reconciliation(): any {
			const r = (this.snapshot && this.snapshot.reconciliation) || {};
			return {
				drift: Number(r.productsWithDrift) || 0,
				diffs: Number(r.totalDiffs) || 0,
				healed: Number(r.healed) || 0,
				lastRunAt: r.lastRunAt ? new Date(r.lastRunAt).toLocaleString() : '',
			};
		},
		outboundSync(): any {
			const o = (this.snapshot && this.snapshot.outboundSync) || {};
			return {
				total: Number(o.total) || 0,
				ok: Number(o.ok) || 0,
				failed: Number(o.failed) || 0,
				dryrun: Number(o.dryrun) || 0,
			};
		},
		locations(): any {
			const l = (this.snapshot && this.snapshot.locations) || {};
			return {
				locations: Number(l.locations) || 0,
				trackedItems: Number(l.trackedItems) || 0,
				lowLocations: Number(l.lowLocations) || 0,
			};
		},
		tasks(): any {
			const t = (this.snapshot && this.snapshot.tasks) || {};
			const by = t.byStatus || {};
			return {
				total: Number(t.total) || 0,
				unassigned: Number(t.unassigned) || 0,
				overdue: Number(by.overdue) || 0,
				open: Number(by.open) || 0,
				unclaimed: Number(by.unclaimed) || 0,
			};
		},
		health(): any {
			const h = (this.snapshot && this.snapshot.health) || {};
			const webhook = h.webhook || {};
			const api = aggregateGroup(h.api);
			const route = aggregateGroup(h.route);
			return {
				days: Number(h.days) || 0,
				received: Number(webhook.received) || 0,
				hmacFailures: Number(webhook.hmac_failure) || 0,
				apiErrorRate: formatPct(api.rate),
				apiPill: ratePill(api.rate),
				routeErrorRate: formatPct(route.rate),
				routePill: ratePill(route.rate),
				avgLatency: formatLatency(route.latency),
			};
		},
	},

	methods: {
		onMounted() {
			const vm = this;

			// Mirror shell theme changes onto <html data-theme>, like the built-in apps.
			vm._messageListener = (event: MessageEvent) => {
				const data: any = event.data || {};
				if (data.type === 'theme-changed' && data.theme) {
					document.documentElement.dataset.theme = data.theme;
				}
			};
			window.addEventListener('message', vm._messageListener);

			window.appLaunch = async (instance: AnyInstance) => {
				vm.instance = vm.$markRaw(instance);

				try {
					const theme = instance.api.theme.currentTheme || 'light';
					document.documentElement.dataset.theme = theme;
				} catch (_) { /* theme service unavailable */ }

				try { instance.windowTitle = 'Commerce Dashboard'; } catch (_) {}

				await vm.resolveBase();
				await vm.load();
				vm.setupRealtime();

				vm.$nextTick(() => { try { instance.notifyLaunched(); } catch (_) {} });
			};
		},

		onUnmount() {
			if (this._messageListener) window.removeEventListener('message', this._messageListener);
			if (this._watchUnsub) { try { this._watchUnsub(); } catch (_) {} this._watchUnsub = null; }
			if (this._pollTimer) clearInterval(this._pollTimer);
			if (this._debounceTimer) clearTimeout(this._debounceTimer);
		},

		// ---- Window controls -------------------------------------------------
		onMinimizeWindow() { this.instance?.minimize(); },
		onToggleMaximizeWindow() { this.instance?.toggleMaximize(); },
		onCloseWindow() { this.instance?.requestClose(); },

		// ---- Drill-down + range ----------------------------------------------
		// Change the sales window (7/30/90 days) and refetch.
		setSalesDays(n: number) {
			if (this.salesDays === n) return;
			this.salesDays = n;
			this.refresh();
		},

		// Drill-down: launch the Commerce Operations console focused on the
		// relevant view (and optional pre-set filters), instead of dumping raw
		// JSON. The shell focuses the running console and re-targets it when it
		// is already open (singleton).
		openOps(section: 'sync' | 'reconcile' | 'events', options?: Record<string, any>) {
			const opts = { section, ...(options || {}) };
			try {
				window.parent?.postMessage({ type: 'open-app', appId: OPS_APP_ID, options: opts }, window.location.origin);
			} catch (_) { /* parent unavailable */ }
		},

		// ---- Data ------------------------------------------------------------
		// Resolve the CGI base ("/bin/cms.cgi/<workspace>") for script endpoints.
		async resolveBase() {
			let ws: string | null = null;
			try { ws = this.instance?.api?.workspace || null; } catch (_) { ws = null; }
			if (ws) { this._base = `/bin/cms.cgi/${ws}`; return; }
			// Fallback: derive the workspace from a content node's download URL.
			try {
				const node = await this.instance.api.content.getNode('/content');
				const url = String(node?.downloadUrl || '');
				const m = url.match(/\/bin\/[^/]*cgi\/([^/?#]+)/);
				if (m) { this._base = `/bin/cms.cgi/${m[1]}`; return; }
			} catch (_) { /* fall through */ }
			this._base = '';
		},

		async load() {
			try {
				const data = await this.fetchSnapshot();
				this.snapshot = this.$markRaw ? this.$markRaw(data) : data;
				this.lastUpdated = new Date().toLocaleTimeString();
				this.view = 'ready';
			} catch (e: any) {
				this.errorMessage = (e && e.message) ? e.message : String(e);
				this.view = 'error';
			}
		},

		async refresh() {
			if (this.refreshing) return;
			this.refreshing = true;
			try {
				const data = await this.fetchSnapshot();
				this.snapshot = this.$markRaw ? this.$markRaw(data) : data;
				this.lastUpdated = new Date().toLocaleTimeString();
				if (this.view !== 'ready') this.view = 'ready';
			} catch (e: any) {
				// Keep the last good data on a transient refresh failure; only show
				// the error screen if we never loaded anything.
				if (this.view !== 'ready') {
					this.errorMessage = (e && e.message) ? e.message : String(e);
					this.view = 'error';
				}
			} finally {
				this.refreshing = false;
			}
		},

		async fetchSnapshot(): Promise<any> {
			if (!this._base) throw new Error('Could not resolve the workspace for the dashboard endpoint.');
			const url = `${this._base}${DASHBOARD_SCRIPT}?salesDays=${this.salesDays}`;
			const res = await fetch(url, {
				method: 'GET',
				headers: { 'Accept': 'application/json' },
				credentials: 'same-origin',
			});
			if (!res.ok) throw new Error(`Dashboard request failed (${res.status})`);
			return await res.json();
		},

		// ---- Real-time -------------------------------------------------------
		setupRealtime() {
			const vm = this;
			// Subscribe to content changes under /content/commerce (orders, products,
			// health metrics are JCR writes) and refetch, debounced.
			try {
				const eventHub = vm.instance?.api?.eventHub;
				if (eventHub && typeof eventHub.watchNode === 'function') {
					vm._watchUnsub = eventHub.watchNode(WATCH_PATH, () => {
						if (vm._debounceTimer) clearTimeout(vm._debounceTimer);
						vm._debounceTimer = window.setTimeout(() => { vm._debounceTimer = null; vm.refresh(); }, WATCH_DEBOUNCE_MS);
					}, true /* deep */);
					vm.connected = true;
				}
			} catch (_) { vm.connected = false; }

			// Slow poll catches task/SLA changes (engine state, not JCR) and acts as
			// a fallback when the subscription is unavailable.
			vm._pollTimer = window.setInterval(() => vm.refresh(), POLL_INTERVAL_MS);
		},
	},
};

VDOM.createApp(App).mount('#app');
