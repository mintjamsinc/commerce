/**
 * Wire-format datetime helpers shared by the Commerce consoles.
 *
 * The platform convention is ISO-8601 on the wire (cf. content-browser). These
 * helpers turn an `<input type="datetime-local">` wall-clock into a UTC ISO-8601
 * instant, resolving the wall-clock in a given IANA timezone (the user's effective
 * Preferences zone) — NOT the browser zone — so a filter boundary stays consistent
 * with a list that displays timestamps in that same zone.
 *
 * Kept as pure functions (timeZone passed in) so any app can reuse the exact same,
 * DST-correct conversion instead of duplicating the subtle offset math.
 */

// Offset (ms) of `timeZone` at instant `ts`: (wall-clock shown in tz) − UTC.
function tzOffsetMs(ts: number, timeZone: string): number {
	const dtf = new Intl.DateTimeFormat('en-US', {
		timeZone, hourCycle: 'h23',
		year: 'numeric', month: '2-digit', day: '2-digit',
		hour: '2-digit', minute: '2-digit', second: '2-digit',
	});
	const p: any = {};
	for (const part of dtf.formatToParts(new Date(ts))) p[part.type] = part.value;
	const asUTC = Date.UTC(+p.year, +p.month - 1, +p.day, (+p.hour) % 24, +p.minute, +p.second);
	return asUTC - ts;
}

/**
 * Epoch ms of a wall-clock ("yyyy-MM-ddTHH:mm[:ss]") interpreted in the given IANA
 * timezone. DST-correct via a two-pass offset adjustment. NaN on malformed input.
 */
export function zonedWallClockToMs(local: string, timeZone: string): number {
	const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?/.exec(local);
	if (!m) return NaN;
	const asUTC = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], m[6] ? +m[6] : 0);
	const off1 = tzOffsetMs(asUTC, timeZone);
	let inst = asUTC - off1;
	const off2 = tzOffsetMs(inst, timeZone);
	if (off2 !== off1) inst = asUTC - off2;
	return inst;
}

/**
 * A datetime-local wall-clock → an ISO-8601 UTC instant for the wire. The
 * wall-clock is resolved in `timeZone` (the effective Preferences zone), falling
 * back to the browser zone when empty. `end` completes the minute inclusively
 * (+59.999s), matching the seconds-completion contract. Returns '' for empty/invalid.
 */
export function wallClockToIso(v: any, timeZone: string, end: boolean): string {
	const s = String(v || '').trim();
	if (!s) return '';
	const tz = String(timeZone || '').trim();
	let base = tz ? zonedWallClockToMs(s, tz) : new Date(s).getTime();
	if (base == null || isNaN(base)) return '';
	if (end) base += 59_999;
	return new Date(base).toISOString();
}

/**
 * Complete a bare date (yyyy-MM-dd, e.g. from a pre-datetime-local saved session or
 * a launch option) to a value the `<input type="datetime-local">` will render:
 * start of day for a from-bound, end-of-day minute for a to-bound. Values that
 * already carry a time (contain a 'T') pass through unchanged.
 */
export function completeDateTimeLocal(v: string, end: boolean): string {
	const s = String(v || '').trim();
	if (!s || s.indexOf('T') !== -1) return s;
	if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return s + (end ? 'T23:59' : 'T00:00');
	return s;
}

function pad2(n: number): string { return String(n).padStart(2, '0'); }

/**
 * Today's calendar date ("yyyy-MM-dd") as observed in `timeZone` (the effective
 * Preferences zone; the browser zone when empty). Used to seed a default filter
 * window on the same date-only granularity an operator's `<input type="date">` uses,
 * so the default and a hand-typed range share one code path.
 */
export function todayInZone(timeZone: string): string {
	const tz = String(timeZone || '').trim();
	const now = new Date();
	if (!tz) return `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}`;
	const dtf = new Intl.DateTimeFormat('en-US', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' });
	const p: any = {};
	for (const part of dtf.formatToParts(now)) p[part.type] = part.value;
	return `${p.year}-${p.month}-${p.day}`;
}

/**
 * A bare date ("yyyy-MM-dd") shifted by whole calendar days. The arithmetic runs on a
 * UTC epoch so a DST transition in the window never drifts the result — a calendar-day
 * offset must add the same number of days regardless of any wall-clock gap that day.
 * Passes malformed input through unchanged.
 */
export function shiftDate(date: string, deltaDays: number): string {
	const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(date || '').trim());
	if (!m) return date;
	const d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3]) + deltaDays * 86_400_000);
	return `${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())}`;
}

function minuteOfDayToHHmm(totalMin: number): string {
	const m = ((totalMin % 1440) + 1440) % 1440;
	const hh = Math.floor(m / 60);
	const mm = m % 60;
	return `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`;
}

/**
 * A wall-clock time-of-day (`HH:mm`, no date — e.g. a `<input type="time">` value)
 * stored/evaluated in UTC (cf. reconcile.yml `schedules`) → the equivalent `HH:mm`
 * in `timeZone` (the effective Preferences zone), for display/editing. Since there
 * is no date to anchor on, the conversion uses "today" (UTC) as a best-effort
 * reference instant — the same convention the config's UI has always used. Falls
 * back to passing the value through unchanged when `timeZone` is empty. Returns
 * '' for malformed input.
 */
export function utcTimeToZone(at: any, timeZone: string): string {
	const m = /^(\d{1,2}):(\d{2})$/.exec(String(at || '').trim());
	if (!m) return '';
	const hh = +m[1], mm = +m[2];
	const tz = String(timeZone || '').trim();
	if (!tz) return minuteOfDayToHHmm(hh * 60 + mm);
	const now = new Date();
	const utcMs = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(), hh, mm);
	const offMs = tzOffsetMs(utcMs, tz);
	return minuteOfDayToHHmm(Math.floor((utcMs + offMs) / 60000));
}

/**
 * Inverse of {@link utcTimeToZone}: a wall-clock `HH:mm` as entered/displayed in
 * `timeZone` → the equivalent `HH:mm` in UTC, for the wire (cf. reconcile.yml
 * `schedules[].at`). Anchored on "today" as observed in `timeZone` and DST-correct
 * via the same two-pass offset adjustment as {@link zonedWallClockToMs}. Falls back
 * to passing the value through unchanged when `timeZone` is empty. Returns '' for
 * malformed input.
 */
export function zoneTimeToUtc(at: any, timeZone: string): string {
	const m = /^(\d{1,2}):(\d{2})$/.exec(String(at || '').trim());
	if (!m) return '';
	const hh = +m[1], mm = +m[2];
	const tz = String(timeZone || '').trim();
	if (!tz) return minuteOfDayToHHmm(hh * 60 + mm);
	const dtf = new Intl.DateTimeFormat('en-US', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' });
	const p: any = {};
	for (const part of dtf.formatToParts(new Date())) p[part.type] = part.value;
	const asUTC = Date.UTC(+p.year, +p.month - 1, +p.day, hh, mm);
	const off1 = tzOffsetMs(asUTC, tz);
	let inst = asUTC - off1;
	const off2 = tzOffsetMs(inst, tz);
	if (off2 !== off1) inst = asUTC - off2;
	return minuteOfDayToHHmm(Math.floor(inst / 60000));
}
