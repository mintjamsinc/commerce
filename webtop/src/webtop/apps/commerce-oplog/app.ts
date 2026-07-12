// Commerce Operation Log — the outbound-write AUDIT console: a searchable,
// filterable list of every CMS → Shopify write (product/inventory edits from the
// editors, order cancellations, incoming inventory transfers). Reads
// reports.groovy?type=operations over /content/commerce/sync.
//
// Three panes: a left filter sidebar (a user/actor picker + a from/to date
// range — all server-side XPath conditions), a center list
// (When / Who / Target / Identifier / Action / Result), and a right detail pane showing the
// selected operation's full record — including the error/failure reason, which
// is NOT a list column. The center list's CSV button exports EXACTLY the rows
// currently shown: the download is generated client-side from the same
// operations the table renders, so it can never drift from the list (and it
// only reflects the last APPLIED filter, never in-progress edits).
//
// Observation-only: writing to Shopify happens in the editors and the workflow
// tasks — this console carries NO write forms. Self-contained (ichigo.js runtime).

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

const REPORTS_SCRIPT = '/content/commerce/endpoints/reports.groovy';

// Server fetch cap — the endpoint returns up to 5000, but the client list stays
// snappy over a bounded window.
const ROW_CAP = 1000;

// Debounce + shell-popup handle for the sidebar user (actor) autocomplete —
// module-scoped, like the content-browser principal picker this mirrors.
let actorDebounce: ReturnType<typeof setTimeout> | null = null;
let actorPopupHandle: any = null;

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

			// LIVE filter the operator edits — all server-side XPath conditions:
			// `actor` (a chosen user id) and the from/to date range. `actorLabel`
			// is just the display name shown in the picker. Takes effect only when
			// APPLIED (the Search button) — see `applied`.
			filter: { actor: '', actorLabel: '', from: '', to: '' },
			// The APPLIED snapshot: the list + the CSV derive from THIS, never the
			// live `filter`, so unapplied edits never change the shown rows/export.
			applied: { actor: '', from: '', to: '' },
			hasApplied: false,      // gates the CSV button (disabled before a search)
			operations: [] as any[],
			selectedRow: null as any,   // the row shown in the detail pane

			// Sidebar user (actor) autocomplete — mirrors the content-browser
			// principal picker: a debounced search over searchPrincipals whose
			// suggestions render through the shell popup.
			actorSearch: { keyword: '', results: [] as any[], isSearching: false },

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
		// CSV is available only once a search/apply has produced a shown list.
		canDownload(): boolean { return this.hasApplied && !this.busy && this.operations.length > 0; },
		// The server already filtered by actor + from/to (XPath), so the shown rows
		// ARE the operations — there is no client-side filtering.
		rowCount(): number { return Array.isArray(this.operations) ? this.operations.length : 0; },
	},

	methods: {
		// ---- i18n / locale-aware formatting ------------------------------------
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},
		// List timestamps match content-browser's list: the 'friendly' preset
		// (relative within a week, absolute beyond).
		fmtTime(v: any): string {
			if (!v) return '—';
			const d = new Date(v);
			if (isNaN(d.getTime())) return String(v);
			return formatDate(this.localization, d, { format: 'friendly' });
		},
		// Absolute date/time for the CSV export (a relative time like "5 minutes ago" is useless there).
		csvTime(v: any): string {
			if (!v) return '';
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
				// Re-target from a drill-down (e.g. the dashboard): re-apply + reload.
				else if (data.type === 'app-reopen') vm.applyLaunchOptions(data.options, false);
			};
			window.addEventListener('message', vm._messageListener);

			window.appLaunch = async (instance: AnyInstance, options?: any) => {
				vm.instance = vm.$markRaw(instance);
				try { document.documentElement.dataset.theme = instance.api.theme.currentTheme || 'light'; } catch (_) {}
				refreshLocalization(vm.localization, vm.instance);
				try { instance.windowTitle = vm.t('app.commerce-oplog.title', undefined, 'Commerce Operation Log'); } catch (_) {}
				instance.appState = () => ({ filter: vm.filter });
				await vm.resolveBase();
				await vm.loadPanesState();
				vm.applyLaunchOptions(options, true);
				await vm.apply();   // snapshot the filter → applied, load, enable CSV
				vm.$nextTick(() => { try { instance.notifyLaunched(); } catch (_) {} });
			};
		},
		onUnmount() {
			if (this._messageListener) window.removeEventListener('message', this._messageListener);
			if (this._toastTimer) clearTimeout(this._toastTimer);
			if (actorDebounce) { clearTimeout(actorDebounce); actorDebounce = null; }
			this.closeActorPopup();
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
				await db.setUserSetting(userID, 'commerce-oplog', 'panes', JSON.parse(JSON.stringify({
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
				let state = await db.getUserSetting(userID, 'commerce-oplog', 'panes');
				if (!state) {
					const legacy = await db.getUserSetting(userID, 'commerce-oplog', 'sidebar');
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
		selectRow(o: any) { this.selectedRow = o; this.detailVisible = true; },

		// Apply launch / re-target options. Shape: { filter?: {actor?,actorLabel?,from?,to?} }.
		applyLaunchOptions(options: any, initial = false) {
			const o = (options && typeof options === 'object') ? options : {};
			const f = (o.filter && typeof o.filter === 'object') ? o.filter : null;
			if (f) {
				if (f.actor != null) { this.filter.actor = String(f.actor); this.actorSearch.keyword = String(f.actorLabel ?? f.actor); }
				if (f.actorLabel != null) this.filter.actorLabel = String(f.actorLabel);
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

		// ---- Query (from the APPLIED snapshot) -------------------------------
		// Every condition is a server-side XPath predicate; nothing is filtered
		// client-side. No date range → the server returns the newest rows (capped).
		opsQuery(): string {
			const p = new URLSearchParams();
			p.set('type', 'operations');
			const actor = String(this.applied.actor || '').trim();
			if (actor) p.set('actor', actor);
			// Dates go over the wire as ISO-8601 instants (platform convention, cf.
			// content-browser). The datetime-local wall-clock is resolved in the
			// effective (Preferences) timezone so the boundary matches the list display.
			const fromIso = wallClockToIso(this.applied.from, this.localization.timeZone, false);
			if (fromIso) p.set('from', fromIso);
			const toIso = wallClockToIso(this.applied.to, this.localization.timeZone, true);
			if (toIso) p.set('to', toIso);
			return p.toString();
		},

		// ---- Apply / Load ----------------------------------------------------
		// Apply commits the live filter into the snapshot, then loads. Everything
		// downstream (list + CSV) reads the snapshot, so the shown rows can never
		// include an unapplied edit. Also called for the initial load.
		async apply() {
			this.applied = { actor: this.filter.actor, from: this.filter.from, to: this.filter.to };
			this.hasApplied = true;
			this.selectedRow = null;   // a stale selection is meaningless against new rows
			return this.load();
		},
		async load() {
			this.busy = true;
			try { await this.loadOperations(); }
			finally { this.busy = false; }
		},
		async loadOperations() {
			try {
				const j = await this.getJson(`${REPORTS_SCRIPT}?${this.opsQuery()}`);
				this.operations = this.$markRaw(Array.isArray(j.operations) ? j.operations.slice(0, ROW_CAP) : []);
			} catch (e: any) {
				this.operations = [];
				this.showToast(e?.message || this.t('app.commerce-oplog.loadFailed', undefined, 'Could not load the operation log.'), true);
			}
		},

		// Download the CSV of EXACTLY the shown rows. Built client-side from
		// `operations` (the same array the table renders), so it always matches
		// the list and never reflects an in-progress, unapplied filter. Columns
		// carry the same human labels shown in the list (resolved actor, localized
		// target/action/status), with an absolute timestamp for spreadsheet use.
		downloadCsv() {
			if (!this.canDownload) return;
			const esc = (v: any) => {
				const s = v == null ? '' : String(v);
				return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
			};
			const header = [
				this.t('app.commerce-oplog.col.when'),
				this.t('app.commerce-oplog.col.actor'),
				this.t('app.commerce-oplog.col.target'),
				this.t('app.commerce-oplog.col.identifier', undefined, '識別子'),
				this.t('app.commerce-oplog.col.action'),
				this.t('app.commerce-oplog.col.status'),
				this.t('app.commerce-oplog.col.error'),
			];
			const lines = [header.map(esc).join(',')];
			for (const o of this.operations) {
				lines.push([
					this.csvTime(o.at), this.actorName(o), this.entityLabel(o.entity), o.entityId || '',
					this.actionLabel(o.action), this.statusText(o.status), o.error || '',
				].map(esc).join(','));
			}
			const csv = String.fromCharCode(0xFEFF) + lines.join('\r\n');   // BOM so Excel reads UTF-8
			try {
				const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
				const url = URL.createObjectURL(blob);
				const a = document.createElement('a');
				a.href = url; a.download = 'commerce-operations.csv';
				document.body.appendChild(a); a.click(); a.remove();
				window.setTimeout(() => URL.revokeObjectURL(url), 1000);
			} catch (_) { /* ignore */ }
		},

		// ---- Display helpers: localized labels + resolved actor name ---------
		// Target (entity), Action (action) and Result (status) render human labels via i18n
		// keys, falling back to the raw value for anything unmapped. '' for empty so
		// the template can show a muted em-dash.
		entityLabel(e: any): string {
			const s = String(e || '');
			return s ? this.t('app.commerce-oplog.entity.' + s, undefined, s) : '';
		},
		actionLabel(a: any): string {
			const s = String(a || '');
			return s ? this.t('app.commerce-oplog.action.' + s, undefined, s) : '';
		},
		statusText(s: any): string {
			const v = String(s || '');
			return v ? this.t('app.commerce-oplog.status.' + v, undefined, v) : '';
		},
		// Who: the server-resolved display name (like content-browser's "updated by"),
		// with a raw-id fallback. Takes the ROW so it can read actorLabel.
		actorName(o: any): string {
			return String((o && (o.actorLabel || o.actor)) || '');
		},

		// ---- Sidebar user (actor) picker — mirrors the content-browser principal
		//      picker: debounced searchPrincipals → shell popup suggestions. --------
		onActorSearchInput() {
			if (actorDebounce) clearTimeout(actorDebounce);
			actorDebounce = setTimeout(() => { this.searchActors(); }, 300);
		},
		onActorSearchFocus() {
			if (this.actorSearch.results.length > 0) this.refreshActorPopup();
		},
		async searchActors() {
			const kw = String(this.actorSearch.keyword || '').trim();
			if (!kw) { this.actorSearch.results = []; this.closeActorPopup(); return; }
			const content = this.instance?.api?.content;
			if (!content || typeof content.searchPrincipals !== 'function') return;
			this.actorSearch.isSearching = true;
			try {
				const res = await content.searchPrincipals(kw, 0, 20);
				this.actorSearch.results = Array.isArray(res) ? res : [];
				this.refreshActorPopup();
			} catch (_) {
				this.actorSearch.results = []; this.closeActorPopup();
			} finally {
				this.actorSearch.isSearching = false;
			}
		},
		buildActorItems(): any[] {
			return (this.actorSearch.results as any[]).map((r) => ({
				id: r.identifier,
				label: r.displayName || r.identifier,
				description: r.displayName ? r.identifier : (r.isGroup ? 'Group' : 'User'),
				icon: r.isGroup ? 'bi bi-people' : 'bi bi-person',
			}));
		},
		refreshActorPopup() {
			const results = this.actorSearch.results as any[];
			if (!results.length) { this.closeActorPopup(); return; }
			const items = this.buildActorItems();
			if (actorPopupHandle) { actorPopupHandle.update(items); return; }
			const input = this.$refs.actorInput as HTMLInputElement | undefined;
			const popup = this.instance?.popup;
			if (!input || !popup) return;
			const rect = input.getBoundingClientRect();
			actorPopupHandle = popup.open({ anchor: rect, placement: 'bottom-start', minWidth: rect.width, maxHeight: 320, items });
			actorPopupHandle.result.then((picked: any) => {
				actorPopupHandle = null;
				if (picked == null) return;
				const match = (this.actorSearch.results as any[]).find((r) => r.identifier === picked);
				if (match) this.selectActor(match);
			});
		},
		closeActorPopup() {
			if (actorPopupHandle) { actorPopupHandle.close(); actorPopupHandle = null; }
		},
		selectActor(p: any) {
			this.filter.actor = p.identifier;
			this.filter.actorLabel = p.displayName || p.identifier;
			this.actorSearch.keyword = p.displayName || p.identifier;
			this.actorSearch.results = [];
			this.closeActorPopup();
		},
		clearActor() {
			this.filter.actor = '';
			this.filter.actorLabel = '';
			this.actorSearch.keyword = '';
			this.actorSearch.results = [];
			this.closeActorPopup();
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
