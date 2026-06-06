// Commerce PIM — operator UI for the Product Information Management overlay
// (category G, #23).
//
// Lets an operator full-text search the mirrored Shopify catalog, then author the
// CMS-side enrichment overlay for a product: multi-language titles/descriptions,
// custom attributes and metafields. It reads/writes through the PIM endpoint
// (/content/commerce/endpoints/pim.groovy), so every save is versioned, full-text
// searchable and ACL-governed on the product node. CMS-authored metafields can be
// pushed to Shopify through the sync endpoint (Admin API permitting).
//
// The Shopify base (title/handle/status/variants) is shown read-only for reference;
// only the overlay is editable.

import { VDOM } from '@mintjamsinc/ichigojs';

// Type-only: the shell passes a fully-featured ApplicationInstance at launch.
type AnyInstance = any;

const PIM_SCRIPT = '/content/commerce/endpoints/pim.groovy';
const SYNC_SCRIPT = '/content/commerce/endpoints/sync.groovy';

interface LocaleRow { locale: string; title: string; description_html: string; }
interface AttrRow { key: string; value: string; }
interface MetafieldRow { namespace: string; key: string; type: string; value: string; }

// Overlay keys the editor manages directly; anything else is preserved verbatim.
const MANAGED_KEYS = new Set(['localized', 'attributes', 'metafields', 'updatedAt', 'updatedBy']);

const App = {
	data() {
		return {
			instance: null as AnyInstance,

			// Search
			searchQuery: '',
			searching: false,
			searched: false,
			results: [] as any[],

			// Selected product
			loading: false,
			product: null as any,            // the unified view { productId, base, metafields, pim }
			base: { title: '', handle: '', status: '', vendor: '', productType: '', tags: '', variants: [] as any[] },

			// Editable overlay model
			localized: [] as LocaleRow[],
			attributes: [] as AttrRow[],
			metafields: [] as MetafieldRow[],
			overlayStamp: '',

			// Capabilities / state
			adminApiEnabled: false,
			saving: false,
			pushing: false,
			status: '',
			statusKind: '' as '' | 'ok' | 'err',
			toast: '',
			toastError: false,

			confirmDialog: { visible: false, resolve: null as null | ((a: 'save' | 'discard' | 'cancel') => void) },

			_rawPim: {} as any,              // last-loaded/saved overlay (for change tracking + preservation)
			_savedModelJson: '[]',
			_base: '' as string,
			_messageListener: null as any,
			_toastTimer: null as any,
		};
	},

	computed: {
		// Any edit to the overlay model (vs the last load/save) marks the editor dirty.
		// "Unsaved changes" only has meaning once a product is loaded; with no product
		// selected there is nothing to save, so the editor is never dirty.
		hasChanges(): boolean {
			if (!this.product) return false;
			return JSON.stringify(this.modelSnapshot()) !== this._savedModelJson;
		},
	},

	methods: {
		onMounted() {
			const vm = this;

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

				try { instance.windowTitle = 'Commerce PIM'; } catch (_) {}

				// Warn before discarding unsaved edits on window close.
				if (typeof instance.setBeforeCloseCallback === 'function') {
					instance.setBeforeCloseCallback(async () => vm.confirmDiscard());
				}

				await vm.resolveBase();
				await vm.loadCapabilities();

				vm.$nextTick(() => { try { instance.notifyLaunched(); } catch (_) {} });
			};
		},

		onUnmount() {
			if (this._messageListener) window.removeEventListener('message', this._messageListener);
			if (this._toastTimer) clearTimeout(this._toastTimer);
		},

		// ---- Window controls -------------------------------------------------
		onMinimizeWindow() { this.instance?.minimize(); },
		onToggleMaximizeWindow() { this.instance?.toggleMaximize(); },
		onCloseWindow() { this.instance?.requestClose(); },

		// ---- Base / capabilities ---------------------------------------------
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

		async loadCapabilities() {
			try {
				const res = await fetch(`${this._base}${SYNC_SCRIPT}`, { headers: { Accept: 'application/json' }, credentials: 'same-origin' });
				if (res.ok) { const j = await res.json(); this.adminApiEnabled = j.enabled === true; }
			} catch (_) { this.adminApiEnabled = false; }
		},

		// ---- Search ----------------------------------------------------------
		async search() {
			const q = (this.searchQuery || '').trim();
			if (!q) { this.results = []; this.searched = false; return; }
			if (!this._base) { this.showToast('Could not resolve the workspace.', true); return; }
			this.searching = true;
			try {
				const res = await fetch(`${this._base}${PIM_SCRIPT}?q=${encodeURIComponent(q)}&limit=100`, { headers: { Accept: 'application/json' }, credentials: 'same-origin' });
				if (!res.ok) throw new Error(`Search failed (${res.status})`);
				const j = await res.json();
				this.results = this.$markRaw(Array.isArray(j.results) ? j.results : []);
				this.searched = true;
			} catch (e: any) {
				this.showToast(e?.message || 'Search failed.', true);
			} finally {
				this.searching = false;
			}
		},

		// Clear the query and reset the result list back to the initial state.
		clearSearch() {
			this.searchQuery = '';
			this.results = [];
			this.searched = false;
		},

		// ---- Product load / select -------------------------------------------
		async selectProduct(productId: string) {
			if (this.product && this.product.productId === productId) return;
			if (this.hasChanges) {
				const action = await this.askDiscard();
				if (action === 'cancel') return;
				if (action === 'save') { await this.save(); if (this.hasChanges) return; /* save failed */ }
			}
			await this.loadProduct(productId);
		},

		async loadProduct(productId: string) {
			if (!this._base) { this.showToast('Could not resolve the workspace.', true); return; }
			this.loading = true;
			try {
				const res = await fetch(`${this._base}${PIM_SCRIPT}?productId=${encodeURIComponent(productId)}`, { headers: { Accept: 'application/json' }, credentials: 'same-origin' });
				if (res.status === 404) { this.showToast('Product not found.', true); this.loading = false; return; }
				if (!res.ok) throw new Error(`Load failed (${res.status})`);
				const view = await res.json();
				this.product = this.$markRaw(view);
				const b = view.base || {};
				this.base = {
					title: b.title || '', handle: b.handle || '', status: b.status || '',
					vendor: b.vendor || '', productType: b.productType || '',
					tags: Array.isArray(b.tags) ? b.tags.join(', ') : (b.tags || ''),
					variants: Array.isArray(b.variants) ? b.variants : [],
				};
				this._rawPim = view.pim && typeof view.pim === 'object' ? view.pim : {};
				this.populateFromOverlay();
				this.setStatus('', '');
			} catch (e: any) {
				this.showToast(e?.message || 'Could not load product.', true);
			} finally {
				this.loading = false;
			}
		},

		// Derive the editable arrays from the loaded overlay and snapshot them.
		populateFromOverlay() {
			const pim = this._rawPim || {};
			const loc: LocaleRow[] = [];
			if (pim.localized && typeof pim.localized === 'object') {
				for (const code of Object.keys(pim.localized)) {
					const e = pim.localized[code] || {};
					loc.push({ locale: code, title: e.title || '', description_html: e.description_html || '' });
				}
			}
			const attrs: AttrRow[] = [];
			if (pim.attributes && typeof pim.attributes === 'object') {
				for (const k of Object.keys(pim.attributes)) attrs.push({ key: k, value: stringify(pim.attributes[k]) });
			}
			const mfs: MetafieldRow[] = [];
			if (Array.isArray(pim.metafields)) {
				for (const m of pim.metafields) mfs.push({ namespace: m.namespace || '', key: m.key || '', type: m.type || '', value: stringify(m.value) });
			}
			this.localized = loc;
			this.attributes = attrs;
			this.metafields = mfs;
			this.overlayStamp = pim.updatedAt
				? `${new Date(pim.updatedAt).toLocaleString()}${pim.updatedBy ? ' · ' + pim.updatedBy : ''}`
				: '';
			this._savedModelJson = JSON.stringify(this.modelSnapshot());
		},

		modelSnapshot() { return { localized: this.localized, attributes: this.attributes, metafields: this.metafields }; },

		revert() {
			if (!this.hasChanges) return;
			this.populateFromOverlay();
			this.setStatus('', '');
		},

		// ---- Row management --------------------------------------------------
		addLocale() { this.localized.push({ locale: '', title: '', description_html: '' }); },
		addAttribute() { this.attributes.push({ key: '', value: '' }); },
		addMetafield() { this.metafields.push({ namespace: '', key: '', type: 'single_line_text_field', value: '' }); },

		// ---- Save ------------------------------------------------------------
		// Build the overlay to persist, preserving any unmanaged keys (and unmanaged
		// per-locale subfields) from the loaded overlay so the editor never drops data.
		buildOverlay(): any {
			const overlay: any = {};
			for (const k of Object.keys(this._rawPim || {})) if (!MANAGED_KEYS.has(k)) overlay[k] = this._rawPim[k];

			const loc: any = {};
			for (const row of this.localized) {
				const code = (row.locale || '').trim();
				if (!code) continue;
				const prior = (this._rawPim?.localized && this._rawPim.localized[code]) || {};
				const entry: any = { ...prior };
				if ((row.title || '').trim()) entry.title = row.title; else delete entry.title;
				if ((row.description_html || '').trim()) entry.description_html = row.description_html; else delete entry.description_html;
				if (Object.keys(entry).length) loc[code] = entry;
			}
			if (Object.keys(loc).length) overlay.localized = loc;

			const attrs: any = {};
			for (const row of this.attributes) { const k = (row.key || '').trim(); if (k) attrs[k] = row.value; }
			if (Object.keys(attrs).length) overlay.attributes = attrs;

			const mfs: any[] = [];
			for (const row of this.metafields) {
				const ns = (row.namespace || '').trim(), key = (row.key || '').trim();
				if (!ns || !key) continue;
				mfs.push({ namespace: ns, key, type: (row.type || '').trim() || 'single_line_text_field', value: row.value });
			}
			if (mfs.length) overlay.metafields = mfs;

			return overlay;
		},

		async save() {
			if (!this.product || this.saving) return;
			this.saving = true;
			try {
				const overlay = this.buildOverlay();
				const res = await fetch(`${this._base}${PIM_SCRIPT}`, {
					method: 'POST',
					headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
					credentials: 'same-origin',
					body: JSON.stringify({ productId: this.product.productId, pim: overlay, merge: false }),
				});
				const j = await res.json().catch(() => ({}));
				if (!res.ok || j.ok === false) throw new Error(j.error || `Save failed (${res.status})`);
				this._rawPim = j.pim && typeof j.pim === 'object' ? j.pim : overlay;
				this.populateFromOverlay();
				this.setStatus('ok', 'Saved');
			} catch (e: any) {
				this.showToast(e?.message || 'Save failed.', true);
				this.setStatus('err', 'Save failed');
			} finally {
				this.saving = false;
			}
		},

		// ---- Push metafields to Shopify --------------------------------------
		async pushMetafields() {
			if (!this.product || this.pushing) return;
			if (this.hasChanges) { this.showToast('Save your changes before pushing.', true); return; }
			this.pushing = true;
			try {
				const res = await fetch(`${this._base}${SYNC_SCRIPT}`, {
					method: 'POST',
					headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
					credentials: 'same-origin',
					body: JSON.stringify({ action: 'metafields', productId: this.product.productId }),
				});
				const j = await res.json().catch(() => ({}));
				if (!res.ok || j.ok === false) throw new Error(j.error || `Push failed (${res.status})`);
				this.showToast('Metafields pushed to Shopify.', false);
			} catch (e: any) {
				this.showToast(e?.message || 'Push failed.', true);
			} finally {
				this.pushing = false;
			}
		},

		// ---- Unsaved-changes dialog ------------------------------------------
		async confirmDiscard(): Promise<boolean> {
			if (!this.hasChanges) return true;
			const action = await this.askDiscard();
			if (action === 'cancel') return false;
			if (action === 'discard') return true;
			await this.save();
			return !this.hasChanges;
		},

		askDiscard(): Promise<'save' | 'discard' | 'cancel'> {
			const vm = this;
			vm.confirmDialog.visible = true;
			return new Promise((resolve) => { vm.confirmDialog.resolve = resolve; });
		},

		onConfirmAction(action: 'save' | 'discard' | 'cancel') {
			if (this.confirmDialog.resolve) this.confirmDialog.resolve(action);
			this.confirmDialog.visible = false;
			this.confirmDialog.resolve = null;
		},

		// ---- Status / toast --------------------------------------------------
		setStatus(kind: '' | 'ok' | 'err', msg: string) { this.statusKind = kind; this.status = msg; },
		showToast(msg: string, isError: boolean) {
			this.toast = msg; this.toastError = !!isError;
			if (this._toastTimer) clearTimeout(this._toastTimer);
			this._toastTimer = window.setTimeout(() => { this.toast = ''; }, 3200);
		},
	},
};

function stringify(v: any): string {
	if (v == null) return '';
	if (typeof v === 'string') return v;
	try { return JSON.stringify(v); } catch (_) { return String(v); }
}

VDOM.createApp(App).mount('#app');
