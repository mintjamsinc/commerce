package commerce

/**
 * Cross-location allocation planning for multi-location inventory.
 *
 * Given the available quantity of an item at each location and a quantity to
 * fulfil, produces an allocation plan — which locations to draw from and how
 * much from each — according to a strategy. This is decision support: it does
 * NOT override Shopify's own fulfillment routing, it advises operators / other
 * tooling where stock can come from.
 *
 * Strategies:
 *   priority   : draw from the configured priority locations first (in order),
 *                then any remaining locations by most stock.
 *   most_stock : draw from the location with the most stock first.
 *
 * A per-location safety stock is held back (not allocatable). Pure logic over
 * plain data; lives under /content/WEB-INF/classes; use via
 * {@code import commerce.Allocation}.
 */
class Allocation {

    /**
     * Plan an allocation of {@code qtyNeeded} units.
     *
     * @param availableByLocation locationId(String) -> available quantity (int)
     * @param cfg { strategy, priorityOrder (list or CSV of locationIds),
     *              defaultSafetyStock (int) }
     * @return [ requested, allocated, shortfall,
     *           allocations: [ [ locationId, qty ], ... ] ]   (only non-zero draws)
     */
    static Map plan(Map availableByLocation, int qtyNeeded, Map cfg) {
        int requested = Math.max(0, qtyNeeded)
        int safety = Math.max(0, intOr(cfg?.defaultSafetyStock, 0))
        String strategy = (cfg?.strategy ?: "most_stock").toString().trim().toLowerCase()
        def priority = asList(cfg?.priorityOrder).collect { it.toString().trim() }.findAll { it }

        // Allocatable (usable) quantity per location after holding back safety stock.
        def usable = [:]
        (availableByLocation ?: [:]).each { loc, avail ->
            int u = Math.max(0, intOr(avail, 0) - safety)
            if (u > 0) {
                usable[loc.toString()] = u
            }
        }

        // Order the locations per the strategy.
        def ordered
        if (strategy == "priority") {
            def head = priority.findAll { usable.containsKey(it) }
            def tail = usable.keySet().findAll { !priority.contains(it) }.sort { a, b -> usable[b] <=> usable[a] }
            ordered = head + tail
        } else {
            ordered = usable.keySet().sort { a, b -> usable[b] <=> usable[a] }
        }

        def allocations = []
        int remaining = requested
        ordered.each { loc ->
            if (remaining <= 0) {
                return
            }
            int take = Math.min(usable[loc], remaining)
            if (take > 0) {
                allocations << [locationId: loc, qty: take]
                remaining -= take
            }
        }

        int allocated = requested - remaining
        return [
            requested  : requested,
            allocated  : allocated,
            shortfall  : Math.max(0, requested - allocated),
            allocations: allocations,
        ]
    }

    // --- Helpers ---------------------------------------------------------------

    private static List asList(v) {
        if (v == null) return []
        if (v instanceof List) return v
        if (v instanceof String) return v.split(",").collect { it.trim() }.findAll { it }
        return [v]
    }

    private static int intOr(v, int dflt) {
        if (v == null) return dflt
        if (v instanceof Number) return ((Number) v).intValue()
        try { return Integer.parseInt(v.toString().trim()) } catch (Exception e) { return dflt }
    }
}
