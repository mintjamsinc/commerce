# Tasks Form SDK (`forms-sdk.js`)

This is the reference for the **client-side** SDK shared by the Shopify task
forms. It exists so that the form ↔ host plumbing lives in exactly one place, and
so that anyone writing or changing a form knows how to talk to the Tasks host
without hand-copying the bridge again.

## Why it exists

Each form under `content/commerce/forms/shopify/*.html` is user-authored HTML the
Webtop **Tasks** app renders in a sandboxed iframe. The forms had grown copies of
the same ~150 lines of plumbing — the `postMessage` JSON-RPC bridge, the
`message` event router, the ICU/i18n runtime (compile + cache), theme and
localization handling, and the ready/standalone lifecycle. Every copy was a place
for the protocol to drift. The SDK collapses all of it into one implementation;
each form now keeps only its own business logic (data loading, computed UI,
decisions). `intl-messageformat` is imported **once**, inside the SDK, and used
through SDK methods rather than imported by every form.

## Where it lives and how forms use it

| Aspect | Convention |
|---|---|
| Location | `content/commerce/forms/shopify/forms-sdk.js`, beside the form HTML. |
| Load | A **classic** `<script>` tag with a relative path, placed before the form's module script: `<script src="./forms-sdk.js"></script>`. No cache-busting query is needed — the CMS serves the file with an ETag/`Cache-Control: no-cache` validator, so the iframe revalidates on each load and picks up an edited SDK automatically (304 while unchanged). |
| Global | The script publishes `window.createTasksForm` / `window.TasksFormSDK`. The form's module script does `const sdk = window.createTasksForm();`. |
| ICU engine | `intl-messageformat` is loaded **inside** the SDK (dynamic `import()` of the CDN ESM build) and surfaced via `formatMessage()` / `translate()`. Forms do **not** load it. |
| Instance | One per form, at module scope — it holds transport, **not** reactive UI state. |

> **Why a classic `<script>` and not `import`.** The form iframe is declared
> without `allow-same-origin`, so it runs in an *opaque (null) origin*. An ES
> `import` of a same-origin module would be a cross-origin (CORS) fetch and get
> blocked unless the CMS served the file with `Access-Control-Allow-Origin` — the
> same reason the host proxies `readNodeText` instead of letting the form fetch
> CMS URLs itself. A classic `<script src>` is **not** CORS-restricted, so the
> iframe loads the SDK from the same CMS with no server change. The SDK itself
> then loads `intl-messageformat` from the (CORS-enabled) CDN with a dynamic
> `import()`.

> **ICU is loaded asynchronously.** Because the engine arrives via a dynamic
> `import()`, `translate()` returns its literal fallbacks until the engine is
> ready, then the SDK fires a `'ready'` event. A form bumps its render revision
> on `'ready'` so the affected bindings repaint with the compiled ICU output.

## Reactivity contract

The SDK is framework-agnostic and deliberately holds no reactive state. A form
keeps `localization` and `i18n: { messages, revision }` in its own `data()` so
ichigo.js repaints correctly, and wires the SDK in its lifecycle hooks:

```html
<script src="./forms-sdk.js"></script>
<script type="module">
import { VDOM } from 'https://cdn.jsdelivr.net/npm/@mintjamsinc/ichigojs@0.1.75/dist/ichigo.esm.min.js';

const I18N_PREFIX = 'form.commerce.shopify.';
const sdk = window.createTasksForm();

const App = {
  data() { return { localization: { locale: '' }, i18n: { messages: {}, revision: 0 } }; },
  methods: {
    t(id, params, fallback) {
      void this.i18n.revision;                         // repaint on locale change / ICU ready
      return sdk.translate(this.i18n.messages, this.localization.locale, id, params, fallback);
    },
    onMounted() {
      sdk.on('context', (p)   => this.handleContext(p))
         .on('localization', (loc) => this.applyLocalization(loc))
         .on('standalone', () => { if (this.view === 'loading') this.view = 'standalone'; })
         .on('ready', () => { this.i18n.revision++; }) // repaint once the ICU engine loads
         .start();
    },
    onUnmount() { sdk.stop(); },
  },
};
VDOM.createApp(App).mount('#app');
</script>
```

## API

### Lifecycle & events

| Member | Purpose |
|---|---|
| `createTasksForm(options?)` | Factory → a `TasksFormClient`. Options: `target` (default `window.parent`), `standaloneTimeout` (ms, default 3000; 0 disables), `icuOptions` (default `{ ignoreTag: true }`). |
| `sdk.start()` | Listen for host messages, start the standalone watchdog, post `ready`, and raise the host window on `pointerdown`. Idempotent. |
| `sdk.stop()` | Tear down listeners/timers and reject in-flight calls. Call on unmount. |

Between `start()` and `stop()` the SDK also forwards every `pointerdown` inside
the form to the host as an `activate` signal. The form runs in an opaque
(null-origin) sandboxed iframe, so the shell can't see clicks inside it;
without this, clicking the form body would not bring a background Tasks window
to the front (only the window chrome would). This is automatic — forms do not
call it.
| `sdk.on(type, fn)` / `sdk.off(type, fn)` | Subscribe to `'context'` (payload), `'localization'` (snapshot), `'theme'` (string — SDK already applied it to `<html>`), `'standalone'` (no args), `'ready'` (no args — ICU engine loaded). |
| `sdk.whenReady()` | Promise that resolves once the ICU engine has loaded (or failed to load). |

### Transport (the hidden RPC)

| Member | Purpose |
|---|---|
| `sdk.call(method, params?)` | Generic escape hatch — the single place a request crosses the bridge. |

### Named RPC surface (mirrors the host dispatch table)

| Method | Purpose |
|---|---|
| `getCurrentUser()` | Signed-in user `{ id, displayName, groups }`. |
| `getUser(username)` | Look up a user → `{ id, displayName, mail }` or null. |
| `getI18nMessages(prefix?)` | Resolved flat message map for the effective locale → `{ locale, messages }`. |
| `getProcessDefinition()` | The definition a start form is for *(start mode)*. |
| `startProcess({ variables?, businessKey? })` | Start the selected process *(start mode)*. |
| `getTask()` / `getTaskWithVariables()` / `getTaskVariables()` | Read the selected task (optionally with variables). |
| `setTaskVariables(variables, local?)` | Write task variables. |
| `getProcessVariables()` / `setProcessVariables(variables)` | Read/write process instance variables. |
| `claimTask()` / `unclaimTask()` / `setAssignee(assignee)` | Assignment. |
| `completeTask(variables?)` | Complete the selected task. |
| `getNode(path)` | Read one CMS node (server-side JCR ACLs apply) → the serialized node `{ path, name, …, properties }`. |
| `listChildren(path, { first?, after? })` | List a node's children (JCR ACLs apply). The host's GraphQL-style connection is **normalized** to `{ nodes, pageInfo, totalCount }` — `nodes` is a plain array of serialized nodes; page with `pageInfo.endCursor` + `hasNextPage` via `opts.after`. |
| `setNodeProperty(path, name, value)` | Write one scalar property. |
| `readNodeText(path)` | Read a node's content as text (host performs the credentialed fetch). |

### i18n & formatting (stateless / pure)

| Method | Purpose |
|---|---|
| `formatMessage(template, locale?, params?)` | Compile + format an ICU template (cached); null on a malformed template. |
| `translate(messages, locale?, id, params?, fallback?)` | Resolve `id` against `messages`, else `fallback`, else `id` — both run through ICU. |
| `formatNumber(value, localization)` | Thousands-separated number, `—` when empty. |
| `formatMoney(value, localization)` | Two-fraction-digit amount, suffixed with `localization.currency` when present. |
| `formatDate(value, localization)` | Locale + time-zone aware date/time. |
| `window.TasksFormSDK.IntlMessageFormat` | The raw ICU engine (null until loaded), for callers that need a custom formatter. |

### Node & config accessors (stateless / pure)

These read data the SDK itself already returns, so forms never re-derive how to
interpret it. `getProp` / `getStringProp` are the **read-side complement** to
`setNodeProperty`: the SDK owns the node serialization contract (a serialized node —
from `getNode()`, or an element of `listChildren().nodes` — carries
`{ properties: [{ name, propertyValue }] }`, where `propertyValue` carries a scalar
`value` or a multi-valued `values`), so a form pulls a property straight off `sdk`
instead of re-walking that shape.

| Method | Purpose |
|---|---|
| `getProp(node, name)` | Raw value of property `name` on a serialized `node` → its scalar `value`, the `values` array for a multi-valued property, or `undefined` when absent. |
| `getStringProp(node, name)` | Same, coerced to a string; `''` when absent or null (never `null`/`undefined`), so it drops straight into a text binding. |
| `readYamlScalar(yamlText, key)` | Read one top-level scalar by `key` from a simple YAML config (e.g. a settings node fetched via `readNodeText()`). Tolerates optional quotes and a trailing `# comment`; trimmed value, or `''` when absent. A deliberately small key/value extractor — **not** a full YAML parser; it does not descend into nested mappings or sequences. |

## Design rules

1. **Hide the RPC.** Forms never touch `window.parent.postMessage` or the
   `__tasksRpc` envelope — they call named methods or `sdk.call(...)`.
2. **Complete surface, not part-optimal.** The SDK exposes *every* method the
   host supports today, not only the handful a given form happens to use, so any
   future form relies on the same predictable client.
3. **One ICU engine.** `intl-messageformat` is pinned and imported once, here.
4. **No reactive state in the SDK.** UI state stays in the form's component; the
   SDK holds transport + pure helpers only.
