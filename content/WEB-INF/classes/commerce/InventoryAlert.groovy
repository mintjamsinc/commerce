package commerce

/**
 * Inventory-alert pending queue, edge-trigger state, and the pure transition decision.
 *
 * The alert trigger is the {@code inventory_levels/update} webhook. To collapse bursts,
 * each update only marks the item PENDING; a short-period timer sweep
 * ({@code sweepInventoryAlerts.groovy}) evaluates pending items. Evaluation is
 * EDGE-triggered on a per-item alert state, so an alert fires only on the ok→low
 * transition, never re-fires while the item stays low, and re-arms on recovery.
 *
 * Storage:
 *   /content/commerce/inventory/pending/{inventory_item_id}.json   { inventory_item_id, at }
 *   /content/commerce/inventory/state/{inventory_item_id}.json
 *       { inventory_item_id, alertState: "ok"|"low", lastEvaluatedTotal,
 *         threshold, thresholdSource, thresholdRule, evaluatedAt }
 *   The threshold trio is the EFFECTIVE threshold the sweep resolved (manual / rule /
 *   default / policy-default), recorded so the review form can show the same value the
 *   alert decision used rather than re-resolving rules client-side.
 *
 * The JCR methods are DEFENSIVE (a bookkeeping failure must never break the sweep or the
 * inventory route); {@link #decide} is PURE and unit-testable. Config (the YAML at
 * {@link #CONFIG_PATH}) is parsed by the caller with the YAML binding and passed in, as
 * the other commerce.* classes do.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.InventoryAlert}.
 */
class InventoryAlert {

    static final String PENDING_DIR = "/content/commerce/inventory/pending"
    static final String STATE_DIR   = "/content/commerce/inventory/state"
    static final String CONFIG_PATH = "/etc/commerce/config/inventory-alert.yml"
    static final String SWEEP_STATE_PATH = "/content/commerce/inventory/sweep-state.json"

    static final String STATE_OK  = "ok"
    static final String STATE_LOW = "low"

    // --- Pending queue ---------------------------------------------------------

    /** Mark an inventory item as needing evaluation (upsert a marker). Defensive. */
    static void markPending(session, log, inventoryItemId) {
        def id = inventoryItemId?.toString()
        if (!id) {
            return
        }
        try {
            def res = Jcr.getOrCreateFile(session, "${PENDING_DIR}/${id}.json".toString())
            res.write(Jcr.toJson([inventory_item_id: id, at: java.time.Instant.now().toString()]))
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("InventoryAlert.markPending ${id}: ${e.message}") } catch (Exception ignore) {}
        }
    }

    /**
     * Stage a pending marker WITHOUT committing — the caller batches the commit (e.g. a large
     * bulk reconcile). Same marker as {@link #markPending} but leaves the commit to the caller.
     */
    static boolean writePending(session, log, inventoryItemId) {
        def id = inventoryItemId?.toString()
        if (!id) {
            return false
        }
        try {
            def res = Jcr.getOrCreateFile(session, "${PENDING_DIR}/${id}.json".toString())
            res.write(Jcr.toJson([inventory_item_id: id, at: java.time.Instant.now().toString()]))
            return true
        } catch (Exception e) {
            try { log.warn("InventoryAlert.writePending ${id}: ${e.message}") } catch (Exception ignore) {}
            return false
        }
    }

    /** The inventory_item_ids currently pending evaluation (pending marker basenames). */
    static List pendingItemIds(session) {
        def out = []
        def base = Jcr.safeGet(session, PENDING_DIR)
        if (base == null || !base.exists()) {
            return out
        }
        try {
            def it = base.list()
            while (it.hasNext()) {
                def child = it.next()
                def name = child.getName()
                if (name != null && name.endsWith(".json")) {
                    out << name.substring(0, name.length() - 5)
                }
            }
        } catch (Exception ignore) {}
        return out
    }

    /** Remove a pending marker. Call BEFORE evaluating (delete-before-evaluate). Defensive. */
    static void clearPending(session, log, inventoryItemId) {
        def id = inventoryItemId?.toString()
        if (!id) {
            return
        }
        try {
            def res = Jcr.safeGet(session, "${PENDING_DIR}/${id}.json".toString())
            if (res != null && res.exists()) {
                res.remove()
                session.commit()
            }
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("InventoryAlert.clearPending ${id}: ${e.message}") } catch (Exception ignore) {}
        }
    }

    // --- Edge-trigger state ----------------------------------------------------

    /** Read [alertState, lastEvaluatedTotal] for an item; defaults to ["ok", null]. */
    static Map readState(session, inventoryItemId) {
        def id = inventoryItemId?.toString()
        def doc = (id == null) ? [:] : Jcr.readMap(session, "${STATE_DIR}/${id}.json".toString())
        def st = doc?.alertState?.toString()
        return [
            alertState        : (st == STATE_LOW ? STATE_LOW : STATE_OK),
            lastEvaluatedTotal: intOrNull(doc?.lastEvaluatedTotal),
        ]
    }

    /** Persist the alert state for an item, including the effective threshold. Defensive. */
    static void writeState(session, log, inventoryItemId, String alertState, Integer total,
                           Integer threshold, String thresholdSource, String thresholdRule) {
        def id = inventoryItemId?.toString()
        if (!id) {
            return
        }
        try {
            def res = Jcr.getOrCreateFile(session, "${STATE_DIR}/${id}.json".toString())
            res.write(Jcr.toJson([
                inventory_item_id : id,
                alertState        : (alertState == STATE_LOW ? STATE_LOW : STATE_OK),
                lastEvaluatedTotal: total,
                threshold         : threshold,
                thresholdSource   : thresholdSource,
                thresholdRule     : thresholdRule,
                evaluatedAt       : java.time.Instant.now().toString(),
            ]))
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("InventoryAlert.writeState ${id}: ${e.message}") } catch (Exception ignore) {}
        }
    }

    // --- Config (caller parses the YAML and passes the map) --------------------

    /** unconfiguredPolicy: "prompt" (default) | "default" | "silent". */
    static String unconfiguredPolicy(Map cfg) {
        def p = cfg?.unconfiguredPolicy?.toString()?.trim()?.toLowerCase()
        return (p == "default" || p == "silent") ? p : "prompt"
    }

    /** Threshold used when unconfiguredPolicy == "default" (else null). */
    static Integer defaultThreshold(Map cfg) {
        return intOrNull(cfg?.defaultThreshold)
    }

    /**
     * Debounce window for the sweep, in seconds. 0 (the default) evaluates on every sweep
     * heartbeat; a larger value spaces sweeps out so bursts coalesce into one evaluation.
     * Values at or below the heartbeat behave like 0. See sweepInventoryAlerts.groovy.
     */
    static int sweepDebounceSeconds(Map cfg) {
        def v = intOrNull(cfg?.sweepDebounceSeconds)
        return (v == null || v < 0) ? 0 : v
    }

    // --- Sweep pacing (debounce) ----------------------------------------------

    /** Epoch millis of the last sweep that actually ran, or 0 if none recorded. Defensive. */
    static long lastSweepAtMillis(session) {
        try {
            def doc = Jcr.readMap(session, SWEEP_STATE_PATH)
            def v = doc?.lastSweepAt
            if (v instanceof Number) return ((Number) v).longValue()
            if (v != null) return Long.valueOf(v.toString().trim())
        } catch (Exception ignore) {}
        return 0L
    }

    /** Record the wall-clock (epoch millis) of a sweep that ran. Defensive. */
    static void recordSweepAt(session, log, long millis) {
        try {
            def res = Jcr.getOrCreateFile(session, SWEEP_STATE_PATH)
            res.write(Jcr.toJson([lastSweepAt: millis]))
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("InventoryAlert.recordSweepAt: ${e.message}") } catch (Exception ignore) {}
        }
    }

    // --- Pure edge-trigger decision -------------------------------------------

    /**
     * Decide the action for an item from its previous alertState, the current total, and
     * the effective threshold (null = not monitored). Returns [action, newState]:
     *   "fire"    : ok→low — raise a Manual Inventory Check. newState "low".
     *   "hold"    : low→low — still low; do NOT re-alert (policy A). newState "low".
     *   "recover" : low→ok — stock recovered; re-arm. newState "ok".
     *   "ok"      : ok→ok — fine. newState "ok".
     *   "none"    : threshold == null — not monitored. newState = prevState.
     *
     * alertState reflects the LAST state we acted on (not Shopify's live state), so we
     * still fire on ok→low however many intermediate updates were missed, and never
     * flap. Task completion does NOT reset the state, so a still-low item is not re-raised.
     */
    static List decide(String prevState, Integer total, Integer threshold) {
        boolean wasLow = (prevState == STATE_LOW)
        if (threshold == null) {
            return ["none", wasLow ? STATE_LOW : STATE_OK]
        }
        boolean low = (total != null && total < threshold)
        if (low) {
            return wasLow ? ["hold", STATE_LOW] : ["fire", STATE_LOW]
        }
        return wasLow ? ["recover", STATE_OK] : ["ok", STATE_OK]
    }

    // --- Helpers ---------------------------------------------------------------

    private static Integer intOrNull(v) {
        if (v == null) return null
        if (v instanceof Number) return ((Number) v).intValue()
        try { return Integer.valueOf(v.toString().trim()) } catch (Exception e) { return null }
    }
}
