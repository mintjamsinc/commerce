// Commerce Reports — the occurrence-date sales summary console for the
// Shopify→CMS integration.
//
// Two panes (mirroring commerce-oplog / commerce-events): a left filter sidebar
// (a from/to period) and a center column stacking the occurrence KPI hero island
// above the daily list island. The list header carries the "Download CSV" export.
//
// Driven by a single admin endpoint:
//   reports.groovy?type=occurrence&from=<ISO>&to=<ISO>[&tz=<IANA>][&format=csv]
//     — from/to are ISO-8601 UTC instants (platform wire convention). The client
//       ALWAYS sends an explicit window: it takes the operator's date-only picks
//       (or, when the range is left empty, the last 30 calendar days ending today)
//       and pads them to the day boundaries — 00:00:00 / 23:59:59 — of the effective
//       timezone. It never sends the endpoint's `days` shorthand. Every event is
//       counted on its OWN date (new orders by ordered_at, full cancels by
//       cancelled_at, payments by paid_at, refunds by refunded_at); confirmedSales =
//       paymentAmount + refundAmount (the payment basis: cash in minus cash out;
//       refundAmount is NEGATIVE). No population params — the report counts every
//       event on its date. Content-Disposition makes a plain navigation save for the
//       CSV.
//
// Read-only: this console never writes to Shopify or the CMS. Self-contained
// (ichigo.js runtime only).

import { VDOM } from '@mintjamsinc/ichigojs';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
	formatNumber,
	formatCurrency,
} from '../../composables/use-localization.js';
import { wallClockToIso, completeDateTimeLocal, todayInZone, shiftDate } from '../../composables/wire-datetime.js';
import { fetchPendingCount } from '../../composables/use-pending-badge.js';

type AnyInstance = any;

const REPORTS_SCRIPT = '/content/commerce/endpoints/reports.groovy';
// The default window when the operator leaves the range empty: the last N calendar
// days ending today (inclusive — today and the N-1 preceding days).
const DEFAULT_WINDOW_DAYS = 30;
// The Commerce Orders (facet browser) app id — the cancelled-count drill-down
// asks the shell to open/re-target it with a { cancelled, from, to } filter.
const ORDERS_APP_ID = 'e5a3c6f8-7b1d-4e2a-9c04-1f6b8d3e5a7c';

const App = {
	data() {
		return {
			instance: null as AnyInstance,
			// Reactive localization snapshot — drives every t() / fmtDate() binding
			// so the app repaints when the user switches language or a bundle is
			// hot-reloaded.
			localization: createLocalizationSnapshot(),
			busy: false,
			toast: '',
			toastError: false,

			// Operator-picked date range (date-only, yyyy-MM-dd; empty → last 30 days).
			report: { from: '', to: '' },
			// The APPLIED snapshot: the list AND the CSV derive from THIS, never the
			// live inputs, so the export can never drift from the shown preview.
			applied: { from: '', to: '' },
			hasApplied: false,
			// The occurrence-date report (type=occurrence). Cleared on every apply so
			// the new window never shows stale figures; the hero stays MOUNTED and
			// renders '--' placeholders while the fetch is in flight (it must never
			// blink out of the layout during a search).
			occurrencePreview: null as null | any,

			_base: '' as string,
			_messageListener: null as any,
			_toastTimer: null as any,

			// "反映待ち" badge: the live count of orders whose sales facts are still being recomputed
			// (async drainer backlog). Polled so every operator — not just whoever ran the import — sees
			// when the figures are still catching up.
			pendingCount: 0,
			_pendingTimer: null as any,

			// Resizable filter sidebar (LEFT).
			sidebarVisible: true,
			sidebarWidth: 272,
			sidebarMinWidth: 180,
			sidebarMaxWidth: 480,
			_boundSidebarResizeMove: null as any,
			_boundSidebarResizeUp: null as any,
		};
	},

	computed: {
		occurrenceDaily(): any[] { return (this.occurrencePreview && Array.isArray(this.occurrencePreview.daily)) ? this.occurrencePreview.daily : []; },
		occurrenceTotals(): any { return (this.occurrencePreview && this.occurrencePreview.totals) || {}; },
		rowCount(): number { return this.occurrenceDaily.length; },
		baseCurrency(): string { return (this.occurrencePreview && this.occurrencePreview.baseCurrency) || ''; },
		canDownload(): boolean { return this.hasApplied && !this.busy && this.occurrenceDaily.length > 0; },
		// The hero's period line — '--' while the window's data is loading (the hero
		// itself never unmounts).
		occRangeLabel(): string {
			const p: any = this.occurrencePreview;
			if (!p) return '--';
			return `${this.fmtRangeDate(p.from)} – ${this.fmtRangeDate(p.to)}`;
		},
	},

	methods: {
		// ---- i18n / locale-aware formatting ------------------------------------
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},
		// Effective-window endpoints (report.from/to) are full ISO INSTANTS, so format
		// them through Date in the viewer's zone — fmtDate below reads the yyyy-MM-dd
		// PREFIX, which for an instant is the UTC date and would shift a JST midnight
		// to the previous day.
		fmtRangeDate(v: any): string {
			if (!v) return '—';
			const d = new Date(v);
			if (isNaN(d.getTime())) return String(v);
			try {
				return d.toLocaleDateString(this.localization.locale || undefined, { year: 'numeric', month: 'short', day: 'numeric' });
			} catch (_) { return String(v).slice(0, 10); }
		},
		// Standard (non-friendly) date + weekday, e.g. Jul 5, 2026 (Sun).
		// `d.date` is a bare calendar date ("yyyy-MM-dd"); build it as a LOCAL date so a
		// timezone behind/ahead of UTC never shifts the calendar day or the weekday.
		fmtDate(v: any): string {
			const s = String(v || '').trim();
			const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(s);
			if (!m) return s || '—';
			const d = new Date(+m[1], +m[2] - 1, +m[3]);
			if (isNaN(d.getTime())) return s;
			try {
				return d.toLocaleDateString(this.localization.locale || undefined, { year: 'numeric', month: 'short', day: 'numeric', weekday: 'short' });
			} catch (_) { return s; }
		},
		// Money via Intl.NumberFormat (grouping + currency-standard decimals: JPY 0,
		// USD 2, …). Every value here is the ONE base currency, so the compact
		// 'narrowSymbol' (¥1,000) is used throughout — repeating the code on every
		// cell of a single-currency table is noise.
		fmtMoney(amount: any, currency: any): string {
			if (amount == null || amount === '') return '—';
			const c = String(currency || '').trim();
			if (c) return formatCurrency(this.localization, amount, { currency: c, currencyDisplay: 'narrowSymbol' });
			return formatNumber(this.localization, amount);
		},
		// Integer count with grouping (orders).
		fmtInt(v: any): string {
			if (v == null || v === '') return '—';
			return formatNumber(this.localization, v, { maximumFractionDigits: 0 });
		},
		// Base-currency amount, formatted in the report's base currency.
		fmtBase(v: any): string { return this.fmtMoney(v, this.baseCurrency); },
		// Hero variants: '--' while the window's data is loading, so the KPI strip
		// keeps its shape instead of unmounting during a search.
		occInt(v: any): string { return this.occurrencePreview ? this.fmtInt(v) : '--'; },
		occBase(v: any): string { return this.occurrencePreview ? this.fmtBase(v) : '--'; },
		// Negative-amount signal — drives the danger color on money cells
		// (refundAmount is negative by design; confirmedSales can go negative).
		isNeg(v: any): boolean {
			const n = Number(v);
			return isFinite(n) && n < 0;
		},

		onMounted() {
			const vm = this;
			vm._messageListener = (event: MessageEvent) => {
				const data: any = event.data || {};
				if (handleLocalizationMessage(data.type, vm.localization, vm.instance)) return;
				if (data.type === 'theme-changed' && data.theme) document.documentElement.dataset.theme = data.theme;
				else if (data.type === 'app-reopen') vm.applyLaunchOptions(data.options, false);
			};
			window.addEventListener('message', vm._messageListener);

			window.appLaunch = async (instance: AnyInstance, options?: any) => {
				vm.instance = vm.$markRaw(instance);
				try { document.documentElement.dataset.theme = instance.api.theme.currentTheme || 'light'; } catch (_) {}
				refreshLocalization(vm.localization, vm.instance);
				try { instance.windowTitle = vm.t('app.commerce-reports.title', undefined, 'Commerce Reports'); } catch (_) {}
				instance.appState = () => ({
					from: vm.report.from,
					to: vm.report.to,
				});
				await vm.resolveBase();
				await vm.loadPanesState();
				vm.applyLaunchOptions(options, true);
				await vm.apply();
				vm.startPendingPoll();
				vm.$nextTick(() => { try { instance.notifyLaunched(); } catch (_) {} });
			};
		},
		onUnmount() {
			if (this._messageListener) window.removeEventListener('message', this._messageListener);
			if (this._toastTimer) clearTimeout(this._toastTimer);
			this.stopPendingPoll();
			if (this._boundSidebarResizeMove) document.removeEventListener('mousemove', this._boundSidebarResizeMove);
			if (this._boundSidebarResizeUp) document.removeEventListener('mouseup', this._boundSidebarResizeUp);
		},

		// ---- Window controls -------------------------------------------------
		onMinimizeWindow() { this.instance?.minimize(); },
		onToggleMaximizeWindow() { this.instance?.toggleMaximize(); },
		onCloseWindow() { this.instance?.requestClose(); },

		// ---- Sidebar: toggle / resize / persist --------------------------------
		toggleSidebar() { this.sidebarVisible = !this.sidebarVisible; this.persistPanesState(); },

		onSidebarResizeStart(event: MouseEvent) {
			const vm = this;
			event.preventDefault();
			const startX = event.clientX;
			const startWidth = vm.sidebarWidth;
			vm._boundSidebarResizeMove = (e: MouseEvent) => {
				const delta = e.clientX - startX;
				vm.sidebarWidth = Math.max(vm.sidebarMinWidth, Math.min(vm.sidebarMaxWidth, startWidth + delta));
			};
			vm._boundSidebarResizeUp = () => {
				if (vm._boundSidebarResizeMove) document.removeEventListener('mousemove', vm._boundSidebarResizeMove);
				if (vm._boundSidebarResizeUp) document.removeEventListener('mouseup', vm._boundSidebarResizeUp);
				vm._boundSidebarResizeMove = null;
				vm._boundSidebarResizeUp = null;
				vm.persistPanesState();
			};
			document.addEventListener('mousemove', vm._boundSidebarResizeMove);
			document.addEventListener('mouseup', vm._boundSidebarResizeUp);
		},
		async persistPanesState() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				await db.setUserSetting(userID, 'commerce-reports', 'panes', JSON.parse(JSON.stringify({
					sidebar: { visible: vm.sidebarVisible, width: vm.sidebarWidth },
				})));
			} catch (_) { /* non-critical */ }
		},
		async loadPanesState() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				let state = await db.getUserSetting(userID, 'commerce-reports', 'panes');
				if (!state) {
					const legacy = await db.getUserSetting(userID, 'commerce-reports', 'sidebar');
					if (legacy) state = { sidebar: legacy };
				}
				if (state && state.sidebar) {
					vm.sidebarVisible = state.sidebar.visible ?? true;
					vm.sidebarWidth = state.sidebar.width ?? vm.sidebarWidth;
				}
			} catch (_) { /* non-critical */ }
		},

		// Apply a saved state / deep-link. Shape: { from?, to? }.
		applyLaunchOptions(options: any, initial = false) {
			const o = (options && typeof options === 'object') ? options : {};
			// Accepts either a bare date or a legacy full instant/datetime-local value
			// (older saved sessions) — either way, only the yyyy-MM-dd prefix survives,
			// matching the date-only <input type="date"> this now feeds.
			if (o.from != null) this.report.from = String(o.from).trim().slice(0, 10);
			if (o.to != null) this.report.to = String(o.to).trim().slice(0, 10);
			if (!initial) this.apply();
		},
		refresh() { this.load(); },

		// ---- HTTP ------------------------------------------------------------
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

		// ---- Occurrence-date report (date-range preview + CSV download) --------
		// The query is derived from the APPLIED snapshot, so the CSV download always
		// matches the shown preview. Dates go over the wire as ISO-8601 instants
		// resolved in the effective (Preferences) timezone.
		occurrenceQuery(format: string): string {
			const p = new URLSearchParams();
			p.set('type', 'occurrence');
			const tz = this.localization.timeZone;
			// Resolve the effective window as bare dates first. An empty operator range
			// defaults to the last DEFAULT_WINDOW_DAYS calendar days ending today,
			// resolved in the effective timezone — the SAME date-only basis as a manual
			// pick, so the default and a hand-typed range share one wire path. Both
			// endpoints then go over the wire as ISO-8601 UTC instants padded to the day
			// boundaries (00:00:00 / 23:59:59) of that zone; this client never sends the
			// `days` shorthand, so the endpoint always receives an explicit from/to.
			let fromDate = this.applied.from;
			let toDate = this.applied.to;
			if (!fromDate && !toDate) {
				toDate = todayInZone(tz);
				fromDate = shiftDate(toDate, -(DEFAULT_WINDOW_DAYS - 1));
			}
			const fromIso = wallClockToIso(completeDateTimeLocal(fromDate, false), tz, false);
			const toIso = wallClockToIso(completeDateTimeLocal(toDate, true), tz, true);
			if (fromIso) p.set('from', fromIso);
			if (toIso) p.set('to', toIso);
			// The same timezone that resolved the wall-clock range also labels the
			// day rows: the endpoint buckets each event on its local day in this zone.
			if (tz) p.set('tz', tz);
			if (format) p.set('format', format);
			return p.toString();
		},
		async apply() {
			this.applied = {
				from: this.report.from,
				to: this.report.to,
			};
			this.hasApplied = true;
			// Invalidate for the new window: the hero/list must never show the OLD
			// window's figures against the new period — they render '--' / empty
			// instead until the fetch lands.
			this.occurrencePreview = null;
			return this.load();
		},
		// Reload the applied window. On a plain refresh (same window) the previous
		// figures stay visible until the response replaces them.
		async load() {
			this.busy = true;
			try {
				const j = await this.getJson(`${REPORTS_SCRIPT}?${this.occurrenceQuery('')}`);
				this.occurrencePreview = this.$markRaw(j);
			} catch (e: any) {
				this.occurrencePreview = null;
				this.showToast(e?.message || this.t('app.commerce-reports.loadFailed', undefined, 'Could not load the report.'), true);
			} finally { this.busy = false; }
		},
		// Stream the CSV through the browser's downloader; the endpoint sets
		// Content-Disposition, so a plain navigation saves the file.
		downloadReportCsv() {
			if (!this.canDownload) return;
			const url = `${this._base}${REPORTS_SCRIPT}?${this.occurrenceQuery('csv')}`;
			try {
				const a = document.createElement('a');
				a.href = url;
				a.download = '';
				document.body.appendChild(a);
				a.click();
				a.remove();
			} catch (_) { window.open(url, '_blank'); }
		},

		// Drill-down from the "cancelled count" cell → open (or re-target) the Commerce
		// Orders browser filtered to the FULL cancels of that calendar day. The day range is sent as bare
		// wall-clock strings; the browser resolves them in the effective timezone and (with cancelled=true)
		// ranges on the cancel date. No-op when the day had zero cancellations.
		openCancelledOrders(row: any) {
			if (!row || !Number(row.cancelledCount)) return;
			const day = String(row.date || '').slice(0, 10);
			if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) return;
			const options = { cancelled: true, from: `${day}T00:00`, to: `${day}T23:59` };
			try {
				window.parent?.postMessage({ type: 'open-app', appId: ORDERS_APP_ID, options }, window.location.origin);
			} catch (_) { /* parent unavailable */ }
		},

		// ---- "反映待ち" pending badge ----------------------------------------
		async pollPending() {
			try { this.pendingCount = await fetchPendingCount(this._base); } catch (_) { /* keep last */ }
		},
		startPendingPoll() {
			this.pollPending();
			this._pendingTimer = window.setInterval(() => this.pollPending(), 15000);
		},
		stopPendingPoll() {
			if (this._pendingTimer) { clearInterval(this._pendingTimer); this._pendingTimer = null; }
		},

		// ---- Toast -----------------------------------------------------------
		showToast(msg: string, isError: boolean) {
			this.toast = msg; this.toastError = !!isError;
			if (this._toastTimer) clearTimeout(this._toastTimer);
			this._toastTimer = window.setTimeout(() => { this.toast = ''; }, 3400);
		},
	},
};

VDOM.createApp(App).mount('#app');
