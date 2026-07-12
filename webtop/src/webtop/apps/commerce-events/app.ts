// Commerce Events — the Shopify→CMS event log console: inspect ingested
// webhook/event records by status / source / topic / period, and re-dispatch a
// single event or every event matching the current filter.
//
// Three panes (mirroring commerce-oplog): a left filter sidebar (status / source
// / topic / from-to period), a center list (Date / Source / Topic / Event /
// Status / Attempts + per-row retry), and a right detail pane showing the selected
// event's full record — including the last error, which is NOT a list column.
// The list header carries the bulk "replay matching" action.
//
// Driven by a single admin endpoint:
//   • events.groovy — GET summary + filterable list; POST re-dispatch (single
//                     event, or all matching). Dates go over the wire as ISO-8601
//                     instants (platform convention).
//
// Read-mostly, with explicit confirmation before anything that re-dispatches many
// events. Self-contained (ichigo.js runtime only).

import { VDOM } from '@mintjamsinc/ichigojs';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
	formatDate,
} from '../../composables/use-localization.js';
import { wallClockToIso, completeDateTimeLocal } from '../../composables/wire-datetime.js';

type AnyInstance = any;

const EVENTS_SCRIPT = '/content/commerce/endpoints/events.groovy';
const ROW_CAP = 200;

const App = {
	data() {
		return {
			instance: null as AnyInstance,
			// Reactive localization snapshot — drives every t() / fmtTime() binding
			// so the app repaints when the user switches language or a bundle is
			// hot-reloaded.
			localization: createLocalizationSnapshot(),
			busy: false,
			toast: '',
			toastError: false,

			// LIVE filter the operator edits. from/to are datetime-local wall-clocks
			// sent over the wire as ISO-8601 instants. Takes effect only when APPLIED
			// (the Search button) — see `applied`.
			filter: { status: 'error', source: '', topic: '', from: '', to: '' },
			// The APPLIED snapshot: the list AND the bulk-replay target derive from
			// THIS, never the live filter, so an unapplied edit never changes what is
			// shown or what a bulk replay would touch.
			applied: { status: 'error', source: '', topic: '', from: '', to: '' },
			hasApplied: false,
			events: [] as any[],
			selectedRow: null as any,   // the row shown in the detail pane

			confirmDialog: { visible: false, title: '', message: '', ok: '', resolve: null as null | ((v: boolean) => void) },

			_base: '' as string,
			_messageListener: null as any,
			_toastTimer: null as any,

			// Resizable filter sidebar (LEFT).
			sidebarVisible: true,
			sidebarWidth: 272,
			sidebarMinWidth: 180,
			sidebarMaxWidth: 480,
			_boundSidebarResizeMove: null as any,
			_boundSidebarResizeUp: null as any,

			// Resizable detail pane (RIGHT).
			detailVisible: true,
			detailWidth: 340,
			detailMinWidth: 240,
			detailMaxWidth: 640,
			_boundDetailResizeMove: null as any,
			_boundDetailResizeUp: null as any,
		};
	},

	computed: {
		rowCount(): number { return Array.isArray(this.events) ? this.events.length : 0; },
		canReplayMatching(): boolean { return this.hasApplied && !this.busy && this.events.length > 0; },
	},

	methods: {
		// ---- i18n / locale-aware formatting ------------------------------------
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},
		// List timestamps match content-browser / commerce-oplog: the 'friendly'
		// preset (relative within a week, absolute beyond).
		fmtTime(v: any): string {
			if (!v) return '—';
			const d = new Date(v);
			if (isNaN(d.getTime())) return String(v);
			return formatDate(this.localization, d, { format: 'friendly' });
		},
		// Localized status label (Received / Processed / Error …), raw value fallback.
		statusText(s: any): string {
			const v = String(s || '');
			return v ? this.t('app.commerce-events.status.' + v, undefined, v) : '';
		},

		onMounted() {
			const vm = this;
			vm._messageListener = (event: MessageEvent) => {
				const data: any = event.data || {};
				if (handleLocalizationMessage(data.type, vm.localization, vm.instance)) return;
				if (data.type === 'theme-changed' && data.theme) document.documentElement.dataset.theme = data.theme;
				// Drill-down re-target from another app (e.g. the dashboard).
				else if (data.type === 'app-reopen') vm.applyLaunchOptions(data.options, false);
			};
			window.addEventListener('message', vm._messageListener);

			window.appLaunch = async (instance: AnyInstance, options?: any) => {
				vm.instance = vm.$markRaw(instance);
				try { document.documentElement.dataset.theme = instance.api.theme.currentTheme || 'light'; } catch (_) {}
				refreshLocalization(vm.localization, vm.instance);
				try { instance.windowTitle = vm.t('app.commerce-events.title', undefined, 'Commerce Events'); } catch (_) {}
				instance.appState = () => ({ filter: vm.filter });
				await vm.resolveBase();
				await vm.loadPanesState();
				vm.applyLaunchOptions(options, true);
				await vm.apply();
				vm.$nextTick(() => { try { instance.notifyLaunched(); } catch (_) {} });
			};
		},
		onUnmount() {
			if (this._messageListener) window.removeEventListener('message', this._messageListener);
			if (this._toastTimer) clearTimeout(this._toastTimer);
			if (this._boundSidebarResizeMove) document.removeEventListener('mousemove', this._boundSidebarResizeMove);
			if (this._boundSidebarResizeUp) document.removeEventListener('mouseup', this._boundSidebarResizeUp);
			if (this._boundDetailResizeMove) document.removeEventListener('mousemove', this._boundDetailResizeMove);
			if (this._boundDetailResizeUp) document.removeEventListener('mouseup', this._boundDetailResizeUp);
		},

		// ---- Window controls -------------------------------------------------
		onMinimizeWindow() { this.instance?.minimize(); },
		onToggleMaximizeWindow() { this.instance?.toggleMaximize(); },
		onCloseWindow() { this.instance?.requestClose(); },

		// ---- Panes: toggle / resize / persist --------------------------------
		toggleSidebar() { this.sidebarVisible = !this.sidebarVisible; this.persistPanesState(); },
		toggleDetail() { this.detailVisible = !this.detailVisible; this.persistPanesState(); },

		// LEFT sidebar: drag its RIGHT edge → wider as the cursor moves right.
		onSidebarResizeStart(event: MouseEvent) {
			const vm = this;
			event.preventDefault();
			const startX = event.clientX;
			const startWidth = vm.sidebarWidth;
			vm._boundSidebarResizeMove = (e: MouseEvent) => {
				const delta = e.clientX - startX;
				vm.sidebarWidth = Math.max(vm.sidebarMinWidth, Math.min(vm.sidebarMaxWidth, startWidth + delta));
			};
			vm._boundSidebarResizeUp = () => {
				if (vm._boundSidebarResizeMove) document.removeEventListener('mousemove', vm._boundSidebarResizeMove);
				if (vm._boundSidebarResizeUp) document.removeEventListener('mouseup', vm._boundSidebarResizeUp);
				vm._boundSidebarResizeMove = null;
				vm._boundSidebarResizeUp = null;
				vm.persistPanesState();
			};
			document.addEventListener('mousemove', vm._boundSidebarResizeMove);
			document.addEventListener('mouseup', vm._boundSidebarResizeUp);
		},
		// RIGHT detail: handle sits on the pane's LEFT edge, so the delta sign is
		// FLIPPED — dragging LEFT widens it.
		onDetailResizeStart(event: MouseEvent) {
			const vm = this;
			event.preventDefault();
			const startX = event.clientX;
			const startWidth = vm.detailWidth;
			vm._boundDetailResizeMove = (e: MouseEvent) => {
				const delta = startX - e.clientX;
				vm.detailWidth = Math.max(vm.detailMinWidth, Math.min(vm.detailMaxWidth, startWidth + delta));
			};
			vm._boundDetailResizeUp = () => {
				if (vm._boundDetailResizeMove) document.removeEventListener('mousemove', vm._boundDetailResizeMove);
				if (vm._boundDetailResizeUp) document.removeEventListener('mouseup', vm._boundDetailResizeUp);
				vm._boundDetailResizeMove = null;
				vm._boundDetailResizeUp = null;
				vm.persistPanesState();
			};
			document.addEventListener('mousemove', vm._boundDetailResizeMove);
			document.addEventListener('mouseup', vm._boundDetailResizeUp);
		},
		async persistPanesState() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				await db.setUserSetting(userID, 'commerce-events', 'panes', JSON.parse(JSON.stringify({
					sidebar: { visible: vm.sidebarVisible, width: vm.sidebarWidth },
					detail: { visible: vm.detailVisible, width: vm.detailWidth },
				})));
			} catch (_) { /* non-critical */ }
		},
		async loadPanesState() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				// Prefer the new combined key; fall back to the legacy 'sidebar' key.
				let state = await db.getUserSetting(userID, 'commerce-events', 'panes');
				if (!state) {
					const legacy = await db.getUserSetting(userID, 'commerce-events', 'sidebar');
					if (legacy) state = { sidebar: legacy };
				}
				if (state) {
					if (state.sidebar) {
						vm.sidebarVisible = state.sidebar.visible ?? true;
						vm.sidebarWidth = state.sidebar.width ?? vm.sidebarWidth;
					}
					if (state.detail) {
						vm.detailVisible = state.detail.visible ?? true;
						vm.detailWidth = state.detail.width ?? vm.detailWidth;
					}
				}
			} catch (_) { /* non-critical */ }
		},

		// ---- Row selection (detail pane) -------------------------------------
		selectRow(e: any) { this.selectedRow = e; this.detailVisible = true; },

		// ---- Navigation ------------------------------------------------------
		refresh() { this.load(); },

		// ---- Status filter (shell wt-select popup) ---------------------------
		async openStatusMenu(event: MouseEvent) {
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !this.instance) return;
			const rect = trigger.getBoundingClientRect();
			const cur = this.filter.status;
			// Reversed item order (all → processed → received → error).
			const items = [
				{ id: '', label: this.t('app.commerce-events.filter.statusAll'), selected: cur === '' },
				{ id: 'processed', label: this.t('app.commerce-events.filter.statusProcessed'), selected: cur === 'processed' },
				{ id: 'received', label: this.t('app.commerce-events.filter.statusReceived'), selected: cur === 'received' },
				{ id: 'error', label: this.t('app.commerce-events.filter.statusError'), selected: cur === 'error' },
			];
			const handle = this.instance.popup.open({ anchor: rect, placement: 'bottom-start', minWidth: rect.width, items });
			const result = await handle.result;
			if (result == null) return;
			this.filter.status = String(result);
		},
		statusLabel(v: string): string {
			switch (v) {
				case 'error': return this.t('app.commerce-events.filter.statusError');
				case 'received': return this.t('app.commerce-events.filter.statusReceived');
				case 'processed': return this.t('app.commerce-events.filter.statusProcessed');
				default: return this.t('app.commerce-events.filter.statusAll');
			}
		},

		// Apply launch / re-target options. Shape:
		//   { eventFilter?: { status?, source?, topic?, from?, to? } }
		applyLaunchOptions(options: any, initial = false) {
			const o = (options && typeof options === 'object') ? options : {};
			const f = (o.eventFilter && typeof o.eventFilter === 'object') ? o.eventFilter : null;
			if (f) {
				if (f.status != null) this.filter.status = String(f.status);
				if (f.source != null) this.filter.source = String(f.source);
				if (f.topic != null) this.filter.topic = String(f.topic);
				if (f.from != null) this.filter.from = completeDateTimeLocal(String(f.from), false);
				if (f.to != null) this.filter.to = completeDateTimeLocal(String(f.to), true);
			}
			if (!initial) this.apply();
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

		// ---- Query (from the APPLIED snapshot) -------------------------------
		// The shared filter params for the list query AND the bulk replay body, so
		// both always agree with what the operator has applied. Dates go over the
		// wire as ISO-8601 instants resolved in the effective (Preferences) timezone.
		filterParams(): Record<string, string> {
			const a = this.applied;
			const out: Record<string, string> = {};
			if (a.status) out.status = a.status;
			if (String(a.source || '').trim()) out.source = String(a.source).trim();
			if (String(a.topic || '').trim()) out.topic = String(a.topic).trim();
			const fromIso = wallClockToIso(a.from, this.localization.timeZone, false);
			if (fromIso) out.from = fromIso;
			const toIso = wallClockToIso(a.to, this.localization.timeZone, true);
			if (toIso) out.to = toIso;
			return out;
		},
		eventsQuery(): string {
			const p = new URLSearchParams(this.filterParams());
			p.set('limit', String(ROW_CAP));
			return p.toString();
		},

		// ---- Apply / Load ----------------------------------------------------
		async apply() {
			this.applied = { ...this.filter };
			this.hasApplied = true;
			this.selectedRow = null;
			return this.load();
		},
		async load() {
			this.busy = true;
			try { await this.loadEvents(); }
			finally { this.busy = false; }
		},
		async loadEvents() {
			try {
				const j = await this.getJson(`${EVENTS_SCRIPT}?${this.eventsQuery()}`);
				this.events = this.$markRaw(Array.isArray(j.events) ? j.events.slice(0, ROW_CAP) : []);
				this.selectedRow = null;   // row references change on reload
			} catch (e: any) {
				this.events = [];
				this.showToast(e?.message || this.t('app.commerce-events.loadFailed', undefined, 'Could not load events.'), true);
			}
		},

		// ---- Replay ----------------------------------------------------------
		async replayOne(e: any) {
			this.busy = true;
			try {
				const { json } = await this.postJson(EVENTS_SCRIPT, { eventId: e.eventId, source: e.source });
				this.showToast(this.t('app.commerce-events.replayed', { count: json.replayed || 0 }, `Replayed ${json.replayed || 0} event(s).`), false);
				setTimeout(() => this.loadEvents(), 1500);
			} catch (err: any) { this.showToast(err?.message || this.t('app.commerce-events.replayFailed', undefined, 'Replay failed.'), true); }
			finally { this.busy = false; }
		},
		async replayMatching() {
			const params = this.filterParams();
			const desc = [
				params.status ? `status=${params.status}` : 'all',
				params.source && `source=${params.source}`,
				params.topic && `topic=${params.topic}`,
				params.from && `from=${params.from}`,
				params.to && `to=${params.to}`,
			].filter(Boolean).join(', ');
			const ok = await this.confirm(
				this.t('app.commerce-events.replayMatchingTitle', undefined, 'Replay matching'),
				this.t('app.commerce-events.replayMatchingMsg', { desc }, `Re-dispatch every event matching: ${desc}. This can replay many events.`),
				this.t('app.commerce-events.replayOk', undefined, 'Replay'),
			);
			if (!ok) return;
			this.busy = true;
			try {
				const { json } = await this.postJson(EVENTS_SCRIPT, params);
				this.showToast(this.t('app.commerce-events.replayedMatched', { replayed: json.replayed || 0, matched: json.matched || 0 }, `Replayed ${json.replayed || 0} of ${json.matched || 0} matched.`), false);
				setTimeout(() => this.loadEvents(), 1800);
			} catch (e: any) { this.showToast(e?.message || this.t('app.commerce-events.replayFailed', undefined, 'Replay failed.'), true); }
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

		// ---- Toast -----------------------------------------------------------
		showToast(msg: string, isError: boolean) {
			this.toast = msg; this.toastError = !!isError;
			if (this._toastTimer) clearTimeout(this._toastTimer);
			this._toastTimer = window.setTimeout(() => { this.toast = ''; }, 3400);
		},
	},
};

VDOM.createApp(App).mount('#app');
