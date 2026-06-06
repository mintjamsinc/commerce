package commerce

/**
 * Numeric / money helpers shared across the commerce scripts.
 *
 * This class lives under /content/WEB-INF/classes (a per-workspace classpath
 * root that the CMS deploys and exposes to the Groovy script engine), so any
 * script - BPMN CmsDelegate listeners/tasks, Camel `cms:` routes, or web
 * endpoints - can use it via `import commerce.Money`.
 *
 * Every method is static and pure: it takes plain values and needs none of the
 * script bindings (repositorySession / log / JSON), so it is safe to call from
 * any context.
 */
class Money {

    /**
     * Parse a value (String, Number, or null) into a BigDecimal.
     * Returns null when the value is null or not a valid number, so callers can
     * treat "missing" and "unparseable" the same way.
     */
    static BigDecimal toNumber(Object value) {
        if (value == null) {
            return null
        }
        try {
            return new BigDecimal(value.toString().trim())
        } catch (Exception ignore) {
            return null
        }
    }

    /**
     * Format a number with thousands separators. Whole numbers render without
     * decimals (100000 -> "100,000"); fractional values keep two places
     * (1234.5 -> "1,234.50"). Returns "" for null.
     */
    static String format(Number n) {
        if (n == null) {
            return ""
        }
        BigDecimal bd = (n instanceof BigDecimal) ? (BigDecimal) n : new BigDecimal(n.toString())
        if (bd.stripTrailingZeros().scale() <= 0) {
            return String.format("%,d", bd.toBigInteger())
        }
        return String.format("%,.2f", bd)
    }
}
