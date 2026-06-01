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

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

// --- Resolve task / process context ---------------------------------------
def taskName = task?.getName() ?: "Manual task"
def assignee = task?.getAssignee()

// Process variables carried by the flow (set when the process was started).
def productID = safeVar("productID")
def productPath = safeVar("productPath")

// --- Resolve product title and current inventory state ---------------------
// productTitle : human-friendly product name
// variants     : list of [title, available, threshold, below] maps, where
//                threshold is null when none is configured for that variant.
// hasThreshold : true if at least one variant has a configured threshold
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

            // Configured thresholds, keyed by variant ID.
            def thresholdByVariantID = [:]
            if (resource.hasProperty("inventory_level_config")) {
                try {
                    def thresholdJson = JSON.parse(resource.getProperty("inventory_level_config").getValue())
                    thresholdJson?.variants?.each { tv ->
                        if (tv.id != null && tv.inventory_alert_threshold != null) {
                            thresholdByVariantID[tv.id.toString()] = tv.inventory_alert_threshold as int
                        }
                    }
                } catch (Exception e) {
                    log.warn("notifyTaskCreated: could not parse inventory_level_config: ${e.message}")
                }
            }
            hasThreshold = !thresholdByVariantID.isEmpty()

            // Current inventory per variant from the product JSON (Shopify webhook payload).
            def productJson = JSON.parse(resource.content.toString())
            if (productTitle == "Product ${productID}" && productJson?.title) {
                productTitle = productJson.title.toString()
            }
            productJson?.variants?.each { v ->
                def variantID = v.id?.toString()
                def threshold = variantID != null ? thresholdByVariantID[variantID] : null
                def available = null
                try {
                    available = v.inventory_quantity == null ? null : (v.inventory_quantity as int)
                } catch (Exception ignore) {
                    available = null
                }
                variants << [
                    title    : v.title?.toString(),
                    available : available,
                    threshold : threshold,
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
// Rendered twice (once per channel) so bold markers match each platform:
// Slack uses *bold*, Discord uses **bold**.
def slackText = buildMessage("*", taskName, productTitle, productID, variants, hasThreshold, assignee)
def discordText = buildMessage("**", taskName, productTitle, productID, variants, hasThreshold, assignee)

// --- Dispatch to each enabled channel -------------------------------------
def httpClient = HttpClient.newHttpClient()

// Slack: expects { "text": "..." }
def slack = config?.slack
if (slack && isEnabled(slack) && slack.webhookUrl) {
    post(httpClient, slack.webhookUrl.toString().trim(), JSON.stringify([text: slackText]), "Slack")
}

// Discord: expects { "content": "..." }
def discord = config?.discord
if (discord && isEnabled(discord) && discord.webhookUrl) {
    post(httpClient, discord.webhookUrl.toString().trim(), JSON.stringify([content: discordText]), "Discord")
}

// --- Helpers ---------------------------------------------------------------

// Build the full notification text. `b` is the bold delimiter for the target
// channel ("*" for Slack, "**" for Discord).
String buildMessage(String b, String taskName, String productTitle, String productID,
                    List variants, boolean hasThreshold, String assignee) {
    def variantHasName = { v ->
        def t = v.title
        return t != null && !t.trim().isEmpty() && t != "Default Title"
    }

    def sb = new StringBuilder()
    sb.append("📦 ${b}Inventory alert workflow${b}\n\n")

    def lowVariants = variants.findAll { it.below }

    if (hasThreshold) {
        // Review context: one or more variants are below their threshold.
        sb.append("⚠ ${b}Inventory review required${b}\n\n")
        sb.append("Product: ${productTitle}")
        if (productID) {
            sb.append(" (ID: ${productID})")
        }
        sb.append("\n")

        // Fall back to all variants with a threshold if the below-list is empty
        // (e.g. the listener fired but stock recovered between steps).
        def rows = lowVariants.isEmpty() ? variants.findAll { it.threshold != null } : lowVariants
        rows.each { v ->
            // Group named variants with a blank line; for a single unnamed
            // (default) variant, list the values directly under the product.
            if (variantHasName(v)) {
                sb.append("\n")
                sb.append("Variant: ${v.title}\n")
            }
            sb.append("Available: ${v.available == null ? "unknown" : v.available}\n")
            sb.append("Threshold: ${v.threshold == null ? "unknown" : v.threshold}\n")
        }
    } else {
        // Setup context: no threshold configured yet.
        sb.append("⚙ ${b}Inventory threshold setup required${b}\n\n")
        sb.append("Product: ${productTitle}")
        if (productID) {
            sb.append(" (ID: ${productID})")
        }
        sb.append("\n")
        sb.append("No inventory threshold is configured yet. Please set one.\n")

        variants.each { v ->
            if (variantHasName(v)) {
                sb.append("\n")
                sb.append("Variant: ${v.title}\n")
            }
            sb.append("Available: ${v.available == null ? "unknown" : v.available}\n")
        }
    }

    sb.append("\n")
    sb.append("Task: ${taskName}\n")
    sb.append(assignee ? "Assignee: ${assignee}" : "Unassigned - awaiting claim")

    return sb.toString()
}

def safeVar(String name) {
    try {
        def v = task?.getVariable(name)
        return v == null ? null : v.toString()
    } catch (Exception e) {
        return null
    }
}

boolean isEnabled(channel) {
    // Treat a channel as enabled unless explicitly disabled; a missing webhook
    // URL is filtered out by the caller anyway.
    if (channel == null) return false
    def enabled = channel.enabled
    if (enabled == null) return true
    return enabled.toString().toLowerCase() == "true"
}

void post(HttpClient client, String url, String body, String label) {
    if (!url || url.startsWith("REPLACE")) {
        log.info("notifyTaskCreated: ${label} webhook not configured - skipping")
        return
    }
    try {
        def request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        def res = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() >= 200 && res.statusCode() < 300) {
            log.info("notifyTaskCreated: ${label} notification sent (status ${res.statusCode()})")
        } else {
            log.warn("notifyTaskCreated: ${label} notification failed: ${res.statusCode()} - ${res.body()}")
        }
    } catch (Exception e) {
        log.warn("notifyTaskCreated: ${label} notification error: ${e.message}")
    }
}
