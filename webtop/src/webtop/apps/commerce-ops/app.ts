// Commerce Operations — operator console for the outbound sync (#2), reconciliation
// (#24) and event log / replay (#1, #4) surfaces of the integration.
//
// Three tabs, each driven by an existing admin endpoint:
//   • Sync       → sync.groovy      — push a correction to Shopify (inventory / price /
//                  publish / metafields), with a Dry run toggle (#28); recent outbound
//                  writes come from reports.groovy?type=operations.
//   • Reconcile  → reconcile.groovy — latest CMS↔Shopify drift report + cursor state,
//                  and a "Run now" trigger.
//   • Events     → events.groovy    — the event log with status/source/topic/since
//                  filters, single-event replay and replay-all-matching.
//
// Read-mostly, with explicit confirmation before anything that mutates Shopify or
// re-dispatches many events. Self-contained (ichigo.js runtime only).

import { VDOM } from '@mintjamsinc/ichigojs';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
	formatDate,
} from '../../composables/use-localization.js';

type AnyInstance = any;

const SYNC_SCRIPT = '/content/commerce/endpoints/sync.groovy';
const RECONCILE_SCRIPT = '/content/commerce/endpoints/reconcile.groovy';
const EVENTS_SCRIPT = '/content/commerce/endpoints/events.groovy';
const REPORTS_SCRIPT = '/content/commerce/endpoints/reports.groovy';

const App = {
	data() {
		return {
			instance: null as AnyInstance,
			// Reactive localization snapshot — drives every t() / fmtTime() binding
			// so the app repaints when the user switches language or a bundle is
			// hot-reloaded. See composables/use-localization.ts.
			localization: createLocalizationSnapshot(),
			section: 'sync' as 'sync' | 'reconcile' | 'events',
			busy: false,
			status: '',
			statusKind: '' as '' | 'ok' | 'err',
			toast: '',
			toastError: false,

			// Sync tab
			sync: { enabled: false, shopDomain: '', apiVersion: '' },
			form: { action: 'inventory', dryRun: true, inventoryItemId: '', locationId: '', quantity: '', reason: '', productId: '', variantId: '', price: '', published: true },
			syncResult: null as null | { ok: boolean; title: string; body: string },
			operations: [] as any[],

			// Reconcile tab
			recon: { productsWithDrift: 0, totalDiffs: 0, healed: 0, checked: 0, lastRunAt: '', cursor: '', diffs: [] as any[] },

			// Events tab
			evFilter: { status: 'error', source: '', topic: '', sinceDays: '' },
			events: { summary: { total: 0, received: 0, processed: 0, error: 0 }, rows: [] as any[] },

			confirmDialog: { visible: false, title: '', message: '', ok: '', resolve: null as null | ((v: boolean) => void) },

			_base: '' as string,
			_messageListener: null as any,
			_toastTimer: null as any,
			_loaded: { sync: false, reconcile: false, events: false },
		};
	},

	computed: {
		canExecute(): boolean {
			if (this.busy) return false;
			if (!this.form.dryRun && !this.sync.enabled) return false;
			const f = this.form;
			switch (f.action) {
				case 'inventory': return !!(f.inventoryItemId && f.locationId && String(f.quantity).trim() !== '');
				case 'price': return !!(f.productId && f.variantId && String(f.price).trim() !== '');
				case 'publish': return !!f.productId;
				case 'metafields': return !!f.productId;
				default: return false;
			}
		},
	},

	methods: {
		// ---- i18n / locale-aware formatting ------------------------------------
		// Reactive i18n lookup: reading the localization snapshot inside
		// translate() subscribes every {{ t(...) }} binding, so the UI repaints
		// the instant the user switches language or a bundle hot-reloads.
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},
		// Locale- and timezone-aware datetime formatting.
		fmtTime(v: any): string {
			if (!v) return '—';
			const d = new Date(v);
			if (isNaN(d.getTime())) return String(v);
			return formatDate(this.localization, d, { format: 'datetime' });
		},

		onMounted() {
			const vm = this;
			vm._messageListener = (event: MessageEvent) => {
				const data: any = event.data || {};
				// Fold locale / time-zone / currency changes and i18n bundle
				// hot-reloads into the reactive snapshot so the UI re-localizes live.
				if (handleLocalizationMessage(data.type, vm.localization, vm.instance)) return;
				if (data.type === 'theme-changed' && data.theme) document.documentElement.dataset.theme = data.theme;
				// Drill-down re-target from another app (e.g. the dashboard) when this
				// singleton console is already open: route to the requested view.
				else if (data.type === 'app-reopen') vm.applyLaunchOptions(data.options, false);
			};
			window.addEventListener('message', vm._messageListener);

			window.appLaunch = async (instance: AnyInstance, options?: any) => {
				vm.instance = vm.$markRaw(instance);
				try { document.documentElement.dataset.theme = instance.api.theme.currentTheme || 'light'; } catch (_) {}

				// Snapshot the effective localization preference so the first paint
				// is already in the user's language / region.
				refreshLocalization(vm.localization, vm.instance);

				try { instance.windowTitle = vm.t('app.commerce-ops.title', undefined, 'Commerce Operations'); } catch (_) {}
				// Persist the active section so a saved session reopens the same tab.
				instance.appState = () => ({ section: vm.section });
				await vm.resolveBase();
				vm.applyLaunchOptions(options, true);
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
		selectSection(section: 'sync' | 'reconcile' | 'events') {
			this.section = section;
			if (!this._loaded[section]) this.loadSection(section);
		},
		refresh() { this.loadSection(this.section, true); },

		// Apply launch options from a drill-down / deep-link. Shape:
		//   { section: 'sync' | 'reconcile' | 'events',
		//     eventFilter?: { status?, source?, topic?, sinceDays? } }
		// `initial` is true on first launch (defaults to the Sync tab when no
		// section is given) and false on re-target (keeps the current tab).
		applyLaunchOptions(options: any, initial = false) {
			const o = (options && typeof options === 'object') ? options : {};
			let section = o.section;
			if (section !== 'sync' && section !== 'reconcile' && section !== 'events') {
				section = initial ? 'sync' : this.section;
			}
			if (section === 'events' && o.eventFilter && typeof o.eventFilter === 'object') {
				const f = o.eventFilter;
				this.evFilter = {
					status: f.status != null ? String(f.status) : 'error',
					source: f.source != null ? String(f.source) : '',
					topic: f.topic != null ? String(f.topic) : '',
					sinceDays: f.sinceDays != null ? String(f.sinceDays) : '',
				};
				this._loaded.events = false; // force a reload with the new filter
			}
			// Always (re)load the target section so a drill-down reflects fresh data.
			this._loaded[section] = false;
			this.selectSection(section);
		},

		async loadSection(section: string, force = false) {
			if (section === 'sync') { await this.loadSync(); if (force) await this.loadOperations(); else await this.loadOperations(); }
			else if (section === 'reconcile') await this.loadReconcile();
			else if (section === 'events') await this.loadEvents();
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

		// ---- Sync ------------------------------------------------------------
		async loadSync() {
			try {
				const j = await this.getJson(SYNC_SCRIPT);
				this.sync = { enabled: j.enabled === true, shopDomain: j.shopDomain || '', apiVersion: j.apiVersion || '' };
			} catch (_) { this.sync = { enabled: false, shopDomain: '', apiVersion: '' }; }
		},
		async loadOperations() {
			try {
				const j = await this.getJson(`${REPORTS_SCRIPT}?type=operations&days=30`);
				this.operations = this.$markRaw(Array.isArray(j.operations) ? j.operations.slice(0, 100) : []);
			} catch (_) { this.operations = []; }
		},
		buildSyncBody(): any {
			const f = this.form;
			const body: any = { action: f.action, dryRun: !!f.dryRun };
			if (f.action === 'inventory') { body.inventoryItemId = f.inventoryItemId; body.locationId = f.locationId; body.quantity = f.quantity; if (f.reason) body.reason = f.reason; }
			else if (f.action === 'price') { body.productId = f.productId; body.variantId = f.variantId; body.price = f.price; }
			else if (f.action === 'publish') { body.productId = f.productId; body.published = !!f.published; }
			else if (f.action === 'metafields') { body.productId = f.productId; }
			return body;
		},
		async executeSync() {
			if (!this.canExecute) return;
			if (!this.form.dryRun) {
				const ok = await this.confirm(
					this.t('app.commerce-ops.confirm.pushTitle', undefined, 'Push to Shopify'),
					this.t('app.commerce-ops.confirm.pushMsg', { action: this.form.action }, `Execute "${this.form.action}" against Shopify now? This mutates live data.`),
					this.t('app.commerce-ops.confirm.pushOk', undefined, 'Execute'),
				);
				if (!ok) return;
			}
			this.busy = true; this.syncResult = null;
			try {
				const { status, json } = await this.postJson(SYNC_SCRIPT, this.buildSyncBody());
				const ok = status >= 200 && status < 300 && json.ok !== false;
				const payload = json.plan ?? json.result ?? json;
				this.syncResult = {
					ok,
					title: ok
						? (json.dryRun
							? this.t('app.commerce-ops.sync.result.dryRun', undefined, 'Dry run — validated')
							: this.t('app.commerce-ops.sync.result.executed', undefined, 'Executed'))
						: (json.error || this.t('app.commerce-ops.sync.result.failed', { status }, `Failed (${status})`)),
					body: JSON.stringify(payload, null, 2),
				};
				this.setStatus(ok ? 'ok' : 'err', ok
					? this.t('app.commerce-ops.statusbar.done', undefined, 'Done')
					: this.t('app.commerce-ops.statusbar.failed', undefined, 'Failed'));
				if (ok && !json.dryRun) this.loadOperations();
			} catch (e: any) {
				this.syncResult = { ok: false, title: e?.message || this.t('app.commerce-ops.sync.result.failed', { status: '?' }, 'Request failed'), body: '' };
				this.setStatus('err', this.t('app.commerce-ops.statusbar.failed', undefined, 'Failed'));
			} finally { this.busy = false; }
		},

		// ---- Reconcile -------------------------------------------------------
		async loadReconcile() {
			try {
				const j = await this.getJson(RECONCILE_SCRIPT);
				const r = j.latest || {};
				const st = j.state || {};
				this.recon = {
					productsWithDrift: Number(r.productsWithDrift) || 0,
					totalDiffs: Number(r.totalDiffs) || 0,
					healed: Number(r.healed) || 0,
					checked: Number(r.checked) || 0,
					lastRunAt: r.generatedAt || st.lastRunAt || '',
					cursor: st.cursor || '',
					diffs: this.$markRaw(Array.isArray(r.diffs) ? r.diffs : []),
				};
			} catch (_) { /* keep */ }
		},
		async runReconcile() {
			const ok = await this.confirm(
				this.t('app.commerce-ops.reconcile.runTitle', undefined, 'Run reconciliation'),
				this.t('app.commerce-ops.reconcile.runMsg', undefined, 'Run a reconciliation pass now? It detects and reports drift (healing stays opt-in per field).'),
				this.t('app.commerce-ops.reconcile.runOk', undefined, 'Run'),
			);
			if (!ok) return;
			this.busy = true;
			try {
				const { status } = await this.postJson(RECONCILE_SCRIPT, {});
				if (status === 202 || status === 200) {
					this.showToast(this.t('app.commerce-ops.reconcile.started', undefined, 'Reconciliation started. Refreshing shortly…'), false);
					setTimeout(() => this.loadReconcile(), 4000);
					setTimeout(() => this.loadReconcile(), 12000);
				} else { this.showToast(this.t('app.commerce-ops.reconcile.couldNotStart', { status }, `Could not start (${status}).`), true); }
			} catch (e: any) { this.showToast(e?.message || this.t('app.commerce-ops.reconcile.couldNotStart', { status: '?' }, 'Could not start.'), true); }
			finally { this.busy = false; }
		},
		healClass(d: any): string {
			if (d.healed === 'ok') return 'st-processed';
			if (d.heal === 'push' || d.heal === 'refresh') return 'st-received';
			return 'st-report';
		},

		// ---- Events ----------------------------------------------------------
		eventsQuery(): string {
			const f = this.evFilter; const p = new URLSearchParams();
			if (f.status) p.set('status', f.status);
			if (f.source) p.set('source', f.source);
			if (f.topic) p.set('topic', f.topic);
			if (String(f.sinceDays).trim()) p.set('sinceDays', String(f.sinceDays).trim());
			p.set('limit', '200');
			return p.toString();
		},
		async loadEvents() {
			this.busy = true;
			try {
				const j = await this.getJson(`${EVENTS_SCRIPT}?${this.eventsQuery()}`);
				const by = (j.summary && j.summary.byStatus) || {};
				this.events = {
					summary: { total: Number(j.summary?.total) || 0, received: Number(by.received) || 0, processed: Number(by.processed) || 0, error: Number(by.error) || 0 },
					rows: this.$markRaw(Array.isArray(j.events) ? j.events : []),
				};
			} catch (e: any) { this.showToast(e?.message || this.t('app.commerce-ops.events.loadFailed', undefined, 'Could not load events.'), true); }
			finally { this.busy = false; }
		},
		async replayOne(e: any) {
			this.busy = true;
			try {
				const { json } = await this.postJson(EVENTS_SCRIPT, { eventId: e.event_id, source: e.source });
				this.showToast(this.t('app.commerce-ops.events.replayed', { count: json.replayed || 0 }, `Replayed ${json.replayed || 0} event(s).`), false);
				setTimeout(() => this.loadEvents(), 1500);
			} catch (err: any) { this.showToast(err?.message || this.t('app.commerce-ops.events.replayFailed', undefined, 'Replay failed.'), true); }
			finally { this.busy = false; }
		},
		async replayMatching() {
			const f = this.evFilter;
			const desc = [f.status ? `status=${f.status}` : 'all', f.source && `source=${f.source}`, f.topic && `topic=${f.topic}`, String(f.sinceDays).trim() && `since=${f.sinceDays}d`].filter(Boolean).join(', ');
			const ok = await this.confirm(
				this.t('app.commerce-ops.events.replayMatchingTitle', undefined, 'Replay matching'),
				this.t('app.commerce-ops.events.replayMatchingMsg', { desc }, `Re-dispatch every event matching: ${desc}. This can replay many events.`),
				this.t('app.commerce-ops.events.replayOk', undefined, 'Replay'),
			);
			if (!ok) return;
			this.busy = true;
			try {
				const body: any = {};
				if (f.status) body.status = f.status;
				if (f.source) body.source = f.source;
				if (f.topic) body.topic = f.topic;
				if (String(f.sinceDays).trim()) body.sinceDays = Number(f.sinceDays);
				const { json } = await this.postJson(EVENTS_SCRIPT, body);
				this.showToast(this.t('app.commerce-ops.events.replayedMatched', { replayed: json.replayed || 0, matched: json.matched || 0 }, `Replayed ${json.replayed || 0} of ${json.matched || 0} matched.`), false);
				setTimeout(() => this.loadEvents(), 1800);
			} catch (e: any) { this.showToast(e?.message || this.t('app.commerce-ops.events.replayFailed', undefined, 'Replay failed.'), true); }
			finally { this.busy = false; }
		},

		// ---- Confirm dialog --------------------------------------------------
		confirm(title: string, message: string, ok: string): Promise<boolean> {
			const vm = this;
			vm.confirmDialog = { visible: true, title, message, ok, resolve: null };
			return new Promise((resolve) => { vm.confirmDialog.resolve = resolve; });
		},
		onConfirm(value: boolean) {
			if (this.confirmDialog.resolve) this.confirmDialog.resolve(value);
			this.confirmDialog.visible = false;
			this.confirmDialog.resolve = null;
		},

		// ---- Format / status -------------------------------------------------
		// NOTE: fmtTime is defined above in the i18n section; this stub is removed
		// to avoid shadowing the locale-aware implementation.
		setStatus(kind: '' | 'ok' | 'err', msg: string) { this.statusKind = kind; this.status = msg; },
		showToast(msg: string, isError: boolean) {
			this.toast = msg; this.toastError = !!isError;
			if (this._toastTimer) clearTimeout(this._toastTimer);
			this._toastTimer = window.setTimeout(() => { this.toast = ''; }, 3400);
		},
	},
};

VDOM.createApp(App).mount('#app');
