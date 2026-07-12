package commerce

import javax.jcr.query.Query

/**
 * First-class customer store.
 *
 * One file per customer under {@code /content/commerce/customers/}:
 *
 *   customer_{id}.json           member (Shopify customer id known)
 *   customer_email_{hash}.json   guest (order-derived / GDPR shell only; body {})
 *
 * <b>Body = the raw Shopify customer JSON only</b> (same convention as the
 * product mirror). Lifetime figures (orders_count / total_spent) are read from
 * that mirror body, NOT recomputed here — this platform DISPLAYS Shopify's own
 * numbers and EDITS through the Admin API (parity with product-360). No wallet,
 * no segmentation, no VIP judgement (VIP is a manual Shopify tag). The only
 * things promoted to TYPED JCR properties (auto-indexed → browse/facets/GDPR
 * queries work) are the lifecycle + profile fields:
 *
 *   (a) lifecycle : commerce:status (received/deleted/redacted), commerce:customer_id,
 *                   commerce:source_status, commerce:updated_at (Date), commerce:redacted_at (Date)
 *   (b) profile   : commerce:email, commerce:name, commerce:marketing_consent,
 *                   commerce:marketing_enabled (Boolean), commerce:tags,
 *                   commerce:tax_exempt (Boolean), commerce:created_at (Date)
 *
 * Writers: customers/* webhooks upsert body+(a)(b) via {@link #upsertFromWebhook}
 * (also stamping the dedicated customer MIME type so the node launches the
 * customer editor); customers/delete marks the shell {@link #markDeleted}; GDPR
 * redaction (see {@link commerce.Gdpr}) reduces a node to a redacted shell.
 *
 * JCR methods are defensive. Lives under /content/WEB-INF/classes; use via
 * {@code import commerce.Customers}.
 */
class Customers {

    static final String STORE_DIR = "/content/commerce/customers"
    static final String CUSTOMER_MIME = "application/vnd.mintjams.commerce.customer+json"

    private static final int WRITE_RETRIES = 6

    // --- Keys -------------------------------------------------------------------

    /** Store key: customer_{id} for members, customer_email_{hash} for guests, null when neither. */
    static String keyFor(customerId, email) {
        def id = blank(customerId) ? null : customerId.toString().trim()
        if (id) return "customer_${id}".toString()
        def em = blank(email) ? null : email.toString().trim().toLowerCase()
        if (em) return "customer_email_${sanitize(em)}".toString()
        return null
    }

    static String pathFor(String key) { "${STORE_DIR}/${key}.json".toString() }

    // --- Writer: customers/* webhooks (body + (a)(b)) -----------------------------

    /**
     * Upsert a customer from a customers/create|update|enable|disable webhook:
     * body = the raw Shopify JSON, lifecycle/profile promoted to typed props and
     * the customer MIME type stamped. Only the KEEP profile props are written —
     * no wallet / segment data lives here. Defensive — returns the store key or null.
     */
    static String upsertFromWebhook(session, log, String payloadJson, Map customer) {
        def id = customer?.id?.toString()
        if (blank(id)) {
            log.warn("Customers.upsertFromWebhook: payload has no customer id - skipping")
            return null
        }
        def key = "customer_${id}".toString()
        def email = blank(customer?.email) ? null : customer.email.toString().trim()
        for (int attempt = 0; attempt < WRITE_RETRIES; attempt++) {
            try {
                def res = Jcr.getOrCreateFile(session, pathFor(key))
                res.write(payloadJson ?: "{}")
                res.setProperty("jcr:mimeType", CUSTOMER_MIME)
                res.setProperty("commerce:status", "received")
                res.setProperty("commerce:customer_id", id)
                if (customer.state != null) res.setProperty("commerce:source_status", customer.state.toString())
                setDate(res, "commerce:updated_at", customer.updated_at)
                setDate(res, "commerce:created_at", customer.created_at)
                if (email) res.setProperty("commerce:email", email)
                def name = [customer.first_name, customer.last_name].findAll { !blank(it) }.join(" ").trim()
                if (name) res.setProperty("commerce:name", name)
                def consent = customer.email_marketing_consent?.state?.toString()
                if (consent != null) res.setProperty("commerce:marketing_consent", consent)
                res.setProperty("commerce:marketing_enabled", consent == "subscribed")
                if (customer.tags != null) res.setProperty("commerce:tags", customer.tags.toString())
                res.setProperty("commerce:tax_exempt", customer.tax_exempt == true)
                session.commit()
                return key
            } catch (Exception e) {
                try { session.rollback() } catch (Exception ignore) {}
                if (attempt == WRITE_RETRIES - 1) {
                    try { log.warn("Customers.upsertFromWebhook ${key}: ${e.message}") } catch (Exception ignore) {}
                } else {
                    try { Thread.sleep(20L * (attempt + 1)) } catch (Exception ignore) {}
                }
            }
        }
        return null
    }

    /** Mark a customer deleted (customers/delete — parity with products/delete; ≠ GDPR redact). */
    static void markDeleted(session, log, customerId) {
        def key = keyFor(customerId, null)
        if (key == null) return
        try {
            def res = Jcr.safeGet(session, pathFor(key))
            if (res == null || !res.exists()) return
            res.setProperty("commerce:status", "deleted")
            res.setProperty("commerce:deletedAt", new java.util.Date())
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            try { log.warn("Customers.markDeleted ${customerId}: ${e.message}") } catch (Exception ignore) {}
        }
    }

    // --- Lookup / queries (endpoint) -----------------------------------------------

    /** Customer node by email: property query, falling back to the guest key. Null when none. */
    static Object findByEmail(session, String email) {
        if (blank(email)) return null
        try {
            def v = email.trim().replace("'", "''")
            def stmt = "/jcr:root${STORE_DIR}//element(*, nt:file)[@commerce:email='${v}']"
            def q = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
            q.limit(1)
            def r = q.execute().getResources()
            if (r != null && r.length > 0) return r[0]
        } catch (Exception ignore) {}
        def guest = Jcr.safeGet(session, pathFor(keyFor(null, email)))
        return (guest != null && guest.exists()) ? guest : null
    }

    /**
     * Partial-match customer search over name / email / customer id / store key
     * (case-insensitive contains), by name (fallback: newest updated first). Same
     * in-memory pass as the browse endpoint — the store is one flat folder and
     * this backs an admin UI.
     */
    static List search(session, String query, int limit) {
        def q = query == null ? "" : query.trim().toLowerCase()
        if (q.isEmpty()) return []
        def rows = []
        eachCustomer(session) { res ->
            try {
                def hay = [propStr(res, "commerce:email"), propStr(res, "commerce:name"),
                           propStr(res, "commerce:customer_id"), res.getName()]
                if (hay.any { it != null && it.toLowerCase().contains(q) }) {
                    rows << row(session, res)
                }
            } catch (Exception ignore) {}
        }
        rows.sort { a, b ->
            int c = (a.name ?: "").toString().toLowerCase() <=> (b.name ?: "").toString().toLowerCase()
            c != 0 ? c : ((b.updatedAt ?: "").toString() <=> (a.updatedAt ?: "").toString())
        }
        return limit > 0 && rows.size() > limit ? rows.subList(0, limit) : rows
    }

    /** One customer's record (props + raw mirror body) by store key. Empty map when absent. */
    static Map read(session, String key) {
        def res = Jcr.safeGet(session, pathFor(key))
        if (res == null || !res.exists()) return [:]
        def out = row(session, res)
        out.customer = Jcr.readMap(session, res.getPath())
        return out
    }

    /** The endpoint row shape for one customer node (KEEP profile props only). */
    // WIRE-SHAPED row (commerce.Api contract): id is the Shopify GID (guests
    // without a Shopify id keep id=null and are addressed by key/path),
    // timestamps are ms-precision ISO-8601.
    static Map row(session, res) {
        return [
            key             : res.getName().replaceAll(/\.json$/, ""),
            path            : res.getPath(),
            id              : Api.gid("Customer", propStr(res, "commerce:customer_id")),
            email           : propStr(res, "commerce:email"),
            name            : propStr(res, "commerce:name"),
            status          : propStr(res, "commerce:status"),
            sourceStatus    : propStr(res, "commerce:source_status"),
            tags            : propStr(res, "commerce:tags"),
            taxExempt       : propStr(res, "commerce:tax_exempt") == "true",
            marketingConsent: propStr(res, "commerce:marketing_consent"),
            marketingEnabled: propStr(res, "commerce:marketing_enabled") == "true",
            createdAt       : Api.instant(propVal(res, "commerce:created_at")),
            updatedAt       : Api.instant(propVal(res, "commerce:updated_at")),
        ]
    }

    // --- Internals -------------------------------------------------------------------

    private static void eachCustomer(session, Closure cb) {
        def base = Jcr.safeGet(session, STORE_DIR)
        if (base == null || !base.exists()) return
        try {
            def it = base.list()
            while (it.hasNext()) {
                def c = it.next()
                try { if (c.getName().endsWith(".json")) cb(c) } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
    }

    // Property readers tolerant of typed values (Date/Calendar/Number) AND legacy
    // String values.
    private static Object propVal(res, String name) {
        try { if (res.hasProperty(name)) return res.getProperty(name).getValue() } catch (Exception ignore) {}
        return null
    }

    private static String propStr(res, String name) {
        def v = propVal(res, name)
        return v == null ? null : v.toString()
    }

    private static void setDate(res, String name, value) {
        long ms = parseMs(value)
        if (ms > 0) res.setProperty(name, new java.util.Date(ms))
    }

    /** Value → epoch ms: Calendar / Date / Number / ISO string. 0 when unknown. */
    private static boolean blank(v) { v == null || v.toString().trim().isEmpty() }

    private static String sanitize(String s) { s == null ? "" : s.replaceAll("[^A-Za-z0-9_.-]", "_") }
}
