package commerce

/**
 * Replenishment math for the auto-reorder workflow: how much to order to cover
 * future demand. Pure logic (no JCR / engine) so it stays testable; the calling
 * script gathers stock + velocity and persists / sends the resulting order.
 *
 * Suggested order quantity covers (leadTime + targetCover) days of demand at the
 * current sales velocity, minus what is already on the shelf, floored at the
 * minimum order quantity and rounded up to the order multiple:
 *
 *   need = velocity * (leadTimeDays + targetCoverDays) - currentStock
 *   qty  = roundUp( max(ceil(need), minOrderQty), roundTo )   (0 when need <= 0)
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.Replenishment}.
 */
class Replenishment {

    static final String CONFIG_PATH = "/etc/commerce/config/reorder.yml"

    /**
     * Suggested reorder quantity (>= 0). Returns 0 when stock already covers the
     * target horizon or velocity is unknown/zero.
     */
    static int suggest(Double perDay, Integer currentStock, Map cfg) {
        if (perDay == null || perDay <= 0) {
            return 0
        }
        int stock = currentStock == null ? 0 : currentStock
        int leadTime = intOr(cfg?.leadTimeDays, 7)
        int targetCover = intOr(cfg?.targetCoverDays, 14)
        int minOrderQty = Math.max(0, intOr(cfg?.minOrderQty, 1))
        int roundTo = Math.max(1, intOr(cfg?.roundTo, 1))

        double need = perDay * (leadTime + targetCover) - stock
        if (need <= 0) {
            return 0
        }
        int qty = (int) Math.ceil(need)
        if (qty < minOrderQty) {
            qty = minOrderQty
        }
        if (qty % roundTo != 0) {
            qty = (((int) (qty / roundTo)) + 1) * roundTo
        }
        return qty
    }

    /** Days of stock cover remaining at the current velocity (null when unknown). */
    static Double coverDays(Integer currentStock, Double perDay) {
        if (perDay == null || perDay <= 0 || currentStock == null) {
            return null
        }
        return Math.round((currentStock / perDay) * 10) / 10.0d
    }

    private static int intOr(v, int dflt) {
        if (v == null) return dflt
        if (v instanceof Number) return ((Number) v).intValue()
        try { return Integer.parseInt(v.toString().trim()) } catch (Exception e) { return dflt }
    }
}
