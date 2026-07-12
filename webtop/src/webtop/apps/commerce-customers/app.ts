// Commerce Customers — the customer facet browser.
//
// A read-only browser over the mirrored Shopify customers
// (/content/commerce/customers, backend: crm.groovy?view=browse →
// commerce.Crm.browse): full-text search plus drill-down facets built from the
// auto-indexed customer properties (tags / marketing consent / Shopify source
// status). Facet counts always reflect the current filter set.
//
// The browser never edits anything. Opening a row hands the customer node to
// the Commerce Customer editor through the shell's MIME association — the same
// path a double-click in the Content Browser takes — so the editor stays the
// single write hub.

import { VDOM } from '@mintjamsinc/ichigojs';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
	formatDate,
	formatNumber,
	formatCurrency,
} from '../../composables/use-localization.js';

type AnyInstance = any;

const CRM_SCRIPT = '/content/commerce/endpoints/crm.groovy';
const CUSTOMER_MIME = 'application/vnd.mintjams.commerce.customer+json';
const PAGE_SIZE = 50;

// crm.groovy returns each facet as a { value: count } map; render them as
// count-descending rows (ties broken alphabetically) like the product browser.
function facetRows(map: any): any[] {
	if (!map || typeof map !== 'object') return [];
	const out: any[] = [];
	for (const k of Object.keys(map)) out.push({ value: k, count: Number(map[k]) || 0 });
	out.sort((a, b) => (b.count - a.count) || String(a.value).localeCompare(String(b.value)));
	return out;
}

const App = {
	data() {
		return {
			instance: null as AnyInstance,
			localization: createLocalizationSnapshot(),

			// Active drill-down (q = full-text; the rest are exact facet filters).
			filters: { q: '', tag: '', marketing: '', sourceStatus: '' },
			// Spend axis (backend: sort=updated|spend, spendMetric, spendFrom,
			// minSpend — base-currency amounts). days 0 = all time; minSpend is a
			// base-currency decimal, sent only when non-empty.
			spend: { sort: 'updated', metric: 'totalPrice', days: 0, minSpend: '' },
			// The window echoed by the last response — drives the spend columns, so
			// they only appear once spend-carrying rows are actually shown.
			spendWindow: null as any,
			page: 1,

			loading: false,
			loaded: false,
			total: 0,
			pageSize: PAGE_SIZE,
			capped: false,
			results: [] as any[],
			facets: {} as any,

			toast: '',
			toastError: false,

			_base: '' as string,
			_messageListener: null as any,
			_toastTimer: null as any,

			// Resizable facet sidebar (mirrors commerce settings / content-browser).
			sidebarVisible: true,
			sidebarWidth: 240,          // 15rem default — matches the prior fixed width
			sidebarMinWidth: 180,
			sidebarMaxWidth: 400,
			sidebarResizing: false,
			sidebarResizeStartX: 0,
			sidebarResizeStartWidth: 0,
			_boundSidebarResizeMove: null as any,
			_boundSidebarResizeUp: null as any,
		};
	},

	computed: {
		facetTags(): any[] { return facetRows(this.facets.tags); },
		facetMarketing(): any[] { return facetRows(this.facets.marketing); },
		facetSourceStatus(): any[] { return facetRows(this.facets.sourceStatus); },

		// The spend axis is active when it ranks (sort=spend) or filters (minSpend).
		spendActive(): boolean {
			return this.spend.sort === 'spend' || String(this.spend.minSpend).trim() !== '';
		},
		// Spend columns appear only when the response actually carried the axis.
		spendColumns(): boolean { return !!this.spendWindow; },

		activeFilterCount(): number {
			let n = 0;
			if (this.filters.tag) n++;
			if (this.filters.marketing) n++;
			if (this.filters.sourceStatus) n++;
			if (String(this.spend.minSpend).trim()) n++;
			return n;
		},
		pageStart(): number { return this.total === 0 ? 0 : (this.page - 1) * this.pageSize + 1; },
		pageEnd(): number { return (this.page - 1) * this.pageSize + this.results.length; },
		hasPrev(): boolean { return this.page > 1; },
		hasNext(): boolean { return (this.page - 1) * this.pageSize + this.results.length < this.total; },
	},

	methods: {
		// ---- i18n / locale-aware formatting ---------------------------------
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},
		fmtDateTime(value: any): string {
			return formatDate(this.localization, value, { format: 'datetime' });
		},
		// Integer count with grouping (orders).
		fmtInt(v: any): string {
			if (v == null || v === '') return '—';
			return formatNumber(this.localization, v, { maximumFractionDigits: 0 });
		},
		// Base-currency amount with the currency CODE shown (unambiguous for a
		// multi-currency shop), mirroring commerce-reports. Amounts are JSON
		// numbers on the wire (commerce.Api) — no string coercion needed.
		fmtMoney(amount: any, currency: any): string {
			if (amount == null || amount === '') return '—';
			const c = String(currency || '').trim();
			if (c) return formatCurrency(this.localization, amount, { currency: c, currencyDisplay: 'code' });
			return formatNumber(this.localization, amount);
		},
		marketingLabel(enabled: any): string {
			return enabled
				? this.t('app.commerce-customers.marketing.subscribed', undefined, 'Subscribed')
				: this.t('app.commerce-customers.marketing.unsubscribed', undefined, 'Not subscribed');
		},

		onMounted() {
			const vm = this;
			vm._messageListener = (event: MessageEvent) => {
				const data: any = event.data || {};
				if (handleLocalizationMessage(data.type, vm.localization, vm.instance)) return;
				if (data.type === 'theme-changed' && data.theme) {
					document.documentElement.dataset.theme = data.theme;
				} else if (data.type === 'app-reopen') {
					vm.load();
				}
			};
			window.addEventListener('message', vm._messageListener);

			window.appLaunch = async (instance: AnyInstance) => {
				vm.instance = vm.$markRaw(instance);
				try { document.documentElement.dataset.theme = instance.api.theme.currentTheme || 'light'; } catch (_) {}
				refreshLocalization(vm.localization, vm.instance);
				try { instance.windowTitle = vm.t('app.commerce-customers.title', undefined, 'Commerce Customers'); } catch (_) {}

				await vm.resolveBase();
				await vm.loadSidebarState();
				await vm.load();

				vm.$nextTick(() => { try { instance.notifyLaunched(); } catch (_) {} });
			};
		},

		onUnmount() {
			if (this._messageListener) window.removeEventListener('message', this._messageListener);
			if (this._toastTimer) clearTimeout(this._toastTimer);
			if (this._boundSidebarResizeMove) document.removeEventListener('mousemove', this._boundSidebarResizeMove);
			if (this._boundSidebarResizeUp) document.removeEventListener('mouseup', this._boundSidebarResizeUp);
		},

		// ---- Window controls -------------------------------------------------
		onMinimizeWindow() { this.instance?.minimize(); },
		onToggleMaximizeWindow() { this.instance?.toggleMaximize(); },
		onCloseWindow() { this.instance?.requestClose(); },

		// ---- Left facet sidebar (toggle / resize / persist) ------------------
		toggleSidebar() {
			this.sidebarVisible = !this.sidebarVisible;
			this.persistSidebarState();
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
				vm.persistSidebarState();
			};
			document.addEventListener('mousemove', vm._boundSidebarResizeMove);
			document.addEventListener('mouseup', vm._boundSidebarResizeUp);
		},
		async persistSidebarState() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				await db.setUserSetting(userID, 'commerce-customers', 'sidebar', JSON.parse(JSON.stringify({
					visible: vm.sidebarVisible,
					width: vm.sidebarWidth,
				})));
			} catch (_) { /* non-critical */ }
		},
		async loadSidebarState() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				const state = await db.getUserSetting(userID, 'commerce-customers', 'sidebar');
				if (state) {
					vm.sidebarVisible = state.visible ?? true;
					vm.sidebarWidth = state.width ?? vm.sidebarWidth;
				}
			} catch (_) { /* non-critical */ }
		},

		// ---- Data ------------------------------------------------------------
		async resolveBase() {
			let ws: string | null = null;
			try { ws = this.instance?.api?.workspace || null; } catch (_) { ws = null; }
			if (ws) { this._base = `/bin/cms.cgi/${ws}`; return; }
			try {
				const node = await this.instance.api.content.getNode('/content');
				const url = String(node?.downloadUrl || '');
				const m = url.match(/\/bin\/[^/]*cgi\/([^/?#]+)/);
				if (m) { this._base = `/bin/cms.cgi/${m[1]}`; return; }
			} catch (_) { /* fall through */ }
			this._base = '';
		},

		browseQuery(): string {
			const p = new URLSearchParams();
			p.set('view', 'browse');
			if (this.filters.q.trim()) p.set('q', this.filters.q.trim());
			if (this.filters.tag) p.set('tag', this.filters.tag);
			if (this.filters.marketing) p.set('marketing', this.filters.marketing);
			if (this.filters.sourceStatus) p.set('sourceStatus', this.filters.sourceStatus);
			// Spend axis: parameters go over the wire only while the axis is active
			// (sort=spend and/or a minSpend filter). spendFrom is an ISO-8601
			// instant (platform wire convention); absent = all time. spendMetric is
			// omitted at its backend default (totalPrice).
			if (this.spend.sort === 'spend') p.set('sort', 'spend');
			const minSpend = String(this.spend.minSpend).trim();
			if (minSpend) p.set('minSpend', minSpend);
			if (this.spend.sort === 'spend' || minSpend) {
				if (this.spend.metric !== 'totalPrice') p.set('spendMetric', this.spend.metric);
				if (this.spend.days > 0) p.set('spendFrom', new Date(Date.now() - this.spend.days * 86400000).toISOString());
			}
			p.set('limit', String(PAGE_SIZE));
			p.set('page', String(this.page));
			return p.toString();
		},

		async getJson(query: string): Promise<any> {
			const res = await fetch(`${this._base}${CRM_SCRIPT}?${query}`, {
				headers: { Accept: 'application/json' }, credentials: 'same-origin',
			});
			if (!res.ok) throw new Error(`Request failed (${res.status})`);
			return res.json();
		},

		async load() {
			this.loading = true;
			try {
				const j = await this.getJson(this.browseQuery());
				this.total = Number(j.total) || 0;
				this.page = Number(j.page) || this.page;
				this.pageSize = Number(j.pageSize) || PAGE_SIZE;
				this.capped = j.capped === true;
				this.results = this.$markRaw(Array.isArray(j.items) ? j.items : []);
				this.facets = this.$markRaw((j.facets && typeof j.facets === 'object') ? j.facets : {});
				this.spendWindow = (j.spendWindow && typeof j.spendWindow === 'object') ? this.$markRaw(j.spendWindow) : null;
				this.loaded = true;
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-customers.err.loadFailed', undefined, 'Could not load customers.'), true);
			} finally {
				this.loading = false;
			}
		},

		search() { this.page = 1; this.load(); },

		// ---- Spend axis --------------------------------------------------------
		// Pick the sort axis (always exactly one active) and reload from page 1.
		setSpendSort(value: 'updated' | 'spend') {
			if (this.spend.sort === value) return;
			this.spend.sort = value;
			this.page = 1;
			this.load();
		},
		// Pick the metric / rolling window; requery only while the axis is active
		// (otherwise neither parameter is on the wire and the list is unchanged).
		setSpendMetric(value: 'totalPrice' | 'gross' | 'net') {
			if (this.spend.metric === value) return;
			this.spend.metric = value;
			if (this.spendActive) { this.page = 1; this.load(); }
		},
		setSpendDays(days: number) {
			if (this.spend.days === days) return;
			this.spend.days = days;
			if (this.spendActive) { this.page = 1; this.load(); }
		},
		// Apply the min-spend filter (Enter in the input or the Apply button).
		applyMinSpend() { this.page = 1; this.load(); },

		// Toggle a facet filter (click again to clear) and reload from page 1.
		toggleFacet(kind: 'tag' | 'marketing' | 'sourceStatus', value: string) {
			const current = (this.filters as any)[kind];
			(this.filters as any)[kind] = current === value ? '' : value;
			this.page = 1;
			this.load();
		},

		clearFilters() {
			this.filters = { q: '', tag: '', marketing: '', sourceStatus: '' };
			this.spend.minSpend = '';   // a filter — the sort axis itself stays put
			this.page = 1;
			this.load();
		},

		prevPage() { if (this.hasPrev) { this.page = Math.max(1, this.page - 1); this.load(); } },
		nextPage() { if (this.hasNext) { this.page = this.page + 1; this.load(); } },

		// ---- Open in the editor -------------------------------------------------
		// Same contract as the Content Browser's double-click: resolve the editor
		// registered for the customer MIME type from the shell's app registry and
		// ask the shell to launch it with the node path.
		openCustomer(row: any) {
			if (!row || !row.path) return;
			const editor = this.findEditorForMimeType(CUSTOMER_MIME);
			if (!editor) {
				this.showToast(this.t('app.commerce-customers.err.noEditor', undefined, 'The Commerce Customer editor is not installed.'), true);
				return;
			}
			window.parent.postMessage({
				type: 'open-file-with-app',
				appId: editor.id,
				filePath: row.path,
				mimeType: CUSTOMER_MIME,
			}, window.location.origin);
		},

		findEditorForMimeType(mimeType: string): any {
			let apps: any[] = [];
			try { apps = (window.parent as any)?.Webtop?.apps || []; } catch (_) { apps = []; }
			for (const app of apps) {
				if (!app.editor) continue;
				const contentTypes = app.contentTypes || [];
				for (const pattern of contentTypes) {
					if (pattern.endsWith('/*')) {
						if (mimeType.startsWith(pattern.slice(0, -1))) return app;
					} else if (pattern === mimeType) {
						return app;
					}
				}
			}
			return null;
		},

		// ---- Toast -------------------------------------------------------------
		showToast(msg: string, isError: boolean) {
			this.toast = msg; this.toastError = !!isError;
			if (this._toastTimer) clearTimeout(this._toastTimer);
			this._toastTimer = window.setTimeout(() => { this.toast = ''; }, 3200);
		},
	},
};

VDOM.createApp(App).mount('#app');
