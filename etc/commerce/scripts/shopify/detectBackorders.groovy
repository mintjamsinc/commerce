// Detect backorders / pre-orders for a freshly received order.
//
// Invoked from the orders/paid Camel route (order-paid.xml) as the service user,
// right after the order is stored. For each order line it resolves the variant to
// its inventory item, reads the aggregate stock we hold for that item and whether
// the product is a pre-order, then records a line-level backorder for any line that
// is short on stock or sold ahead as a pre-order. When stock later arrives the
// inventory_levels/update route releases covered backorders.
//
// Inputs (mapped from route headers via ?inputs=orderPath,order_id):
//   - orderPath : repository path to the stored order JSON
//   - order_id  : Shopify order ID (for logging)
//
// The decision itself lives in the pure, testable commerce.Backorders.detect; this
// script only gathers the JCR-side inputs (variant→item map, tracked availability,
// pre-order items) and persists/notifies. Fully DEFENSIVE: a failure here must
// never move the order to the error folder, so everything is wrapped and swallowed.

import commerce.Backorders
import commerce.Locations
import commerce.Notifications
import commerce.NotificationMessage

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }

try {
    def orderPath = hv("orderPath")?.toString()
    if (!orderPath) {
        log.warn("detectBackorders: no orderPath - skipping")
        return
    }

    // --- Load configuration --------------------------------------------------
    def config = readYaml("/etc/commerce/config/backorder.yml")
    if (config == null) {
        // No config deployed → feature inert (matches the platform's opt-in posture
        // for anything that writes operator-facing records).
        return
    }
    if (config.enabled != null && config.enabled.toString().toLowerCase() == "false") {
        return
    }
    def preorderTags = ((config.preorderTags ?: []) as List)
        .collect { it?.toString()?.trim()?.toLowerCase() }.findAll { it } as Set

    // --- Parse the order -----------------------------------------------------
    def orderRes = repositorySession.getResource(orderPath)
    if (orderRes == null || !orderRes.exists()) {
        log.warn("detectBackorders: order not found at ${orderPath}")
        return
    }
    def order = JSON.parse(orderRes.content.toString())
    def lineItems = order?.line_items
    if (!(lineItems instanceof List) || lineItems.isEmpty()) {
        return
    }

    // --- Resolve variant→item, tracked availability and pre-order items ------
    // Order line items carry variant_id + product_id but neither the inventory
    // item id nor the product tags, so we read each referenced product once.
    def variantToItem = [:]
    def availableByItem = [:]
    def preorderItemIds = [] as Set

    def productCache = [:]
    lineItems.each { li ->
        def productId = li?.product_id?.toString()
        if (!productId || productCache.containsKey(productId)) {
            return
        }
        productCache[productId] = true
        def product = readProduct(productId)
        if (product == null) {
            return
        }
        boolean isPreorderProduct = !preorderTags.isEmpty() && productTags(product).any { preorderTags.contains(it) }
        def variants = product.variants
        if (variants instanceof List) {
            variants.each { v ->
                def variantId = v?.id?.toString()
                def itemId = v?.inventory_item_id?.toString()
                if (!variantId || !itemId) {
                    return
                }
                variantToItem[variantId] = itemId
                // Track availability only for items we actually hold levels for.
                if (!availableByItem.containsKey(itemId)) {
                    def levels = Locations.levels(repositorySession, itemId)
                    if (levels != null && !levels.isEmpty()) {
                        availableByItem[itemId] = Locations.aggregate(repositorySession, itemId)
                    }
                }
                if (isPreorderProduct) {
                    preorderItemIds << itemId
                }
            }
        }
    }

    // --- Decide + persist ----------------------------------------------------
    def descriptors = Backorders.detect(order, variantToItem, availableByItem, preorderItemIds)
    if (descriptors.isEmpty()) {
        return
    }

    def created = []
    descriptors.each { d ->
        if (Backorders.create(repositorySession, log, d)) {
            created << d
        }
    }
    if (created.isEmpty()) {
        return
    }

    // --- Notify operators (one summary per order) ----------------------------
    if (config.notify?.onCreated == null || config.notify.onCreated.toString().toLowerCase() != "false") {
        notifyCreated(order, created)
    }
} catch (Exception e) {
    try { log.warn("detectBackorders: ${e.message}") } catch (Exception ignore) {}
}

// --- Helpers -----------------------------------------------------------------

def readProduct(String productId) {
    try {
        def res = repositorySession.getResource("/content/commerce/products/product_${productId}.json")
        if (res != null && res.exists()) {
            return JSON.parse(res.content.toString())
        }
    } catch (Exception e) {
        log.warn("detectBackorders: could not read product ${productId}: ${e.message}")
    }
    return null
}

// Product tags from the product JSON (Shopify gives a comma-separated string;
// fall back to the mirrored commerce:tags property is unnecessary as we read JSON).
List productTags(product) {
    def raw = product?.tags
    if (raw instanceof List) {
        return raw.collect { it?.toString()?.trim()?.toLowerCase() }.findAll { it }
    }
    if (raw == null) {
        return []
    }
    return raw.toString().split(",").collect { it?.trim()?.toLowerCase() }.findAll { it }
}

def readYaml(String path) {
    try {
        def res = repositorySession.getResource(path)
        if (res != null && res.exists()) {
            return YAML.parse(res)
        }
    } catch (Exception e) {
        log.warn("detectBackorders: could not read ${path}: ${e.message}")
    }
    return null
}

void notifyCreated(order, List created) {
    try {
        def configNode = repositorySession.getResource("/etc/commerce/config/notifications.yml")
        if (configNode == null || !configNode.exists()) {
            return
        }
        def config = YAML.parse(configNode)

        def number = (order?.order_number ?: order?.name)?.toString()
        def orderLabel = number ? "Order #${number}" : "Order ${order?.id}"
        int units = created.sum { it.quantity ?: 0 } ?: 0
        def lines = created.collect {
            def t = it.title ?: "item"
            def vt = (it.variant_title && it.variant_title != "Default Title") ? " (${it.variant_title})" : ""
            "${t}${vt} ×${it.quantity} — ${it.reason}"
        }
        def hasPreorder = created.any { it.reason == "preorder" }

        def message = NotificationMessage.create()
            .title("📦", "Backorder workflow")
            .status(hasPreorder ? "🗓" : "⏳", hasPreorder ? "Pre-order / backorder recorded" : "Items on backorder")
            .text(orderLabel)
            .field("Customer", (order?.contact_email ?: order?.email)?.toString())
            .field("Backordered lines", created.size())
            .field("Units awaited", units)
            .bullets("Items", lines)

        Notifications.dispatch(log, "detectBackorders", config, message)
    } catch (Exception e) {
        log.warn("detectBackorders: notification failed: ${e.message}")
    }
}
