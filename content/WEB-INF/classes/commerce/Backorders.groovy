package commerce

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Backorder / pre-order management.
 *
 * A <em>backorder</em> is a line-level record that a paid order could not be — or
 * should not yet be — fulfilled from on-hand stock, and is therefore waiting for
 * stock. Two causes:
 *
 *   shortfall : the ordered quantity for a stock-tracked variant exceeds the
 *               aggregate available stock at the moment the order was received.
 *   preorder  : the product is flagged as a pre-order (a configured tag), so it is
 *               intentionally sold ahead of stock regardless of current availability.
 *
 * A backorder is its own resource with its own {@code commerce:status} lifecycle —
 * the same modelling as refunds and purchase orders, so operators read it the same
 * way:
 *
 *   backordered -> ready -> released        (stock arrived, operator released it)
 *   backordered -> cancelled                (order refunded / cancelled)
 *   *           -> error                    (processing failure)
 *
 * Storage (created at detection time, keyed by order + line so it is idempotent):
 *   /content/commerce/backorders/{yyyy}/{MM}/backorder_{orderId}_{lineItemId}.json
 *
 * Design (mirrors the other commerce.* classes):
 *   - {@link #detect} is <b>pure</b>: it decides which order lines need a backorder
 *     from already-resolved maps the caller supplies (variant→item, available,
 *     pre-order items), so it is unit-testable and reusable across backends.
 *   - the JCR methods (create / find / mark*) are <b>defensive</b>: a read/write
 *     error is logged and swallowed so a bookkeeping failure never breaks the
 *     webhook route or the release workflow.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.Backorders}.
 */
class Backorders {

    static final String BASE_DIR = "/content/commerce/backorders"

    /** Statuses for a backorder that is still awaiting stock / handling. */
    static final List OPEN_STATUSES = ["backordered", "ready"]

    private static final ObjectMapper MAPPER = new ObjectMapper()
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy/MM")

    // -------------------------------------------------------------------------
    // Pure decision
    // -------------------------------------------------------------------------

    /**
     * Decide which lines of an order need a backorder. PURE — no JCR access.
     *
     * @param order           parsed Shopify order map (uses line_items + order meta)
     * @param variantToItem   variantId(String) -> inventory_item_id(String). Lines
     *                        whose variant is not in this map are skipped (the
     *                        product/variant is unknown to us, so stock is unknown).
     * @param availableByItem inventory_item_id(String) -> available(int), containing
     *                        ONLY stock-tracked items (a key is present iff we hold
     *                        inventory levels for it). A shortfall is only raised for
     *                        tracked items, so an untracked shop never floods with
     *                        false backorders.
     * @param preorderItemIds inventory_item_ids whose product is flagged pre-order.
     * @return a list of backorder descriptor maps (one per backordered line), each:
     *         { order_id, order_number, customer_email, currency,
     *           line_item_id, variant_id, inventory_item_id, sku, title, variant_title,
     *           ordered_quantity, available_at_order, quantity (awaited units), reason }
     */
    static List<Map> detect(Map order, Map variantToItem, Map availableByItem, Set preorderItemIds) {
        def out = []
        def items = order?.line_items
        if (!(items instanceof List)) {
            return out
        }

        def orderId = order?.id?.toString()
        def orderNumber = (order?.order_number ?: order?.name)?.toString()
        def customerEmail = (order?.contact_email ?: order?.email)?.toString()
        def currency = order?.currency?.toString()
        def pre = preorderItemIds ?: ([] as Set)
        def v2i = variantToItem ?: [:]
        def avail = availableByItem ?: [:]

        items.each { li ->
            def lineItemId = li?.id?.toString()
            def variantId = li?.variant_id?.toString()
            if (!lineItemId || !variantId) {
                return
            }
            def itemId = v2i[variantId]?.toString()
            if (!itemId) {
                // Unknown variant (product not mirrored, or no inventory item) — we
                // cannot assess stock, so we do not fabricate a backorder.
                return
            }

            int ordered = intOr(li?.quantity, 0)
            if (ordered <= 0) {
                return
            }
            boolean tracked = avail.containsKey(itemId)
            int available = tracked ? intOr(avail[itemId], 0) : 0
            boolean isPreorder = pre.contains(itemId)

            String reason
            int awaited
            if (isPreorder) {
                // A pre-order holds the whole line until release, regardless of stock.
                reason = "preorder"
                awaited = ordered
            } else if (tracked && ordered > available) {
                reason = "shortfall"
                awaited = ordered - available
            } else {
                // Fully covered, or untracked and not a pre-order → no backorder.
                return
            }

            out << [
                order_id          : orderId,
                order_number      : orderNumber,
                customer_email    : customerEmail,
                currency          : currency,
                line_item_id      : lineItemId,
                variant_id        : variantId,
                inventory_item_id : itemId,
                sku               : li?.sku?.toString(),
                title             : li?.title?.toString(),
                variant_title     : li?.variant_title?.toString(),
                ordered_quantity  : ordered,
                available_at_order: available,
                quantity          : awaited,
                reason            : reason,
            ]
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Persistence (defensive)
    // -------------------------------------------------------------------------

    /** Deterministic record name for an order line (stable across re-runs). */
    static String recordName(orderId, lineItemId) {
        return "backorder_${orderId}_${lineItemId}.json".toString()
    }

    /**
     * Persist a backorder descriptor as a new {@code backordered} record. Idempotent:
     * if a record for the same order+line already exists (this or last month) it is
     * left untouched and {@code false} is returned. Defensive — never throws.
     *
     * @return true when a new record was created.
     */
    static boolean create(session, log, Map descriptor) {
        try {
            def orderId = descriptor?.order_id?.toString()
            def lineItemId = descriptor?.line_item_id?.toString()
            if (!orderId || !lineItemId) {
                return false
            }
            if (exists(session, orderId, lineItemId)) {
                return false
            }

            def now = LocalDate.now(ZoneId.systemDefault())
            def createdAt = Api.now()
            def path = "${BASE_DIR}/${now.format(YM)}/${recordName(orderId, lineItemId)}".toString()

            def record = [:]
            record.putAll(descriptor)
            record.id = "${orderId}_${lineItemId}".toString()
            record.status = "backordered"
            record.created_at = createdAt

            def res = Jcr.getOrCreateFile(session, path)
            res.write(Jcr.toJson(record))
            res.setProperty("commerce:status", "backordered")
            res.setProperty("commerce:reason", str(descriptor?.reason))
            res.setProperty("commerce:order_id", orderId)
            res.setProperty("commerce:order_number", str(descriptor?.order_number))
            res.setProperty("commerce:line_item_id", lineItemId)
            res.setProperty("commerce:variant_id", str(descriptor?.variant_id))
            res.setProperty("commerce:inventory_item_id", str(descriptor?.inventory_item_id))
            res.setProperty("commerce:quantity", (long) intOr(descriptor?.quantity, 0))
            res.setProperty("commerce:ordered_quantity", (long) intOr(descriptor?.ordered_quantity, 0))
            res.setProperty("commerce:customer_email", str(descriptor?.customer_email))
            res.setProperty("commerce:title", str(descriptor?.title))
            res.setProperty("commerce:sku", str(descriptor?.sku))
            res.setProperty("commerce:created_at", new java.util.Date())
            session.commit()
            log.info("Backorders: created ${path} (order ${orderId}, item ${descriptor?.inventory_item_id}, awaited ${descriptor?.quantity}, ${descriptor?.reason})")
            return true
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Backorders.create: ${e.message}") } catch (Exception ignore) {}
            return false
        }
    }

    /** True when a backorder record for this order+line exists (this or last month). */
    static boolean exists(session, orderId, lineItemId) {
        def name = recordName(orderId, lineItemId)
        def today = LocalDate.now(ZoneId.systemDefault())
        for (int i = 0; i <= 1; i++) {
            def path = "${BASE_DIR}/${today.minusMonths(i).format(YM)}/${name}".toString()
            def res = Jcr.safeGet(session, path)
            if (res != null && res.exists()) {
                return true
            }
        }
        return false
    }

    // -------------------------------------------------------------------------
    // Queries (defensive full-tree traversal)
    // -------------------------------------------------------------------------

    /**
     * Open ({@code backordered}) records for an inventory item, oldest first — the
     * order in which stock should be allocated to them (FIFO fairness). Each entry:
     *   { path, id, order_id, quantity(int), createdAt }
     */
    static List findOpenForItem(session, inventoryItemId) {
        def out = []
        if (inventoryItemId == null) {
            return out
        }
        def wanted = inventoryItemId.toString()
        eachRecord(session) { res ->
            try {
                if (prop(res, "commerce:status") != "backordered") return
                if (prop(res, "commerce:inventory_item_id") != wanted) return
                out << [
                    path     : res.getPath(),
                    id       : prop(res, "commerce:order_id") + "_" + prop(res, "commerce:line_item_id"),
                    order_id : prop(res, "commerce:order_id"),
                    quantity : intOr(prop(res, "commerce:quantity"), 0),
                    createdAt: isoProp(res, "commerce:created_at") ?: createdMs(res),
                ]
            } catch (Exception ignore) {}
        }
        out.sort { a, b -> (a.createdAt?.toString() ?: "") <=> (b.createdAt?.toString() ?: "") }
        return out
    }

    /** Records for an order in any of the given statuses. Each: { path, status }. */
    static List findForOrder(session, orderId, Collection statuses) {
        def out = []
        if (orderId == null) {
            return out
        }
        def wanted = orderId.toString()
        def states = (statuses ?: []) as Set
        eachRecord(session) { res ->
            try {
                if (prop(res, "commerce:order_id") != wanted) return
                def st = prop(res, "commerce:status")
                if (!states.isEmpty() && !states.contains(st)) return
                out << [path: res.getPath(), status: st]
            } catch (Exception ignore) {}
        }
        return out
    }

    /**
     * Cancel every open ({@code backordered}) record for an order — used when the
     * order is refunded/cancelled so pending backorders do not linger. Records that
     * are already {@code ready} (an operator is actively releasing them via a task)
     * are left for the operator to resolve. Defensive — never throws.
     *
     * @return the number of records cancelled.
     */
    static int cancelOpenForOrder(session, log, orderId, String reason) {
        int cancelled = 0
        findForOrder(session, orderId, ["backordered"]).each { rec ->
            if (markCancelled(session, log, rec.path, reason)) {
                cancelled++
            }
        }
        if (cancelled > 0) {
            try { log.info("Backorders: cancelled ${cancelled} open backorder(s) for order ${orderId} (${reason})") } catch (Exception ignore) {}
        }
        return cancelled
    }

    /** Mark a single record cancelled (status + cancelled_at + reason). Defensive. */
    static boolean markCancelled(session, log, String path, String reason) {
        try {
            def res = session.getResource(path)
            if (res == null || !res.exists()) {
                return false
            }
            def at = Api.now()
            res.setProperty("commerce:status", "cancelled")
            res.setProperty("commerce:cancelled_at", new java.util.Date())
            if (reason != null) res.setProperty("commerce:cancel_reason", reason)
            // Keep the JSON body in step with the properties.
            try {
                def doc = Jcr.readMap(session, path)
                if (!doc.isEmpty()) {
                    doc.status = "cancelled"
                    doc.cancelled_at = at
                    if (reason != null) doc.cancel_reason = reason
                    res.write(Jcr.toJson(doc))
                }
            } catch (Exception ignore) {}
            session.commit()
            return true
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Backorders.markCancelled: ${path}: ${e.message}") } catch (Exception ignore) {}
            return false
        }
    }

    /** Stamp a record released (released_at + body) — called by recordBackorderRelease. Defensive. */
    static boolean markReleased(session, log, String path) {
        try {
            def res = session.getResource(path)
            if (res == null || !res.exists()) {
                return false
            }
            def at = Api.now()
            res.setProperty("commerce:released_at", new java.util.Date())
            try {
                def doc = Jcr.readMap(session, path)
                if (!doc.isEmpty()) {
                    doc.released_at = at
                    res.write(Jcr.toJson(doc))
                }
            } catch (Exception ignore) {}
            session.commit()
            return true
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Backorders.markReleased: ${path}: ${e.message}") } catch (Exception ignore) {}
            return false
        }
    }

    /**
     * Counts by status across all records, plus the total awaited units still open
     * (backordered). Defensive. Shape:
     *   { total, openUnits, byStatus: { backordered, ready, released, cancelled, ... } }
     */
    static Map summary(session) {
        def byStatus = [:]
        long total = 0
        long openUnits = 0
        eachRecord(session) { res ->
            try {
                total++
                def st = prop(res, "commerce:status") ?: "unknown"
                byStatus[st] = ((byStatus[st] ?: 0L) as long) + 1L
                if (st == "backordered") {
                    openUnits += intOr(prop(res, "commerce:quantity"), 0)
                }
            } catch (Exception ignore) {}
        }
        return [total: total, openUnits: openUnits, byStatus: byStatus]
    }

    /**
     * The most recent open ({@code backordered}/{@code ready}) records, newest first,
     * for the admin endpoint. Each entry is the parsed record body plus its path.
     */
    static List list(session, Collection statuses, int limit) {
        def states = ((statuses ?: OPEN_STATUSES) as Set)
        def rows = []
        eachRecord(session) { res ->
            try {
                def st = prop(res, "commerce:status")
                if (!states.contains(st)) return
                def doc = Jcr.readMap(session, res.getPath())
                doc.path = res.getPath()
                if (doc.status == null) doc.status = st
                if (doc.created_at == null) doc.created_at = isoProp(res, "commerce:created_at")
                rows << doc
            } catch (Exception ignore) {}
        }
        rows.sort { a, b -> (b.created_at?.toString() ?: "") <=> (a.created_at?.toString() ?: "") }
        def page = limit > 0 && rows.size() > limit ? rows.subList(0, limit) : rows
        // Exit mapping (commerce.Api): the stored record keeps its snake_case
        // storage shape; the wire row is camelCase with GID ids, numeric
        // quantities and ms-precision ISO timestamps.
        return page.collect { wireRow(it) }
    }

    /** Stored backorder doc → the wire row (commerce.Api contract). */
    private static Map wireRow(Map doc) {
        def out = Api.camelize(doc)
        out.orderId = Api.gid("Order", out.orderId)
        out.lineItemId = Api.gid("LineItem", out.lineItemId)
        out.variantId = Api.gid("ProductVariant", out.variantId)
        out.inventoryItemId = Api.gid("InventoryItem", out.inventoryItemId)
        ["createdAt", "cancelledAt", "releasedAt"].each { k ->
            if (out[k] != null) out[k] = Api.instant(out[k])
        }
        ["orderedQuantity", "availableAtOrder", "quantity"].each { k ->
            if (out[k] != null) out[k] = Api.num(out[k])
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Walk every backorder record file (BASE_DIR/{yyyy}/{MM}/*.json), calling cb.
     * Defensive. Folders are matched by their numeric names ({yyyy} / {MM}) rather
     * than a resource type-check, so this stays portable across the resource API and
     * never descends into a stray file. Records can be arbitrarily old (an open
     * backorder may wait months for restock), so the whole tree is scanned.
     */
    private static void eachRecord(session, Closure cb) {
        def base = Jcr.safeGet(session, BASE_DIR)
        if (base == null || !base.exists()) {
            return
        }
        children(base).each { yearFolder ->
            if (!(yearFolder.getName() ==~ /\d{4}/)) return
            children(yearFolder).each { monthFolder ->
                if (!(monthFolder.getName() ==~ /\d{1,2}/)) return
                children(monthFolder).each { child ->
                    try {
                        if (child.getName().endsWith(".json")) {
                            cb(child)
                        }
                    } catch (Exception ignore) {}
                }
            }
        }
    }

    private static List children(resource) {
        def out = []
        try {
            def it = resource.list()
            while (it.hasNext()) { out << it.next() }
        } catch (Exception ignore) {}
        return out
    }

    private static String prop(res, String name) {
        try {
            if (res.hasProperty(name)) {
                return res.getProperty(name).getValue()?.toString()
            }
        } catch (Exception ignore) {}
        return null
    }

    // Date-typed properties read back as Calendar/Date; legacy nodes hold ISO
    // strings. Normalize either to an ISO-8601 string (null when absent).
    private static String isoProp(res, String name) {
        try {
            if (!res.hasProperty(name)) return null
            def v = res.getProperty(name).getValue()
            if (v == null) return null
            if (v instanceof java.util.Calendar) return v.getTime().toInstant().toString()
            if (v instanceof java.util.Date) return v.toInstant().toString()
            return v.toString()
        } catch (Exception ignore) {}
        return null
    }

    private static long createdMs(res) {
        try { return res.getCreated().getTime() } catch (Exception e) { return 0L }
    }

    private static String str(v) {
        return v == null ? "" : v.toString()
    }

    private static int intOr(v, int dflt) {
        if (v == null) return dflt
        if (v instanceof Number) return ((Number) v).intValue()
        try { return Integer.parseInt(v.toString().trim()) } catch (Exception e) { return dflt }
    }
}
