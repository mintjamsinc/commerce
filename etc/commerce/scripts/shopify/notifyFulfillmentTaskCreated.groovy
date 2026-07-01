// Task listener script: notify configured destinations when a "Fulfill Order"
// task is created in the order processing workflow.
//
// Attached to the user task as a Camunda "create" task listener via CmsDelegate
// (org.mintjams.script.bpm.CmsDelegate implements TaskListener). The listener
// receives the current task as the `task` global (a DelegateTask).
//
// The message tells the warehouse that an approved order is ready to pick, pack
// and ship, and includes the shipping address so a fulfiller can triage at a
// glance. Order details are read straight from the order resource - the same
// source of truth used elsewhere in the flow.
//
// Notification destinations are shared with the rest of the commerce tooling:
//   /etc/commerce/config/notifications.yml
//
// Delivery goes to every enabled channel in the shared registry - Slack,
// Discord, Teams, LINE, generic webhook and email - via commerce.Notifications
// (see Notifications.registry()). Each channel renders the same message in its
// own format using the JDK built-in java.net.http.HttpClient / SMTP client (no
// extra JAR required).
//
// IMPORTANT: a notification failure must never break the business process, so
// every external call is wrapped defensively and only logged on error.

// Delivery is shared and pluggable (see /content/WEB-INF/classes/commerce/Notifications.groovy).
import commerce.Notifications
import commerce.NotificationMessage

// --- Resolve task / process context ------------------------------------------
def taskName = task?.getName() ?: "Fulfill Order"
def assignee = task?.getAssignee()

def orderPath = Notifications.taskVar(task, "orderPath")
def orderID = Notifications.taskVar(task, "order_id")

// --- Resolve order summary from the order resource ---------------------------
def orderLabel = orderID ? "Order ${orderID}" : "an order"
def totalText = null
def itemCount = null
def shippingLines = []
try {
    if (orderPath) {
        def resource = repositorySession.getResource(orderPath)
        if (resource != null && resource.exists()) {
            def order = JSON.parse(resource.content.toString())
            if (order != null) {
                def number = order.order_number ?: order.name
                if (number != null) {
                    orderLabel = "Order #${number}"
                }
                if (order.total_price != null) {
                    def currency = order.currency?.toString() ?: ""
                    totalText = "${order.total_price} ${currency}".trim()
                }
                if (order.line_items instanceof List) {
                    itemCount = order.line_items.size()
                }
                shippingLines = addressLines(order.shipping_address)
            }
        }
    }
} catch (Exception e) {
    log.warn("notifyFulfillmentTaskCreated: could not resolve order summary: ${e.message}")
}

// --- Load notification configuration -----------------------------------------
def configNode
try {
    configNode = repositorySession.getResource("/etc/commerce/config/notifications.yml")
} catch (Exception e) {
    log.info("notifyFulfillmentTaskCreated: notifications.yml not accessible - skipping notification")
    return
}
if (configNode == null || !configNode.exists()) {
    log.info("notifyFulfillmentTaskCreated: notifications.yml not found - skipping notification")
    return
}

def config
try {
    config = YAML.parse(configNode)
} catch (Exception e) {
    log.warn("notifyFulfillmentTaskCreated: failed to parse notifications.yml: ${e.message}")
    return
}

// --- Build the notification message ------------------------------------------
// One channel-agnostic message; each enabled channel renders it in its own
// format (see commerce.NotificationMessage / commerce.Notifications).
def message = NotificationMessage.create()
    .title("📦", "Order fulfillment")
    .status("🚚", "Order ready to fulfill")
    .text(orderLabel)
    .field("Total", totalText)
    .field("Items", itemCount)
    .lines("Ship to", shippingLines)
    .footer(taskName, assignee)

// --- Dispatch to each enabled channel ----------------------------------------
Notifications.dispatch(log, "notifyFulfillmentTaskCreated", config, message)

// --- Helpers -----------------------------------------------------------------

List addressLines(address) {
    if (address == null) return []
    def lines = []
    addIfPresent(lines, address.name)
    addIfPresent(lines, address.company)
    addIfPresent(lines, address.address1)
    addIfPresent(lines, address.address2)
    def cityLine = [address.city, address.province, address.zip].findAll { it != null && !it.toString().trim().isEmpty() }.join(" ")
    if (!cityLine.isEmpty()) lines << cityLine
    addIfPresent(lines, address.country)
    return lines
}

void addIfPresent(List lines, value) {
    if (value != null && !value.toString().trim().isEmpty()) {
        lines << value.toString().trim()
    }
}
