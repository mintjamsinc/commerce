// Task listener script: notify configured destinations when a manual task is
// created in the inventory alert workflow.
//
// Attached to user tasks as a Camunda "create" task listener via CmsDelegate
// (org.mintjams.script.bpm.CmsDelegate implements TaskListener). The listener
// receives the current task as the `task` global (a DelegateTask).
//
// The same listener is attached to two distinct user tasks:
//   - "Set Inventory Threshold"       : fired when the product has no threshold
//                                       (= reorder point) configured yet (setup).
//   - "Inventory & Reorder Review"    : fired when a variant's mirror total
//                                       crossed below its ROP (unified review:
//                                       stock check + suggested order quantity).
// Rather than depend on which task fired, the notification reads the current
// inventory state from the product resource and the multi-location mirror - the
// same total the inventory-alert sweep uses - and renders the appropriate message.
// This keeps both contexts consistent and supports multi-variant products.
//
// Notification destinations are read from a dedicated config file that is kept
// separate from the Shopify credentials (managed by the Commerce app).
//
// Delivery goes through commerce.Notifications under the "inventory" category:
// the channel set configured for that category in notifications.yml (or the
// default set when the category has none) receives the message on every enabled
// channel - Slack, Discord, Teams, LINE, generic webhook and email. Each channel
// renders the same message in its own format using the JDK built-in
// java.net.http.HttpClient / SMTP client (no extra JAR required).
//
// IMPORTANT: a notification failure must never break the business process, so
// every external call is wrapped defensively and only logged on error.

import commerce.Locations
import commerce.Planning
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
// variants     : list of [title, available, threshold, source, below] maps,
//                where threshold is the EFFECTIVE reorder point (explicit
//                planning value / manual override / default) resolved by
//                commerce.Planning, or null when the variant is not monitored.
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

            // Current inventory per variant from the product JSON (Shopify webhook payload).
            def productJson = JSON.parse(resource.content.toString())
            if (productTitle == "Product ${productID}" && productJson?.title) {
                productTitle = productJson.title.toString()
            }

            // Per-variant planning resolution (explicit → manual → default; the
            // rule engine was retired). threshold IS the reorder point.
            def planningCfg = Planning.config(repositorySession)
            def variantIds = (productJson?.variants ?: []).collect { it?.id?.toString() }.findAll { it }
            hasThreshold = Planning.hasEffectiveThreshold(resource, variantIds, planningCfg)

            productJson?.variants?.each { v ->
                def variantID = v.id?.toString()
                def eff = variantID != null ? Planning.resolve(resource, variantID, planningCfg).threshold : null
                def threshold = eff?.value
                // Report the multi-location mirror total (the same total the inventory-alert
                // sweep uses), falling back to the payload's inventory_quantity until the
                // mirror is populated for this item.
                def available = null
                try {
                    def itemId = v.inventory_item_id
                    def levels = (itemId != null) ? Locations.levels(repositorySession, itemId) : [:]
                    if (!levels.isEmpty()) {
                        available = levels.values().sum() as int
                    } else {
                        available = v.inventory_quantity == null ? null : (v.inventory_quantity as int)
                    }
                } catch (Exception ignore) {
                    available = null
                }
                variants << [
                    title     : v.title?.toString(),
                    available : available,
                    threshold : threshold,
                    source    : eff?.source,
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
// One channel-agnostic message; each enabled channel renders it in its own format.
def variantHasName = { v ->
    def t = v.title
    return t != null && !t.trim().isEmpty() && t != "Default Title"
}

def productLabel = productID ? "${productTitle} (ID: ${productID})" : productTitle

// Render an effective threshold (= reorder point) with its origin so reviewers
// can see WHY the review fired (e.g. "5 (default)"; an explicit value shows no note).
def formatThreshold = { v ->
    if (v.threshold == null) {
        return "unknown"
    }
    if (v.source == "default") {
        return "${v.threshold} (default)"
    }
    return "${v.threshold}"
}

def message = NotificationMessage.create()
    .title("📦", "Inventory alert workflow")

if (hasThreshold) {
    // Review context: a variant crossed below its reorder point.
    message.status("⚠", "Inventory & reorder review required")
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
Notifications.dispatch(log, "notifyTaskCreated", config, message, Notifications.CAT_INVENTORY)
