// Commerce Order — the single-order editor.
//
// Everything about ONE order in one place, Shopify-admin-like:
//   • Overview     — order number / dates / customer / financial & fulfillment
//                    status / total (incl. currency) / refunded amount / tracking
//                    (read-only mirror of the Shopify order).
//   • Line items   — the ordered lines (title / sku / qty / price / total),
//                    READ-ONLY. Quantity / add-variant editing (the stateful Order
//                    Editing session) is a follow-up phase; an edit-guard note
//                    explains why, and when the order is cancelled / fulfilled why
//                    it cannot be edited at all.
//   • Note         — the order note.
//   • Tags         — the order's tags (same tag-chip UI as the customer editor).
//   • Custom attrs — the order's custom attributes (key / value list).
//   • Addresses    — shipping & billing addresses (read-only display).
//
// WRITE MODEL (v1 = metadata ONLY): the three editable channels — note, tags and
// customAttributes — are Shopify-owned. Save writes to SHOPIFY via the sync
// endpoint (Admin API → orderUpdate); the CMS mirror is never edited directly — it
// follows through the webhook round-trip. The editor optimistically reflects the
// saved values; a manual refresh re-reads orders.groovy. Line-item quantity /
// variant editing is intentionally NOT implemented here.
//
// Launch: from the order browser / Content Browser via the order MIME type
// (options.path → /content/commerce/orders/raw/{yyyy}/{MM}/order_{id}.json).

import { VDOM } from '@mintjamsinc/ichigojs';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
	formatDate,
	formatCurrency,
} from '../../composables/use-localization.js';

type AnyInstance = any;

const ORDERS_SCRIPT = '/content/commerce/endpoints/orders.groovy';
const SYNC_SCRIPT = '/content/commerce/endpoints/sync.groovy';

interface AttrRow { key: string; value: string; }
interface EditModel {
	note: string;
	tags: string[];
	customAttributes: AttrRow[];
}

// Split a Shopify tags value (comma-separated string or array) into a trimmed,
// de-duplicated list.
function parseTags(v: any): string[] {
	let raw: string[];
	if (Array.isArray(v)) raw = v.map((x) => String(x));
	else raw = String(v || '').split(',');
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

// Read the order's custom attributes from the mirror. The raw Shopify order
// (REST webhook body) carries them as note_attributes [{ name, value }]; accept a
// GraphQL-shaped customAttributes [{ key, value }] too. Blank-key entries drop.
function parseAttrs(v: any): AttrRow[] {
	if (!Array.isArray(v)) return [];
	const out: AttrRow[] = [];
	for (const a of v) {
		if (!a || typeof a !== 'object') continue;
		const key = String(a.key != null ? a.key : (a.name != null ? a.name : '')).trim();
		if (!key) continue;
		out.push({ key, value: a.value == null ? '' : String(a.value) });
	}
	return out;
}

// Normalize the edit rows into the canonical [{ key, value }] form used for both
// dirty-diffing and the Shopify write: trimmed keys, blank-key rows dropped.
function normAttrs(rows: AttrRow[]): AttrRow[] {
	const out: AttrRow[] = [];
	for (const r of (rows || [])) {
		const key = String(r.key || '').trim();
		if (!key) continue;
		out.push({ key, value: r.value == null ? '' : String(r.value) });
	}
	return out;
}

function arraysEqual(a: string[], b: string[]): boolean {
	if (a.length !== b.length) return false;
	for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
	return true;
}

function attrsEqual(a: AttrRow[], b: AttrRow[]): boolean {
	if (a.length !== b.length) return false;
	for (let i = 0; i < a.length; i++) if (a[i].key !== b[i].key || a[i].value !== b[i].value) return false;
	return true;
}

const App = {
	data() {
		return {
			instance: null as AnyInstance,
			localization: createLocalizationSnapshot(),

			section: 'overview' as 'overview' | 'lineitems' | 'note' | 'tags' | 'attributes' | 'addresses',

			// Selected order
			loading: false,
			orderId: '' as string,
			order: null as any,              // orders view { id (GID), path, body, props }
			body: {} as any,                 // the parsed Shopify order mirror JSON

			// Edit model (only the Shopify-owned metadata this editor curates)
			edit: { note: '', tags: [], customAttributes: [] } as EditModel,
			tagInput: '' as string,

			// Capabilities / state
			adminApiEnabled: false,
			saving: false,
			status: '',
			statusKind: '' as '' | 'ok' | 'err',
			toast: '',
			toastError: false,

			confirmDialog: { visible: false, resolve: null as null | ((a: 'save' | 'discard' | 'cancel') => void) },
			actionDialog: { visible: false, title: '', message: '', ok: '', resolve: null as null | ((v: boolean) => void) },

			_original: { note: '', tags: [], customAttributes: [] } as EditModel,
			_savedEditJson: '{}',
			_base: '' as string,
			_messageListener: null as any,
			_toastTimer: null as any,
		};
	},

	computed: {
		hasChanges(): boolean {
			if (!this.order) return false;
			return JSON.stringify(this.editSnapshot()) !== this._savedEditJson;
		},
		orderNumber(): string {
			const b = this.body || {};
			if (b.order_number != null) return String(b.order_number);
			const p = (this.order && this.order.props) || {};
			if (p.orderNumber != null) return String(p.orderNumber);
			return '';
		},
		orderTitle(): string {
			const b = this.body || {};
			if (b.name) return String(b.name);
			const n = this.orderNumber;
			if (n) return '#' + n;
			return this.orderId ? this.t('app.commerce-order.order.idFallback', { id: this.orderId }) : '';
		},
		customerName(): string {
			const b = this.body || {};
			const c = (b.customer && typeof b.customer === 'object') ? b.customer : {};
			const n = [c.first_name, c.last_name].filter(Boolean).join(' ').trim();
			if (n) return n;
			const sa = (b.shipping_address && typeof b.shipping_address === 'object') ? b.shipping_address : {};
			const sn = (sa.name || [sa.first_name, sa.last_name].filter(Boolean).join(' ')).trim();
			if (sn) return sn;
			return this.customerEmail;
		},
		customerEmail(): string {
			const b = this.body || {};
			const p = (this.order && this.order.props) || {};
			return String(b.email || b.contact_email || p.customerEmail || '');
		},
		// The integration lifecycle status (commerce:status): received / review_pending
		// / approved / fulfillment_pending / fulfilled / cancelled / error. Shown raw
		// in the header pill (domain data — not translated).
		integrationStatus(): string {
			const p = (this.order && this.order.props) || {};
			return String(p.status || '').toLowerCase();
		},
		// Shopify financial_status (source_status): paid / pending / refunded / …
		financialStatus(): string {
			const b = this.body || {};
			const p = (this.order && this.order.props) || {};
			return String(b.financial_status || p.sourceStatus || '').toLowerCase();
		},
		// Shopify fulfillment_status; a null mirror value means nothing shipped yet.
		fulfillmentStatus(): string {
			const b = this.body || {};
			return b.fulfillment_status ? String(b.fulfillment_status).toLowerCase() : 'unfulfilled';
		},
		baseCurrency(): string {
			const b = this.body || {};
			const p = (this.order && this.order.props) || {};
			// The wire money object carries its own currency (commerce.Api shape).
			const fromProps = (p.totalPriceBase && p.totalPriceBase.currency) || '';
			const fromBody = (b.total_price_set && b.total_price_set.shop_money && b.total_price_set.shop_money.currency_code) || '';
			return String(fromProps || fromBody || '');
		},
		// Show the base-currency total only when it is a different currency from the
		// order's own (a cross-currency order); otherwise it is redundant.
		showBaseTotal(): boolean {
			const p = (this.order && this.order.props) || {};
			if (p.totalPriceBase == null || p.totalPriceBase.amount == null) return false;
			const cur = String((this.body && this.body.currency) || '').toUpperCase();
			const base = String(this.baseCurrency || '').toUpperCase();
			return !!base && base !== cur;
		},
		refundedAmount(): any {
			const p = (this.order && this.order.props) || {};
			return p.refundedAmount != null ? p.refundedAmount : null;
		},
		refundCount(): number {
			const p = (this.order && this.order.props) || {};
			return Number(p.refundCount) || 0;
		},
		trackingText(): string {
			const p = (this.order && this.order.props) || {};
			const num = String(p.trackingNumber || '').trim();
			const co = String(p.trackingCompany || '').trim();
			if (!num && !co) return '';
			return [co, num].filter(Boolean).join(' · ');
		},
		cancelledAt(): any {
			const b = this.body || {};
			const p = (this.order && this.order.props) || {};
			return p.cancelledAt || b.cancelled_at || null;
		},
		fulfilledAt(): any {
			const p = (this.order && this.order.props) || {};
			return p.fulfilledAt || null;
		},
		isCancelled(): boolean {
			return !!this.cancelledAt || this.integrationStatus === 'cancelled';
		},
		isFulfilled(): boolean {
			return this.fulfillmentStatus === 'fulfilled' || !!this.fulfilledAt || this.integrationStatus === 'fulfilled';
		},
		// Read-only line rows from the mirror. total = unit price × quantity.
		lineItems(): any[] {
			const b = this.body || {};
			const list = Array.isArray(b.line_items) ? b.line_items : [];
			return list.map((li: any) => {
				const qty = Number(li.quantity) || 0;
				const price = (li.price != null && li.price !== '') ? Number(li.price) : null;
				return {
					title: li.title || li.name || '',
					variantTitle: li.variant_title || '',
					sku: li.sku || '',
					quantity: qty,
					price,
					total: (price != null && Number.isFinite(price)) ? price * qty : null,
				};
			});
		},
		// Shipping + billing addresses, tagged by role (read-only display).
		addresses(): any[] {
			const b = this.body || {};
			const rows: any[] = [];
			const mk = (a: any, role: string) => ({
				role,
				name: (a.name || [a.first_name, a.last_name].filter(Boolean).join(' ')).trim(),
				company: a.company || '',
				lines: [a.address1, a.address2].filter(Boolean),
				cityLine: [a.zip, a.city, a.province].filter(Boolean).join(' '),
				country: a.country || a.country_code || '',
				phone: a.phone || '',
			});
			if (b.shipping_address && typeof b.shipping_address === 'object') rows.push(mk(b.shipping_address, 'shipping'));
			if (b.billing_address && typeof b.billing_address === 'object') rows.push(mk(b.billing_address, 'billing'));
			return rows;
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
		// Money: accepts the wire money object { currency, amount } (commerce.Api
		// shape) or a bare amount, degrading to the order's own currency
		// (body.currency) when the value carries none.
		fmtMoney(value: any): string {
			let cur = String((this.body && this.body.currency) || '').trim();
			if (value != null && typeof value === 'object') {
				cur = String(value.currency || cur).trim();
				value = value.amount;
			}
			if (value == null || value === '') return '—';
			return formatCurrency(this.localization, value, cur ? { currency: cur } : {});
		},
		// Money in the shop / base currency (for a cross-currency order total).
		fmtMoneyBase(value: any): string {
			let cur = String(this.baseCurrency || '').trim();
			if (value != null && typeof value === 'object') {
				cur = String(value.currency || cur).trim();
				value = value.amount;
			}
			if (value == null || value === '') return '—';
			return formatCurrency(this.localization, value, cur ? { currency: cur } : {});
		},
		// Localized label for a Shopify status enum, falling back to the raw value
		// (domain data) when no translation exists — same pattern as the customer
		// editor's marketing state.
		financialLabel(): string {
			const s = this.financialStatus;
			return s ? this.t('app.commerce-order.financial.' + s, undefined, s) : '—';
		},
		fulfillmentLabel(): string {
			const s = this.fulfillmentStatus;
			return s ? this.t('app.commerce-order.fulfillment.' + s, undefined, s) : '—';
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
				try { instance.windowTitle = vm.t('app.commerce-order.title', undefined, 'Commerce Order'); } catch (_) {}

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
		},

		// MIME launch: the Content Browser / order browser hands the node path
		// (options.path). Derive the order id from order_{id}.json (the store nests
		// by year/month, so match anywhere in the path).
		applyLaunchOptions(options: any) {
			const o = (options && typeof options === 'object') ? options : {};
			const path = String(o.path || (Array.isArray(o.paths) && o.paths[0]) || '');
			const m = path.match(/order_(\d+)\.json$/);
			if (m) this.selectOrder(m[1]);
			else if (o.orderId) this.selectOrder(String(o.orderId));
			else if (o.id) this.selectOrder(String(o.id));
		},

		// ---- Window controls -------------------------------------------------
		onMinimizeWindow() { this.instance?.minimize(); },
		onToggleMaximizeWindow() { this.instance?.toggleMaximize(); },
		onCloseWindow() { this.instance?.requestClose(); },

		selectSection(section: any) { this.section = section; },

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

		// ---- Order load ------------------------------------------------------
		async selectOrder(orderId: string) {
			const id = String(orderId || '').trim();
			if (!id) return;
			if (this.order && this.orderId === id) return;
			if (this.hasChanges) {
				const action = await this.askDiscard();
				if (action === 'cancel') return;
				if (action === 'save') { await this.save(); if (this.hasChanges) return; }
			}
			await this.loadOrder(id);
		},

		async reload() {
			if (!this.orderId) return;
			if (this.hasChanges) {
				const action = await this.askDiscard();
				if (action === 'cancel') return;
				if (action === 'save') { await this.save(); if (this.hasChanges) return; }
			}
			await this.loadOrder(this.orderId);
		},

		async loadOrder(orderId: string) {
			this.loading = true;
			try {
				const view = await this.getJson(`${ORDERS_SCRIPT}?view=order&id=${encodeURIComponent(orderId)}`);
				this.order = view;
				// The wire id is the Shopify GID (commerce.Api) — carried opaquely;
				// endpoints accept it back as-is and peel it server-side.
				this.orderId = String(view.id || orderId);
				this.body = (view.body && typeof view.body === 'object') ? view.body : {};
				try { this.instance.windowTitle = this.orderTitle || this.t('app.commerce-order.title', undefined, 'Commerce Order'); } catch (_) {}
				this.populateFromOrder();
				this.setStatus('', '');
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-order.err.loadFailed', undefined, 'Could not load the order.'), true);
			} finally {
				this.loading = false;
			}
		},

		// Seed the edit model + baseline from the loaded mirror.
		populateFromOrder() {
			const b = this.body || {};
			const props = (this.order && this.order.props) || {};
			const note = String(b.note || '');
			const tags = parseTags(b.tags != null ? b.tags : props.tags);
			const attrs = parseAttrs(b.note_attributes != null ? b.note_attributes : (b.customAttributes || b.custom_attributes));
			this.edit = { note, tags, customAttributes: attrs.map((a) => ({ ...a })) };
			this._original = { note, tags: tags.slice(), customAttributes: attrs.map((a) => ({ ...a })) };
			this._savedEditJson = JSON.stringify(this.editSnapshot());
		},

		editSnapshot(): EditModel {
			return {
				note: this.edit.note,
				tags: this.edit.tags.slice(),
				customAttributes: normAttrs(this.edit.customAttributes),
			};
		},

		// ---- Tags editor -----------------------------------------------------
		addTag() {
			const parts = String(this.tagInput || '').split(',');
			for (const p of parts) {
				const s = p.trim();
				if (!s) continue;
				if (this.edit.tags.some((t) => t.toLowerCase() === s.toLowerCase())) continue;
				this.edit.tags.push(s);
			}
			this.tagInput = '';
		},
		removeTag(i: number) { this.edit.tags.splice(i, 1); },

		// ---- Custom attributes editor ----------------------------------------
		addAttr() { this.edit.customAttributes.push({ key: '', value: '' }); },
		removeAttr(i: number) { this.edit.customAttributes.splice(i, 1); },

		// ---- Revert ----------------------------------------------------------
		revert() {
			if (!this.hasChanges) return;
			this.edit = {
				note: this._original.note,
				tags: this._original.tags.slice(),
				customAttributes: this._original.customAttributes.map((a) => ({ ...a })),
			};
			this.tagInput = '';
			this.setStatus('', '');
		},

		// ---- Save (Shopify-side: orderUpdate via sync.groovy) ----------------
		// Only changed fields are sent.
		buildChangedFields(): any {
			const fields: any = {};
			if (this.edit.note !== this._original.note) fields.note = this.edit.note;
			if (!arraysEqual(this.edit.tags, this._original.tags)) fields.tags = this.edit.tags.slice();
			const curAttrs = normAttrs(this.edit.customAttributes);
			if (!attrsEqual(curAttrs, this._original.customAttributes)) {
				fields.customAttributes = curAttrs.map((a) => ({ key: a.key, value: a.value }));
			}
			return fields;
		},

		async save() {
			if (!this.order || this.saving || !this.hasChanges) return;
			if (!this.adminApiEnabled) {
				this.showToast(this.t('app.commerce-order.hint.adminApiOff', undefined, 'The Shopify Admin API is disabled — edits cannot be written.'), true);
				return;
			}
			const ok = await this.confirmAction(
				this.t('app.commerce-order.confirm.saveTitle', undefined, 'Save changes to Shopify'),
				this.t('app.commerce-order.confirm.saveMsg', { name: this.orderTitle }, 'Write these changes to the order in Shopify?'),
				this.t('app.commerce-order.confirm.apply', undefined, 'Save to Shopify'),
			);
			if (!ok) return;

			this.saving = true;
			try {
				const fields = this.buildChangedFields();
				const { status, json } = await this.postJson(SYNC_SCRIPT, {
					action: 'order', orderId: this.orderId, fields,
				});
				if (status < 200 || status >= 300 || json.ok === false || json.error) {
					throw new Error(json.error || `Save failed (${status})`);
				}
				this.applySavedBaseline(fields);
				this.setStatus('ok', this.t('app.commerce-order.status.saved', undefined, 'Saved to Shopify. The mirror follows via webhook.'));
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-order.err.saveFailed', undefined, 'Write to Shopify failed.'), true);
				this.setStatus('err', this.t('app.commerce-order.err.saveFailed', undefined, 'Write to Shopify failed.'));
			} finally {
				this.saving = false;
			}
		},

		// Optimistically reflect the saved values: reset the baseline (clears the
		// dirty state) and mirror ONLY the written fields into the displayed body so
		// the read-only panels stay consistent until the webhook re-ingests the
		// mirror. customAttributes mirror back into the REST-shaped note_attributes
		// ({ name, value }) the mirror actually carries.
		applySavedBaseline(changed: any) {
			this._original = {
				note: this.edit.note,
				tags: this.edit.tags.slice(),
				customAttributes: normAttrs(this.edit.customAttributes),
			};
			const c = changed || {};
			if ('note' in c) this.body.note = this.edit.note;
			if ('tags' in c) this.body.tags = this.edit.tags.join(', ');
			if ('customAttributes' in c) {
				this.body.note_attributes = this._original.customAttributes.map((a) => ({ name: a.key, value: a.value }));
			}
			this._savedEditJson = JSON.stringify(this.editSnapshot());
		},

		// ---- Dialogs ---------------------------------------------------------
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

VDOM.createApp(App).mount('#app');
