// Commerce Customer — the single-customer editor.
//
// Everything about ONE customer in one place, Shopify-admin-like:
//   • Overview     — profile & wallet: name / email / phone / orders / lifetime
//                    spend / created / account state / verified email (read-only
//                    mirror of the Shopify customer).
//   • Marketing & tax — the two Shopify-owned toggles the shop curates: email
//                    marketing consent (subscribed / unsubscribed) and tax
//                    exemption.
//   • Tags         — the customer's tags (VIP is just a tag the operator adds).
//   • Note         — the internal note.
//   • Addresses    — the default address + address book (read-only display).
//
// WRITE MODEL: every editable field here is Shopify-owned. Save writes to SHOPIFY
// via the sync endpoint (Admin API → customerUpdate, plus
// customerEmailMarketingConsentUpdate when consent changes); the CMS mirror is
// never edited directly — it follows through the webhook round-trip. The editor
// optimistically reflects the saved values; a manual refresh re-reads crm.groovy.
//
// Launch: from the customer browser / Content Browser via the customer MIME type
// (options.path → /content/commerce/customers/customer_{id}.json).

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

const CRM_SCRIPT = '/content/commerce/endpoints/crm.groovy';
const SYNC_SCRIPT = '/content/commerce/endpoints/sync.groovy';

interface EditModel {
	tags: string[];
	note: string;
	taxExempt: boolean;
	marketingSubscribed: boolean;
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

function arraysEqual(a: string[], b: string[]): boolean {
	if (a.length !== b.length) return false;
	for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
	return true;
}

const App = {
	data() {
		return {
			instance: null as AnyInstance,
			localization: createLocalizationSnapshot(),

			section: 'overview' as 'overview' | 'marketing' | 'tags' | 'note' | 'addresses',

			// Selected customer
			loading: false,
			customerId: '' as string,
			customer: null as any,           // crm view { id (GID), path, body, props }
			body: {} as any,                 // the parsed Shopify customer mirror JSON

			// Edit model (only Shopify-owned fields this editor curates)
			edit: { tags: [], note: '', taxExempt: false, marketingSubscribed: false } as EditModel,
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

			_original: { tags: [], note: '', taxExempt: false, marketingSubscribed: false } as EditModel,
			_savedEditJson: '{}',
			_base: '' as string,
			_messageListener: null as any,
			_toastTimer: null as any,
		};
	},

	computed: {
		hasChanges(): boolean {
			if (!this.customer) return false;
			return JSON.stringify(this.editSnapshot()) !== this._savedEditJson;
		},
		displayName(): string {
			const b = this.body || {};
			const n = [b.first_name, b.last_name].filter(Boolean).join(' ').trim();
			if (n) return n;
			const pn = String((this.customer && this.customer.props && this.customer.props.name) || '').trim();
			if (pn) return pn;
			if (b.email) return String(b.email);
			return this.customerId ? this.t('app.commerce-customer.customer.idFallback', { id: this.customerId }) : '';
		},
		accountState(): string { return String((this.body && this.body.state) || '').toLowerCase(); },
		marketingState(): string {
			const b = this.body || {};
			const c = b.email_marketing_consent;
			if (c && c.state) return String(c.state).toLowerCase();
			const p = this.customer && this.customer.props && this.customer.props.marketingConsent;
			if (p && typeof p === 'object' && p.state) return String(p.state).toLowerCase();
			if (typeof p === 'string') return p.toLowerCase();
			return '';
		},
		// Normalized address rows: the address book, with the default flagged. Falls
		// back to default_address alone when addresses[] is absent.
		addresses(): any[] {
			const b = this.body || {};
			const list = Array.isArray(b.addresses) ? b.addresses : [];
			const def = b.default_address || null;
			const defId = def && def.id != null ? def.id : null;
			const rows = list.length ? list : (def ? [def] : []);
			return rows.map((a: any) => ({
				id: a.id,
				isDefault: defId != null ? a.id === defId : (!list.length && !!def),
				name: (a.name || [a.first_name, a.last_name].filter(Boolean).join(' ')).trim(),
				company: a.company || '',
				lines: [a.address1, a.address2].filter(Boolean),
				cityLine: [a.zip, a.city, a.province].filter(Boolean).join(' '),
				country: a.country || a.country_code || '',
				phone: a.phone || '',
			}));
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
		// Lifetime spend in the customer's own currency (body.currency), degrading
		// to a plain number when the mirror carries no currency.
		fmtMoney(value: any): string {
			if (value == null || value === '') return '—';
			const cur = String((this.body && this.body.currency) || '').trim();
			return formatCurrency(this.localization, value, cur ? { currency: cur } : {});
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
				try { instance.windowTitle = vm.t('app.commerce-customer.title', undefined, 'Commerce Customer'); } catch (_) {}

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

		// MIME launch: the Content Browser / customer browser hands the node path
		// (options.path). Derive the customer id from customer_{id}.json.
		applyLaunchOptions(options: any) {
			const o = (options && typeof options === 'object') ? options : {};
			const path = String(o.path || (Array.isArray(o.paths) && o.paths[0]) || '');
			const m = path.match(/customer_(\d+)\.json$/);
			if (m) this.selectCustomer(m[1]);
			else if (o.customerId) this.selectCustomer(String(o.customerId));
			else if (o.id) this.selectCustomer(String(o.id));
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

		// ---- Customer load ---------------------------------------------------
		async selectCustomer(customerId: string) {
			const id = String(customerId || '').trim();
			if (!id) return;
			if (this.customer && this.customerId === id) return;
			if (this.hasChanges) {
				const action = await this.askDiscard();
				if (action === 'cancel') return;
				if (action === 'save') { await this.save(); if (this.hasChanges) return; }
			}
			await this.loadCustomer(id);
		},

		async reload() {
			if (!this.customerId) return;
			if (this.hasChanges) {
				const action = await this.askDiscard();
				if (action === 'cancel') return;
				if (action === 'save') { await this.save(); if (this.hasChanges) return; }
			}
			await this.loadCustomer(this.customerId);
		},

		async loadCustomer(customerId: string) {
			this.loading = true;
			try {
				const view = await this.getJson(`${CRM_SCRIPT}?view=customer&id=${encodeURIComponent(customerId)}`);
				this.customer = view;
				// The wire id is the Shopify GID (commerce.Api) — carried opaquely;
				// endpoints accept it back as-is and peel it server-side.
				this.customerId = String(view.id || customerId);
				this.body = (view.body && typeof view.body === 'object') ? view.body : {};
				try { this.instance.windowTitle = this.displayName || this.t('app.commerce-customer.title', undefined, 'Commerce Customer'); } catch (_) {}
				this.populateFromCustomer();
				this.setStatus('', '');
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-customer.err.loadFailed', undefined, 'Could not load the customer.'), true);
			} finally {
				this.loading = false;
			}
		},

		// Seed the edit model + baseline from the loaded mirror.
		populateFromCustomer() {
			const b = this.body || {};
			const props = (this.customer && this.customer.props) || {};
			const tags = parseTags(b.tags != null ? b.tags : props.tags);
			const note = String(b.note || '');
			const taxExempt = b.tax_exempt === true;
			const marketingSubscribed = this.marketingState === 'subscribed';
			this.edit = { tags, note, taxExempt, marketingSubscribed };
			this._original = { tags: tags.slice(), note, taxExempt, marketingSubscribed };
			this._savedEditJson = JSON.stringify(this.editSnapshot());
		},

		editSnapshot(): EditModel {
			return {
				tags: this.edit.tags.slice(),
				note: this.edit.note,
				taxExempt: this.edit.taxExempt,
				marketingSubscribed: this.edit.marketingSubscribed,
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

		// ---- Revert ----------------------------------------------------------
		revert() {
			if (!this.hasChanges) return;
			this.edit = {
				tags: this._original.tags.slice(),
				note: this._original.note,
				taxExempt: this._original.taxExempt,
				marketingSubscribed: this._original.marketingSubscribed,
			};
			this.tagInput = '';
			this.setStatus('', '');
		},

		// ---- Save (Shopify-side: customerUpdate via sync.groovy) --------------
		// Only changed fields are sent.
		buildChangedFields(): any {
			const fields: any = {};
			if (!arraysEqual(this.edit.tags, this._original.tags)) fields.tags = this.edit.tags.slice();
			if (this.edit.note !== this._original.note) fields.note = this.edit.note;
			if (this.edit.taxExempt !== this._original.taxExempt) fields.taxExempt = this.edit.taxExempt;
			if (this.edit.marketingSubscribed !== this._original.marketingSubscribed) {
				fields.marketingConsent = {
					state: this.edit.marketingSubscribed ? 'subscribed' : 'unsubscribed',
					optInLevel: 'single_opt_in',
				};
			}
			return fields;
		},

		async save() {
			if (!this.customer || this.saving || !this.hasChanges) return;
			if (!this.adminApiEnabled) {
				this.showToast(this.t('app.commerce-customer.hint.adminApiOff', undefined, 'The Shopify Admin API is disabled — edits cannot be written.'), true);
				return;
			}
			const ok = await this.confirmAction(
				this.t('app.commerce-customer.confirm.saveTitle', undefined, 'Save changes to Shopify'),
				this.t('app.commerce-customer.confirm.saveMsg', { name: this.displayName }, 'Write these changes to the customer in Shopify?'),
				this.t('app.commerce-customer.confirm.apply', undefined, 'Save to Shopify'),
			);
			if (!ok) return;

			this.saving = true;
			try {
				const fields = this.buildChangedFields();
				const { status, json } = await this.postJson(SYNC_SCRIPT, {
					action: 'customer', customerId: this.customerId, fields,
				});
				if (status < 200 || status >= 300 || json.ok === false || json.error) {
					throw new Error(json.error || `Save failed (${status})`);
				}
				this.applySavedBaseline(fields);
				this.setStatus('ok', this.t('app.commerce-customer.status.saved', undefined, 'Saved to Shopify. The mirror follows via webhook.'));
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-customer.err.saveFailed', undefined, 'Write to Shopify failed.'), true);
				this.setStatus('err', this.t('app.commerce-customer.err.saveFailed', undefined, 'Write to Shopify failed.'));
			} finally {
				this.saving = false;
			}
		},

		// Optimistically reflect the saved values: reset the baseline (clears the
		// dirty state) and mirror them into the displayed body so the read-only
		// panels stay consistent until the webhook re-ingests the mirror.
		applySavedBaseline(changed: any) {
			this._original = {
				tags: this.edit.tags.slice(),
				note: this.edit.note,
				taxExempt: this.edit.taxExempt,
				marketingSubscribed: this.edit.marketingSubscribed,
			};
			// Mirror ONLY the fields that were actually written. Marketing state is a
			// binary in the editor, so mirroring it on an unrelated save would collapse
			// a non-binary source state ('not_subscribed'/'pending') to 'unsubscribed';
			// leaving untouched fields alone keeps the display honest until the webhook
			// re-ingests the mirror.
			const c = changed || {};
			if ('tags' in c) this.body.tags = this.edit.tags.join(', ');
			if ('note' in c) this.body.note = this.edit.note;
			if ('taxExempt' in c) this.body.tax_exempt = this.edit.taxExempt;
			if ('marketingConsent' in c) {
				this.body.email_marketing_consent = {
					...((this.body.email_marketing_consent && typeof this.body.email_marketing_consent === 'object') ? this.body.email_marketing_consent : {}),
					state: this.edit.marketingSubscribed ? 'subscribed' : 'unsubscribed',
				};
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
