// Commerce Product — the "product 360" editor (replaces Commerce PIM).
//
// Everything about ONE product in one place, Shopify-admin-like:
//   • Overview  — Shopify base + processing state, publish / unpublish
//   • Variants  — price editing and per-location stock editing
//   • Content   — the multi-language PIM overlay (titles / rich descriptions)
//   • Metafields— CMS-authored metafields + push to Shopify
//   • Planning  — the per-variant fixed reorder threshold (a unit count): when
//                 stock drops at or below it, the "Stock Check + Reorder" task opens.
//                 Operator-set only; no system proposal.
//
// WRITE MODEL (the Shopify integration's data-flow policy): every Shopify-owned field (price,
// stock, publish state, metafields) is written to SHOPIFY via the sync endpoint
// (Admin API); the CMS mirror is never edited directly — it follows through the
// webhook round-trip. Only CMS-authoritative data (the PIM overlay, planning
// values) is saved to the CMS (pim.groovy).
//
// Launch: from the product browser / Content Browser via the product MIME type
// (options.path → /content/commerce/products/product_{id}.json). Catalog search
// lives in the separate product browser app (commerce-products).

import { VDOM } from '@mintjamsinc/ichigojs';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
	formatDate,
} from '../../composables/use-localization.js';

type AnyInstance = any;

const PIM_SCRIPT = '/content/commerce/endpoints/pim.groovy';
const SYNC_SCRIPT = '/content/commerce/endpoints/sync.groovy';
const LOCATIONS_SCRIPT = '/content/commerce/endpoints/inventory-locations.groovy';
const MEDIA_SCRIPT = '/content/commerce/endpoints/product-media.groovy';
const PLANNING_YML = '/etc/commerce/config/planning.yml';

// Product base status values (Shopify GraphQL productStatus enum). The mirror
// carries these lower-cased; the editor's status dropdown / save use the enum form.
const PRODUCT_STATUSES = ['ACTIVE', 'DRAFT', 'ARCHIVED'] as const;

// Split a Shopify tags value (comma string or array) into a trimmed, de-duplicated
// list — used both to normalize the base tags input and to diff it for a save.
function splitTags(v: any): string[] {
	const raw = Array.isArray(v) ? v.map((x) => String(x)) : String(v || '').split(',');
	const out: string[] = [];
	const seen = new Set<string>();
	for (const t of raw) {
		const s = t.trim();
		if (!s) continue;
		const k = s.toLowerCase();
		if (seen.has(k)) continue;
		seen.add(k);
		out.push(s);
	}
	return out;
}

function tagsEqual(a: string[], b: string[]): boolean {
	if (a.length !== b.length) return false;
	for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
	return true;
}

interface LocaleRow { locale: string; title: string; description_html: string; }
interface MetafieldRow { namespace: string; key: string; type: string; value: string; }
interface PlanningRow {
	variantId: string; title: string; threshold: any;
}

// Overlay keys the editor manages directly; anything else (including the retired
// free-form `attributes`) is preserved verbatim so a save never drops data.
const MANAGED_KEYS = new Set(['localized', 'metafields', 'planning', 'updatedAt', 'updatedBy']);

const PLANNING_PARAMS = ['threshold'] as const;

// Minimal 2-level YAML reader for planning.yml (same controlled shape as the
// Commerce settings app's parser).
function parseSimpleYaml(text: string): Record<string, any> {
	const root: Record<string, any> = {};
	let parent: Record<string, any> | null = null;
	for (const rawLine of String(text || '').split(/\r?\n/)) {
		const noComment = rawLine.replace(/\s+#.*$/, '');
		const trimmed = noComment.trim();
		if (!trimmed || trimmed.startsWith('#')) continue;
		const m = trimmed.match(/^([A-Za-z0-9_.-]+)\s*:\s*(.*)$/);
		if (!m) continue;
		const key = m[1];
		let v: any = m[2].trim();
		if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) v = v.slice(1, -1);
		else if (v === 'true') v = true;
		else if (v === 'false') v = false;
		else if (/^-?\d+(\.\d+)?$/.test(v)) v = Number(v);
		const indent = noComment.length - noComment.replace(/^\s+/, '').length;
		if (indent === 0) {
			if (m[2].trim() === '') { root[key] = {}; parent = root[key]; }
			else { root[key] = v; parent = null; }
		} else if (parent) {
			parent[key] = v;
		}
	}
	return root;
}

function numOrEmpty(v: any): any {
	if (v == null || v === '') return '';
	const n = Number(v);
	return Number.isFinite(n) ? n : '';
}

const App = {
	data() {
		return {
			instance: null as AnyInstance,
			localization: createLocalizationSnapshot(),

			section: 'overview' as 'overview' | 'variants' | 'content' | 'metafields' | 'planning' | 'media',

			// Selected product
			loading: false,
			product: null as any,            // unified view { id (GID), base, metafields, pim }
			// Loaded Shopify base (the confirmed baseline; updated optimistically on save).
			base: { title: '', handle: '', status: '', vendor: '', productType: '', tags: '', bodyHtml: '', variants: [] as any[] },

			// DD2 — editable base fields (Shopify-owned; written via sync.groovy, NOT part
			// of the CMS-overlay dirty tracking). status is the ACTIVE|DRAFT|ARCHIVED enum.
			baseEdit: { title: '', description: '', vendor: '', productType: '', handle: '', tags: '', status: 'DRAFT' },

			// DD1 — media (Shopify-owned; live-read via product-media.groovy, written via
			// sync.groovy). Lazy-loaded the first time the Media section opens.
			media: [] as any[],              // [{ id, url, alt, altEdit, status, width, height }]
			mediaLoading: false,
			mediaLoaded: false,
			mediaAddUrl: '',
			mediaAddAlt: '',

			// Variants & stock (breakdown from the locations endpoint + editable inputs)
			stockRows: [] as any[],          // [{variantId, inventoryItemId, title, sku, price, newPrice, total, byLocation:[{locationId,name,available,newQty}]}]
			stockLoading: false,

			// Content / metafields (PIM overlay)
			localized: [] as LocaleRow[],
			metafields: [] as MetafieldRow[],
			overlayStampDate: null as Date | null,
			overlayStampUser: '' as string,

			// Planning (per-variant fixed threshold)
			planningRows: [] as PlanningRow[],
			planningDefaults: {} as Record<string, any>,   // planning.yml defaults

			// Capabilities / state
			adminApiEnabled: false,
			saving: false,
			pushing: false,
			applying: false,
			status: '',
			statusKind: '' as '' | 'ok' | 'err',
			toast: '',
			toastError: false,

			confirmDialog: { visible: false, resolve: null as null | ((a: 'save' | 'discard' | 'cancel') => void) },
			actionDialog: { visible: false, title: '', message: '', ok: '', resolve: null as null | ((v: boolean) => void) },

			_rawPim: {} as any,
			_savedModelJson: '[]',
			_base: '' as string,
			_messageListener: null as any,
			_toastTimer: null as any,
			_mediaTimer: null as any,
		};
	},

	computed: {
		// Any edit to the CMS-side model (overlay + planning) marks the editor dirty.
		// Shopify-side writes (price/stock/publish) apply immediately and separately.
		hasChanges(): boolean {
			if (!this.product) return false;
			return JSON.stringify(this.modelSnapshot()) !== this._savedModelJson;
		},
		// DD2: are there unsaved edits to the Shopify-owned base fields? Drives the
		// 'Save to Shopify' button (this channel is separate from hasChanges).
		baseChanged(): boolean {
			if (!this.product) return false;
			return Object.keys(this.changedBaseFields()).length > 0;
		},
		// Per-tab unsaved state for the tab dirty dots (T6). hasChanges is all-or-
		// nothing for the CMS overlay; these split it back out by section so each tab
		// dot reflects only its own unsaved edits (compared against the saved baseline).
		contentDirty(): boolean {
			if (!this.product) return false;
			return JSON.stringify(this.localized) !== JSON.stringify(this.savedModelPart('localized'));
		},
		metafieldsDirty(): boolean {
			if (!this.product) return false;
			return JSON.stringify(this.metafields) !== JSON.stringify(this.savedModelPart('metafields'));
		},
		planningDirty(): boolean {
			if (!this.product) return false;
			return JSON.stringify(this.planningModel()) !== JSON.stringify(this.savedModelPart('planning'));
		},
	},

	methods: {
		// ---- i18n / locale-aware formatting ---------------------------------
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
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
					vm.applyLaunchOptions(data.options);
				}
			};
			window.addEventListener('message', vm._messageListener);

			window.appLaunch = async (instance: AnyInstance, options?: any) => {
				vm.instance = vm.$markRaw(instance);
				try { document.documentElement.dataset.theme = instance.api.theme.currentTheme || 'light'; } catch (_) {}
				refreshLocalization(vm.localization, vm.instance);
				try { instance.windowTitle = vm.t('app.commerce-product.title', undefined, 'Commerce Product'); } catch (_) {}

				if (typeof instance.setBeforeCloseCallback === 'function') {
					instance.setBeforeCloseCallback(async () => vm.confirmDiscard());
				}

				await vm.resolveBase();
				await vm.loadCapabilities();
				vm.applyLaunchOptions(options);

				vm.$nextTick(() => { try { instance.notifyLaunched(); } catch (_) {} });
			};
		},

		onUnmount() {
			if (this._messageListener) window.removeEventListener('message', this._messageListener);
			if (this._toastTimer) clearTimeout(this._toastTimer);
			if (this._mediaTimer) clearTimeout(this._mediaTimer);
		},

		// MIME launch: the Content Browser / product browser hands the node path
		// (options.path). Derive the product id from product_{id}.json.
		applyLaunchOptions(options: any) {
			const o = (options && typeof options === 'object') ? options : {};
			const path = String(o.path || (Array.isArray(o.paths) && o.paths[0]) || '');
			const m = path.match(/product_(\d+)\.json$/);
			if (m) this.selectProduct(m[1]);
			else if (o.productId) this.selectProduct(String(o.productId));
		},

		// ---- Window controls -------------------------------------------------
		onMinimizeWindow() { this.instance?.minimize(); },
		onToggleMaximizeWindow() { this.instance?.toggleMaximize(); },
		onCloseWindow() { this.instance?.requestClose(); },

		selectSection(section: any) {
			this.section = section;
			// Media is lazy: load the live list the first time the section opens.
			if (section === 'media') this.loadMedia();
		},

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
		// Read a repository text file through the content service (e.g. planning.yml).
		// Null when absent/unreadable.
		async readRepoText(path: string): Promise<string | null> {
			try {
				const node = await this.instance.api.content.getNode(path);
				if (!node || !node.downloadUrl) return null;
				const res = await fetch(node.downloadUrl);
				return res.ok ? await res.text() : null;
			} catch (_) { return null; }
		},

		// ---- Product load ------------------------------------------------------
		async selectProduct(productId: string) {
			if (this.product && this.product.id === productId) return;
			if (!(await this.guardUnsaved())) return;
			await this.loadProduct(productId);
		},

		async loadProduct(productId: string) {
			this.loading = true;
			try {
				const view = await this.getJson(`${PIM_SCRIPT}?productId=${encodeURIComponent(productId)}`);
				this.product = this.$markRaw(view);
				const b = view.base || {};
				this.base = {
					title: b.title || '', handle: b.handle || '', status: b.status || '',
					vendor: b.vendor || '', productType: b.productType || '',
					tags: Array.isArray(b.tags) ? b.tags.join(', ') : (b.tags || ''),
					bodyHtml: b.bodyHtml || '',
					variants: Array.isArray(b.variants) ? b.variants : [],
				};
				this.populateBaseEdit();
				// Reset the lazy Media section so switching products re-fetches on open.
				this.media = []; this.mediaLoaded = false; this.mediaLoading = false;
				this.mediaAddUrl = ''; this.mediaAddAlt = '';
				this._rawPim = view.pim && typeof view.pim === 'object' ? view.pim : {};
				try { this.instance.windowTitle = this.base.title || this.t('app.commerce-product.title', undefined, 'Commerce Product'); } catch (_) {}
				this.populateFromOverlay();
				// The Shopify-side panels load lazily but kick off right away.
				this.loadStock();
				if (this.section === 'media') this.loadMedia(true);
				this.loadPlanningContext();
				this.setStatus('', '');
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-product.err.loadFailed', undefined, 'Could not load product.'), true);
			} finally {
				this.loading = false;
			}
		},

		// ---- Variants & stock ---------------------------------------------------
		async loadStock() {
			if (!this.product) return;
			this.stockLoading = true;
			try {
				const j = await this.getJson(`${LOCATIONS_SCRIPT}?productId=${encodeURIComponent(this.product.id)}`);
				const byVariant: Record<string, any> = {};
				for (const v of (this.base.variants || [])) byVariant[String(v.id)] = v;
				this.stockRows = (Array.isArray(j.variants) ? j.variants : []).map((r: any) => {
					const bv = byVariant[String(r.variantId)] || {};
					return {
						variantId: String(r.variantId || ''),
						inventoryItemId: String(r.inventoryItemId || ''),
						title: r.title || bv.title || '',
						sku: bv.sku || '',
						price: bv.price != null ? String(bv.price) : '',
						newPrice: bv.price != null ? String(bv.price) : '',
						total: Number(r.total) || 0,
						byLocation: (Array.isArray(r.byLocation) ? r.byLocation : []).map((l: any) => ({
							locationId: String(l.locationId || ''),
							name: l.name || l.locationId,
							available: Number(l.available) || 0,
							newQty: Number(l.available) || 0,
						})),
					};
				});
			} catch (_) { this.stockRows = []; }
			finally { this.stockLoading = false; }
		},

		// Set a variant's price in SHOPIFY (Admin API); the mirror follows via webhook.
		async applyPrice(row: any) {
			const price = String(row.newPrice ?? '').trim();
			if (!price || price === String(row.price)) return;
			const ok = await this.confirmAction(
				this.t('app.commerce-product.confirm.priceTitle', undefined, 'Update the price in Shopify'),
				this.t('app.commerce-product.confirm.priceMsg', { title: row.title || row.variantId, price }, `Set the price of "${row.title || row.variantId}" to ${price} in Shopify?`),
				this.t('app.commerce-product.confirm.apply', undefined, 'Apply'),
			);
			if (!ok) return;
			this.applying = true;
			try {
				const { status, json } = await this.postJson(SYNC_SCRIPT, {
					action: 'price', productId: this.product.id, variantId: row.variantId, price,
				});
				if (status < 200 || status >= 300 || json.ok === false) throw new Error(json.error || `Failed (${status})`);
				row.price = price;
				this.showToast(this.t('app.commerce-product.status.priceApplied', undefined, 'Price sent to Shopify. The mirror follows via webhook.'), false);
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-product.err.applyFailed', undefined, 'Write to Shopify failed.'), true);
			} finally { this.applying = false; }
		},

		// Set a location's available quantity in SHOPIFY (absolute correction).
		async applyStock(row: any, loc: any) {
			const qty = Math.round(Number(loc.newQty));
			if (!Number.isFinite(qty) || qty === Number(loc.available)) return;
			const ok = await this.confirmAction(
				this.t('app.commerce-product.confirm.stockTitle', undefined, 'Update stock in Shopify'),
				this.t('app.commerce-product.confirm.stockMsg', { title: row.title || row.variantId, name: loc.name, qty }, `Set "${row.title || row.variantId}" at ${loc.name} to ${qty} in Shopify?`),
				this.t('app.commerce-product.confirm.apply', undefined, 'Apply'),
			);
			if (!ok) return;
			this.applying = true;
			try {
				const { status, json } = await this.postJson(SYNC_SCRIPT, {
					action: 'inventory', inventoryItemId: row.inventoryItemId, locationId: loc.locationId,
					quantity: qty, reason: 'correction',
				});
				if (status < 200 || status >= 300 || json.ok === false) throw new Error(json.error || `Failed (${status})`);
				loc.available = qty;
				this.showToast(this.t('app.commerce-product.status.stockApplied', undefined, 'Stock sent to Shopify. The mirror follows via webhook.'), false);
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-product.err.applyFailed', undefined, 'Write to Shopify failed.'), true);
			} finally { this.applying = false; }
		},

		// ---- DD2: editable base fields (Shopify-owned; channel B) -------------------
		// Seed the base-edit inputs from the loaded base. status is normalized to the
		// ACTIVE|DRAFT|ARCHIVED enum the dropdown + save use.
		populateBaseEdit() {
			const b = this.base;
			const up = String(b.status || '').toUpperCase();
			this.baseEdit = {
				title: b.title || '',
				description: b.bodyHtml || '',
				vendor: b.vendor || '',
				productType: b.productType || '',
				handle: b.handle || '',
				tags: b.tags || '',
				status: (PRODUCT_STATUSES as readonly string[]).includes(up) ? up : 'DRAFT',
			};
		},

		// Diff the base-edit inputs against the loaded base; return ONLY changed fields,
		// keyed by their Shopify (GraphQL productUpdate) names.
		changedBaseFields(): any {
			const b = this.base, e = this.baseEdit;
			const fields: any = {};
			if (e.title !== (b.title || '')) fields.title = e.title;
			if (e.description !== (b.bodyHtml || '')) fields.descriptionHtml = e.description;
			if (e.vendor !== (b.vendor || '')) fields.vendor = e.vendor;
			if (e.productType !== (b.productType || '')) fields.productType = e.productType;
			if (e.handle !== (b.handle || '')) fields.handle = e.handle;
			const curTags = splitTags(e.tags), baseTags = splitTags(b.tags);
			if (!tagsEqual(curTags, baseTags)) fields.tags = curTags;
			// Diff the dropdown against the SAME normalized/fallback value populateBaseEdit
			// seeded it from, so an empty/legacy mirror status doesn't phantom-diff to DRAFT
			// (which would silently push status on an unrelated save).
			const rawUp = String(b.status || '').toUpperCase();
			const baseUp = (PRODUCT_STATUSES as readonly string[]).includes(rawUp) ? rawUp : 'DRAFT';
			if (e.status !== baseUp && (PRODUCT_STATUSES as readonly string[]).includes(e.status)) fields.status = e.status;
			return fields;
		},

		// Write the changed base fields to SHOPIFY (Admin API productUpdate); the mirror
		// follows via webhook. Optimistically reflects the saved values in the header.
		async saveBase() {
			if (!this.product || this.applying) return;
			if (!this.adminApiEnabled) {
				this.showToast(this.t('app.commerce-product.hint.adminApiOff', undefined, 'The Shopify Admin API is disabled — edits cannot be written.'), true);
				return;
			}
			const fields = this.changedBaseFields();
			if (!Object.keys(fields).length) return;
			const ok = await this.confirmAction(
				this.t('app.commerce-product.confirm.baseTitle', undefined, 'Save base fields to Shopify'),
				this.t('app.commerce-product.confirm.baseMsg', { title: this.baseEdit.title || this.product.id }, 'Write these base-field changes to the product in Shopify?'),
				this.t('app.commerce-product.action.saveBase', undefined, 'Save to Shopify'),
			);
			if (!ok) return;
			this.applying = true;
			try {
				const { status, json } = await this.postJson(SYNC_SCRIPT, {
					action: 'product', productId: this.product.id, fields,
				});
				if (status < 200 || status >= 300 || json.ok === false || json.error) throw new Error(json.error || `Failed (${status})`);
				// Optimistically update the loaded baseline (header/pill reflect the save
				// until the webhook re-ingests the mirror).
				if ('title' in fields) this.base.title = this.baseEdit.title;
				if ('descriptionHtml' in fields) this.base.bodyHtml = this.baseEdit.description;
				if ('vendor' in fields) this.base.vendor = this.baseEdit.vendor;
				if ('productType' in fields) this.base.productType = this.baseEdit.productType;
				if ('handle' in fields) this.base.handle = this.baseEdit.handle;
				if ('tags' in fields) this.base.tags = (fields.tags as string[]).join(', ');
				if ('status' in fields) this.base.status = String(fields.status).toLowerCase();
				this.populateBaseEdit();
				try { this.instance.windowTitle = this.base.title || this.t('app.commerce-product.title', undefined, 'Commerce Product'); } catch (_) {}
				this.showToast(this.t('app.commerce-product.status.baseApplied', undefined, 'Base fields sent to Shopify. The mirror follows via webhook.'), false);
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-product.err.applyFailed', undefined, 'Write to Shopify failed.'), true);
			} finally { this.applying = false; }
		},

		// Shell wt-select popup for the base status dropdown (ACTIVE|DRAFT|ARCHIVED),
		// anchored to the trigger button. Mirrors content-browser's filter dropdowns.
		async openProductStatusMenu(event: MouseEvent) {
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !this.instance) return;
			const rect = trigger.getBoundingClientRect();
			const cur = this.baseEdit.status;
			const items = [
				{ id: 'ACTIVE', label: this.t('app.commerce-product.overview.statusActive'), selected: cur === 'ACTIVE' },
				{ id: 'DRAFT', label: this.t('app.commerce-product.overview.statusDraft'), selected: cur === 'DRAFT' },
				{ id: 'ARCHIVED', label: this.t('app.commerce-product.overview.statusArchived'), selected: cur === 'ARCHIVED' },
			];
			const handle = this.instance.popup.open({ anchor: rect, placement: 'bottom-start', minWidth: rect.width, items });
			const result = await handle.result;
			if (result == null) return;
			this.baseEdit.status = String(result);
		},
		// Display label for the current base status value (shown in .wt-select-value).
		productStatusLabel(v: string): string {
			switch (v) {
				case 'ACTIVE': return this.t('app.commerce-product.overview.statusActive');
				case 'DRAFT': return this.t('app.commerce-product.overview.statusDraft');
				case 'ARCHIVED': return this.t('app.commerce-product.overview.statusArchived');
				default: return this.t('app.commerce-product.overview.statusDraft');
			}
		},

		// ---- DD1: media (Shopify-owned; live-read + channel B writes) --------------
		// Lazy live-read from product-media.groovy (the authoritative editable list; the
		// mirror lacks MediaImage gids). force re-reads after an async add/reorder.
		async loadMedia(force?: boolean) {
			if (!this.product) return;
			if (this.mediaLoaded && !force) return;
			this.mediaLoading = true;
			try {
				const j = await this.getJson(`${MEDIA_SCRIPT}?productId=${encodeURIComponent(this.product.id)}`);
				this.media = (Array.isArray(j.media) ? j.media : []).map((m: any) => ({
					id: String(m.id || ''),
					url: m.url || '',
					alt: m.alt || '',
					altEdit: m.alt || '',
					status: String(m.status || ''),
					width: m.width, height: m.height,
				}));
				this.mediaLoaded = true;
			} catch (_) {
				this.media = [];
				this.showToast(this.t('app.commerce-product.err.mediaLoadFailed', undefined, 'Could not load media.'), true);
			} finally { this.mediaLoading = false; }
		},

		// Add an image by URL (ASYNC in Shopify: UPLOADED→PROCESSING→READY, url null
		// until READY). Re-fetch the list after a short delay.
		async addMedia() {
			const url = String(this.mediaAddUrl || '').trim();
			if (!url || this.applying || !this.adminApiEnabled) return;
			const ok = await this.confirmAction(
				this.t('app.commerce-product.confirm.mediaAddTitle', undefined, 'Add media in Shopify'),
				this.t('app.commerce-product.confirm.mediaAddMsg', { url }, 'Add this image to the product in Shopify?'),
				this.t('app.commerce-product.action.addMedia', undefined, 'Add'),
			);
			if (!ok) return;
			this.applying = true;
			try {
				const alt = String(this.mediaAddAlt || '').trim();
				const body: any = { action: 'media', op: 'add', productId: this.product.id, originalSource: url };
				if (alt) body.alt = alt;
				const { status, json } = await this.postJson(SYNC_SCRIPT, body);
				if (status < 200 || status >= 300 || json.ok === false || json.error) throw new Error(json.error || `Failed (${status})`);
				this.mediaAddUrl = ''; this.mediaAddAlt = '';
				this.showToast(this.t('app.commerce-product.status.mediaProcessing', undefined, 'Sent to Shopify — processing. Refreshing shortly…'), false);
				if (this._mediaTimer) clearTimeout(this._mediaTimer);
				this._mediaTimer = window.setTimeout(() => this.loadMedia(true), 1500);
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-product.err.applyFailed', undefined, 'Write to Shopify failed.'), true);
			} finally { this.applying = false; }
		},

		// Delete a media object in Shopify (immediate).
		async deleteMedia(row: any) {
			if (this.applying || !this.adminApiEnabled) return;
			const ok = await this.confirmAction(
				this.t('app.commerce-product.confirm.mediaDeleteTitle', undefined, 'Delete media in Shopify'),
				this.t('app.commerce-product.confirm.mediaDeleteMsg', undefined, 'Delete this image from the product in Shopify?'),
				this.t('app.commerce-product.action.deleteMedia', undefined, 'Delete'),
			);
			if (!ok) return;
			this.applying = true;
			try {
				const { status, json } = await this.postJson(SYNC_SCRIPT, {
					action: 'media', op: 'delete', productId: this.product.id, mediaIds: [row.id],
				});
				if (status < 200 || status >= 300 || json.ok === false || json.error) throw new Error(json.error || `Failed (${status})`);
				const idx = this.media.indexOf(row);
				if (idx >= 0) this.media.splice(idx, 1);
				this.showToast(this.t('app.commerce-product.status.mediaDeleted', undefined, 'Media deleted. The mirror follows via webhook.'), false);
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-product.err.applyFailed', undefined, 'Write to Shopify failed.'), true);
			} finally { this.applying = false; }
		},

		// Update a media object's alt text in Shopify (immediate).
		async updateAlt(row: any) {
			if (this.applying || !this.adminApiEnabled) return;
			if (row.altEdit === row.alt) return;
			const ok = await this.confirmAction(
				this.t('app.commerce-product.confirm.mediaAltTitle', undefined, 'Update alt text in Shopify'),
				this.t('app.commerce-product.confirm.mediaAltMsg', { alt: row.altEdit }, 'Update this image’s alt text in Shopify?'),
				this.t('app.commerce-product.action.updateAlt', undefined, 'Update alt'),
			);
			if (!ok) return;
			this.applying = true;
			try {
				const { status, json } = await this.postJson(SYNC_SCRIPT, {
					action: 'media', op: 'updateAlt', mediaId: row.id, alt: row.altEdit,
				});
				if (status < 200 || status >= 300 || json.ok === false || json.error) throw new Error(json.error || `Failed (${status})`);
				row.alt = row.altEdit;
				this.showToast(this.t('app.commerce-product.status.mediaAltApplied', undefined, 'Alt text sent to Shopify. The mirror follows via webhook.'), false);
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-product.err.applyFailed', undefined, 'Write to Shopify failed.'), true);
			} finally { this.applying = false; }
		},

		// Move a media item up/down and persist the new order in Shopify. Reorder is
		// ASYNC (Shopify returns a job that settles after the call). We KEEP the optimistic
		// order on success — it IS the order Shopify will converge to — instead of clobbering
		// it with a too-early live read of the not-yet-settled job (which would spuriously
		// revert the move on screen). Only on FAILURE do we re-sync from Shopify. No confirm
		// dialog — this is a click-to-move control (kept simple).
		async moveMedia(index: number, dir: number) {
			if (this.applying || !this.adminApiEnabled) return;
			const j = index + dir;
			if (j < 0 || j >= this.media.length) return;
			const arr = this.media.slice();
			const [item] = arr.splice(index, 1);
			arr.splice(j, 0, item);
			this.media = arr;
			this.applying = true;
			try {
				const { status, json } = await this.postJson(SYNC_SCRIPT, {
					action: 'media', op: 'reorder', productId: this.product.id,
					orderedMediaIds: arr.map((m: any) => m.id),
				});
				if (status < 200 || status >= 300 || json.ok === false || json.error) throw new Error(json.error || `Failed (${status})`);
				this.showToast(this.t('app.commerce-product.status.mediaReordered', undefined, 'Reorder sent to Shopify. The mirror follows via webhook.'), false);
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-product.err.applyFailed', undefined, 'Write to Shopify failed.'), true);
				// Re-sync the displayed order with Shopify after a failed reorder.
				this.loadMedia(true);
			} finally { this.applying = false; }
		},

		// ---- Planning ---------------------------------------------------------------
		// Load the resolution context (planning.yml defaults) and derive per-variant
		// rows: the fixed threshold set in pim.planning (empty → planning.yml default).
		async loadPlanningContext() {
			try {
				const yml = parseSimpleYaml((await this.readRepoText(PLANNING_YML)) || '');
				this.planningDefaults = (yml.defaults && typeof yml.defaults === 'object') ? yml.defaults : {};
			} catch (_) { /* defaults stay empty */ }
			this.populatePlanning();
		},

		populatePlanning() {
			const planning = (this._rawPim && typeof this._rawPim.planning === 'object') ? this._rawPim.planning : {};
			const rows: PlanningRow[] = [];
			for (const v of (this.base.variants || [])) {
				const vid = String(v.id);
				const p = (planning[vid] && typeof planning[vid] === 'object') ? planning[vid] : {};
				rows.push({ variantId: vid, title: v.title || '', threshold: numOrEmpty(p.threshold) });
			}
			this.planningRows = rows;
			this._savedModelJson = JSON.stringify(this.modelSnapshot());
		},

		// The effective (resolved) value shown as the input placeholder.
		defaultFor(param: string): string {
			const d = this.planningDefaults[param];
			return d == null ? '' : String(d);
		},

		// ---- Overlay model (content / metafields / planning) ----------------------
		populateFromOverlay() {
			const pim = this._rawPim || {};
			const loc: LocaleRow[] = [];
			if (pim.localized && typeof pim.localized === 'object') {
				for (const code of Object.keys(pim.localized)) {
					const e = pim.localized[code] || {};
					loc.push({ locale: code, title: e.title || '', description_html: e.description_html || '' });
				}
			}
			const mfs: MetafieldRow[] = [];
			if (Array.isArray(pim.metafields)) {
				for (const m of pim.metafields) mfs.push({ namespace: m.namespace || '', key: m.key || '', type: m.type || '', value: stringify(m.value) });
			}
			this.localized = loc;
			this.metafields = mfs;
			this.overlayStampDate = pim.updatedAt ? new Date(pim.updatedAt) : null;
			this.overlayStampUser = pim.updatedBy || '';
			this.populatePlanning();
		},

		modelSnapshot() {
			return { localized: this.localized, metafields: this.metafields, planning: this.planningModel() };
		},

		// The saved baseline's sub-part (from _savedModelJson) for the per-tab dirty
		// computeds (T6). Parses lazily; undefined on a stale/empty baseline.
		savedModelPart(key: string): any {
			try { const m = JSON.parse(this._savedModelJson); return (m && typeof m === 'object') ? m[key] : undefined; } catch (_) { return undefined; }
		},
		// Whether a tab has unsaved edits — drives its dirty dot (T6). Overview = the
		// Shopify base edits; content/metafields/planning = the CMS overlay parts;
		// variants & media write to Shopify immediately, so they never carry unsaved state.
		sectionDirty(section: string): boolean {
			switch (section) {
				case 'overview': return this.baseChanged;
				case 'content': return this.contentDirty;
				case 'metafields': return this.metafieldsDirty;
				case 'planning': return this.planningDirty;
				default: return false;
			}
		},

		// Per-variant planning values from the rows: only explicitly set numbers.
		planningModel(): any {
			const out: any = {};
			for (const row of this.planningRows) {
				const entry: any = {};
				for (const param of PLANNING_PARAMS) {
					const v = (row as any)[param];
					if (v !== '' && v != null && Number.isFinite(Number(v))) entry[param] = Number(v);
				}
				if (Object.keys(entry).length) out[row.variantId] = entry;
			}
			return out;
		},

		revert() {
			if (!this.hasChanges) return;
			this.populateFromOverlay();
			this.setStatus('', '');
		},

		addLocale() { this.localized.push({ locale: '', title: '', description_html: '' }); },
		addMetafield() { this.metafields.push({ namespace: '', key: '', type: 'single_line_text_field', value: '' }); },

		// ---- Save (CMS-side: overlay + planning) -----------------------------------
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

			const mfs: any[] = [];
			for (const row of this.metafields) {
				const ns = (row.namespace || '').trim(), key = (row.key || '').trim();
				if (!ns || !key) continue;
				mfs.push({ namespace: ns, key, type: (row.type || '').trim() || 'single_line_text_field', value: row.value });
			}
			if (mfs.length) overlay.metafields = mfs;

			const planning = this.planningModel();
			if (Object.keys(planning).length) overlay.planning = planning;

			return overlay;
		},

		async save() {
			if (!this.product || this.saving) return;
			this.saving = true;
			try {
				const overlay = this.buildOverlay();
				const { status, json } = await this.postJson(PIM_SCRIPT, {
					productId: this.product.id, pim: overlay, merge: false,
				});
				if (status < 200 || status >= 300 || json.ok === false) throw new Error(json.error || `Save failed (${status})`);
				this._rawPim = json.pim && typeof json.pim === 'object' ? json.pim : overlay;
				this.populateFromOverlay();
				this.setStatus('ok', this.t('app.commerce-product.status.saved', undefined, 'Saved'));
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-product.err.saveFailed', undefined, 'Save failed.'), true);
				this.setStatus('err', this.t('app.commerce-product.err.saveFailed', undefined, 'Save failed'));
			} finally {
				this.saving = false;
			}
		},

		// ---- Push metafields to Shopify --------------------------------------
		async pushMetafields() {
			if (!this.product || this.pushing) return;
			if (this.hasChanges) { this.showToast(this.t('app.commerce-product.err.saveFirst', undefined, 'Save your changes before pushing.'), true); return; }
			this.pushing = true;
			try {
				const { status, json } = await this.postJson(SYNC_SCRIPT, { action: 'metafields', productId: this.product.id });
				if (status < 200 || status >= 300 || json.ok === false) throw new Error(json.error || `Push failed (${status})`);
				this.showToast(this.t('app.commerce-product.status.pushed', undefined, 'Metafields pushed to Shopify.'), false);
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-product.err.pushFailed', undefined, 'Push failed.'), true);
			} finally {
				this.pushing = false;
			}
		},

		// ---- Dialogs -----------------------------------------------------------
		// Guard unsaved edits before switching product / closing the window. Handles the
		// CMS-overlay dirty state (three-way save/discard/cancel) AND staged Shopify
		// base-field edits (binary discard/cancel — the base save is an explicit Shopify
		// write, never auto-fired here). Returns true when it is OK to proceed.
		async guardUnsaved(): Promise<boolean> {
			if (this.hasChanges) {
				const action = await this.askDiscard();
				if (action === 'cancel') return false;
				if (action === 'save') { await this.save(); if (this.hasChanges) return false; }
			}
			if (this.baseChanged) {
				const discard = await this.confirmAction(
					this.t('app.commerce-product.confirm.discardBaseTitle', undefined, 'Unsaved base-field edits'),
					this.t('app.commerce-product.confirm.discardBaseMsg', undefined, 'You have unsaved Shopify base-field edits that were not sent. Discard them?'),
					this.t('app.commerce-product.action.discardBase', undefined, 'Discard'),
				);
				if (!discard) return false;
			}
			return true;
		},

		async confirmDiscard(): Promise<boolean> {
			return await this.guardUnsaved();
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
		confirmAction(title: string, message: string, ok: string): Promise<boolean> {
			const vm = this;
			vm.actionDialog = { visible: true, title, message, ok, resolve: null };
			return new Promise((resolve) => { vm.actionDialog.resolve = resolve; });
		},
		onActionDialog(value: boolean) {
			if (this.actionDialog.resolve) this.actionDialog.resolve(value);
			this.actionDialog.visible = false;
			this.actionDialog.resolve = null;
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
