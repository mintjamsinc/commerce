package commerce

import com.fasterxml.jackson.databind.ObjectMapper
import javax.jcr.query.Query

import commerce.migration.Migrations

/**
 * GDPR compliance engine (mandatory: the platform stores PII the
 * moment an order is ingested). Handles Shopify's three compliance topics:
 *
 *   customers/redact       → {@link #redactCustomer}   anonymize-and-keep
 *   customers/data_request → {@link #dataRequest}      collect held PII into a report
 *   shop/redact            → {@link #shopRedact}       erase the shop's data wholesale
 *
 * <h2>Redaction policy (anonymize, don't delete)</h2>
 * Records are KEPT for accounting / legal-claim purposes (GDPR art. 17(3)); only
 * the personally identifying fields are dummied out. What stays vs. goes:
 *
 *   KEEP  : order/refund/variant ids, quantities, amounts, taxes, timestamps,
 *           country/province-level region (tax + statistics)
 *   REDACT: names, emails, phone numbers, street-level address (address1/2, city,
 *           zip, company, coordinates), client IPs, the raw Shopify customer JSON
 *
 * The customer node is
 * reduced to a SHELL ({@code commerce:status=redacted} + dummy identity), never
 * deleted — that keeps order→customer references intact, leaves an audit trace,
 * and makes a duplicate redact webhook a no-op (idempotency).
 *
 * All methods are defensive per store (one bad record never aborts the run) but
 * report per-store counters so the operator notification shows what happened.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.Gdpr}.
 */
class Gdpr {

    static final String CONTENT_DIR    = "/content/commerce"
    static final String CUSTOMERS_DIR  = "/content/commerce/customers"
    static final String CRM_DIR        = "/content/commerce/crm/customers"     // legacy pre-migration location
    static final String ORDERS_DIR     = "/content/commerce/orders/raw"
    static final String REFUNDS_DIR    = "/content/commerce/refunds/raw"
    static final String BACKORDERS_DIR = "/content/commerce/backorders"
    static final String ENTITIES_DIR   = "/content/commerce/entities"
    static final String EVENTS_DIR     = "/content/commerce/events"
    static final String REQUESTS_DIR   = "/content/commerce/gdpr/data-requests"
    static final String PUBLIC_CATALOG = "/content/public/commerce/catalog"

    static final String REDACTED = "GDPR_REDACTED"

    private static final ObjectMapper MAPPER = new ObjectMapper()

    // Address-shaped subtrees keep only the coarse region; everything else in
    // them is street-level PII.
    private static final java.util.Set ADDRESS_KEEP = [
        "country", "country_code", "country_name", "province", "province_code",
    ] as java.util.Set

    // Subtree keys (at any depth) that are address-shaped.
    private static final java.util.Set ADDRESS_KEYS = [
        "billing_address", "shipping_address", "default_address",
    ] as java.util.Set

    // Scalar keys (at any depth) that are directly identifying.
    private static final java.util.Set SCALAR_PII_KEYS = [
        "email", "contact_email", "phone", "browser_ip",
    ] as java.util.Set

    // --- customers/redact --------------------------------------------------------

    /**
     * Anonymize everything held for one customer. {@code payload} is the Shopify
     * compliance webhook body: { customer: { id, email, phone }, orders_to_redact:
     * [ids] }. Returns per-store counters (also persisted in the shell + used for
     * the operator notification).
     */
    static Map redactCustomer(session, log, Map payload) {
        def customerId = payload?.customer?.id?.toString()
        def email = blankToNull(payload?.customer?.email)
        def orderIds = ((payload?.orders_to_redact instanceof List) ? payload.orders_to_redact : [])
            .collect { it?.toString() }.findAll { it }
        def dummyEmail = dummyEmail(customerId, email)

        // Idempotency: a second redact webhook for an already-shelled customer is a no-op.
        def shellRes = customerId ? Jcr.safeGet(session, "${CUSTOMERS_DIR}/customer_${customerId}.json".toString()) : null
        if (shellRes != null && shellRes.exists() && propStr(shellRes, "commerce:status") == "redacted") {
            log.info("Gdpr.redactCustomer: customer ${customerId} already redacted - no-op")
            return [ok: true, alreadyRedacted: true, customerId: customerId]
        }

        int orders = 0, refunds = 0, backorders = 0, checkouts = 0, entities = 0, events = 0
        def redactedOrderIds = [] as java.util.LinkedHashSet

        // 1. Orders — the ids Shopify names, plus anything else carrying the email.
        orderIds.each { oid ->
            if (redactOrderById(session, log, oid, dummyEmail)) { orders++; redactedOrderIds << oid }
        }
        queryByProp(session, log, ORDERS_DIR, "commerce:customer_email", email).each { res ->
            def oid = propStr(res, "commerce:order_id")
            if (redactOrderResource(session, log, res, dummyEmail)) { orders++; if (oid) redactedOrderIds << oid }
        }

        // 2. Refunds referencing the redacted orders (payloads embed order fields).
        redactedOrderIds.each { oid ->
            queryByProp(session, log, REFUNDS_DIR, "commerce:order_id", oid).each { res ->
                if (scrubBody(session, log, res, dummyEmail)) refunds++
            }
        }

        // 3. Backorders (typed commerce:customer_email axis + body field).
        queryByProp(session, log, BACKORDERS_DIR, "commerce:customer_email", email).each { res ->
            if (redactBackorder(session, log, res, dummyEmail)) backorders++
        }
        redactedOrderIds.each { oid ->
            queryByProp(session, log, BACKORDERS_DIR, "commerce:order_id", oid).each { res ->
                if (redactBackorder(session, log, res, dummyEmail)) backorders++
            }
        }

        // 4. Generic entity mirrors: checkouts (and anything else carrying the
        //    email), plus the customer's own entity record.
        queryByProp(session, log, ENTITIES_DIR, "commerce:customer_email", email).each { res ->
            if (scrubBody(session, log, res, dummyEmail)) {
                setProps(session, log, res, ["commerce:customer_email": dummyEmail])
                checkouts++
            }
        }
        if (customerId) {
            eachChild(session, ENTITIES_DIR) { srcFolder ->
                def res = Jcr.safeGet(session, "${srcFolder.getPath()}/customers/${customerId}.json".toString())
                if (res != null && res.exists()) {
                    if (emptyBody(session, log, res)) {
                        setProps(session, log, res, ["commerce:customer_email": dummyEmail, "commerce:status": "redacted"])
                        entities++
                    }
                }
            }
        }

        // 5. Event log — raw payloads. Redaction is rare, so a full scan with a
        //    cheap contains() pre-filter is acceptable.
        events = redactEvents(session, log, customerId, email, redactedOrderIds, dummyEmail)

        // 6. Legacy CRM records (from the old pre-migration store) are derived data: drop them.
        dropLegacyCrm(session, log, customerId, email)

        // 7. The customer node becomes a shell (created if the store never saw
        //    this customer, so the redact itself leaves an audit trace).
        writeShell(session, log, customerId, email, dummyEmail)

        def summary = [ok: true, customerId: customerId, orders: orders, refunds: refunds,
                       backorders: backorders, checkouts: checkouts, entities: entities, events: events]
        log.info("Gdpr.redactCustomer: ${Jcr.toJson(summary)}")
        return summary
    }

    // --- customers/data_request ---------------------------------------------------

    /**
     * Collect every record held for the customer into a report the merchant can
     * hand over: { customer, orders, refunds, backorders, checkouts }. The report
     * is stored under {@link #REQUESTS_DIR} (admin-only) and its path returned.
     */
    static Map dataRequest(session, log, Map payload) {
        def customerId = payload?.customer?.id?.toString()
        def email = blankToNull(payload?.customer?.email)
        def requestId = payload?.data_request?.id?.toString()
        def orderIds = ((payload?.orders_requested instanceof List) ? payload.orders_requested : [])
            .collect { it?.toString() }.findAll { it }

        def orders = []
        orderIds.each { oid ->
            def res = Orders.findResource(session, oid)
            if (res != null) orders << Jcr.readMap(session, res.getPath())
        }
        queryByProp(session, log, ORDERS_DIR, "commerce:customer_email", email).each { res ->
            def doc = Jcr.readMap(session, res.getPath())
            if (doc && !orders.any { it?.id?.toString() == doc?.id?.toString() }) orders << doc
        }

        def refunds = []
        orders.each { o ->
            queryByProp(session, log, REFUNDS_DIR, "commerce:order_id", o?.id?.toString()).each { res ->
                refunds << Jcr.readMap(session, res.getPath())
            }
        }

        def backorders = []
        queryByProp(session, log, BACKORDERS_DIR, "commerce:customer_email", email).each { res ->
            backorders << Jcr.readMap(session, res.getPath())
        }

        def checkouts = []
        queryByProp(session, log, ENTITIES_DIR, "commerce:customer_email", email).each { res ->
            checkouts << Jcr.readMap(session, res.getPath())
        }

        def customer = [:]
        if (customerId) {
            customer = Jcr.readMap(session, "${CUSTOMERS_DIR}/customer_${customerId}.json".toString())
            if (customer.isEmpty()) {
                eachChild(session, ENTITIES_DIR) { srcFolder ->
                    if (customer.isEmpty()) {
                        customer = Jcr.readMap(session, "${srcFolder.getPath()}/customers/${customerId}.json".toString())
                    }
                }
            }
        }

        // Month fold in UTC — the shared storage fold rule (server-timezone independent).
        def now = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        def ym = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM"))
        def name = "request_${sanitize(requestId ?: customerId ?: (email ?: 'unknown'))}_${System.currentTimeMillis()}.json"
        def path = "${REQUESTS_DIR}/${ym}/${name}".toString()
        def report = [
            requestId : requestId,
            customerId: customerId,
            email     : email,
            createdAt : Api.now(),
            customer  : customer,
            orders    : orders,
            refunds   : refunds,
            backorders: backorders,
            checkouts : checkouts,
        ]
        def res = Jcr.getOrCreateFile(session, path)
        res.write(MAPPER.writeValueAsString(report))
        res.setProperty("commerce:status", "received")
        res.setProperty("commerce:customer_id", str(customerId))
        res.setProperty("commerce:created_at", new java.util.Date())
        session.commit()

        def summary = [ok: true, path: path, customerId: customerId, orders: orders.size(),
                       refunds: refunds.size(), backorders: backorders.size(), checkouts: checkouts.size()]
        log.info("Gdpr.dataRequest: ${Jcr.toJson(summary)}")
        return summary
    }

    // --- shop/redact ---------------------------------------------------------------

    /**
     * Erase the shop's data wholesale: every store under /content/commerce plus the
     * public catalog projection. Sent by Shopify 48h after the app is uninstalled.
     * Config under /etc/commerce is left in place (it is the operator's, not the
     * shop's data). Deletes child-by-child with per-child commits to bound
     * transaction size.
     */
    static Map shopRedact(session, log) {
        int removed = 0
        def base = Jcr.safeGet(session, CONTENT_DIR)
        if (base != null && base.exists()) {
            def names = []
            try { def it = base.list(); while (it.hasNext()) { names << it.next().getPath() } } catch (Exception ignore) {}
            names.each { p ->
                if (Migrations.hardDelete(session, log, p)) removed++
            }
        }
        if (Migrations.hardDelete(session, log, PUBLIC_CATALOG)) removed++
        def summary = [ok: true, removedRoots: removed]
        log.info("Gdpr.shopRedact: ${Jcr.toJson(summary)}")
        return summary
    }

    // --- PII scrubbing (pure JSON transforms) --------------------------------------

    /**
     * Scrub a parsed JSON tree in place: dummy the scalar PII keys, reduce
     * address subtrees to region, and reduce embedded customer objects to
     * { id, dummy identity }. Returns the same (mutated) structure.
     */
    static Object scrubTree(node, String dummyEmail) {
        if (node instanceof List) {
            node.eachWithIndex { v, i -> node[i] = scrubTree(v, dummyEmail) }
            return node
        }
        if (!(node instanceof Map)) {
            return node
        }
        def keys = new ArrayList(node.keySet())
        keys.each { k ->
            def key = k.toString()
            def v = node[k]
            if (ADDRESS_KEYS.contains(key) && v instanceof Map) {
                node[k] = scrubAddress(v)
            } else if (key == "addresses" && v instanceof List) {
                node[k] = v.collect { it instanceof Map ? scrubAddress(it) : it }
            } else if (key == "customer" && v instanceof Map) {
                node[k] = scrubCustomerObject(v, dummyEmail)
            } else if (SCALAR_PII_KEYS.contains(key)) {
                if (v != null) node[k] = (key == "email" || key == "contact_email") ? dummyEmail : null
            } else if (v instanceof Map || v instanceof List) {
                node[k] = scrubTree(v, dummyEmail)
            }
        }
        return node
    }

    /** Keep only the coarse region of an address; dummy/clear the street level. */
    static Map scrubAddress(Map addr) {
        def out = [:]
        addr.each { k, v ->
            if (ADDRESS_KEEP.contains(k.toString())) out[k] = v
        }
        return out
    }

    /** Reduce an embedded Shopify customer object to a referenceable dummy. */
    static Map scrubCustomerObject(Map c, String dummyEmail) {
        return [
            id        : c?.id,
            email     : dummyEmail,
            first_name: REDACTED,
            last_name : REDACTED,
        ]
    }

    /** The unique-but-anonymous placeholder email for a redacted customer. */
    static String dummyEmail(String customerId, String email) {
        def key = customerId ?: (email == null ? "unknown" : Integer.toHexString(email.toLowerCase().hashCode()))
        return "redacted_${key}@example.com".toString()
    }

    // --- Per-store redaction helpers -----------------------------------------------

    private static boolean redactOrderById(session, log, String orderId, String dummyEmail) {
        try {
            def res = Orders.findResource(session, orderId)
            return res == null ? false : redactOrderResource(session, log, res, dummyEmail)
        } catch (Exception e) {
            try { log.warn("Gdpr: order ${orderId}: ${e.message}") } catch (Exception ignore) {}
            return false
        }
    }

    private static boolean redactOrderResource(session, log, res, String dummyEmail) {
        if (!scrubBody(session, log, res, dummyEmail)) return false
        setProps(session, log, res, ["commerce:customer_email": dummyEmail])
        return true
    }

    private static boolean redactBackorder(session, log, res, String dummyEmail) {
        try {
            def doc = Jcr.readMap(session, res.getPath())
            if (doc.containsKey("customer_email")) doc.customer_email = dummyEmail
            res.write(MAPPER.writeValueAsString(scrubTree(doc, dummyEmail)))
            res.setProperty("commerce:customer_email", dummyEmail)
            markRedacted(res)
            session.commit()
            return true
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Gdpr: backorder ${res.getPath()}: ${e.message}") } catch (Exception ignore) {}
            return false
        }
    }

    /** Scrub a JSON file body in place; marks the node redacted. */
    private static boolean scrubBody(session, log, res, String dummyEmail) {
        try {
            if (propBool(res, "commerce:gdpr_redacted")) return false   // already done
            def doc = Jcr.readMap(session, res.getPath())
            if (doc.isEmpty()) return false
            res.write(MAPPER.writeValueAsString(scrubTree(doc, dummyEmail)))
            markRedacted(res)
            session.commit()
            return true
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Gdpr: scrub ${res.getPath()}: ${e.message}") } catch (Exception ignore) {}
            return false
        }
    }

    /** Replace a JSON file body with {} (the raw customer mirror). */
    private static boolean emptyBody(session, log, res) {
        try {
            res.write("{}")
            markRedacted(res)
            session.commit()
            return true
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Gdpr: empty ${res.getPath()}: ${e.message}") } catch (Exception ignore) {}
            return false
        }
    }

    private static int redactEvents(session, log, String customerId, String email, java.util.Collection orderIds, String dummyEmail) {
        int touched = 0
        def needles = []
        if (email) needles << email
        if (customerId) needles << customerId
        needles.addAll(orderIds ?: [])
        if (needles.isEmpty()) return 0
        eachChild(session, EVENTS_DIR) { srcFolder ->
            eachChild(session, srcFolder.getPath()) { yearFolder ->
                if (!(yearFolder.getName() ==~ /\d{4}/)) return
                eachChild(session, yearFolder.getPath()) { monthFolder ->
                    eachChild(session, monthFolder.getPath()) { res ->
                        try {
                            if (!res.getName().endsWith(".json")) return
                            if (propBool(res, "commerce:gdpr_redacted")) return
                            def content = res.content?.toString()
                            if (content == null || !needles.any { content.contains(it.toString()) }) return
                            def doc = Jcr.parseMap(content)
                            if (doc.isEmpty()) return
                            if (doc.payload != null) doc.payload = scrubTree(doc.payload, dummyEmail)
                            res.write(MAPPER.writeValueAsString(doc))
                            markRedacted(res)
                            session.commit()
                            touched++
                        } catch (Exception e) {
                            try { session.rollback() } catch (Exception ignore) {}
                            try { log.warn("Gdpr: event ${res.getPath()}: ${e.message}") } catch (Exception ignore) {}
                        }
                    }
                }
            }
        }
        return touched
    }

    private static void dropLegacyCrm(session, log, String customerId, String email) {
        def keys = []
        if (customerId) keys << "id_${customerId}"
        if (email) keys << "email_${email.toLowerCase().replaceAll('[^A-Za-z0-9_.-]', '_')}"
        keys.each { key ->
            Migrations.hardDelete(session, log, "${CRM_DIR}/${key}.json".toString())
        }
    }

    private static void writeShell(session, log, String customerId, String email, String dummyEmail) {
        def name = customerId ? "customer_${customerId}.json"
            : "customer_email_${(email ?: 'unknown').toLowerCase().replaceAll('[^A-Za-z0-9_.-]', '_')}.json"
        try {
            def res = Jcr.getOrCreateFile(session, "${CUSTOMERS_DIR}/${name}".toString())
            res.write("{}")
            res.setProperty("commerce:status", "redacted")
            res.setProperty("commerce:redacted_at", new java.util.Date())
            res.setProperty("commerce:customer_id", str(customerId))
            res.setProperty("commerce:email", dummyEmail)
            res.setProperty("commerce:name", REDACTED)
            res.setProperty("commerce:marketing_enabled", false)
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Gdpr.writeShell ${name}: ${e.message}") } catch (Exception ignore) {}
        }
    }

    // --- Small helpers -------------------------------------------------------------

    private static void markRedacted(res) {
        res.setProperty("commerce:gdpr_redacted", true)
        res.setProperty("commerce:redacted_at", new java.util.Date())
    }

    /** XPath property-equality query under a subtree. Empty list on any failure. */
    private static List queryByProp(session, log, String root, String prop, String value) {
        if (!value) return []
        try {
            def v = value.replace("'", "''")
            def stmt = "/jcr:root${root}//element(*, nt:file)[@${prop}='${v}']"
            def q = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
            def resources = q.execute().getResources()
            return resources == null ? [] : (resources as List)
        } catch (Exception e) {
            try { log.warn("Gdpr.queryByProp ${root} ${prop}: ${e.message}") } catch (Exception ignore) {}
            return []
        }
    }

    private static void eachChild(session, String path, Closure cb) {
        def base = (path instanceof CharSequence) ? Jcr.safeGet(session, path.toString()) : path
        if (base == null || !base.exists()) return
        try {
            def it = base.list()
            while (it.hasNext()) {
                def c = it.next()
                try { cb(c) } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
    }

    private static void setProps(session, log, res, Map props) {
        try {
            props.each { k, v -> res.setProperty(k.toString(), v == null ? "" : v.toString()) }
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Gdpr.setProps ${res.getPath()}: ${e.message}") } catch (Exception ignore) {}
        }
    }

    private static String propStr(res, String name) {
        try { if (res.hasProperty(name)) return res.getProperty(name).getValue()?.toString() } catch (Exception ignore) {}
        return null
    }

    private static boolean propBool(res, String name) {
        def v = propStr(res, name)
        return v != null && v.toString().equalsIgnoreCase("true")
    }

    private static String blankToNull(v) {
        def s = v?.toString()?.trim()
        return (s == null || s.isEmpty()) ? null : s
    }

    private static String sanitize(String s) { s == null ? "" : s.replaceAll("[^A-Za-z0-9_.-]", "_") }
    private static String str(v) { v == null ? "" : v.toString() }
}
