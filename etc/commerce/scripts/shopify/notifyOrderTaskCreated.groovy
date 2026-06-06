// Task listener script: notify configured destinations when a manual "Order
// Review" task is created in the order-review workflow.
//
// Attached to the user task as a Camunda "create" task listener via CmsDelegate
// (org.mintjams.script.bpm.CmsDelegate implements TaskListener). The listener
// receives the current task as the `task` global (a DelegateTask).
//
// The message summarizes the order and lists WHY it was flagged for review
// (the reasons produced by screenOrder.groovy and carried on the process
// variable `reviewReasons`). The order details themselves are read straight
// from the order resource - the same source of truth used by screenOrder - so
// the notification and the review form always agree.
//
// Notification destinations are shared with the inventory alert workflow and
// read from:
//   /etc/commerce/config/notifications.yml
//
// Supported channels: Slack and Discord incoming webhooks, using the JDK
// built-in java.net.http.HttpClient (no extra JAR required).
//
// IMPORTANT: a notification failure must never break the business process, so
// every external call is wrapped defensively and only logged on error.

// Delivery is shared and pluggable (see /content/WEB-INF/classes/commerce/Notifications.groovy).
import commerce.Notifications
import commerce.NotificationMessage

// --- Resolve task / process context ------------------------------------------
def taskName = task?.getName() ?: "Order Review"
def assignee = task?.getAssignee()

def orderPath = Notifications.taskVar(task, "orderPath")
def orderID = Notifications.taskVar(task, "order_id")

// Reasons are a JSON array string set by screenOrder.groovy.
def reasons = []
try {
    def raw = Notifications.taskVar(task, "reviewReasons")
    if (raw) {
        def parsed = JSON.parse(raw)
        if (parsed instanceof List) {
            reasons = parsed.collect { it?.toString() }.findAll { it }
        }
    }
} catch (Exception e) {
    log.warn("notifyOrderTaskCreated: could not parse reviewReasons: ${e.message}")
}

// --- Resolve order summary from the order resource ---------------------------
def orderLabel = orderID ? "Order ${orderID}" : "an order"
def customerEmail = null
def totalText = null
def financialStatus = null
def itemCount = null
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
                customerEmail = (order.contact_email ?: order.email)?.toString()
                financialStatus = order.financial_status?.toString()
                if (order.total_price != null) {
                    def currency = order.currency?.toString() ?: ""
                    totalText = "${order.total_price} ${currency}".trim()
                }
                if (order.line_items instanceof List) {
                    itemCount = order.line_items.size()
                }
            }
        }
    }
} catch (Exception e) {
    log.warn("notifyOrderTaskCreated: could not resolve order summary: ${e.message}")
}

// --- Load notification configuration -----------------------------------------
def configNode
try {
    configNode = repositorySession.getResource("/etc/commerce/config/notifications.yml")
} catch (Exception e) {
    log.info("notifyOrderTaskCreated: notifications.yml not accessible - skipping notification")
    return
}
if (configNode == null || !configNode.exists()) {
    log.info("notifyOrderTaskCreated: notifications.yml not found - skipping notification")
    return
}

def config
try {
    config = YAML.parse(configNode)
} catch (Exception e) {
    log.warn("notifyOrderTaskCreated: failed to parse notifications.yml: ${e.message}")
    return
}

// --- Build the notification message ------------------------------------------
// One channel-agnostic message; each enabled channel renders it in its own
// format (see commerce.NotificationMessage / commerce.Notifications).
def message = NotificationMessage.create()
    .title("🧾", "Order review workflow")
    .status("⚠", "Order review required")
    .text(orderLabel)
    .field("Customer", customerEmail)
    .field("Total", totalText)
    .field("Payment", financialStatus)
    .field("Items", itemCount)
    .bullets("Reasons", reasons)
    .footer(taskName, assignee)

// --- Dispatch to each enabled channel ----------------------------------------
Notifications.dispatch(log, "notifyOrderTaskCreated", config, message)
