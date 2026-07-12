// Commerce Reports — the sales report console for the Shopify→CMS integration.
//
// Three panes (mirroring commerce-oplog / commerce-events): a left filter sidebar
// (a from/to period), a center list (daily orders + revenue per currency + the
// base-currency rollup), and a right detail pane showing the selected day's
// breakdown. The list header carries the "Download CSV" export.
//
// Driven by a single admin endpoint:
//   reports.groovy?type=sales[&from&to | &days]
//                 [&includeCancelled=true|false][&financialStatus=paid,…]
//                 [&format=csv[&csvView=refunds][&taxMode=excl|incl]]
//     — from/to are ISO-8601 instants (platform wire convention; the client
//       resolves the datetime-local wall-clock in the effective timezone), and
//       win over the rolling 30-day window. The population params slice the
//       report (cancelled in/out, financial statuses). The report has two views:
//       the sales P/L (`pl`, order date) and the refund cash-out (`refunds`,
//       refund date); the CSV follows the on-screen tab. returnsBasis is NOT sent
//       (the P/L is order-date only; the refund-date view is the `refunds` block).
//       Content-Disposition makes a plain navigation save.
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
import { wallClockToIso, completeDateTimeLocal } from '../../composables/wire-datetime.js';

type AnyInstance = any;

const REPORTS_SCRIPT = '/content/commerce/endpoints/reports.groovy';

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

			// Operator-picked date range (datetime-local; empty → last 30 days) plus
			// the population controls: returns basis (order/refund date), cancelled
			// orders in/out, and a comma-separated financial-status list (empty →
			// server default).
			report: { from: '', to: '', includeCancelled: '', financialStatus: '' },
			// The APPLIED snapshot: the list AND the CSV derive from THIS, never the
			// live inputs, so the export can never drift from the shown preview.
			applied: { from: '', to: '', includeCancelled: '', financialStatus: '' },
			hasApplied: false,
			reportPreview: null as null | any,
			selectedRow: null as any,   // the day shown in the detail pane

			// The P/L is tax-exclusive with tax as its own row; the hero toggle only re-picks the headline
			// figure (revenue excl. tax ↔ total charged incl. tax) — it never mixes tax into a number.
			taxMode: 'excl' as 'excl' | 'incl',
			// Two exclusive views: the sales P/L (order date) and the refund cash-out list (refund date).
			// They come from different cohorts, so switching REMOVES the other from view — never side by side.
			view: 'sales' as 'sales' | 'refunds',

			_base: '' as string,
			_messageListener: null as any,
			_toastTimer: null as any,

			// Resizable filter sidebar (LEFT).
			sidebarVisible: true,
			sidebarWidth: 272,
			sidebarMinWidth: 180,
			sidebarMaxWidth: 480,
			_boundSidebarResizeMove: null as any,
			_boundSidebarResizeUp: null as any,

			// Resizable detail pane (RIGHT).
			detailVisible: true,
			detailWidth: 340,
			detailMinWidth: 240,
			detailMaxWidth: 640,
			_boundDetailResizeMove: null as any,
			_boundDetailResizeUp: null as any,
		};
	},

	computed: {
		reportDaily(): any[] { return (this.reportPreview && Array.isArray(this.reportPreview.daily)) ? this.reportPreview.daily : []; },
		rowCount(): number { return this.reportDaily.length; },
		baseCurrency(): string { return (this.reportPreview && this.reportPreview.totals && this.reportPreview.totals.baseCurrency) || ''; },
		canDownload(): boolean {
			if (!this.hasApplied || this.busy) return false;
			return (this.view === 'refunds') ? this.refundsDaily.length > 0 : this.reportDaily.length > 0;
		},
		// Totals block of the sales response (null while nothing is loaded — drives
		// the summary header's v-if). The nested getters default to {} so the
		// template can dereference them without guards; fmtBase(null) renders '—'.
		reportTotals(): any { return (this.reportPreview && this.reportPreview.totals) || null; },
		reportMetrics(): any { return (this.reportTotals && this.reportTotals.metrics) || {}; },
		reportStats(): any { return (this.reportTotals && this.reportTotals.stats) || {}; },
		reportPercentiles(): any { return (this.reportTotals && this.reportTotals.percentiles) || {}; },
		// Orders the backend could not decompose into components — when > 0 the
		// summary shows a warning that the breakdown columns are partial.
		incompleteOrders(): number { return Number((this.reportTotals && this.reportTotals.incompleteOrders) || 0) || 0; },

		// ---- P/L (the primary reading; order-date basis) -----------------------
		// The canonical tax-exclusive P/L block the server composes additively (grossSales − discounts −
		// returns … = totalRevenue, + tax + duties = totalCharged). New screens read `pl` ONLY — never
		// `metrics` (whose `returns`/`totalSales` are tax-INCLUSIVE, a name collision).
		plTotals(): any { return (this.reportTotals && this.reportTotals.pl) || {}; },
		diagnostics(): any { return (this.reportTotals && this.reportTotals.diagnostics) || {}; },
		// ---- Refund cash-out (refund-date basis; the Refunds tab) ----------------
		refundsSummary(): any { return (this.reportTotals && this.reportTotals.refunds) || {}; },
		refundsDaily(): any[] { return (this.reportPreview && this.reportPreview.refunds && Array.isArray(this.reportPreview.refunds.daily)) ? this.reportPreview.refunds.daily : []; },
		refundsHasCrossPeriod(): boolean { return this.refundsDaily.some((r: any) => r && r.crossPeriod); },
		refundsMixedCurrency(): boolean { return !!(this.refundsSummary && this.refundsSummary.mixedCurrency); },
		refundsUnmigrated(): number { return Number((this.refundsSummary && this.refundsSummary.unmigratedRefunds) || 0) || 0; },

		// ---- Diagnostics (footer: "reconciled OK" when all clear, the issues when not) ----------
		// The server counts what it could NOT place (lossy orders, unclassified/unreconciled/unmigrated
		// refunds, day-vs-total drift). All zero → "OK". Non-zero → surfaced so someone notices — a zero
		// here means "checked and clean", never "not looked at".
		diagnosticsIssues(): string[] {
			const d: any = this.diagnostics || {};
			const r: any = this.refundsSummary || {};
			const n = (v: any) => Number(v || 0) || 0;
			const out: string[] = [];
			if (n(d.lossyOrders)) out.push(this.t('app.commerce-reports.diag.lossyOrders', { count: n(d.lossyOrders), amount: this.fmtBase(d.lossyRevenue) }));
			if (n(d.unclassifiedRefundAdjustments)) out.push(this.t('app.commerce-reports.diag.unclassified', { amount: this.fmtBase(d.unclassifiedRefundAdjustments) }));
			if (n(d.unreconciledRefunds)) out.push(this.t('app.commerce-reports.diag.unreconciled', { count: n(d.unreconciledRefunds) }));
			if (n(r.unmigratedRefunds)) out.push(this.t('app.commerce-reports.diag.unmigrated', { count: n(r.unmigratedRefunds) }));
			const drift: any = d.dayTotalDrift || {};
			if (Object.keys(drift).some((k) => n(drift[k]))) out.push(this.t('app.commerce-reports.diag.drift'));
			if (r.mixedCurrency) out.push(this.t('app.commerce-reports.diag.mixedCurrency'));
			return out;
		},
		diagnosticsOk(): boolean { return !!this.reportTotals && this.diagnosticsIssues.length === 0; },
		taxIncl(): boolean { return this.taxMode === 'incl'; },
		heroMainValue(): any { return this.taxIncl ? this.plTotals.totalCharged : this.plTotals.totalRevenue; },
		heroMainLabel(): string { return this.taxIncl ? this.t('app.commerce-reports.hero.totalCharged') : this.t('app.commerce-reports.hero.totalRevenue'); },
		// Mini-ladder groupings (the waterfall): grossSales − (discounts+returns) + (shipping+otherIncome) = totalRevenue.
		ladderDeductions(): number { return Number(this.plTotals.discounts || 0) + Number(this.plTotals.returns || 0); },
		ladderAdditions(): number { return Number(this.plTotals.shipping || 0) + Number(this.plTotals.otherIncome || 0); },
		// Return rate = returns / grossSales (tax-exclusive both). Null when there is no gross to divide by.
		returnRate(): number | null {
			const g = Number(this.plTotals.grossSales || 0);
			if (!g) return null;
			return Number(this.plTotals.returns || 0) / g;
		},
		// The server's population string ("financialStatus=paid,…; includeCancelled=false") rendered as
		// localized labels (e.g. "Target: Paid, Partially Refunded / Cancelled Orders: Excluded") — the raw
		// wire string must never reach the screen (that was the earlier "financialStatus=…" leak).
		populationLabel(): string {
			const raw = String((this.reportPreview && this.reportPreview.population) || '').trim();
			if (!raw) return '—';
			const out: string[] = [];
			const fs = /financialStatus=([^;]*)/.exec(raw);
			if (fs) {
				const v = fs[1].trim();
				const target = (!v || v === 'all')
					? this.t('app.commerce-reports.status.all')
					: v.split(',').map((s) => s.trim()).filter(Boolean)
						.map((s) => this.t('app.commerce-reports.status.' + s, undefined, s)).join('・');
				out.push(`${this.t('app.commerce-reports.population.target')}: ${target}`);
			}
			const cx = /includeCancelled=(true|false)/.exec(raw);
			if (cx) {
				const key = cx[1] === 'true' ? 'app.commerce-reports.population.cancelledInclude' : 'app.commerce-reports.population.cancelledExclude';
				out.push(`${this.t('app.commerce-reports.population.cancelled')}: ${this.t(key)}`);
			}
			return out.length ? out.join(' ／ ') : raw;
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
		// USD 2, …). Display picks the context: 'code' (JPY 1,000) where several
		// currencies can sit side by side (the native per-currency line), the
		// compact 'narrowSymbol' (¥1,000) where every value is the ONE base
		// currency (tiles, daily table, detail pane) — repeating the code on every
		// cell of a single-currency table is noise.
		fmtMoney(amount: any, currency: any, display: 'code' | 'narrowSymbol' = 'code'): string {
			if (amount == null || amount === '') return '—';
			const c = String(currency || '').trim();
			if (c) return formatCurrency(this.localization, amount, { currency: c, currencyDisplay: display });
			return formatNumber(this.localization, amount);
		},
		// Integer count with grouping (orders).
		fmtInt(v: any): string {
			if (v == null || v === '') return '—';
			return formatNumber(this.localization, v, { maximumFractionDigits: 0 });
		},
		// Base-currency amount, formatted in the report's base currency.
		fmtBase(v: any): string { return this.fmtMoney(v, this.baseCurrency, 'narrowSymbol'); },
		// Deduction amount (discounts, returns) rendered as an explicit NEGATIVE, so the
		// P/L row reads left to right: sales total − discounts − returns = total. The
		// server keeps deductions as positive magnitudes; the sign is presentation only.
		// Zero stays a plain 0 (no "-¥0").
		fmtBaseNeg(v: any): string {
			if (v == null || v === '') return '—';
			const n = Number(v);
			if (!isFinite(n) || n === 0) return this.fmtBase(v);
			return this.fmtMoney(-Math.abs(n), this.baseCurrency, 'narrowSymbol');
		},
		// Per-currency revenue array [{currency, amount}] → formatted "JPY 1,385" lines.
		revenueLines(rev: any): string[] {
			if (!Array.isArray(rev)) return [];
			return rev.map((e: any) => this.fmtMoney(e && e.amount, e && e.currency));
		},
		fmtRevenue(rev: any): string {
			const lines = this.revenueLines(rev);
			return lines.length ? lines.join(' · ') : '—';
		},
		// Percent (return rate), locale-aware. Null → '—'.
		fmtPct(v: any): string {
			if (v == null) return '—';
			try { return formatNumber(this.localization, v, { style: 'percent', maximumFractionDigits: 1 }); }
			catch (_) { return `${(Number(v) * 100).toFixed(1)}%`; }
		},
		// A P/L column is shown only when it is non-zero somewhere (total or any day) — the all-zero
		// columns (discounts / other income, typically) auto-collapse so the table reads as one clean path.
		plColShow(field: string): boolean {
			if (Number((this.plTotals as any)[field] || 0) !== 0) return true;
			return this.reportDaily.some((d: any) => Number((d && d.pl && d.pl[field]) || 0) !== 0);
		},
		// Hero tax toggle (headline figure only) and the sales/refunds view switch (exclusive).
		setTaxMode(m: 'excl' | 'incl') { this.taxMode = m; },
		setView(v: 'sales' | 'refunds') { this.view = v; this.selectedRow = null; },

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
					includeCancelled: vm.report.includeCancelled,
					financialStatus: vm.report.financialStatus,
				});
				await vm.resolveBase();
				await vm.loadPanesState();
				vm.applyLaunchOptions(options, true);
				await vm.apply();
				vm.$nextTick(() => { try { instance.notifyLaunched(); } catch (_) {} });
			};
		},
		onUnmount() {
			if (this._messageListener) window.removeEventListener('message', this._messageListener);
			if (this._toastTimer) clearTimeout(this._toastTimer);
			if (this._boundSidebarResizeMove) document.removeEventListener('mousemove', this._boundSidebarResizeMove);
			if (this._boundSidebarResizeUp) document.removeEventListener('mouseup', this._boundSidebarResizeUp);
			if (this._boundDetailResizeMove) document.removeEventListener('mousemove', this._boundDetailResizeMove);
			if (this._boundDetailResizeUp) document.removeEventListener('mouseup', this._boundDetailResizeUp);
		},

		// ---- Window controls -------------------------------------------------
		onMinimizeWindow() { this.instance?.minimize(); },
		onToggleMaximizeWindow() { this.instance?.toggleMaximize(); },
		onCloseWindow() { this.instance?.requestClose(); },

		// ---- Panes: toggle / resize / persist --------------------------------
		toggleSidebar() { this.sidebarVisible = !this.sidebarVisible; this.persistPanesState(); },
		toggleDetail() { this.detailVisible = !this.detailVisible; this.persistPanesState(); },

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
		onDetailResizeStart(event: MouseEvent) {
			const vm = this;
			event.preventDefault();
			const startX = event.clientX;
			const startWidth = vm.detailWidth;
			vm._boundDetailResizeMove = (e: MouseEvent) => {
				const delta = startX - e.clientX;
				vm.detailWidth = Math.max(vm.detailMinWidth, Math.min(vm.detailMaxWidth, startWidth + delta));
			};
			vm._boundDetailResizeUp = () => {
				if (vm._boundDetailResizeMove) document.removeEventListener('mousemove', vm._boundDetailResizeMove);
				if (vm._boundDetailResizeUp) document.removeEventListener('mouseup', vm._boundDetailResizeUp);
				vm._boundDetailResizeMove = null;
				vm._boundDetailResizeUp = null;
				vm.persistPanesState();
			};
			document.addEventListener('mousemove', vm._boundDetailResizeMove);
			document.addEventListener('mouseup', vm._boundDetailResizeUp);
		},
		async persistPanesState() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				await db.setUserSetting(userID, 'commerce-reports', 'panes', JSON.parse(JSON.stringify({
					sidebar: { visible: vm.sidebarVisible, width: vm.sidebarWidth },
					detail: { visible: vm.detailVisible, width: vm.detailWidth },
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
				if (state) {
					if (state.sidebar) {
						vm.sidebarVisible = state.sidebar.visible ?? true;
						vm.sidebarWidth = state.sidebar.width ?? vm.sidebarWidth;
					}
					if (state.detail) {
						vm.detailVisible = state.detail.visible ?? true;
						vm.detailWidth = state.detail.width ?? vm.detailWidth;
					}
				}
			} catch (_) { /* non-critical */ }
		},

		// ---- Row selection (detail pane) -------------------------------------
		selectRow(d: any) { this.selectedRow = d; this.detailVisible = true; },

		// 3-state cancelled-orders picker ('' = server default | exclude | include).
		async openCancelledMenu(event: MouseEvent) {
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !this.instance) return;
			const rect = trigger.getBoundingClientRect();
			const cur = String(this.report.includeCancelled);
			const items = [
				{ id: 'default', label: this.t('app.commerce-reports.filter.serverDefault'), selected: cur === '' },
				{ id: 'false', label: this.t('app.commerce-reports.filter.cancelled.exclude'), selected: cur === 'false' },
				{ id: 'true', label: this.t('app.commerce-reports.filter.cancelled.include'), selected: cur === 'true' },
			];
			const handle = this.instance.popup.open({ anchor: rect, placement: 'bottom-start', minWidth: rect.width, items });
			const result = await handle.result;
			if (result == null) return;
			this.report.includeCancelled = (String(result) === 'default') ? '' : String(result);
		},
		cancelledLabel(v: any): string {
			switch (String(v)) {
				case 'true': return this.t('app.commerce-reports.filter.cancelled.include');
				case 'false': return this.t('app.commerce-reports.filter.cancelled.exclude');
				default: return this.t('app.commerce-reports.filter.serverDefault');
			}
		},

		// Apply a saved state / deep-link. Shape:
		//   { from?, to?, includeCancelled?, financialStatus? }.
		applyLaunchOptions(options: any, initial = false) {
			const o = (options && typeof options === 'object') ? options : {};
			if (o.from != null) this.report.from = completeDateTimeLocal(String(o.from), false);
			if (o.to != null) this.report.to = completeDateTimeLocal(String(o.to), true);
			if (o.includeCancelled != null && String(o.includeCancelled) !== '') this.report.includeCancelled = (o.includeCancelled === true || String(o.includeCancelled) === 'true') ? 'true' : 'false';
			if (o.financialStatus != null) this.report.financialStatus = String(o.financialStatus);
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

		// ---- Sales report (date-range preview + CSV download) ----------------
		// The query is derived from the APPLIED snapshot, so the CSV download always
		// matches the shown preview. Dates go over the wire as ISO-8601 instants
		// resolved in the effective (Preferences) timezone.
		reportQuery(format: string): string {
			const p = new URLSearchParams();
			p.set('type', 'sales');
			const fromIso = wallClockToIso(this.applied.from, this.localization.timeZone, false);
			const toIso = wallClockToIso(this.applied.to, this.localization.timeZone, true);
			if (fromIso) p.set('from', fromIso);
			if (toIso) p.set('to', toIso);
			if (!fromIso && !toIso) p.set('days', '30');
			// Population controls — also from the APPLIED snapshot, so the CSV's
			// population always matches the preview's.
			// Population params ride only when the operator SET them — an absent param keeps the
			// sales.yml default in force (operator sovereignty; resolveOpts treats presence as override).
			// NOTE: returnsBasis is intentionally NOT sent. The P/L is order-date only (pl.basis is always
			// "order"); the refund-date view is the separate Refunds tab (the `refunds` block), so a basis
			// selector here would be a control that changes nothing — removed on purpose.
			if (this.applied.includeCancelled !== '') p.set('includeCancelled', String(this.applied.includeCancelled));
			const fs = String(this.applied.financialStatus || '').trim();
			if (fs) p.set('financialStatus', fs);
			if (format) p.set('format', format);
			// The CSV follows the on-screen tab. The refund cash-out CSV carries basis=refund and no tax
			// mode (it is cash); the sales CSV embeds the current tax-mode in its comment header (the P/L
			// columns are the same either way — it just records which headline the operator was reading).
			if (format === 'csv') {
				if (this.view === 'refunds') p.set('csvView', 'refunds');
				else p.set('taxMode', this.taxIncl ? 'incl' : 'excl');
			}
			return p.toString();
		},
		async apply() {
			this.applied = {
				from: this.report.from,
				to: this.report.to,
				includeCancelled: this.report.includeCancelled,
				financialStatus: this.report.financialStatus,
			};
			this.hasApplied = true;
			this.selectedRow = null;
			return this.load();
		},
		async load() {
			this.busy = true;
			try { await this.loadReportPreview(); }
			finally { this.busy = false; }
		},
		async loadReportPreview() {
			try {
				const j = await this.getJson(`${REPORTS_SCRIPT}?${this.reportQuery('')}`);
				this.reportPreview = this.$markRaw(j);
				this.selectedRow = null;
			} catch (e: any) {
				this.reportPreview = null;
				this.showToast(e?.message || this.t('app.commerce-reports.loadFailed', undefined, 'Could not load the report.'), true);
			}
		},
		// Stream the CSV through the browser's downloader; the endpoint sets
		// Content-Disposition, so a plain navigation saves the file.
		downloadReportCsv() {
			if (!this.canDownload) return;
			const url = `${this._base}${REPORTS_SCRIPT}?${this.reportQuery('csv')}`;
			try {
				const a = document.createElement('a');
				a.href = url;
				a.download = '';
				document.body.appendChild(a);
				a.click();
				a.remove();
			} catch (_) { window.open(url, '_blank'); }
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
