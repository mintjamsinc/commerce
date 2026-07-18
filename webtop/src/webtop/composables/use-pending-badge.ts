/**
 * Pending-update badge — the shared "反映待ち" (sales figures not yet caught up) counter.
 *
 * Sales figures are materialized ASYNCHRONOUSLY: a webhook (order / refund / cancel) only drops a
 * pending marker and returns; a single cluster-guarded drainer recomputes the sales facts a moment
 * later. So there is a window where the report/dashboard numbers are momentarily behind reality. This
 * badge surfaces that backlog on EVERY commerce app that shows fact-derived numbers — so anyone on any
 * screen sees "still catching up", not only the person who happens to be in the Import console.
 *
 * Source: sales-backfill.groovy GET returns `remaining` = SalesFacts.pendingOrderIds().size() — the LIVE
 * count of pending markers, independent of any historical seed run. Pure fetch helper: each app owns its
 * own reactive `pendingCount` + poll timer (matching the plain-object app pattern), and renders the label
 * through its own i18n bundle.
 */

export const PENDING_BADGE_SCRIPT = '/content/commerce/endpoints/sales-backfill.groovy';

/** GET the live pending-recompute count. Returns 0 on any error (the badge simply hides). */
export async function fetchPendingCount(base: string): Promise<number> {
	const res = await fetch(`${base}${PENDING_BADGE_SCRIPT}`, {
		headers: { Accept: 'application/json' },
		credentials: 'same-origin',
	});
	if (!res.ok) throw new Error(`Request failed (${res.status})`);
	const j = await res.json();
	const n = Number(j && j.remaining);
	return isFinite(n) && n > 0 ? n : 0;
}
