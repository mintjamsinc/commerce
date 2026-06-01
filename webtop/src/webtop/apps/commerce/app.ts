// Commerce — Webtop application for editing the headless-commerce configuration.
//
// Two categories, edited in memory and persisted together with a single "Save"
// (the schema-manager model):
//   • Shop          → /etc/commerce/config/shopify.yml      (Shopify store + API)
//   • Notifications → /etc/commerce/config/notifications.yml (Slack / Discord)
//
// The notification destinations are deliberately stored in a separate file from
// the Shopify credentials so the two concerns can be managed independently.
//
// Repository IO uses the Webtop content service exposed on the application
// instance (instance.api.content), mirroring how schema-manager reads and
// writes JSON config files under /etc:
//   • read  : getNode(path) → fetch(node.downloadUrl)
//   • write : initiateMultipartUpload → appendMultipartUploadChunk → completeMultipartUpload (overwrite)

import { VDOM } from '@mintjamsinc/ichigojs';

// Type-only: avoid a hard import so the source stays self-contained. The shell
// passes a fully-featured ApplicationInstance at launch.
type AnyInstance = any;

// --- Repository locations --------------------------------------------------
const CONFIG_DIR = '/etc/commerce/config';
const SHOPIFY_FILE = 'shopify.yml';
const NOTIF_FILE = 'notifications.yml';
const SHOPIFY_PATH = CONFIG_DIR + '/' + SHOPIFY_FILE;
const NOTIF_PATH = CONFIG_DIR + '/' + NOTIF_FILE;
const YAML_MIME = 'application/x-yaml';

// --- Minimal YAML helpers --------------------------------------------------
// Purpose-built for the controlled two-level structure of these config files
// (top-level scalars + one level of nesting). Not a general YAML parser.
function coerce(raw: string): string | boolean {
	let v = (raw || '').trim();
	if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
		v = v.slice(1, -1);
	}
	if (v === 'true') return true;
	if (v === 'false') return false;
	return v;
}

function parseSimpleYaml(text: string): Record<string, any> {
	const root: Record<string, any> = {};
	let parent: Record<string, any> | null = null;
	for (const rawLine of String(text || '').split(/\r?\n/)) {
		const trimmed = rawLine.trim();
		if (!trimmed || trimmed.startsWith('#')) continue;
		const m = trimmed.match(/^([A-Za-z0-9_.-]+)\s*:\s*(.*)$/);
		if (!m) continue;
		const key = m[1];
		const val = m[2];
		const indent = rawLine.length - rawLine.replace(/^\s+/, '').length;
		if (indent === 0) {
			if (val === '') { root[key] = {}; parent = root[key]; }
			else { root[key] = coerce(val); parent = null; }
		} else if (parent) {
			parent[key] = coerce(val);
		}
	}
	return root;
}

function esc(v: any): string {
	return String(v == null ? '' : v).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}

function serializeShopify(s: any): string {
	const a = s.adminApi || {};
	return `# Shopify configuration
# Deploy to: /etc/commerce/config/shopify.yml
# Managed by the Commerce app (Webtop > Commerce > Shop).

# Webhook shared secret (from Shopify Admin > Notifications > Webhooks).
# Used to verify incoming Shopify webhooks (HMAC-SHA256). Required to receive
# webhooks and kept INDEPENDENT of the Admin API credentials below.
webhookSecret: "${esc(s.webhookSecret)}"

# Admin API integration (optional).
# When enabled, product webhooks are enriched with metafields from the Shopify
# Admin API (GraphQL). When disabled, no Admin API calls are made and the
# connection fields below are ignored. The four fields are required when enabled.
adminApi:
  enabled: ${a.enabled === true}
  # Shop domain
  shopDomain: "${esc(a.shopDomain)}"
  # API version
  apiVersion: "${esc(a.apiVersion)}"
  # OAuth credentials (from Shopify Partners > App > Client credentials)
  clientID: "${esc(a.clientID)}"
  clientSecret: "${esc(a.clientSecret)}"
`;
}

function serializeNotifications(n: any): string {
	const slack = n.slack || {};
	const discord = n.discord || {};
	return `# Notification destinations for the inventory alert workflow
# Deploy to: /etc/commerce/config/notifications.yml
# Managed by the Commerce app (Webtop > Commerce > Notifications).
# Kept separate from shopify.yml so notification settings carry no API secrets.

# Slack incoming webhook — https://api.slack.com/messaging/webhooks
slack:
  enabled: ${slack.enabled === true}
  webhookUrl: "${esc(slack.webhookUrl)}"

# Discord incoming webhook — https://support.discord.com/hc/en-us/articles/228383668
discord:
  enabled: ${discord.enabled === true}
  webhookUrl: "${esc(discord.webhookUrl)}"
`;
}

// UTF-8 safe base64 for multipart upload chunks.
function toBase64(text: string): string {
	const bytes = new TextEncoder().encode(text);
	let binary = '';
	for (const b of bytes) binary += String.fromCharCode(b);
	return btoa(binary);
}

const App = {
	data() {
		return {
			instance: null as AnyInstance,
			content: null as AnyInstance,

			section: 'shop' as 'shop' | 'notifications',
			view: 'loading' as 'loading' | 'error' | 'ready',
			errorMessage: '',

			saving: false,
			status: '',
			statusKind: '' as '' | 'ok' | 'err',
			toast: '',
			toastError: false,

			// Unsaved-changes prompt shown on window close (Save / Don't Save /
			// Cancel), mirroring the cms0 text-editor dialog instead of the
			// native window.confirm().
			closeConfirmDialog: {
				visible: false,
				resolve: null as null | ((result: 'save' | 'discard' | 'cancel') => void),
			},

			// Per-field reveal state for the Shop secrets (eye toggles).
			reveal: { clientID: false, clientSecret: false, webhookSecret: false },

			// The webhook shared secret is independent of the Admin API; the
			// Admin API connection settings are grouped (and gated) under adminApi.
			shop: {
				webhookSecret: '',
				adminApi: { enabled: false, shopDomain: '', apiVersion: '', clientID: '', clientSecret: '' },
			},
			notif: {
				slack: { enabled: false, webhookUrl: '' },
				discord: { enabled: false, webhookUrl: '' },
			},

			// Snapshots for dirty detection (the in-memory edit model).
			_origShop: '',
			_origNotif: '',
			_messageListener: null as any,
			_toastTimer: null as any,
		};
	},

	computed: {
		shopDirty(): boolean { return JSON.stringify(this.shop) !== this._origShop; },
		notifDirty(): boolean { return JSON.stringify(this.notif) !== this._origNotif; },
		hasChanges(): boolean { return this.shopDirty || this.notifDirty; },

		// When the Admin API is enabled, all four connection fields are required.
		// Drives the inline field markers, the save guard and the status hint.
		adminApiInvalid(): boolean {
			const a = this.shop.adminApi;
			if (!a.enabled) return false;
			return !String(a.shopDomain).trim()
				|| !String(a.apiVersion).trim()
				|| !String(a.clientID).trim()
				|| !String(a.clientSecret).trim();
		},
		canSave(): boolean { return this.hasChanges && !this.adminApiInvalid && !this.saving; },
	},

	methods: {
		// ---- Lifecycle -------------------------------------------------------
		onMounted() {
			const vm = this;

			// The shell pushes theme changes to the iframe via postMessage; mirror
			// the value onto <html data-theme> exactly like the built-in apps.
			vm._messageListener = (event: MessageEvent) => {
				const data: any = event.data || {};
				if (data.type === 'theme-changed' && data.theme) {
					document.documentElement.dataset.theme = data.theme;
				}
			};
			window.addEventListener('message', vm._messageListener);

			window.appLaunch = async (instance: AnyInstance) => {
				vm.instance = vm.$markRaw(instance);
				try { vm.content = vm.$markRaw(instance.api.content); } catch (_) { vm.content = null; }

				try {
					const theme = instance.api.theme.currentTheme || 'light';
					document.documentElement.dataset.theme = theme;
				} catch (_) { /* theme service unavailable */ }

				try { instance.windowTitle = 'Commerce'; } catch (_) {}

				// Warn before discarding unsaved edits on window close, using the
				// shared Webtop dialog (same look as the cms0 text-editor).
				if (typeof instance.setBeforeCloseCallback === 'function') {
					instance.setBeforeCloseCallback(async () => vm.confirmClose());
				}

				await vm.loadAll();

				vm.$nextTick(() => { try { instance.notifyLaunched(); } catch (_) {} });
			};
		},

		onUnmount() {
			if (this._messageListener) window.removeEventListener('message', this._messageListener);
			if (this._toastTimer) clearTimeout(this._toastTimer);
		},

		selectSection(section: 'shop' | 'notifications') { this.section = section; },

		// ---- Window controls -------------------------------------------------
		onMinimizeWindow() { this.instance?.minimize(); },
		onToggleMaximizeWindow() { this.instance?.toggleMaximize(); },
		onCloseWindow() { this.instance?.requestClose(); },

		// ---- Close confirmation ----------------------------------------------
		// Resolves true when the window may close, false to keep it open. When the
		// user chooses Save, persist first and only close if the save succeeded
		// (a failed save or invalid Admin API leaves hasChanges set → stay open).
		async confirmClose(): Promise<boolean> {
			const vm = this;
			if (!vm.hasChanges) return true;

			const result = await vm.showCloseConfirmDialog();
			if (result === 'cancel') return false;
			if (result === 'discard') return true;

			await vm.saveAll();
			return !vm.hasChanges;
		},

		showCloseConfirmDialog(): Promise<'save' | 'discard' | 'cancel'> {
			const vm = this;
			vm.closeConfirmDialog.visible = true;
			return new Promise((resolve) => {
				vm.closeConfirmDialog.resolve = resolve;
			});
		},

		onCloseConfirmDialogAction(action: 'save' | 'discard' | 'cancel') {
			const vm = this;
			if (vm.closeConfirmDialog.resolve) {
				vm.closeConfirmDialog.resolve(action);
			}
			vm.closeConfirmDialog.visible = false;
			vm.closeConfirmDialog.resolve = null;
		},

		// ---- Repository IO ---------------------------------------------------
		async readText(path: string): Promise<string | null> {
			if (!this.content) return null;
			try {
				const node = await this.content.getNode(path);
				if (!node || !node.downloadUrl) return null;
				const res = await fetch(node.downloadUrl);
				if (!res.ok) return null;
				return await res.text();
			} catch (_) {
				return null; // treat a missing file as "use defaults"
			}
		},

		async writeText(dir: string, file: string, text: string): Promise<void> {
			if (!this.content) throw new Error('Content service is unavailable.');
			const info: any = await this.content.initiateMultipartUpload();
			const uploadID = info?.uploadId ?? info?.uploadID ?? info?.id ?? info;
			await this.content.appendMultipartUploadChunk(uploadID, toBase64(text));
			await this.content.completeMultipartUpload(uploadID, dir, file, YAML_MIME, true);
		},

		async loadAll() {
			try {
				const [shopText, notifText] = await Promise.all([
					this.readText(SHOPIFY_PATH),
					this.readText(NOTIF_PATH),
				]);

				const s = parseSimpleYaml(shopText || '');
				// Admin API settings live under `adminApi`. Legacy flat files kept
				// the four fields at the top level — fall back to them so existing
				// values survive until the next save migrates the file. A flat file
				// has no enabled flag, so the integration defaults to off.
				const a = (s.adminApi && typeof s.adminApi === 'object') ? s.adminApi : {};
				this.shop = {
					webhookSecret: String(s.webhookSecret || ''),
					adminApi: {
						enabled: a.enabled === true,
						shopDomain: String(a.shopDomain ?? s.shopDomain ?? ''),
						apiVersion: String(a.apiVersion ?? s.apiVersion ?? ''),
						clientID: String(a.clientID ?? s.clientID ?? ''),
						clientSecret: String(a.clientSecret ?? s.clientSecret ?? ''),
					},
				};

				const n = parseSimpleYaml(notifText || '');
				this.notif = {
					slack: {
						enabled: !!(n.slack && n.slack.enabled === true),
						webhookUrl: String((n.slack && n.slack.webhookUrl) || ''),
					},
					discord: {
						enabled: !!(n.discord && n.discord.enabled === true),
						webhookUrl: String((n.discord && n.discord.webhookUrl) || ''),
					},
				};

				this.snapshot();
				this.view = 'ready';
			} catch (e: any) {
				this.errorMessage = (e && e.message) ? e.message : String(e);
				this.view = 'error';
			}
		},

		async saveAll() {
			if (this.saving || !this.hasChanges) return;

			// Enforce the required Admin API fields before persisting so we never
			// write an enabled-but-unconfigured integration.
			if (this.adminApiInvalid) {
				this.section = 'shop';
				this.status = 'Enter the shop domain, API version, client ID and client secret, or turn off the Admin API.';
				this.statusKind = 'err';
				this.showToast(this.status, true);
				return;
			}

			this.saving = true;
			this.status = '';
			this.statusKind = '';
			try {
				if (this.shopDirty) {
					await this.writeText(CONFIG_DIR, SHOPIFY_FILE, serializeShopify(this.shop));
				}
				if (this.notifDirty) {
					await this.writeText(CONFIG_DIR, NOTIF_FILE, serializeNotifications(this.notif));
				}
				this.snapshot();
				this.status = 'All changes saved.';
				this.statusKind = 'ok';
			} catch (e: any) {
				this.status = 'Save failed: ' + ((e && e.message) ? e.message : String(e));
				this.statusKind = 'err';
				this.showToast(this.status, true);
			} finally {
				this.saving = false;
			}
		},

		snapshot() {
			this._origShop = JSON.stringify(this.shop);
			this._origNotif = JSON.stringify(this.notif);
		},

		showToast(message: string, isError: boolean) {
			this.toast = message || '';
			this.toastError = !!isError;
			if (this._toastTimer) clearTimeout(this._toastTimer);
			this._toastTimer = setTimeout(() => { this.toast = ''; }, 4000);
		},
	},
};

VDOM.createApp(App).mount('#app');
