// Commerce Products — the product facet browser.
//
// A read-only catalog browser over the mirrored Shopify products: full-text
// search plus drill-down facets built from the auto-indexed commerce:*
// properties (vendor / product type / tags / Shopify status / processing
// status). Facet counts always reflect the current filter set (backend:
// pim.groovy?view=browse → commerce.Pim.browse).
//
// The browser never edits anything. Opening a row hands the product node to
// the Commerce Product editor through the shell's MIME association — the same
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

const PIM_SCRIPT = '/content/commerce/endpoints/pim.groovy';
const PRODUCT_MIME = 'application/vnd.mintjams.commerce.product+json';
const PAGE_SIZE = 50;

function emptyFacets() {
	return { vendors: [], productTypes: [], tags: [], sourceStatuses: [], statuses: [] };
}

const App = {
	data() {
		return {
			instance: null as AnyInstance,
			localization: createLocalizationSnapshot(),

			// Active drill-down (q = full-text; the rest are exact facet filters).
			filters: { q: '', vendor: '', productType: '', tag: '', sourceStatus: '', status: '' },
			// Sales-fact sort axis (backend: sort=updated|sales|quantity + optional
			// salesFrom). salesDays 0 = all time; otherwise a rolling N-day window.
			sort: 'updated',
			salesDays: 0,
			// The sort echoed by the last response — drives the sales columns, so
			// they only appear once sales-ranked rows are actually shown.
			appliedSort: 'updated',
			offset: 0,

			loading: false,
			loaded: false,
			total: 0,
			capped: false,
			results: [] as any[],
			facets: emptyFacets() as any,

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
		activeFilterCount(): number {
			let n = 0;
			if (this.filters.vendor) n++;
			if (this.filters.productType) n++;
			if (this.filters.tag) n++;
			if (this.filters.sourceStatus) n++;
			if (this.filters.status) n++;
			return n;
		},
		// Sales columns appear only for a sales-fact ranking (per the applied sort).
		salesColumns(): boolean { return this.appliedSort === 'sales' || this.appliedSort === 'quantity'; },
		pageStart(): number { return this.total === 0 ? 0 : this.offset + 1; },
		pageEnd(): number { return Math.min(this.offset + this.results.length, this.offset + PAGE_SIZE); },
		hasPrev(): boolean { return this.offset > 0; },
		hasNext(): boolean { return this.offset + this.results.length < this.total; },
	},

	methods: {
		// ---- i18n / locale-aware formatting ---------------------------------
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},
		fmtDateTime(value: any): string {
			return formatDate(this.localization, value, { format: 'datetime' });
		},
		// Integer count with grouping (units sold).
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
				try { instance.windowTitle = vm.t('app.commerce-products.title', undefined, 'Commerce Products'); } catch (_) {}

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
				await db.setUserSetting(userID, 'commerce-products', 'sidebar', JSON.parse(JSON.stringify({
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
				const state = await db.getUserSetting(userID, 'commerce-products', 'sidebar');
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
			if (this.filters.vendor) p.set('vendor', this.filters.vendor);
			if (this.filters.productType) p.set('productType', this.filters.productType);
			if (this.filters.tag) p.set('tag', this.filters.tag);
			if (this.filters.sourceStatus) p.set('sourceStatus', this.filters.sourceStatus);
			if (this.filters.status) p.set('status', this.filters.status);
			// Sales-fact ranking: parameters go over the wire only when a sales sort
			// is active (sort=updated is the backend default). salesFrom is an
			// ISO-8601 instant (platform wire convention); absent = all time.
			if (this.sort !== 'updated') {
				p.set('sort', this.sort);
				if (this.salesDays > 0) p.set('salesFrom', new Date(Date.now() - this.salesDays * 86400000).toISOString());
			}
			p.set('limit', String(PAGE_SIZE));
			p.set('offset', String(this.offset));
			return p.toString();
		},

		async load() {
			this.loading = true;
			try {
				const res = await fetch(`${this._base}${PIM_SCRIPT}?${this.browseQuery()}`, {
					headers: { Accept: 'application/json' }, credentials: 'same-origin',
				});
				if (!res.ok) throw new Error(`Request failed (${res.status})`);
				const j = await res.json();
				this.total = Number(j.total) || 0;
				this.capped = j.capped === true;
				this.appliedSort = (j.sort === 'sales' || j.sort === 'quantity') ? j.sort : 'updated';
				this.results = this.$markRaw(Array.isArray(j.results) ? j.results : []);
				const f = (j.facets && typeof j.facets === 'object') ? j.facets : {};
				this.facets = this.$markRaw({
					vendors: Array.isArray(f.vendors) ? f.vendors : [],
					productTypes: Array.isArray(f.productTypes) ? f.productTypes : [],
					tags: Array.isArray(f.tags) ? f.tags : [],
					sourceStatuses: Array.isArray(f.sourceStatuses) ? f.sourceStatuses : [],
					statuses: Array.isArray(f.statuses) ? f.statuses : [],
				});
				this.loaded = true;
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-products.err.loadFailed', undefined, 'Could not load products.'), true);
			} finally {
				this.loading = false;
			}
		},

		search() { this.offset = 0; this.load(); },

		// Pick the sort axis (always exactly one active) and reload from page 1.
		setSort(value: 'updated' | 'sales' | 'quantity') {
			if (this.sort === value) return;
			this.sort = value;
			this.offset = 0;
			this.load();
		},
		// Pick the rolling sales window (0 = all time) and reload from page 1.
		setSalesDays(days: number) {
			if (this.salesDays === days) return;
			this.salesDays = days;
			this.offset = 0;
			this.load();
		},

		// Toggle a facet filter (click again to clear) and reload from page 1.
		toggleFacet(kind: 'vendor' | 'productType' | 'tag' | 'sourceStatus' | 'status', value: string) {
			const current = (this.filters as any)[kind];
			(this.filters as any)[kind] = current === value ? '' : value;
			this.offset = 0;
			this.load();
		},

		clearFilters() {
			this.filters = { q: '', vendor: '', productType: '', tag: '', sourceStatus: '', status: '' };
			this.offset = 0;
			this.load();
		},

		prevPage() { if (this.hasPrev) { this.offset = Math.max(0, this.offset - PAGE_SIZE); this.load(); } },
		nextPage() { if (this.hasNext) { this.offset = this.offset + PAGE_SIZE; this.load(); } },

		// ---- Open in the editor -------------------------------------------------
		// Same contract as the Content Browser's double-click: resolve the editor
		// registered for the product MIME type from the shell's app registry and
		// ask the shell to launch it with the node path.
		openProduct(row: any) {
			if (!row || !row.path) return;
			const editor = this.findEditorForMimeType(PRODUCT_MIME);
			if (!editor) {
				this.showToast(this.t('app.commerce-products.err.noEditor', undefined, 'The Commerce Product editor is not installed.'), true);
				return;
			}
			window.parent.postMessage({
				type: 'open-file-with-app',
				appId: editor.id,
				filePath: row.path,
				mimeType: PRODUCT_MIME,
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
