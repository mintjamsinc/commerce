// Task listener script: notify configured destinations when a manual task is
// created in the inventory alert workflow.
//
// Attached to user tasks as a Camunda "create" task listener via CmsDelegate
// (org.mintjams.script.bpm.CmsDelegate implements TaskListener). The listener
// receives the current task as the `task` global (a DelegateTask).
//
// The same listener is attached to two distinct user tasks:
//   - "Set Inventory Threshold"  : fired when the product has no threshold
//                                  configured yet (setup context).
//   - "Manual Inventory Check"   : fired when one or more variants dropped
//                                  below their configured threshold (review
//                                  context).
// Rather than depend on which task fired, the notification reads the current
// inventory state straight from the product resource - the same source of
// truth used by checkInventoryLevel.groovy - and renders the appropriate
// message. This keeps both contexts consistent and supports multi-variant
// products.
//
// Notification destinations are read from a dedicated config file that is kept
// separate from the Shopify credentials (managed by the Commerce app):
//   /etc/commerce/config/notifications.yml
//
// Supported channels: Slack and Discord incoming webhooks. Both use the JDK
// built-in java.net.http.HttpClient, so no extra JAR is required.
//
// IMPORTANT: a notification failure must never break the business process, so
// every external call is wrapped defensively and only logged on error.

// Shared commerce helpers (see /content/WEB-INF/classes/commerce/).
import commerce.Inventory
import commerce.InventoryRules
import commerce.SalesVelocity
import commerce.Notifications
import commerce.NotificationMessage

// --- Resolve task / process context ---------------------------------------
def taskName = task?.getName() ?: "Manual task"
def assignee = task?.getAssignee()

// Process variables carried by the flow (set when the process was started).
def productID = Notifications.taskVar(task, "productID")
def productPath = Notifications.taskVar(task, "productPath")

// --- Resolve product title and current inventory state ---------------------
// productTitle : human-friendly product name
// variants     : list of [title, available, threshold, source, rule, below] maps,
//                where threshold is the EFFECTIVE threshold (manual override / rule
//                / default) resolved by commerce.InventoryRules, or null when the
//                variant is not monitored.
// hasThreshold : true if at least one variant has an effective threshold
def productTitle = productID ? "Product ${productID}" : "a product"
def variants = []
def hasThreshold = false
try {
    if (productPath) {
        def resource = repositorySession.getResource(productPath)
        if (resource != null && resource.exists()) {
            // Product title (resource property takes precedence over JSON).
            if (resource.hasProperty("title")) {
                def t = resource.getProperty("title").getValue()
                if (t) {
                    productTitle = t.toString()
                }
            }

            // Manual per-variant overrides (see commerce.Inventory).
            def manual = [:]
            try {
                manual = Inventory.thresholdsByVariantId(resource)
            } catch (Exception e) {
                log.warn("notifyTaskCreated: could not parse inventory_level_config: ${e.message}")
            }

            // Threshold rules (a list structure → parsed with the YAML binding).
            def rulesConfig = null
            try {
                def rn = repositorySession.getResource(InventoryRules.CONFIG_PATH)
                if (rn != null && rn.exists()) {
                    rulesConfig = YAML.parse(rn)
                }
            } catch (Exception e) {
                log.warn("notifyTaskCreated: could not parse inventory-rules.yml: ${e.message}")
            }

            // Current inventory per variant from the product JSON (Shopify webhook payload).
            def productJson = JSON.parse(resource.content.toString())
            if (productTitle == "Product ${productID}" && productJson?.title) {
                productTitle = productJson.title.toString()
            }

            def product = [
                productType: productJson?.product_type,
                vendor     : productJson?.vendor,
                tags       : (productJson?.tags ? productJson.tags.toString().split(",").collect { it.trim() }.findAll { it } : []),
                variants   : (productJson?.variants ?: []).collect { [id: it.id?.toString()] },
            ]
            def resolved = InventoryRules.resolve(product, rulesConfig, manual, SalesVelocity.loadPerDay(repositorySession))
            hasThreshold = InventoryRules.hasEffectiveThreshold(resolved)

            productJson?.variants?.each { v ->
                def variantID = v.id?.toString()
                def eff = variantID != null ? resolved[variantID] : null
                def threshold = eff?.threshold
                def available = null
                try {
                    available = v.inventory_quantity == null ? null : (v.inventory_quantity as int)
                } catch (Exception ignore) {
                    available = null
                }
                variants << [
                    title     : v.title?.toString(),
                    available : available,
                    threshold : threshold,
                    source    : eff?.source,
                    rule      : eff?.rule,
                    below     : (threshold != null && available != null && available < threshold)
                ]
            }
        }
    }
} catch (Exception e) {
    log.warn("notifyTaskCreated: could not resolve inventory state: ${e.message}")
}

// --- Load notification configuration --------------------------------------
def configNode
try {
    configNode = repositorySession.getResource("/etc/commerce/config/notifications.yml")
} catch (Exception e) {
    log.info("notifyTaskCreated: notifications.yml not accessible - skipping notification")
    return
}
if (configNode == null || !configNode.exists()) {
    log.info("notifyTaskCreated: notifications.yml not found - skipping notification")
    return
}

def config
try {
    config = YAML.parse(configNode)
} catch (Exception e) {
    log.warn("notifyTaskCreated: failed to parse notifications.yml: ${e.message}")
    return
}

// --- Build the notification message ---------------------------------------
// One channel-agnostic message; each enabled channel renders it in its own
// format (see commerce.NotificationMessage / commerce.Notifications).
def variantHasName = { v ->
    def t = v.title
    return t != null && !t.trim().isEmpty() && t != "Default Title"
}

def productLabel = productID ? "${productTitle} (ID: ${productID})" : productTitle

// Render an effective threshold with its origin so reviewers can see WHY the
// alert fired (e.g. "20 (rule: Perishable)" / "5 (default)"; manual shows no note).
def formatThreshold = { v ->
    if (v.threshold == null) {
        return "unknown"
    }
    if (v.source == "rule" && v.rule) {
        return "${v.threshold} (rule: ${v.rule})"
    }
    if (v.source == "default") {
        return "${v.threshold} (default)"
    }
    return "${v.threshold}"
}

def message = NotificationMessage.create()
    .title("📦", "Inventory alert workflow")

if (hasThreshold) {
    // Review context: one or more variants are below their threshold.
    message.status("⚠", "Inventory review required")
    message.field("Product", productLabel)

    // Fall back to all variants with a threshold if the below-list is empty
    // (e.g. the listener fired but stock recovered between steps).
    def lowVariants = variants.findAll { it.below }
    def rows = lowVariants.isEmpty() ? variants.findAll { it.threshold != null } : lowVariants
    rows.each { v ->
        // Group named variants with a sub-heading; for a single unnamed
        // (default) variant, list the values directly under the product.
        if (variantHasName(v)) {
            message.heading("Variant: ${v.title}")
        }
        message.field("Available", v.available == null ? "unknown" : v.available)
        message.field("Threshold", formatThreshold(v))
    }
} else {
    // Setup context: no threshold configured yet.
    message.status("⚙", "Inventory threshold setup required")
    message.field("Product", productLabel)
    message.text("No inventory threshold is configured yet. Please set one.")

    variants.each { v ->
        if (variantHasName(v)) {
            message.heading("Variant: ${v.title}")
        }
        message.field("Available", v.available == null ? "unknown" : v.available)
    }
}

message.footer(taskName, assignee)

// --- Dispatch to each enabled channel -------------------------------------
Notifications.dispatch(log, "notifyTaskCreated", config, message)
