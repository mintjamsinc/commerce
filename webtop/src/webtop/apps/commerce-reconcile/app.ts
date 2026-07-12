// Commerce Reconcile — the Shopify → CMS reconciliation console (discrepancy reconciliation).
//
// A single-concern console over the mirrored catalog: it detects drift between
// the CMS mirror and Shopify, and refreshes the CMS mirror from Shopify.
// Reconciliation treats Shopify as the single source of truth — it never writes
// back to Shopify (writing happens in the editors and the workflow tasks).
// Split out of commerce-import so import (data intake) and reconcile (discrepancy
// matching) each stay single-concern.
//
// Driven by one existing admin endpoint:
//   • reconcile.groovy — GET returns the reconciliation state (last run) + the
//                        run history over a selectable window (24h/7d/30d), one
//                        row per run for BOTH scopes: the status/price diff batch
//                        ("diff" — the Products target) and the full inventory
//                        audit ("inventory"); POST triggers a fresh run of the
//                        selected target (diff pass or inventory bulk audit),
//                        which is refresh-only.
//
// Read-mostly, with explicit confirmation before the (refresh-only) "Run now".
// The list is exportable as CSV (BOM-prefixed UTF-8, Excel-safe), mirroring
// commerce-oplog. Self-contained (ichigo.js runtime only).

import { VDOM } from '@mintjamsinc/ichigojs';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
	formatDate,
} from '../../composables/use-localization.js';

type AnyInstance = any;

// Run target: what "Run now" triggers. 'inventory' enqueues a full inventory audit
// through the Bulk job broker; 'products' runs the status/price diff batch. The wire
// scope for products stays 'diff' (the reports' historical scope value).
type RunTarget = 'inventory' | 'products';
const TARGET_SCOPE: Record<RunTarget, string> = { inventory: 'inventory', products: 'diff' };

// History window selector (eip-console's toolbar range design, reduced to the
// windows the run history supports). Keys are the wire values.
type RangeKey = '24h' | '7d' | '30d';
const RANGE_OPTIONS: { key: RangeKey }[] = [{ key: '24h' }, { key: '7d' }, { key: '30d' }];

const RECONCILE_SCRIPT = '/content/commerce/endpoints/reconcile.groovy';
// Admin API capability probe: the reconcile pass fetches from Shopify, so it is
// gated on the Admin API being configured. sync.groovy GET reports
// { enabled, shopDomain, apiVersion } — the same probe the editors use.
const SYNC_SCRIPT = '/content/commerce/endpoints/sync.groovy';

const App = {
	data() {
		return {
			instance: null as AnyInstance,
			// Reactive localization snapshot — drives every t() / fmtTime() binding
			// so the app repaints when the user switches language or a bundle is
			// hot-reloaded.
			localization: createLocalizationSnapshot(),
			busy: false,
			status: '',
			statusKind: '' as '' | 'ok' | 'err',
			toast: '',
			toastError: false,

			// Admin API capability (from sync.groovy). The reconcile pass hits
			// Shopify, so when it is off the banner warns and Run disables.
			sync: { enabled: false },

			// Run history over the selected window.
			history: [] as any[],
			// History window (period selector in the toolbar); default 24h.
			rangeKey: '24h' as RangeKey,
			// Run-now target; defaults to Products so the button keeps its
			// pre-target behavior (the diff pass, not a full bulk audit).
			target: 'products' as RunTarget,

			confirmDialog: { visible: false, title: '', message: '', ok: '', resolve: null as null | ((v: boolean) => void) },

			_base: '' as string,
			_messageListener: null as any,
			_toastTimer: null as any,
			_loadSeq: 0,

			// Resizable trigger sidebar (mirrors commerce-import / settings app).
			sidebarVisible: true,
			sidebarWidth: 304,
			sidebarMinWidth: 180,
			sidebarMaxWidth: 480,
			sidebarResizing: false,
			sidebarResizeStartX: 0,
			sidebarResizeStartWidth: 0,
			_boundSidebarResizeMove: null as any,
			_boundSidebarResizeUp: null as any,
		};
	},

	computed: {
		rangeOptions(): typeof RANGE_OPTIONS {
			return RANGE_OPTIONS;
		},
		// CSV export is available whenever the list has rows to export.
		canDownload(): boolean {
			return (this as any).history.length > 0;
		},
	},

	watch: {
		// The window drives the server-side history query — refetch on change.
		rangeKey() { (this as any).loadReconcile(); },
	},

	methods: {
		// ---- i18n / locale-aware formatting ------------------------------------
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},
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
				if (handleLocalizationMessage(data.type, vm.localization, vm.instance)) return;
				if (data.type === 'theme-changed' && data.theme) document.documentElement.dataset.theme = data.theme;
				// Re-target from another app (e.g. the dashboard) when this singleton
				// console is already open: refresh the latest report.
				else if (data.type === 'app-reopen') vm.refresh();
			};
			window.addEventListener('message', vm._messageListener);

			window.appLaunch = async (instance: AnyInstance) => {
				vm.instance = vm.$markRaw(instance);
				try { document.documentElement.dataset.theme = instance.api.theme.currentTheme || 'light'; } catch (_) {}
				refreshLocalization(vm.localization, vm.instance);
				try { instance.windowTitle = vm.t('app.commerce-reconcile.title', undefined, 'Commerce Reconcile'); } catch (_) {}
				await vm.resolveBase();
				await vm.loadSidebarState();
				await vm.loadSync();
				await vm.loadReconcile();
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

		// ---- Left trigger sidebar (toggle / resize / persist) -----------------
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
				await db.setUserSetting(userID, 'commerce-reconcile', 'sidebar', JSON.parse(JSON.stringify({
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
				const state = await db.getUserSetting(userID, 'commerce-reconcile', 'sidebar');
				if (state) {
					vm.sidebarVisible = state.visible ?? true;
					vm.sidebarWidth = state.width ?? vm.sidebarWidth;
				}
			} catch (_) { /* non-critical */ }
		},

		// ---- Navigation ------------------------------------------------------
		async refresh() { await this.loadSync(); await this.loadReconcile(); },

		// ---- Admin API capability --------------------------------------------
		async loadSync() {
			try {
				const j = await this.getJson(SYNC_SCRIPT);
				this.sync = { enabled: j.enabled === true };
			} catch (_) { this.sync = { enabled: false }; }
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

		// ---- Reconcile -------------------------------------------------------
		async loadReconcile() {
			// Sequence guard: a rapid window switch (or the post-run refresh timers) can
			// resolve out of order — only the LATEST request may write the list.
			const seq = ++this._loadSeq;
			this.busy = true;
			try {
				const j = await this.getJson(`${RECONCILE_SCRIPT}?window=${this.rangeKey}`);
				if (seq !== this._loadSeq) return;
				this.history = this.$markRaw(Array.isArray(j.history) ? j.history : []);
			} catch (_) { /* keep */ }
			finally { this.busy = false; }
		},
		async runReconcile() {
			if (!this.sync.enabled) { this.showToast(this.t('app.commerce-reconcile.adminApi.required', undefined, 'Configure the Shopify Admin API first.'), true); return; }
			const inventory = this.target === 'inventory';
			const ok = await this.confirm(
				this.t('app.commerce-reconcile.reconcile.runTitle', undefined, 'Run reconciliation'),
				inventory
					? this.t('app.commerce-reconcile.reconcile.runMsgInventory', undefined, 'Run a full inventory audit now? It re-checks every inventory item against Shopify through a Bulk operation and refreshes the CMS mirror (nothing is written to Shopify).')
					: this.t('app.commerce-reconcile.reconcile.runMsg', undefined, 'Run a reconciliation pass now? It detects drift and refreshes the CMS mirror from Shopify (nothing is written to Shopify).'),
				this.t('app.commerce-reconcile.reconcile.runOk', undefined, 'Run'),
			);
			if (!ok) return;
			this.busy = true;
			try {
				const { status, json } = await this.postJson(RECONCILE_SCRIPT, { scope: TARGET_SCOPE[this.target] });
				if (status === 202 || status === 200) {
					if (json?.alreadyRunning) {
						this.showToast(this.t('app.commerce-reconcile.reconcile.alreadyRunning', undefined, 'An inventory audit is already running.'), false);
					} else if (inventory) {
						// A bulk audit finishes asynchronously (minutes, not seconds) — the
						// toast points at the history instead of promising a quick refresh.
						this.showToast(this.t('app.commerce-reconcile.reconcile.startedInventory', undefined, 'Inventory audit job enqueued. It appears in the history once it finishes.'), false);
					} else {
						this.showToast(this.t('app.commerce-reconcile.reconcile.started', undefined, 'Reconciliation started. Refreshing shortly…'), false);
					}
					setTimeout(() => this.loadReconcile(), 4000);
					setTimeout(() => this.loadReconcile(), 12000);
				} else { this.showToast(this.t('app.commerce-reconcile.reconcile.couldNotStart', { status }, `Could not start (${status}).`), true); }
			} catch (e: any) { this.showToast(e?.message || this.t('app.commerce-reconcile.reconcile.couldNotStart', { status: '?' }, 'Could not start.'), true); }
			finally { this.busy = false; }
		},
		resultText(result: any): string {
			return result === 'error'
				? this.t('app.commerce-reconcile.history.error', undefined, 'Error')
				: this.t('app.commerce-reconcile.history.success', undefined, 'Success');
		},
		// History-row type column: the wire scope 'inventory' | 'diff' → its list label.
		typeText(scope: any): string {
			return scope === 'inventory'
				? this.t('app.commerce-reconcile.history.type.inventory', undefined, 'Inventory')
				: this.t('app.commerce-reconcile.history.type.products', undefined, 'Products');
		},

		// ---- Run-now target selector -------------------------------------------
		async openTargetMenu(event: MouseEvent) {
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !this.instance) return;
			const rect = trigger.getBoundingClientRect();
			const cur = this.target;
			const items = [
				{ id: 'products', label: this.t('app.commerce-reconcile.reconcile.target.products'), selected: cur === 'products' },
				{ id: 'inventory', label: this.t('app.commerce-reconcile.reconcile.target.inventory'), selected: cur === 'inventory' },
			];
			const handle = this.instance.popup.open({ anchor: rect, placement: 'bottom-start', minWidth: rect.width, items });
			const result = await handle.result;
			if (result == null) return;
			this.target = result as RunTarget;
		},
		targetLabel(v: RunTarget): string {
			return v === 'inventory'
				? this.t('app.commerce-reconcile.reconcile.target.inventory')
				: this.t('app.commerce-reconcile.reconcile.target.products');
		},

		// ---- CSV export --------------------------------------------------------
		// Download the CSV of EXACTLY the shown rows, built client-side from
		// `history` (the same array the table renders) with the same localized
		// labels. BOM-prefixed so Excel reads it as UTF-8 (commerce-oplog pattern).
		downloadCsv() {
			if (!this.canDownload) return;
			const esc = (v: any) => {
				const s = v == null ? '' : String(v);
				return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
			};
			const header = [
				this.t('app.commerce-reconcile.history.type'),
				this.t('app.commerce-reconcile.history.startedAt'),
				this.t('app.commerce-reconcile.history.finishedAt'),
				this.t('app.commerce-reconcile.history.updated'),
				this.t('app.commerce-reconcile.history.result'),
			];
			const lines = [header.map(esc).join(',')];
			for (const h of this.history) {
				lines.push([
					this.typeText(h.scope), this.fmtTime(h.startedAt), this.fmtTime(h.finishedAt),
					h.updated ?? 0, this.resultText(h.result),
				].map(esc).join(','));
			}
			const csv = String.fromCharCode(0xFEFF) + lines.join('\r\n');
			try {
				const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
				const url = URL.createObjectURL(blob);
				const a = document.createElement('a');
				a.href = url; a.download = 'commerce-reconcile-history.csv';
				document.body.appendChild(a); a.click(); a.remove();
				window.setTimeout(() => URL.revokeObjectURL(url), 1000);
			} catch (_) { /* ignore */ }
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
		setStatus(kind: '' | 'ok' | 'err', msg: string) { this.statusKind = kind; this.status = msg; },
		showToast(msg: string, isError: boolean) {
			this.toast = msg; this.toastError = !!isError;
			if (this._toastTimer) clearTimeout(this._toastTimer);
			this._toastTimer = window.setTimeout(() => { this.toast = ''; }, 3400);
		},
	},
};

VDOM.createApp(App).mount('#app');
