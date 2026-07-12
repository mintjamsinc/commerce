// Commerce Orders — the order facet browser.
//
// A read-only browser over the mirrored Shopify orders
// (/content/commerce/orders/raw/{yyyy}/{MM}, backend: orders.groovy?view=browse):
// full-text search plus drill-down facets built from the auto-indexed order
// properties (integration status / Shopify financial status / currency). Facet
// counts always reflect the current filter set. The list is sorted server-side
// by order number, descending.
//
// The browser never edits anything. Opening a row hands the order node to the
// Commerce Order editor through the shell's MIME association — the same path a
// double-click in the Content Browser takes — so the editor stays the single
// write hub.

import { VDOM } from '@mintjamsinc/ichigojs';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
	formatDate,
	formatCurrency,
} from '../../composables/use-localization.js';
import { wallClockToIso, completeDateTimeLocal } from '../../composables/wire-datetime.js';

type AnyInstance = any;

const ORDERS_SCRIPT = '/content/commerce/endpoints/orders.groovy';
const ORDER_MIME = 'application/vnd.mintjams.commerce.order+json';
const PAGE_SIZE = 50;

// orders.groovy returns each facet as a { value: count } map; render them as
// count-descending rows (ties broken alphabetically) like the customer browser.
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
			// customerId / productId take a Shopify GID or a numeric id — passed
			// through opaquely (the client never decomposes GIDs). from / to are
			// bare dates completed to an ordered-at day range on the wire.
			filters: { q: '', status: '', financial: '', currency: '', customerId: '', productId: '', from: '', to: '' },
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
		facetStatus(): any[] { return facetRows(this.facets.status); },
		facetFinancial(): any[] { return facetRows(this.facets.financial); },
		facetCurrency(): any[] { return facetRows(this.facets.currency); },

		activeFilterCount(): number {
			let n = 0;
			if (this.filters.status) n++;
			if (this.filters.financial) n++;
			if (this.filters.currency) n++;
			if (this.filters.customerId.trim()) n++;
			if (this.filters.productId.trim()) n++;
			if (this.filters.from) n++;
			if (this.filters.to) n++;
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
		// Format a wire money object { currency, amount } (or a bare amount +
		// currency pair) in the order's own currency, so a JPY order and a USD
		// order each render in their native currency. The amount is always a
		// JSON number (commerce.Api) — no string coercion needed.
		fmtMoney(value: any, currency?: any): string {
			if (value && typeof value === 'object') {
				currency = currency || value.currency;
				value = value.amount;
			}
			return formatCurrency(this.localization, value, currency ? { currency } : {});
		},
		fmtDateTime(value: any): string {
			return formatDate(this.localization, value, { format: 'datetime' });
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
				try { instance.windowTitle = vm.t('app.commerce-orders.title', undefined, 'Commerce Orders'); } catch (_) {}

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
				await db.setUserSetting(userID, 'commerce-orders', 'sidebar', JSON.parse(JSON.stringify({
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
				const state = await db.getUserSetting(userID, 'commerce-orders', 'sidebar');
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
			if (this.filters.status) p.set('status', this.filters.status);
			if (this.filters.financial) p.set('financial', this.filters.financial);
			if (this.filters.currency) p.set('currency', this.filters.currency);
			// Sales-fact filters, included only when set. IDs pass through as
			// opaque strings (GID or numeric — the endpoint accepts both). The bare
			// date inputs become ISO-8601 instants (platform wire convention)
			// covering the picked days inclusively, resolved in the effective
			// Preferences timezone via the shared wire-datetime composable.
			const customerId = this.filters.customerId.trim();
			if (customerId) p.set('customerId', customerId);
			const productId = this.filters.productId.trim();
			if (productId) p.set('productId', productId);
			const fromIso = wallClockToIso(completeDateTimeLocal(this.filters.from, false), this.localization.timeZone, false);
			if (fromIso) p.set('from', fromIso);
			const toIso = wallClockToIso(completeDateTimeLocal(this.filters.to, true), this.localization.timeZone, true);
			if (toIso) p.set('to', toIso);
			p.set('limit', String(PAGE_SIZE));
			p.set('page', String(this.page));
			return p.toString();
		},

		async getJson(query: string): Promise<any> {
			const res = await fetch(`${this._base}${ORDERS_SCRIPT}?${query}`, {
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
				this.loaded = true;
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-orders.err.loadFailed', undefined, 'Could not load orders.'), true);
			} finally {
				this.loading = false;
			}
		},

		search() { this.page = 1; this.load(); },

		// Toggle a facet filter (click again to clear) and reload from page 1.
		toggleFacet(kind: 'status' | 'financial' | 'currency', value: string) {
			const current = (this.filters as any)[kind];
			(this.filters as any)[kind] = current === value ? '' : value;
			this.page = 1;
			this.load();
		},

		clearFilters() {
			this.filters = { q: '', status: '', financial: '', currency: '', customerId: '', productId: '', from: '', to: '' };
			this.page = 1;
			this.load();
		},

		prevPage() { if (this.hasPrev) { this.page = Math.max(1, this.page - 1); this.load(); } },
		nextPage() { if (this.hasNext) { this.page = this.page + 1; this.load(); } },

		// ---- Open in the editor -------------------------------------------------
		// Same contract as the Content Browser's double-click: resolve the editor
		// registered for the order MIME type from the shell's app registry and ask
		// the shell to launch it with the node path.
		openOrder(row: any) {
			if (!row || !row.path) return;
			const editor = this.findEditorForMimeType(ORDER_MIME);
			if (!editor) {
				this.showToast(this.t('app.commerce-orders.err.noEditor', undefined, 'The Commerce Order editor is not installed.'), true);
				return;
			}
			window.parent.postMessage({
				type: 'open-file-with-app',
				appId: editor.id,
				filePath: row.path,
				mimeType: ORDER_MIME,
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
