/**
 * Localization Composable (Commerce Webtop)
 *
 * The single bridge between the Webtop shell's Localization preference + i18n
 * message bundles and a Commerce app's reactive UI. An app keeps one small
 * reactive snapshot in its `data()` (via {@link createLocalizationSnapshot}) and
 * routes every locale-aware read through the helpers here, so the whole app
 * repaints the instant the user changes their Preferences > Localization or an
 * i18n bundle is hot-reloaded.
 *
 * This file is a self-contained mirror of the cms0 shell composable
 * (`webtop/src/webtop/composables/use-localization.ts`). The Commerce build is
 * deliberately decoupled from cms0 internals — it depends only on the published
 * @mintjamsinc/ichigojs runtime and reaches the shell's services through
 * `instance.api.*` at runtime — so the contract is duplicated (not imported)
 * here, with date formatting inlined rather than pulled from the shell's Dates
 * util. Keep the two in sync when the shell contract changes.
 *
 * What the snapshot carries (all **effective** values — preference → fallback —
 * so callers never re-resolve the fallback themselves):
 *   - `locale`       e.g. 'ja-JP'      — display language
 *   - `timeZone`     e.g. 'Asia/Tokyo' — IANA time zone for date/time
 *   - `numberFormat` e.g. 'de-DE'      — locale used to group/format numbers
 *   - `currency`     e.g. 'JPY'        — ISO 4217 default currency
 *   - `revision`     internal counter, bumped on bundle hot-reload
 *
 * Wiring (do all three in every app):
 *   1. `data()` → `localization: createLocalizationSnapshot()`
 *   2. in `appLaunch` (after `instance` is assigned) →
 *      `refreshLocalization(this.localization, this.instance)`
 *   3. in the `message` listener →
 *      `if (handleLocalizationMessage(type, vm.localization, vm.instance)) return;`
 *
 * Then expose thin wrappers as component methods and use them in templates:
 *
 * ```ts
 * methods: {
 *   t(id, params, fallback) { return translate(this.localization, this.instance, id, params, fallback); },
 *   formatNumber(v, o)      { return formatNumber(this.localization, v, o); },
 *   formatCurrency(v, o)    { return formatCurrency(this.localization, v, o); },
 *   formatDate(v, o)        { return formatDate(this.localization, v, o); },
 * }
 * ```
 *
 * Why this repaints reactively: ichigojs re-evaluates a binding when any
 * reactive value the binding *read* during its last evaluation mutates. Each
 * helper deliberately reads the snapshot field(s) it depends on, so changing
 * `locale` (or bumping `revision` on bundle reload) re-runs every `t()` /
 * `format*()` binding. The shell broadcasts `localization-changed` (locale /
 * zone / currency change) and `i18n-bundles-updated` (bundle hot-reload);
 * {@link handleLocalizationMessage} folds both into the snapshot.
 */

export interface LocalizationSnapshot {
	/** Effective locale (e.g. 'ja-JP'). Empty string until {@link refreshLocalization}. */
	locale: string;
	/** Effective IANA time zone (e.g. 'Asia/Tokyo'). Empty string until {@link refreshLocalization}. */
	timeZone: string;
	/** Effective number-format locale (e.g. 'de-DE'). Empty string until {@link refreshLocalization}. */
	numberFormat: string;
	/** Effective ISO 4217 currency code (e.g. 'JPY'). Empty string until {@link refreshLocalization}. */
	currency: string;
	/**
	 * Reactive revision counter, bumped whenever the i18n message bundles are
	 * hot-reloaded. Read by {@link translate} so message bindings repaint when a
	 * bundle changes without the locale itself changing. Not meant to be read
	 * directly by app code.
	 */
	revision: number;
}

/**
 * Create the initial (empty) snapshot to place inside `data()`.
 */
export function createLocalizationSnapshot(): LocalizationSnapshot {
	return { locale: '', timeZone: '', numberFormat: '', currency: '', revision: 0 };
}

/**
 * Populate the snapshot from the shell `LocalizationManager`
 * (`instance.api.localization`). Safe to call before the manager has loaded —
 * leaves the snapshot untouched if the preference isn't available yet.
 */
export function refreshLocalization(snapshot: LocalizationSnapshot, instance: any): void {
	const loc = instance?.api?.localization;
	if (!loc) return;
	snapshot.locale = loc.effectiveLocale || '';
	snapshot.timeZone = loc.effectiveTimezone || '';
	snapshot.numberFormat = loc.effectiveNumberFormat || '';
	snapshot.currency = loc.effectiveCurrency || '';
}

/**
 * Message-listener helper. Folds the two shell broadcasts that affect
 * localization into the snapshot and returns `true` when the event was one of
 * them (so callers can early-return):
 *
 *   - `localization-changed`   → re-resolve the whole snapshot and bump revision.
 *   - `i18n-bundles-updated`   → bump `revision` so message bindings repaint.
 *
 * ```ts
 * if (handleLocalizationMessage(type, vm.localization, vm.instance)) return;
 * ```
 */
export function handleLocalizationMessage(type: string, snapshot: LocalizationSnapshot, instance: any): boolean {
	if (type === 'localization-changed') {
		refreshLocalization(snapshot, instance);
		snapshot.revision++;
		return true;
	}
	if (type === 'i18n-bundles-updated') {
		snapshot.revision++;
		return true;
	}
	return false;
}

/**
 * Resolve the shell's I18nService from whichever context we're in:
 * `instance.api.i18n` (apps — `instance.api` is the shell's API), then the
 * parent window (iframe apps), then the current window (defensive).
 * Returns `null` until the service has been initialized.
 */
function resolveI18n(instance: any): any {
	const fromInstance = instance?.api?.i18n;
	if (fromInstance) return fromInstance;
	try {
		const fromParent = (window.parent as any)?.Webtop?.i18n;
		if (fromParent) return fromParent;
	} catch {
		// Cross-origin — ignore.
	}
	return (window as any).Webtop?.i18n ?? null;
}

/**
 * Reactively translate an i18n message id against the user's effective locale.
 *
 * Reads `snapshot.locale` and `snapshot.revision` so the calling binding
 * repaints when the language switches or the bundles hot-reload. Falls back —
 * inside `I18nService.format` — through exact locale → language only → 'en' →
 * `fallback` → the id itself, so a missing key degrades gracefully rather than
 * throwing.
 *
 * @param params   ICU MessageFormat arguments (e.g. `{ count: 3 }`).
 * @param fallback Literal shown when no bundle defines the id.
 */
export function translate(
	snapshot: LocalizationSnapshot,
	instance: any,
	messageId: string,
	params?: Record<string, any>,
	fallback?: string,
): string {
	// Establish the reactive dependencies (see file header): reading these
	// snapshot fields subscribes the calling binding, so it repaints when the
	// locale switches (`locale`) or the bundles hot-reload (`revision`).
	const locale = snapshot.locale;
	const revision = snapshot.revision;
	void revision;

	const i18n = resolveI18n(instance);
	if (!i18n || typeof i18n.format !== 'function') {
		return fallback ?? messageId;
	}
	return i18n.format(messageId, params, fallback, locale || undefined);
}

/**
 * Reactively format a number in the user's effective number-format locale.
 * Reads `snapshot.numberFormat` / `snapshot.locale` so it repaints on change.
 */
export function formatNumber(
	snapshot: LocalizationSnapshot,
	value: number | bigint | string | null | undefined,
	options: Intl.NumberFormatOptions = {},
): string {
	if (value == null || value === '') return '';
	const num = typeof value === 'string' ? Number(value) : value;
	if (typeof num === 'number' && Number.isNaN(num)) return String(value);
	const locale = snapshot.numberFormat || snapshot.locale || undefined;
	try {
		return new Intl.NumberFormat(locale, options).format(num as number);
	} catch {
		return String(value);
	}
}

/**
 * Reactively format a monetary value in the user's effective currency and
 * number-format locale. Reads `snapshot.currency` / `snapshot.numberFormat` /
 * `snapshot.locale`. When no currency is resolved yet, degrades to a plain
 * number rather than throwing. Pass an explicit `currency` in `options` to
 * override the effective currency (e.g. when an order carries its own).
 */
export function formatCurrency(
	snapshot: LocalizationSnapshot,
	value: number | bigint | string | null | undefined,
	options: Intl.NumberFormatOptions = {},
): string {
	if (value == null || value === '') return '';
	const num = typeof value === 'string' ? Number(value) : value;
	if (typeof num === 'number' && Number.isNaN(num)) return String(value);
	const locale = snapshot.numberFormat || snapshot.locale || undefined;
	const currency = options.currency || snapshot.currency || undefined;
	if (!currency) {
		return formatNumber(snapshot, value, options);
	}
	try {
		return new Intl.NumberFormat(locale, { style: 'currency', currency, ...options }).format(num as number);
	} catch {
		return String(value);
	}
}

const DATE_PRESETS: Record<string, Intl.DateTimeFormatOptions> = {
	datetime: { year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: 'numeric', weekday: 'short' },
	date: { year: 'numeric', month: 'short', day: 'numeric' },
	time: { hour: 'numeric', minute: 'numeric' },
};

/**
 * Reactively format a date/time in the user's effective locale and time zone.
 * Reads `snapshot.locale` / `snapshot.timeZone` so it repaints on change.
 *
 * Self-contained mirror of the shell's `Dates.format` presets:
 *   - 'datetime' (default): weekday, short date + time
 *   - 'date': short date
 *   - 'time': time only
 *   - 'friendly': relative ("5 minutes ago", "yesterday"), falling back to a
 *     full date/time beyond a week.
 * Any other value falls back to the 'datetime' preset. Returns '' for nullish
 * or invalid input.
 */
export function formatDate(
	snapshot: LocalizationSnapshot,
	value: Date | number | string | null | undefined,
	options: { format?: string } = {},
): string {
	if (value == null || value === '') return '';
	const date = value instanceof Date ? value : new Date(value as any);
	if (Number.isNaN(date.getTime())) return '';

	const locale = snapshot.locale || undefined;
	const timeZone = snapshot.timeZone || undefined;
	const format = options.format || 'datetime';

	try {
		if (format === 'friendly') {
			return formatFriendly(date, locale, timeZone);
		}
		const preset = DATE_PRESETS[format] || DATE_PRESETS.datetime;
		const intlOptions: Intl.DateTimeFormatOptions = { ...preset };
		if (timeZone) intlOptions.timeZone = timeZone;
		return date.toLocaleString(locale, intlOptions);
	} catch {
		return '';
	}
}

/** Relative-time formatting for {@link formatDate}'s 'friendly' preset. */
function formatFriendly(date: Date, locale?: string, timeZone?: string): string {
	const diffSeconds = Math.floor((date.getTime() - Date.now()) / 1000);
	const abs = Math.abs(diffSeconds);
	const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
	if (abs < 60) return rtf.format(Math.round(diffSeconds), 'second');
	if (abs < 3600) return rtf.format(Math.round(diffSeconds / 60), 'minute');
	if (abs < 86400) return rtf.format(Math.round(diffSeconds / 3600), 'hour');
	if (abs < 86400 * 7) return rtf.format(Math.round(diffSeconds / 86400), 'day');
	const absOptions: Intl.DateTimeFormatOptions = { year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' };
	if (timeZone) absOptions.timeZone = timeZone;
	return date.toLocaleString(locale, absOptions);
}
