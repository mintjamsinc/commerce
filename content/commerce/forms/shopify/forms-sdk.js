// ============================================================================
// Tasks Form SDK  (classic script — exposes window.createTasksForm)
// ============================================================================
// Client-side SDK for HTML forms that run inside the Webtop **Tasks** app.
//
// A form is user-authored HTML stored in the CMS. The Tasks host renders it in
// a sandboxed iframe declared WITHOUT `allow-same-origin`, so the form executes
// in an *opaque (null) origin*: it cannot reach the shell's services, read the
// parent's cookies/storage, or fetch CMS URLs with the user's credentials. The
// only channel back to the host is a `postMessage` JSON-RPC bridge.
//
// Every form used to hand-copy that bridge — the request/reply correlation, the
// `message` event router, the i18n runtime (ICU compile + cache), theme and
// localization handling, and the ready/standalone lifecycle. Each copy was a
// place for the protocol to drift. This SDK collapses all of it into one
// implementation so a form keeps only its own business logic.
//
// Loading
// -------
// This file is a **classic script**, loaded by a sibling relative <script> tag:
//
//     <script src="./forms-sdk.js?v=1"></script>
//
// A classic <script src> is intentionally NOT subject to CORS, so the
// opaque-origin form iframe can load it from the same CMS with no extra server
// configuration (an ES-module `import` of a same-origin file would be a
// cross-origin fetch and get blocked). The script publishes its API on the
// global object: `window.createTasksForm` / `window.TasksFormSDK`.
//
// `intl-messageformat` is loaded **inside** the SDK (via a dynamic `import()` of
// the CDN ESM build, which IS CORS-enabled) and surfaced through
// `sdk.formatMessage()` / `sdk.translate()`. Forms no longer load it themselves.
// Because that load is asynchronous, the SDK emits a 'ready' event once the ICU
// engine is available; until then `translate()` falls back to its literal
// fallbacks, and a form can bump its render revision on 'ready' to repaint.
//
// Design goals
// ------------
//  * **Hide the RPC.** Forms never touch `window.parent.postMessage` or the
//    `__tasksRpc` envelope. They call named methods (`sdk.readNodeText(path)`)
//    or the generic `sdk.call(method, params)` escape hatch.
//  * **Complete surface.** The SDK exposes *every* method the host dispatch
//    table supports today — not only the handful a given form happens to use.
//  * **Own the ICU engine.** `intl-messageformat` is loaded here, once.
//  * **Framework-agnostic.** The SDK holds only transport + stateless helpers.
//    Reactive UI state stays in the caller's component so its own reactivity
//    system repaints correctly.
//
// Usage sketch (inside a form's <script type="module"> that imports VDOM):
//
//     const I18N_PREFIX = 'form.commerce.shopify.';
//     const sdk = window.createTasksForm();
//
//     const App = {
//       data() { return { localization: { locale: '' }, i18n: { messages: {}, revision: 0 } }; },
//       methods: {
//         t(id, params, fallback) {
//           void this.i18n.revision;  // repaint on locale switch / ICU ready
//           return sdk.translate(this.i18n.messages, this.localization.locale, id, params, fallback);
//         },
//         onMounted() {
//           sdk.on('context', (p) => this.handleContext(p))
//              .on('localization', (loc) => this.applyLocalization(loc))
//              .on('standalone', () => { if (this.view === 'loading') this.view = 'standalone'; })
//              .on('ready', () => { this.i18n.revision++; })  // repaint once ICU is loaded
//              .start();
//         },
//         onUnmount() { sdk.stop(); },
//       },
//     };
// ============================================================================

(function (global) {
	'use strict';

	// Envelope discriminator shared with the host (`app.ts`). Messages that do
	// not carry this key are ignored — the form's window receives unrelated
	// postMessages too (e.g. from browser extensions).
	var RPC_KEY = '__tasksRpc';

	// `ignoreTag: true` keeps angle-bracket markup in a message as literal text
	// (matching the shell's MESSAGE_FORMAT_OPTS) rather than parsing it as a
	// rich-text tag that would throw without a handler.
	var DEFAULT_ICU_OPTS = { ignoreTag: true };

	// Ms to wait for the host's first `context` event before assuming the form
	// was opened standalone (directly in a browser tab). Matches the historical
	// per-form timeout.
	var DEFAULT_STANDALONE_TIMEOUT = 3000;

	// Event names the SDK emits to subscribers.
	var EVENT_TYPES = ['context', 'localization', 'theme', 'standalone', 'ready'];

	// ------------------------------------------------------------------
	// ICU engine — loaded once, lazily, for the whole module.
	// ------------------------------------------------------------------
	// A classic script cannot use a static `import`, so the ICU engine is pulled
	// in with a dynamic `import()` of the CDN ESM build (CORS-enabled). This is
	// the single place the version is pinned, so every form formats plurals /
	// select / number / date placeholders identically — the same engine the
	// shell uses. `_IntlMessageFormat` stays null until the import settles.
	var _IntlMessageFormat = null;
	var _icuSettled = false;
	var _icuReady = import('https://cdn.jsdelivr.net/npm/intl-messageformat@11.2.8/+esm')
		.then(function (m) { _IntlMessageFormat = m.IntlMessageFormat; })
		.catch(function (err) {
			// Leave the engine null — translate() degrades to literal fallbacks.
			console.error('Tasks Form SDK: failed to load intl-messageformat:', err);
		})
		.then(function () { _icuSettled = true; });

	/**
	 * Client for the Tasks form ↔ host postMessage bridge.
	 *
	 * One instance per form, created at module scope and shared by the
	 * component's methods. The instance is intentionally NOT stored in reactive
	 * data — it holds transport plumbing, not UI state.
	 *
	 * @param {object} [options]
	 * @param {Window} [options.target]            Window to talk to (default `window.parent`).
	 * @param {number} [options.standaloneTimeout] Ms before emitting 'standalone' (default 3000; 0 disables).
	 * @param {object} [options.icuOptions]        IntlMessageFormat options (default `{ ignoreTag: true }`).
	 */
	function TasksFormClient(options) {
		options = options || {};
		this._target = options.target || (typeof window !== 'undefined' ? window.parent : null);
		this._standaloneTimeout = options.standaloneTimeout != null ? options.standaloneTimeout : DEFAULT_STANDALONE_TIMEOUT;
		this._icuOpts = options.icuOptions || DEFAULT_ICU_OPTS;

		// In-flight RPC calls, keyed by string id. Survives any number of context
		// updates from the host.
		this._pending = new Map();
		this._nextId = 1;

		// Compiled-formatter cache, keyed by `${locale}::${template}`. Re-keyed
		// implicitly when the locale changes.
		this._icuCache = new Map();

		// Subscribers per event type.
		this._handlers = Object.create(null);
		for (var i = 0; i < EVENT_TYPES.length; i++) this._handlers[EVENT_TYPES[i]] = [];

		this._messageListener = null;
		this._standaloneTimer = null;
		this._contextSeen = false;
		this._started = false;
		this._readyEmitted = false;
	}

	TasksFormClient.prototype = {
		constructor: TasksFormClient,

		// --------------------------------------------------------------
		// Lifecycle
		// --------------------------------------------------------------

		/**
		 * Begin listening for host messages, start the standalone watchdog, and
		 * announce readiness so the host pushes the initial context. Idempotent.
		 * @returns {TasksFormClient}
		 */
		start: function () {
			if (this._started) return this;
			this._started = true;
			var self = this;

			this._messageListener = function (ev) { self._onMessage(ev); };
			window.addEventListener('message', this._messageListener);

			if (this._standaloneTimeout > 0) {
				this._standaloneTimer = setTimeout(function () {
					self._standaloneTimer = null;
					if (!self._contextSeen) self._emit('standalone');
				}, this._standaloneTimeout);
			}

			// Repaint hook once the ICU engine has loaded (or failed). Fires once.
			_icuReady.then(function () {
				self._readyEmitted = true;
				self._emit('ready');
			});

			this.notifyReady();
			return this;
		},

		/**
		 * Tear down listeners and timers and reject any in-flight RPC calls. Safe
		 * to call multiple times. Call from the component's unmount hook.
		 */
		stop: function () {
			if (this._messageListener) {
				window.removeEventListener('message', this._messageListener);
				this._messageListener = null;
			}
			if (this._standaloneTimer) {
				clearTimeout(this._standaloneTimer);
				this._standaloneTimer = null;
			}
			this._rejectAllPending(new Error('Form closed'));
			this._started = false;
		},

		/** Post the `ready` signal so the host knows the frame can receive context. */
		notifyReady: function () {
			try {
				if (this._target) this._target.postMessage({ __tasksRpc: 'ready' }, '*');
			} catch (_) {
				// Running outside the Tasks app (standalone) — ignore.
			}
		},

		/** Promise that resolves when the ICU engine has finished loading (or failed). */
		whenReady: function () { return _icuReady; },

		// --------------------------------------------------------------
		// Events
		// --------------------------------------------------------------

		/**
		 * Subscribe to a host/SDK event.
		 *  - 'context'      `(payload)` — initial frame ready AND every selection /
		 *                   assignment change. `payload` = `{ mode, currentUser,
		 *                   localization, theme, task?, processDefinition? }`.
		 *  - 'localization' `(localization)` — initial context AND every live
		 *                   locale / time-zone switch or i18n bundle hot-reload.
		 *  - 'theme'        `(theme)` — initial context AND every live theme toggle
		 *                   (the SDK already applied it to <html>).
		 *  - 'standalone'   `()` — no context arrived within `standaloneTimeout`.
		 *  - 'ready'        `()` — the ICU engine finished loading; bind a repaint.
		 * @returns {TasksFormClient}
		 */
		on: function (type, handler) {
			if (!this._handlers[type]) throw new Error('Unknown event type: ' + type);
			if (typeof handler === 'function') {
				this._handlers[type].push(handler);
				// 'ready' may already have fired; deliver late subscribers once.
				if (type === 'ready' && this._readyEmitted) {
					Promise.resolve().then(function () { try { handler(); } catch (e) { console.error(e); } });
				}
			}
			return this;
		},

		/** Remove a previously-registered handler. @returns {TasksFormClient} */
		off: function (type, handler) {
			var list = this._handlers[type];
			if (list) {
				var i = list.indexOf(handler);
				if (i >= 0) list.splice(i, 1);
			}
			return this;
		},

		_emit: function (type, arg) {
			var list = this._handlers[type];
			if (!list) return;
			// Copy so handlers may unsubscribe during dispatch.
			var copy = list.slice();
			for (var i = 0; i < copy.length; i++) {
				try { copy[i](arg); } catch (err) { console.error("Tasks form '" + type + "' handler failed:", err); }
			}
		},

		// --------------------------------------------------------------
		// Transport — the hidden RPC
		// --------------------------------------------------------------

		/**
		 * Invoke a host RPC method. The single place a form's request crosses the
		 * postMessage bridge; everything else is a thin wrapper around it.
		 * @param {string} method @param {object} [params]
		 * @returns {Promise<*>} resolves with the host's `data`, rejects on error.
		 */
		call: function (method, params) {
			var self = this;
			return new Promise(function (resolve, reject) {
				var id = String(self._nextId++);
				self._pending.set(id, { resolve: resolve, reject: reject });
				try {
					if (!self._target) throw new Error('No host window to call');
					self._target.postMessage({ __tasksRpc: 'call', id: id, method: method, params: params || {} }, '*');
				} catch (err) {
					self._pending.delete(id);
					reject(err);
				}
			});
		},

		_rejectAllPending: function (err) {
			this._pending.forEach(function (p) { try { p.reject(err); } catch (_) { /* ignore */ } });
			this._pending.clear();
		},

		// --------------------------------------------------------------
		// Named RPC surface — the complete host dispatch table
		// --------------------------------------------------------------
		// These mirror `dispatchRpc` in the Tasks host (`app.ts`). The host
		// enforces mode (start vs task) and ACLs server-side; callers get a
		// rejected promise when an operation is not permitted in the current
		// context.

		// ----- Identity -----
		/** The signed-in user: `{ id, displayName, groups }`. */
		getCurrentUser: function () { return this.call('getCurrentUser'); },
		/** Look up another user by username → `{ id, displayName, mail }` or null. */
		getUser: function (username) { return this.call('getUser', { username: username }); },

		// ----- Localization -----
		/**
		 * Resolved, flat i18n message map for the user's effective locale (the
		 * fallback chain already merged by the host) → `{ locale, messages }`.
		 * @param {string} [prefix] Namespace to pull (e.g. 'form.commerce.shopify.').
		 */
		getI18nMessages: function (prefix) { return this.call('getI18nMessages', prefix ? { prefix: prefix } : {}); },

		// ----- Process start (start mode only) -----
		/** The process definition the start form is for. */
		getProcessDefinition: function () { return this.call('getProcessDefinition'); },
		/** Start the selected process. @param {{variables?: Array, businessKey?: string}} [opts] */
		startProcess: function (opts) {
			opts = opts || {};
			return this.call('startProcess', { variables: opts.variables || [], businessKey: opts.businessKey });
		},

		// ----- Task operations (task modes only) -----
		/** The selected task (without variables). */
		getTask: function () { return this.call('getTask'); },
		/** The selected task plus its `variables` and `localVariables`. */
		getTaskWithVariables: function () { return this.call('getTaskWithVariables'); },
		/** Just the selected task's `{ variables, localVariables }`. */
		getTaskVariables: function () { return this.call('getTaskVariables'); },
		/** Set task variables. @param {Array} variables @param {boolean} [local=false] */
		setTaskVariables: function (variables, local) { return this.call('setTaskVariables', { variables: variables || [], local: !!local }); },
		/** The process instance variables for the selected task. */
		getProcessVariables: function () { return this.call('getProcessVariables'); },
		/** Set process instance variables. @param {Array} variables */
		setProcessVariables: function (variables) { return this.call('setProcessVariables', { variables: variables || [] }); },
		/** Claim the selected task for the current user → updated task. */
		claimTask: function () { return this.call('claimTask'); },
		/** Release the current user's claim on the selected task → updated task. */
		unclaimTask: function () { return this.call('unclaimTask'); },
		/** Assign the selected task. @param {string|null} assignee username, or null to clear. */
		setAssignee: function (assignee) { return this.call('setAssignee', { assignee: assignee == null ? null : assignee }); },
		/** Complete the selected task. @param {Array} [variables] */
		completeTask: function (variables) { return this.call('completeTask', { variables: variables || [] }); },

		// ----- CMS read/write (server-side JCR ACLs apply) -----
		/** Fetch a node (serialized) by path. */
		getNode: function (path) { return this.call('getNode', { path: path }); },
		/** List a node's children. @param {{first?: number, after?: string}} [opts] */
		listChildren: function (path, opts) {
			opts = opts || {};
			return this.call('listChildren', { path: path, first: opts.first, after: opts.after });
		},
		/**
		 * Write a single scalar (string/number/boolean) property on a node.
		 * @param {string} path @param {string} name @param {string|number|boolean} value
		 */
		setNodeProperty: function (path, name, value) { return this.call('setNodeProperty', { path: path, name: name, value: value }); },
		/**
		 * Read a node's binary content as text. The host performs the credentialed
		 * fetch on the form's behalf (the opaque-origin iframe cannot).
		 */
		readNodeText: function (path) { return this.call('readNodeText', { path: path }); },

		// --------------------------------------------------------------
		// Theme
		// --------------------------------------------------------------

		/**
		 * Apply a host-provided theme to this document. Pass 'light' or 'dark' to
		 * force, or '' / null to release control back to the OS preference.
		 */
		applyTheme: function (theme) {
			var root = document.documentElement;
			if (theme === 'light' || theme === 'dark') root.dataset.theme = theme;
			else delete root.dataset.theme;
		},

		// --------------------------------------------------------------
		// i18n — ICU formatting (stateless / pure)
		// --------------------------------------------------------------

		/**
		 * Compile + format an ICU MessageFormat template against a locale.
		 * Compiled formatters are cached. Returns null on a malformed template, a
		 * format failure, OR when the ICU engine has not finished loading yet — so
		 * callers fall back gracefully.
		 * @param {string} template @param {string} [locale] @param {object} [params]
		 * @returns {string|null}
		 */
		formatMessage: function (template, locale, params) {
			if (template == null || _IntlMessageFormat == null) return null;
			var loc = locale || 'en';
			var key = loc + '::' + template;
			var f = this._icuCache.get(key);
			if (!f) {
				try {
					f = new _IntlMessageFormat(template, loc, undefined, this._icuOpts);
				} catch (_) {
					return null;
				}
				this._icuCache.set(key, f);
			}
			try {
				var out = f.format(params || {});
				return Array.isArray(out) ? out.join('') : String(out);
			} catch (_) {
				return null;
			}
		},

		/**
		 * Translate a message id against a host-resolved bundle. Both the resolved
		 * template and the fallback are run through ICU, so placeholders / plurals
		 * work either way. Falls back to `fallback`, then the id.
		 * @param {Object<string,string>} messages Resolved message map for the locale.
		 * @param {string} [locale] @param {string} id @param {object} [params] @param {string} [fallback]
		 * @returns {string}
		 */
		translate: function (messages, locale, id, params, fallback) {
			var template = messages ? messages[id] : undefined;
			if (template != null) {
				var out = this.formatMessage(template, locale, params);
				if (out != null) return out;
			}
			if (fallback != null) {
				var fb = this.formatMessage(fallback, locale, params);
				return fb != null ? fb : fallback;
			}
			return id;
		},

		// --------------------------------------------------------------
		// Locale-aware value formatting
		// --------------------------------------------------------------
		// `localization` is the snapshot the host pushes: `{ locale, timeZone,
		// numberFormat, currency }`. These mirror the per-form helpers they
		// replace.

		/** Thousands-separated number, or '—' when empty/non-finite-input. */
		formatNumber: function (value, localization) {
			if (value == null || value === '') return '—';
			var num = Number(value);
			if (!Number.isFinite(num)) return String(value);
			var loc = localization || {};
			var locale = loc.numberFormat || loc.locale || undefined;
			return num.toLocaleString(locale);
		},

		/**
		 * Amount with two fraction digits, suffixed with the currency code when
		 * the snapshot carries one (e.g. `1,234.50 USD`). '—' when empty/non-finite.
		 */
		formatMoney: function (value, localization) {
			if (value == null || value === '') return '—';
			var num = Number(value);
			if (!Number.isFinite(num)) return String(value);
			var loc = localization || {};
			var locale = loc.numberFormat || loc.locale || undefined;
			var formatted = num.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
			return loc.currency ? formatted + ' ' + loc.currency : formatted;
		},

		/** Locale + time-zone aware date/time, or '' for empty/unparseable input. */
		formatDate: function (value, localization) {
			if (!value) return '';
			var d = new Date(value);
			if (Number.isNaN(d.getTime())) return '';
			var loc = localization || {};
			var locale = loc.locale || undefined;
			var opts = { year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: '2-digit' };
			if (loc.timeZone) opts.timeZone = loc.timeZone;
			try {
				return d.toLocaleString(locale, opts);
			} catch (_) {
				return d.toLocaleString(locale);
			}
		},

		// --------------------------------------------------------------
		// Node accessors (stateless / pure)
		// --------------------------------------------------------------
		// Read a property out of the serialized node shape the SDK's own
		// `getNode()` / `listChildren()` return: `{ properties: [{ name,
		// propertyValue }] }`, where `propertyValue` carries a scalar `value`
		// or a multi-valued `values`. This is the read-side complement to
		// `setNodeProperty()` — the SDK owns that serialization contract, so
		// forms never re-derive how to pull a property out of a node.

		/**
		 * Read a single property's raw value from a serialized node.
		 * @param {object} node A node from `getNode()`/`listChildren()`.
		 * @param {string} name Property name (e.g. 'commerce:order_number').
		 * @returns {*} The scalar `value`, the `values` array for a
		 *   multi-valued property, or undefined when the node / property /
		 *   value is absent.
		 */
		getProp: function (node, name) {
			if (!node || !Array.isArray(node.properties)) return undefined;
			var p = node.properties.find(function (x) { return x && x.name === name; });
			if (!p) return undefined;
			var v = p.propertyValue;
			if (!v) return undefined;
			if ('value' in v) return v.value;
			if ('values' in v) return v.values;
			return undefined;
		},

		/**
		 * Read a single property coerced to a string. Returns '' when the
		 * property is absent or null (never null/undefined), so the result is
		 * safe to drop straight into a text binding.
		 * @param {object} node @param {string} name
		 * @returns {string}
		 */
		getStringProp: function (node, name) {
			var v = this.getProp(node, name);
			return v == null ? '' : String(v);
		},

		// --------------------------------------------------------------
		// Config parsing (stateless / pure)
		// --------------------------------------------------------------

		/**
		 * Read a single top-level scalar by key from a simple YAML config
		 * (e.g. a form's `*.yml` settings node fetched via `readNodeText()`).
		 * Tolerates optional surrounding quotes and a trailing `# comment`.
		 * This is a deliberately small key/value extractor — NOT a full YAML
		 * parser: it does not descend into nested mappings or sequences.
		 * @param {string} yamlText Raw YAML text.
		 * @param {string} key Top-level key to read (e.g. 'shopDomain').
		 * @returns {string} The trimmed value, or '' when the key is absent.
		 */
		readYamlScalar: function (yamlText, key) {
			if (!yamlText || !key) return '';
			var escaped = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
			var re = new RegExp('^[ \\t]*' + escaped + '[ \\t]*:[ \\t]*["\']?([^"\'#\\r\\n]+?)["\']?[ \\t]*(?:#.*)?$', 'm');
			var m = yamlText.match(re);
			return m ? m[1].trim() : '';
		},

		// --------------------------------------------------------------
		// Internal — host message router
		// --------------------------------------------------------------

		_onMessage: function (ev) {
			var data = ev.data;
			if (!data || typeof data !== 'object' || data[RPC_KEY] == null) return;

			// RPC reply.
			if (data[RPC_KEY] === 'result') {
				var id = String(data.id);
				var p = this._pending.get(id);
				if (!p) return;
				this._pending.delete(id);
				if (data.ok) p.resolve(data.data);
				else p.reject(new Error(data.error || 'RPC error'));
				return;
			}

			if (data[RPC_KEY] !== 'event') return;

			// Context push — initial frame ready AND every selection / assignment
			// change. Theme and localization ride along on the first push; apply /
			// surface them before the business-logic handler runs.
			if (data.type === 'context') {
				this._contextSeen = true;
				if (this._standaloneTimer) { clearTimeout(this._standaloneTimer); this._standaloneTimer = null; }
				var payload = data.payload || {};
				if (payload.theme !== undefined) { this.applyTheme(payload.theme); this._emit('theme', payload.theme); }
				if (payload.localization !== undefined) this._emit('localization', payload.localization);
				this._emit('context', payload);
				return;
			}

			// Live theme toggle from the shell.
			if (data.type === 'theme-changed') {
				this.applyTheme(data.theme);
				this._emit('theme', data.theme);
				return;
			}

			// Live locale / time-zone switch or i18n bundle hot-reload.
			if (data.type === 'localization-changed') {
				this._emit('localization', data.localization);
				return;
			}
		}
	};

	/**
	 * Convenience factory. Equivalent to `new TasksFormClient(options)`.
	 * @param {object} [options] See {@link TasksFormClient}.
	 * @returns {TasksFormClient}
	 */
	function createTasksForm(options) {
		return new TasksFormClient(options);
	}

	// Publish on the global object for classic <script src> consumers.
	global.TasksFormSDK = {
		TasksFormClient: TasksFormClient,
		createTasksForm: createTasksForm,
		whenReady: function () { return _icuReady; },
		get IntlMessageFormat() { return _IntlMessageFormat; }
	};
	global.createTasksForm = createTasksForm;

})(typeof window !== 'undefined' ? window : this);
