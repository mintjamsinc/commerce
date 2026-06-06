package commerce

import javax.jcr.query.Query

/**
 * Helpers for locating and reading the original order resource that a refund or
 * fulfillment relates to. Lives under /content/WEB-INF/classes; use via
 * `import commerce.Orders`.
 */
class Orders {

    /**
     * Locate the original order by its node name (order_{id}.json) under
     * /content/commerce/orders/raw. Returns the Resource, or null when the id is
     * blank or no matching order exists. `session` is the script's
     * repositorySession. Query/lookup errors propagate to the caller, which is
     * expected to treat order resolution as best-effort.
     */
    static Object findResource(session, orderId) {
        if (!orderId) {
            return null
        }
        def fileName = "order_${orderId}.json"
        def stmt = "/jcr:root/content/commerce/orders/raw//${fileName}"
        def query = session.getWorkspace().getQueryManager().createQuery(stmt, Query.XPATH)
        query.limit(1)
        def resources = query.execute().getResources()
        return (resources != null && resources.length > 0) ? resources[0] : null
    }

    /**
     * Read total_price from an already-parsed order map as a BigDecimal, or null
     * when it is absent / unparseable.
     */
    static BigDecimal totalPrice(order) {
        return Money.toNumber(order?.total_price)
    }
}
