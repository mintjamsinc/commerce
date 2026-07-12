package commerce

import javax.jcr.query.Query

/**
 * One-time migration: retype legacy String-typed commerce properties to their
 * real JCR types, store by store.
 *
 * Historically most {@code commerce:*} properties were written as Strings
 * (header-mapped or {@code .toString()}), so numeric/date range predicates were
 * LEXICAL and boolean flags were the string "true". The writers now emit typed
 * values (Boolean flags, Long counts, Decimal money, Date timestamps); this
 * migration brings already-written nodes in line so the auto-index gives real
 * range queries over old data too.
 *
 * Idempotent and lossy-safe: only String-typed values that PARSE cleanly are
 * replaced; anything else is left untouched (and counted). Each area commits
 * separately (NamespaceMigration's transaction-bounding pattern). Nothing is
 * deleted, so verification is simply "no area failed".
 */
class PropertyTypeMigration {

    static final String T_LONG = "long"
    static final String T_DECIMAL = "decimal"
    static final String T_BOOLEAN = "boolean"
    static final String T_DATE = "date"

    // Area root → property → target type. Derived from the writers (routes and
    // scripts) that populate each area; this map is the authoritative catalog.
    static final Map AREAS = [
        "/content/commerce/orders": [
            "commerce:total_price"     : T_DECIMAL,
            "commerce:total_price_base": T_DECIMAL,
            "commerce:order_number"    : T_LONG,
            "commerce:refunded_amount" : T_DECIMAL,
            "commerce:refund_count"    : T_LONG,
            "commerce:fulfilled_at"    : T_DATE,
            "commerce:cancelled_at"    : T_DATE,
        ],
        "/content/commerce/refunds": [
            "commerce:refund_amount"  : T_DECIMAL,
            "commerce:restocked"      : T_BOOLEAN,
            "commerce:order_updated"  : T_BOOLEAN,
            "commerce:line_item_count": T_LONG,
        ],
        "/content/commerce/backorders": [
            "commerce:quantity"        : T_LONG,
            "commerce:ordered_quantity": T_LONG,
            "commerce:created_at"      : T_DATE,
            "commerce:released_at"     : T_DATE,
            "commerce:cancelled_at"    : T_DATE,
        ],
        "/content/commerce/events": [
            "commerce:attempts"   : T_LONG,
            "commerce:received_at": T_DATE,
        ],
        "/content/commerce/entities": [
            "commerce:updated_at"      : T_DATE,
            "commerce:deletedAt"       : T_DATE,
        ],
        "/content/commerce/products": [
            "commerce:updated_at": T_DATE,
            "commerce:deletedAt" : T_DATE,
        ],
        "/content/commerce/reconciliation": [
            "commerce:total_diffs"        : T_LONG,
            "commerce:products_with_drift": T_LONG,
            "commerce:created_at"         : T_DATE,
        ],
        "/content/commerce/sync": [
            "commerce:created_at": T_DATE,
        ],
    ]

    static Map run(session, log) {
        def areas = []
        int converted = 0, skipped = 0, unparseable = 0, errors = 0
        AREAS.each { root, props ->
            def rep = migrateArea(session, log, root.toString(), props)
            areas << rep
            converted += rep.converted
            skipped += rep.skipped
            unparseable += rep.unparseable
            errors += rep.errors
        }
        return [ok: errors == 0, converted: converted, alreadyTyped: skipped,
                unparseable: unparseable, errors: errors, areas: areas]
    }

    private static Map migrateArea(session, log, String root, Map props) {
        int converted = 0, skipped = 0, unparseable = 0, errors = 0, scanned = 0
        def resources = []
        try {
            def rootRes = Jcr.safeGet(session, root)
            if (rootRes != null && rootRes.exists()) {
                def stmt = "/jcr:root${root}//element(*, nt:file)"
                def jq = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
                resources = jq.execute().getResources()
            }
        } catch (Exception e) {
            try { log.warn("m004: query failed for ${root}: ${e.message}") } catch (Exception ignore) {}
        }

        (resources ?: []).each { res ->
            scanned++
            boolean touched = false
            props.each { name, type ->
                try {
                    if (!res.hasProperty(name)) return
                    def val = res.getProperty(name).getValue()
                    if (!(val instanceof CharSequence)) {
                        skipped++
                        return   // already typed
                    }
                    def s = val.toString().trim()
                    if (s.isEmpty()) {
                        unparseable++
                        return
                    }
                    switch (type) {
                        case T_LONG:
                            res.setProperty(name.toString(), Long.parseLong(sanitizeNumber(s)))
                            break
                        case T_DECIMAL:
                            res.setProperty(name.toString(), new BigDecimal(sanitizeNumber(s)))
                            break
                        case T_BOOLEAN:
                            res.setProperty(name.toString(), s.equalsIgnoreCase("true"))
                            break
                        case T_DATE:
                            res.setProperty(name.toString(), parseDate(s))
                            break
                    }
                    converted++
                    touched = true
                } catch (Exception e) {
                    unparseable++
                    try { log.info("m004: left ${res.getPath()} ${name} as-is (${e.message})") } catch (Exception ignore) {}
                }
            }
            if (touched && (converted % 300 == 0)) {
                try { session.commit() } catch (Exception e) {
                    try { session.rollback() } catch (Exception ignore) {}
                    errors++
                }
            }
        }
        try {
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            errors++
            try { log.warn("m004: commit failed for ${root}: ${e.message}") } catch (Exception ignore) {}
        }
        return [path: root, scanned: scanned, converted: converted, skipped: skipped,
                unparseable: unparseable, errors: errors]
    }

    // "1,234" / "1234.50" → machine-parseable.
    private static String sanitizeNumber(String s) {
        return s.replace(",", "")
    }

    // ISO-8601 (with offset or Z) → java.util.Date; raises when unparseable so
    // the caller leaves the value untouched.
    private static java.util.Date parseDate(String s) {
        try {
            return new java.util.Date(java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli())
        } catch (Exception ignore) {}
        return new java.util.Date(java.time.Instant.parse(s).toEpochMilli())
    }
}
