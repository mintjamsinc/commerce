// Task listener script: notify configured destinations when a manual "Refund
// Review" task is created in the refund-review workflow.
//
// Attached to the user task as a Camunda "create" task listener via CmsDelegate
// (org.mintjams.script.bpm.CmsDelegate implements TaskListener). The listener
// receives the current task as the `task` global (a DelegateTask).
//
// The message summarizes the refund (amount, the order it belongs to, the
// customer when the order can be located) and lists WHY it was flagged - the
// reasons produced by the refund-screening step and carried on the process variable
// `reviewReasons`. Refund details are read straight from the refund resource.
//
// Notification destinations are shared with the rest of the commerce tooling.
//
// Delivery goes to every enabled channel in the shared registry - Slack,
// Discord, Teams, LINE, generic webhook and email - via commerce.Notifications.
// Each channel renders the same message in its
// own format using the JDK built-in java.net.http.HttpClient / SMTP client (no
// extra JAR required).
//
// IMPORTANT: a notification failure must never break the business process, so
// every external call is wrapped defensively and only logged on error.

import commerce.Money
import commerce.Refunds
import commerce.Orders
import commerce.Notifications
import commerce.NotificationMessage
import commerce.ReviewReasons

// --- Resolve task / process context ------------------------------------------
def taskName = task?.getName() ?: "Refund Review"
def assignee = task?.getAssignee()

def refundPath = Notifications.taskVar(task, "refundPath")
def refundID = Notifications.taskVar(task, "refund_id")
def orderID = Notifications.taskVar(task, "order_id")

// Reasons are a JSON array string of structured descriptors set by
// the refund-screening step. Rendered to operational English here (notifications have
// no per-user locale); the review form renders the same descriptors localized.
def reasons = []
try {
    def raw = Notifications.taskVar(task, "reviewReasons")
    if (raw) {
        def parsed = JSON.parse(raw)
        if (parsed instanceof List) {
            reasons = ReviewReasons.renderAll(parsed)
        }
    }
} catch (Exception e) {
    log.warn("notifyRefundTaskCreated: could not parse reviewReasons: ${e.message}")
}

// --- Resolve refund summary from the refund resource -------------------------
def amountText = null
def note = null
def restockedText = null
def lineItemCount = null
try {
    if (refundPath) {
        def resource = repositorySession.getResource(refundPath)
        if (resource != null && resource.exists()) {
            def refund = JSON.parse(resource.content.toString())
            if (refund != null) {
                def amount = Refunds.amount(refund)
                def currency = Refunds.currency(refund)
                if (amount != null) {
                    amountText = "${Money.format(amount)} ${currency ?: ''}".trim()
                }
                note = refund.note?.toString()
                def lineItems = refund.refund_line_items ?: []
                lineItemCount = lineItems.size()
                if (lineItemCount > 0) {
                    restockedText = lineItems.any { Refunds.isRestocked(it) } ? "yes" : "no"
                }
            }
        }
    }
} catch (Exception e) {
    log.warn("notifyRefundTaskCreated: could not resolve refund summary: ${e.message}")
}

// --- Resolve order summary from the original order (best-effort) -------------
def orderLabel = orderID ? "Order ${orderID}" : null
def customerEmail = null
try {
    if (orderID) {
        def orderResource = Orders.findResource(repositorySession, orderID)
        if (orderResource != null) {
            def order = JSON.parse(orderResource.content.toString())
            if (order != null) {
                def number = order.order_number ?: order.name
                if (number != null) {
                    orderLabel = "Order #${number}"
                }
                customerEmail = (order.contact_email ?: order.email)?.toString()
            }
        }
    }
} catch (Exception e) {
    log.warn("notifyRefundTaskCreated: could not resolve order summary: ${e.message}")
}

// --- Load notification configuration -----------------------------------------
def configNode
try {
    configNode = repositorySession.getResource("/etc/commerce/config/notifications.yml")
} catch (Exception e) {
    log.info("notifyRefundTaskCreated: notifications.yml not accessible - skipping notification")
    return
}
if (configNode == null || !configNode.exists()) {
    log.info("notifyRefundTaskCreated: notifications.yml not found - skipping notification")
    return
}

def config
try {
    config = YAML.parse(configNode)
} catch (Exception e) {
    log.warn("notifyRefundTaskCreated: failed to parse notifications.yml: ${e.message}")
    return
}

// --- Build the notification message ------------------------------------------
// One channel-agnostic message; each enabled channel renders it in its own format.
def itemsText = null
if (lineItemCount != null && lineItemCount > 0) {
    itemsText = "${lineItemCount}" + (restockedText ? " (restocked: ${restockedText})" : "")
}

def message = NotificationMessage.create()
    .title("💸", "Refund review workflow")
    .status("⚠", "Refund review required")
    .field("Refund", amountText)
    .text(orderLabel)
    .field("Refund ID", refundID)
    .field("Customer", customerEmail)
    .field("Items", itemsText)
    .field("Note", note?.trim())
    .bullets("Reasons", reasons)
    .footer(taskName, assignee)

// --- Dispatch to each enabled channel ----------------------------------------
Notifications.dispatch(log, "notifyRefundTaskCreated", config, message)
