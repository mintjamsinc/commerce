// Commerce Import — the Shopify → CMS import console (data intake).
//
// A single-concern console for getting Shopify data INTO the CMS mirror and the
// sales facts derived from it. Drift detection / mirror refresh lives in the
// separate Commerce Reconcile app (discrepancy reconciliation) — import and
// reconcile are split so each console stays single-concern and operator-driven.
//
// Driven by existing admin endpoints:
//   • backfill.groovy             — GET lists the Bulk backfill jobs; POST enqueues a
//                                   date-ranged historical import (orders / customers /
//                                   products / inventory) over the Bulk job broker. An
//                                   ORDERS backfill is the whole historical sales import:
//                                   refunds are fetched for the refund-bearing orders,
//                                   and the sales-fact seed chains on completion.
//   • sales-backfill.groovy       — GET-only: the chained sales-fact seed's progress
//                                   (watch `remaining` drain to 0).
//
// Everything here is operator-triggered and idempotent — nothing runs "behind the
// operator's back", re-running is safe. Self-contained (ichigo.js runtime only).

import { VDOM } from '@mintjamsinc/ichigojs';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
	formatDate,
} from '../../composables/use-localization.js';

type AnyInstance = any;

const BACKFILL_SCRIPT = '/content/commerce/endpoints/backfill.groovy';
const SALES_SEED_SCRIPT = '/content/commerce/endpoints/sales-backfill.groovy';
// Admin API capability probe: the Bulk backfill fetches from Shopify, so it is gated
// on the Admin API being configured. sync.groovy GET reports { enabled, shopDomain,
// apiVersion } — the same probe the editors use.
const SYNC_SCRIPT = '/content/commerce/endpoints/sync.groovy';

// Bulk job states the broker treats as "still active" (BulkJobs.isActive) — a
// backfill is in progress while any job sits in one of these.
const ACTIVE_JOB_STATUSES = ['QUEUED', 'RUNNING', 'READY', 'PROCESSING'];

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

			// Admin API capability (from sync.groovy). The Bulk backfill hits Shopify,
			// so when it is off the banner warns and the Run disables.
			sync: { enabled: false, shopDomain: '', apiVersion: '' },

			// Full import (Backfill) — the operator-supplied entity + date range and
			// the list of Bulk backfill jobs read from backfill.groovy. Orders,
			// customers and products are all enabled; from/to are optional
			// (empty = the entire history). An orders backfill also fetches and
			// mirrors the refunds of refund-bearing orders and chains the
			// sales-fact seed.
			backfill: { type: 'orders', from: '', to: '' },
			backfillJobs: [] as any[],

			// Sales FACT seed progress (sales-backfill.groovy, GET-only — the seed is
			// chained off a completed orders backfill; enqueue-only, the single-writer
			// drainer materializes and `remaining` drains to 0).
			salesSeed: { status: 'idle', scanned: 0, enqueued: 0, distinctOrders: 0, remaining: 0, startedAt: '', updatedAt: '', finishedAt: '' },

			confirmDialog: { visible: false, title: '', message: '', ok: '', resolve: null as null | ((v: boolean) => void) },

			_base: '' as string,
			_messageListener: null as any,
			_toastTimer: null as any,
			// Auto-refresh timer for the backfill job list / seed progress while active.
			_pollTimer: null as any,

			// Resizable filter sidebar (mirrors commerce-products / settings app).
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
				// Re-target from another app (e.g. the dashboard) when this singleton
				// console is already open: refresh the job list + progress.
				else if (data.type === 'app-reopen') vm.refresh();
			};
			window.addEventListener('message', vm._messageListener);

			window.appLaunch = async (instance: AnyInstance) => {
				vm.instance = vm.$markRaw(instance);
				try { document.documentElement.dataset.theme = instance.api.theme.currentTheme || 'light'; } catch (_) {}

				// Snapshot the effective localization preference so the first paint
				// is already in the user's language / region.
				refreshLocalization(vm.localization, vm.instance);

				try { instance.windowTitle = vm.t('app.commerce-import.title', undefined, 'Commerce Import'); } catch (_) {}
				await vm.resolveBase();
				await vm.loadSidebarState();
				await vm.loadSync();
				await vm.loadBackfill();
				await vm.loadSalesSeed();
				vm.$nextTick(() => { try { instance.notifyLaunched(); } catch (_) {} });
			};
		},
		onUnmount() {
			if (this._messageListener) window.removeEventListener('message', this._messageListener);
			if (this._toastTimer) clearTimeout(this._toastTimer);
			if (this._pollTimer) clearTimeout(this._pollTimer);
			if (this._boundSidebarResizeMove) document.removeEventListener('mousemove', this._boundSidebarResizeMove);
			if (this._boundSidebarResizeUp) document.removeEventListener('mouseup', this._boundSidebarResizeUp);
		},

		// ---- Window controls -------------------------------------------------
		onMinimizeWindow() { this.instance?.minimize(); },
		onToggleMaximizeWindow() { this.instance?.toggleMaximize(); },
		onCloseWindow() { this.instance?.requestClose(); },

		// ---- Left filter sidebar (toggle / resize / persist) -----------------
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
				await db.setUserSetting(userID, 'commerce-import', 'sidebar', JSON.parse(JSON.stringify({
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
				const state = await db.getUserSetting(userID, 'commerce-import', 'sidebar');
				if (state) {
					vm.sidebarVisible = state.visible ?? true;
					vm.sidebarWidth = state.width ?? vm.sidebarWidth;
				}
			} catch (_) { /* non-critical */ }
		},

		// ---- Navigation ------------------------------------------------------
		async refresh() {
			await this.loadSync();
			await this.loadBackfill();
			await this.loadSalesSeed();
		},

		// ---- Admin API capability --------------------------------------------
		async loadSync() {
			try {
				const j = await this.getJson(SYNC_SCRIPT);
				this.sync = { enabled: j.enabled === true, shopDomain: j.shopDomain || '', apiVersion: j.apiVersion || '' };
			} catch (_) { this.sync = { enabled: false, shopDomain: '', apiVersion: '' }; }
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

		// ---- Backfill (Shopify → CMS full import) ----------------------------
		// Mirror-only historical import over the existing Bulk job broker: enqueue
		// a backfill for an entity + optional date range, and read back the job
		// list. Backfill never starts the review workflow — it only refreshes the
		// mirror — and re-running is safe (idempotent per active job).
		// Load the backfill job list. A background poll passes quiet=true so it does
		// NOT toggle `busy` (no spinner flicker / no disabled Run buttons); the
		// manual refresh button (and the toolbar) call it non-quiet. Either way it
		// (re)arms the auto-refresh poll from the finally block.
		async loadBackfill(quiet = false) {
			const quietMode = quiet === true; // template @click passes the event as arg
			if (!quietMode) this.busy = true;
			try {
				const j = await this.getJson(BACKFILL_SCRIPT);
				// Accept either { jobs: [...] } or a bare array; exclude the
				// inventory-full reconcile jobs that share the same broker queue,
				// and show newest first (by enqueue time).
				const arr = Array.isArray(j) ? j : (Array.isArray(j?.jobs) ? j.jobs : []);
				const jobs = arr
					.filter((x: any) => x && String(x.type || '') !== 'inventoryFull')
					.sort((a: any, b: any) => String(b?.enqueuedAt || '').localeCompare(String(a?.enqueuedAt || '')));
				this.backfillJobs = this.$markRaw(jobs);
			} catch (_) { /* keep */ }
			finally {
				if (!quietMode) this.busy = false;
				this.scheduleJobPoll();
			}
		},
		// Keep the job list + seed progress live while anything is running: re-fetch
		// quietly every few seconds so status columns advance without a manual
		// refresh, and stop once nothing is active. At most one timer is armed;
		// loadBackfill()'s finally re-arms it, so the loop self-perpetuates while
		// active and self-stops when done.
		scheduleJobPoll() {
			if (this._pollTimer) { clearTimeout(this._pollTimer); this._pollTimer = null; }
			const seedActive = this.salesSeed.status === 'running' || (this.salesSeed.remaining || 0) > 0;
			if (!this.backfillActive() && !seedActive) return;
			this._pollTimer = window.setTimeout(() => {
				this._pollTimer = null;
				this.loadSalesSeed(true);
				this.loadBackfill(true);
			}, 4000);
		},
		async runBackfill() {
			if (!this.sync.enabled) { this.showToast(this.t('app.commerce-import.adminApi.required', undefined, 'Configure the Shopify Admin API first.'), true); return; }
			// Inventory is a FULL SNAPSHOT, not a historical range — always send an empty
			// range (the date fields are hidden for it), so a range left over from a prior
			// orders/customers selection can't ride along.
			const snapshot = this.backfill.type === 'inventory';
			const from = snapshot ? '' : String(this.backfill.from || '').trim();
			const to = snapshot ? '' : String(this.backfill.to || '').trim();
			// Light client guard: an inverted range is almost certainly a mistake.
			// The endpoint validates the yyyy-MM-dd shape server-side.
			if (from && to && from > to) {
				this.showToast(this.t('app.commerce-import.backfill.invalidRange', undefined, 'The From date must be on or before the To date.'), true);
				return;
			}
			this.busy = true;
			try {
				const { status, json } = await this.postJson(BACKFILL_SCRIPT, { type: this.backfill.type, from, to });
				if (status === 202 || status === 200) {
					// The broker is idempotent per active job: an in-flight backfill is
					// reported (triggered:false / alreadyRunning) rather than duplicated.
					const already = !!(json && (json.triggered === false || json.alreadyRunning));
					if (already) {
						this.showToast(this.t('app.commerce-import.backfill.alreadyRunning', undefined, 'A backfill is already running.'), false);
					} else {
						this.showToast(this.t('app.commerce-import.backfill.started', undefined, 'Backfill queued. Refreshing shortly…'), false);
					}
					this.loadBackfill();
					setTimeout(() => this.loadBackfill(), 4000);
				} else {
					this.showToast(this.t('app.commerce-import.backfill.couldNotStart', { status }, `Could not start (${status}).`), true);
				}
			} catch (e: any) {
				this.showToast(e?.message || this.t('app.commerce-import.backfill.couldNotStart', { status: '?' }, 'Could not start.'), true);
			} finally { this.busy = false; }
		},
		// True while any backfill job is still active (drives the in-progress badge).
		backfillActive(): boolean {
			return (this.backfillJobs || []).some((j: any) => ACTIVE_JOB_STATUSES.indexOf(String(j?.status || '').toUpperCase()) >= 0);
		},
		// Operator-friendly label for a job's bulk type (wire values are camelCase
		// identifiers per the API's naming convention: ordersBackfill → "Orders",
		// customersBackfill → "Customers", productsBackfill → "Products").
		jobTypeLabel(type: any): string {
			const s = String(type || '');
			if (s === 'ordersBackfill' || s === 'orders') return this.t('app.commerce-import.backfill.entity.orders', undefined, 'Orders');
			if (s === 'customersBackfill' || s === 'customers') return this.t('app.commerce-import.backfill.entity.customers', undefined, 'Customers');
			if (s === 'productsBackfill' || s === 'products') return this.t('app.commerce-import.backfill.entity.products', undefined, 'Products');
			if (s === 'inventoryBackfill' || s === 'inventory') return this.t('app.commerce-import.backfill.entity.inventory', undefined, 'Inventory');
			return s || '—';
		},
		// Pill class for a job status, reusing the shared status-pill palette.
		jobStatusClass(status: any): string {
			const v = String(status || '').toUpperCase();
			if (v === 'COMPLETED') return 'st-processed';
			if (v === 'FAILED' || v === 'TIMED_OUT' || v === 'CANCELED') return 'st-failed';
			if (ACTIVE_JOB_STATUSES.indexOf(v) >= 0) return 'st-received';
			return 'st-report';
		},
		// Compact result counters for a COMPLETED job (from the processor's stats):
		// orders jobs show imported orders + mirrored refunds; other jobs show their
		// primary counter when present. Empty until the job completes.
		jobResults(job: any): string {
			const s = (job && job.stats) || null;
			if (!s || typeof s !== 'object') return '—';
			const parts: string[] = [];
			if (s.orders != null) parts.push(this.t('app.commerce-import.backfill.results.orders', { count: Number(s.orders) || 0 }, `${Number(s.orders) || 0} orders`));
			if (s.refundsStored != null && Number(s.refundsStored) > 0) parts.push(this.t('app.commerce-import.backfill.results.refunds', { count: Number(s.refundsStored) || 0 }, `${Number(s.refundsStored) || 0} refunds`));
			if (s.checked != null) parts.push(this.t('app.commerce-import.backfill.results.checked', { count: Number(s.checked) || 0 }, `${Number(s.checked) || 0} checked`));
			return parts.length ? parts.join(' · ') : '—';
		},
		// Human-readable date range for a job. Reads params.{from,to} (with a
		// defensive fall back to top-level from/to); empty both = the whole history.
		jobRange(job: any): string {
			// Inventory is a full snapshot, not a date range — show a snapshot label
			// rather than the misleading "All time".
			const type = String(job?.type || '');
			if (type === 'inventoryBackfill' || type === 'inventory') return this.t('app.commerce-import.backfill.snapshot', undefined, 'Snapshot');
			const p = (job && job.params) || {};
			const from = String((p.from != null ? p.from : job?.from) || '').trim();
			const to = String((p.to != null ? p.to : job?.to) || '').trim();
			if (!from && !to) return this.t('app.commerce-import.backfill.allTime', undefined, 'All time');
			return `${from || '…'} – ${to || '…'}`;
		},

		// Backfill entity picker — shell-side popup anchored to the trigger
		// button (house convention; mirrors content-browser's Date filter). The
		// chosen id maps 1:1 to the previous <select> option values.
		async openBackfillTypeMenu(event: MouseEvent) {
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !this.instance) return;
			const rect = trigger.getBoundingClientRect();
			const cur = this.backfill.type;
			const items = [
				{ id: 'orders', label: this.t('app.commerce-import.backfill.entity.orders'), selected: cur === 'orders' },
				{ id: 'customers', label: this.t('app.commerce-import.backfill.entity.customers'), selected: cur === 'customers' },
				{ id: 'products', label: this.t('app.commerce-import.backfill.entity.products'), selected: cur === 'products' },
				{ id: 'inventory', label: this.t('app.commerce-import.backfill.entity.inventory'), selected: cur === 'inventory' },
			];
			const handle = this.instance.popup.open({ anchor: rect, placement: 'bottom-start', minWidth: rect.width, items });
			const result = await handle.result;
			if (result == null) return;
			this.backfill.type = String(result);
		},
		// Display label for the current backfill entity (used in .wt-select-value).
		backfillTypeLabel(v: string): string {
			switch (v) {
				case 'customers': return this.t('app.commerce-import.backfill.entity.customers');
				case 'products': return this.t('app.commerce-import.backfill.entity.products');
				case 'inventory': return this.t('app.commerce-import.backfill.entity.inventory');
				default: return this.t('app.commerce-import.backfill.entity.orders');
			}
		},

		// ---- Sales FACT seed progress (chained off the orders backfill) --------
		// The seed runs automatically when an orders backfill completes: it walks the
		// ENTIRE order mirror and enqueues every distinct order for the single-writer
		// fact drainer (enqueue-only; it never writes a fact node). Watch `remaining`
		// drain to 0 — that is "all facts materialized".
		async loadSalesSeed(quiet = false) {
			const quietMode = quiet === true;
			if (!quietMode) this.busy = true;
			try {
				const j = await this.getJson(SALES_SEED_SCRIPT);
				this.salesSeed = {
					status: String(j.status || 'idle'),
					scanned: Number(j.scanned) || 0,
					enqueued: Number(j.enqueued) || 0,
					distinctOrders: Number(j.distinctOrders) || 0,
					remaining: Number(j.remaining) || 0,
					startedAt: j.startedAt || '',
					updatedAt: j.updatedAt || '',
					finishedAt: j.finishedAt || '',
				};
			} catch (_) { /* keep */ }
			finally {
				if (!quietMode) this.busy = false;
				this.scheduleJobPoll();
			}
		},
		// Pill class for a seed/backfill status doc ('idle' | 'running' | 'completed'…).
		seedStatusClass(status: any): string {
			const v = String(status || '').toLowerCase();
			if (v === 'completed' || v === 'done') return 'st-processed';
			if (v === 'running') return 'st-received';
			if (v === 'failed' || v === 'error') return 'st-failed';
			return 'st-report';
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
