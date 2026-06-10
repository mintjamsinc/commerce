// Commerce Publishing — operator console for the headless storefront / content
// commerce (category F, #20/#22).
//
// Two tabs:
//   • Publish → storefront.groovy — catalog/inventory/page publish status and a
//     "Rebuild now" trigger, plus links to the public storefront/landing/catalog.
//   • Pages   → pages.groovy      — CRUD editor for the CMS-authored landing block
//     documents (hero / heading / markdown / html / products), which the publisher
//     projects to the public storefront.
//
// Editing is guarded for unsaved changes (switch / window close); deletes and the
// rebuild are confirmed. Self-contained (ichigo.js runtime only).

import { VDOM } from '@mintjamsinc/ichigojs';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
	formatDate,
} from '../../composables/use-localization.js';

type AnyInstance = any;

const STOREFRONT_SCRIPT = '/content/commerce/endpoints/storefront.groovy';
const PAGES_SCRIPT = '/content/commerce/endpoints/pages.groovy';
const PUBLIC_BASE = '/content/public/commerce/';

const SLUG_RE = /^[a-z0-9][a-z0-9-]*$/;

function cleanStr(v: any): string | undefined { const s = (v == null ? '' : String(v)).trim(); return s ? s : undefined; }

const App = {
	data() {
		return {
			instance: null as AnyInstance,
			// Reactive Localization snapshot — drives every t() / formatDate() binding
			// so the app repaints when the user switches language or a bundle is
			// hot-reloaded. See composables/use-localization.ts.
			localization: createLocalizationSnapshot(),
			section: 'publish' as 'publish' | 'pages',
			busy: false,
			status_: '',
			statusKind: '' as '' | 'ok' | 'err',
			toast: '',
			toastError: false,

			// Publish tab
			status: { publishedProducts: 0, inventoryItems: 0, publishedPages: 0, catalogGeneratedAt: '', inventoryUpdatedAt: '' } as any,
			store: { name: '', shopDomain: '', currency: '', lowStock: 0 } as any,

			// Pages tab
			pages: [] as any[],
			publishedSet: {} as Record<string, boolean>,
			editing: null as any,
			isNew: false,
			loadingPage: false,
			newBlockType: 'markdown',

			confirmDialog: { visible: false, title: '', message: '', ok: '', showDiscard: false, resolve: null as null | ((a: string) => void) },

			_savedJson: '',
			_base: '' as string,
			_messageListener: null as any,
			_toastTimer: null as any,
			_loaded: { publish: false, pages: false },
		};
	},

	computed: {
		hasChanges(): boolean { return !!this.editing && JSON.stringify(this.modelSnapshot()) !== this._savedJson; },
		canSave(): boolean { return !!this.editing && this.hasChanges && !this.busy && SLUG_RE.test(String(this.editing.slug || '')); },
	},

	methods: {
		// ---- i18n / locale-aware formatting ------------------------------------
		// Reactive i18n lookup: reading the localization snapshot inside
		// translate() subscribes every `{{ t(...) }}` binding, so the UI repaints
		// the instant the user switches language or a bundle hot-reloads.
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},
		// Locale- and timezone-aware date/time formatting.
		fmtTime(v: any): string {
			if (!v) return '—';
			return formatDate(this.localization, v, { format: 'datetime' });
		},

		onMounted() {
			const vm = this;
			vm._messageListener = (event: MessageEvent) => {
				const data: any = event.data || {};
				// Fold locale / time-zone / currency changes and i18n bundle
				// hot-reloads into the reactive snapshot so the UI re-localizes live.
				if (handleLocalizationMessage(data.type, vm.localization, vm.instance)) return;
				if (data.type === 'theme-changed' && data.theme) document.documentElement.dataset.theme = data.theme;
			};
			window.addEventListener('message', vm._messageListener);

			window.appLaunch = async (instance: AnyInstance) => {
				vm.instance = vm.$markRaw(instance);
				try { document.documentElement.dataset.theme = instance.api.theme.currentTheme || 'light'; } catch (_) {}

				// Snapshot the effective Localization preference so the first paint
				// is already in the user's language / region.
				refreshLocalization(vm.localization, vm.instance);

				try { instance.windowTitle = vm.t('app.commerce-publish.title', undefined, 'Commerce Publishing'); } catch (_) {}
				if (typeof instance.setBeforeCloseCallback === 'function') instance.setBeforeCloseCallback(async () => vm.confirmDiscard());
				await vm.resolveBase();
				await vm.loadSection('publish');
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

		// ---- Navigation ------------------------------------------------------
		async selectSection(section: 'publish' | 'pages') {
			if (section === this.section) return;
			if (this.section === 'pages' && this.hasChanges) {
				const a = await this.askDiscard();
				if (a === 'cancel') return;
				if (a === 'save') { await this.savePage(); if (this.hasChanges) return; }
			}
			this.section = section;
			if (!this._loaded[section]) this.loadSection(section);
		},
		refresh() { this._loaded[this.section] = false; this.loadSection(this.section); },
		async loadSection(section: string) {
			if (section === 'publish') await this.loadStatus();
			else if (section === 'pages') await this.loadPages();
			this._loaded[section] = true;
		},

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
		async postJson(path: string, body: any): Promise<{ status: number; json: any }> {
			const res = await fetch(`${this._base}${path}`, {
				method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
				credentials: 'same-origin', body: JSON.stringify(body),
			});
			const json = await res.json().catch(() => ({}));
			return { status: res.status, json };
		},

		// ---- Publish tab -----------------------------------------------------
		async loadStatus() {
			try {
				const j = await this.getJson(STOREFRONT_SCRIPT);
				this.status = this.$markRaw(j);
				const s = j.store || {};
				this.store = { name: s.name || '', shopDomain: s.shopDomain || '', currency: s.currency || '', lowStock: s.lowStock ?? 0 };
			} catch (e: any) { this.showToast(e?.message || this.t('app.commerce-publish.error.loadStatus', undefined, 'Could not load status.'), true); }
		},
		async rebuild() {
			this.busy = true;
			try {
				const { status } = await this.postJson(STOREFRONT_SCRIPT, {});
				if (status === 202 || status === 200) {
					this.showToast(this.t('app.commerce-publish.toast.rebuildStarted', undefined, 'Rebuild started. Refreshing shortly…'), false);
					setTimeout(() => this.loadStatus(), 4000);
					setTimeout(() => this.loadStatus(), 12000);
				} else this.showToast(this.t('app.commerce-publish.error.rebuildFailed', { status }, `Could not start (${status}).`), true);
			} catch (e: any) { this.showToast(e?.message || this.t('app.commerce-publish.error.rebuildError', undefined, 'Could not start rebuild.'), true); }
			finally { this.busy = false; }
		},
		openPublic(rel: string) {
			if (!this._base) return;
			try { window.open(`${this._base}${PUBLIC_BASE}${rel}`, '_blank', 'noopener'); } catch (_) {}
		},

		// ---- Pages tab -------------------------------------------------------
		async loadPages() {
			try {
				const j = await this.getJson(PAGES_SCRIPT);
				this.pages = this.$markRaw(Array.isArray(j.pages) ? j.pages : []);
				const set: Record<string, boolean> = {};
				(Array.isArray(j.published) ? j.published : []).forEach((s: string) => { set[s] = true; });
				this.publishedSet = set;
			} catch (e: any) { this.showToast(e?.message || this.t('app.commerce-publish.error.loadPages', undefined, 'Could not load pages.'), true); }
		},
		async selectPage(slug: string) {
			if (this.editing && !this.isNew && this.editing.slug === slug) return;
			if (this.hasChanges) {
				const a = await this.askDiscard();
				if (a === 'cancel') return;
				if (a === 'save') { await this.savePage(); if (this.hasChanges) return; }
			}
			await this.loadPage(slug);
		},
		async loadPage(slug: string) {
			this.loadingPage = true;
			try {
				const j = await this.getJson(`${PAGES_SCRIPT}?slug=${encodeURIComponent(slug)}`);
				this.editing = this.pageToEditor(j.page || {});
				this.isNew = false;
				this._savedJson = JSON.stringify(this.modelSnapshot());
				this.setStatus('', '');
			} catch (e: any) { this.showToast(e?.message || this.t('app.commerce-publish.error.loadPage', undefined, 'Could not load page.'), true); }
			finally { this.loadingPage = false; }
		},
		async newPage() {
			if (this.hasChanges) {
				const a = await this.askDiscard();
				if (a === 'cancel') return;
				if (a === 'save') { await this.savePage(); if (this.hasChanges) return; }
			}
			this.editing = { slug: '', title: '', subtitle: '', status: 'published', blocks: [] };
			this.isNew = true;
			this._savedJson = JSON.stringify(this.modelSnapshot());
		},

		pageToEditor(doc: any): any {
			return {
				slug: doc.slug || '', title: doc.title || '', subtitle: doc.subtitle || '',
				status: doc.status || 'published',
				blocks: (Array.isArray(doc.blocks) ? doc.blocks : []).map((b: any) => this.toEditorBlock(b)),
			};
		},
		toEditorBlock(b: any): any {
			const t = b?.type || 'markdown';
			if (t === 'products') {
				const ids = Array.isArray(b.productIds) ? b.productIds : null;
				return { type: 'products', title: b.title || '', layout: b.layout || 'grid', limit: b.limit ?? 8,
					_mode: ids ? 'ids' : 'tag', tag: b.tag || '', _productIds: ids ? ids.join(', ') : '' };
			}
			return { type: t, title: b.title || '', subtitle: b.subtitle || '', image: b.image || '',
				ctaText: b.ctaText || '', ctaHref: b.ctaHref || '', text: b.text || '', value: b.value || '' };
		},
		editorToDoc(): any {
			const e = this.editing;
			const doc: any = { slug: String(e.slug || '').trim(), status: e.status || 'published' };
			const title = cleanStr(e.title); if (title) doc.title = title;
			const subtitle = cleanStr(e.subtitle); if (subtitle) doc.subtitle = subtitle;
			doc.blocks = (e.blocks || []).map((b: any) => this.toRawBlock(b));
			return doc;
		},
		toRawBlock(b: any): any {
			switch (b.type) {
				case 'hero': return prune({ type: 'hero', title: cleanStr(b.title), subtitle: cleanStr(b.subtitle), image: cleanStr(b.image), ctaText: cleanStr(b.ctaText), ctaHref: cleanStr(b.ctaHref) });
				case 'heading': return { type: 'heading', text: b.text || '' };
				case 'markdown': return { type: 'markdown', value: b.value || '' };
				case 'html': return { type: 'html', value: b.value || '' };
				case 'products': {
					const out: any = { type: 'products', layout: b.layout || 'grid' };
					const t = cleanStr(b.title); if (t) out.title = t;
					const lim = parseInt(String(b.limit), 10); if (Number.isFinite(lim) && lim > 0) out.limit = lim;
					if (b._mode === 'ids') out.productIds = String(b._productIds || '').split(',').map((x: string) => x.trim()).filter(Boolean);
					else { const tag = cleanStr(b.tag); if (tag) out.tag = tag; }
					return out;
				}
				default: return { type: b.type };
			}
		},
		modelSnapshot() { const e = this.editing; return e ? { slug: e.slug, title: e.title, subtitle: e.subtitle, status: e.status, blocks: e.blocks } : null; },

		addBlock() {
			const t = this.newBlockType;
			const defs: any = {
				hero: { type: 'hero', title: '', subtitle: '', image: '', ctaText: '', ctaHref: '' },
				heading: { type: 'heading', text: '' },
				markdown: { type: 'markdown', value: '' },
				html: { type: 'html', value: '' },
				products: { type: 'products', title: '', layout: 'grid', limit: 8, _mode: 'tag', tag: '', _productIds: '' },
			};
			this.editing.blocks.push(JSON.parse(JSON.stringify(defs[t] || defs.markdown)));
		},
		moveBlock(i: number, dir: number) {
			const j = i + dir; const a = this.editing.blocks;
			if (j < 0 || j >= a.length) return;
			const tmp = a[i]; a.splice(i, 1); a.splice(j, 0, tmp);
		},
		blockIcon(type: string): string {
			return { hero: 'bi-card-image', heading: 'bi-type-h2', markdown: 'bi-markdown', html: 'bi-code-slash', products: 'bi-grid-3x3-gap' }[type] || 'bi-square';
		},

		async savePage() {
			if (!this.canSave) { if (this.editing && !SLUG_RE.test(String(this.editing.slug || ''))) this.showToast(this.t('app.commerce-publish.error.slugInvalid', undefined, 'Slug must be lowercase letters, digits and hyphens.'), true); return; }
			this.busy = true;
			try {
				const slug = String(this.editing.slug).trim();
				const { status, json } = await this.postJson(PAGES_SCRIPT, { slug, page: this.editorToDoc() });
				if (status < 200 || status >= 300 || json.ok === false) throw new Error(json.error || `Save failed (${status})`);
				this.isNew = false;
				this._savedJson = JSON.stringify(this.modelSnapshot());
				this.setStatus('ok', this.t('app.commerce-publish.status.saved', undefined, 'Saved'));
				this.showToast(this.t('app.commerce-publish.toast.saved', undefined, 'Saved. Rebuild to publish to the storefront.'), false);
				await this.loadPages();
			} catch (e: any) { this.showToast(e?.message || this.t('app.commerce-publish.error.saveFailed', undefined, 'Save failed.'), true); this.setStatus('err', this.t('app.commerce-publish.status.saveFailed', undefined, 'Save failed')); }
			finally { this.busy = false; }
		},
		async deletePage() {
			if (this.isNew || !this.editing) return;
			const ok = await this.confirm(
				this.t('app.commerce-publish.dialog.deleteTitle', undefined, 'Delete page'),
				this.t('app.commerce-publish.dialog.deleteMessage', { name: this.editing.title || this.editing.slug }, `Delete the landing page "${this.editing.title || this.editing.slug}"? It will be removed from the storefront on the next rebuild.`),
				this.t('app.commerce-publish.dialog.deleteOk', undefined, 'Delete'),
			);
			if (!ok) return;
			this.busy = true;
			try {
				const { status, json } = await this.postJson(PAGES_SCRIPT, { slug: this.editing.slug, delete: true });
				if (status < 200 || status >= 300 || json.ok === false) throw new Error(json.error || `Delete failed (${status})`);
				this.editing = null; this._savedJson = '';
				await this.loadPages();
				this.showToast(this.t('app.commerce-publish.toast.deleted', undefined, 'Page deleted. Rebuild to remove it from the storefront.'), false);
			} catch (e: any) { this.showToast(e?.message || this.t('app.commerce-publish.error.deleteFailed', undefined, 'Delete failed.'), true); }
			finally { this.busy = false; }
		},
		revert() {
			if (!this.hasChanges) return;
			this.editing = JSON.parse(this._savedJson);
			this.setStatus('', '');
		},

		// ---- Confirm dialog --------------------------------------------------
		async confirmDiscard(): Promise<boolean> {
			if (!this.editing || !this.hasChanges) return true;
			const a = await this.askDiscard();
			if (a === 'cancel') return false;
			if (a === 'discard') return true;
			await this.savePage();
			return !this.hasChanges;
		},
		askDiscard(): Promise<string> {
			const vm = this;
			vm.confirmDialog = {
				visible: true,
				title: vm.t('app.commerce-publish.dialog.unsavedTitle', undefined, 'Unsaved Changes'),
				message: vm.t('app.commerce-publish.dialog.unsavedMessage', undefined, 'You have unsaved changes to this page. Save them first?'),
				ok: vm.t('common.save', undefined, 'Save'),
				showDiscard: true,
				resolve: null,
			};
			return new Promise((resolve) => { vm.confirmDialog.resolve = resolve; });
		},
		confirm(title: string, message: string, ok: string): Promise<boolean> {
			const vm = this;
			vm.confirmDialog = { visible: true, title, message, ok, showDiscard: false, resolve: null };
			return new Promise((resolve) => { vm.confirmDialog.resolve = (a: string) => resolve(a === 'ok'); });
		},
		onConfirm(action: string) {
			if (this.confirmDialog.resolve) this.confirmDialog.resolve(action);
			this.confirmDialog.visible = false;
			this.confirmDialog.resolve = null;
		},

		// ---- Format / status -------------------------------------------------
		setStatus(kind: '' | 'ok' | 'err', msg: string) { this.statusKind = kind; this.status_ = msg; },
		showToast(msg: string, isError: boolean) {
			this.toast = msg; this.toastError = !!isError;
			if (this._toastTimer) clearTimeout(this._toastTimer);
			this._toastTimer = window.setTimeout(() => { this.toast = ''; }, 3400);
		},
	},
};

function prune(o: any): any { const out: any = {}; for (const k in o) if (o[k] !== undefined && o[k] !== null) out[k] = o[k]; return out; }

VDOM.createApp(App).mount('#app');
