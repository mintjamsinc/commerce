// Task listener: notify configured destinations when a "Release Backorder" task is
// created in the backorder-release workflow (feature #12).
//
// Attached as a Camunda "create" task listener via CmsDelegate. The awaited stock
// for a backordered order line has arrived, so an operator is asked to release it
// to fulfilment. Reads the backorder record (backorderPath) for the item/quantity
// and dispatches a channel-agnostic message to every enabled channel (see
// commerce.Notifications). A notification failure never breaks the workflow.

import commerce.Notifications
import commerce.NotificationMessage

def taskName = task?.getName() ?: "Release Backorder"
def assignee = task?.getAssignee()

def backorderPath = Notifications.taskVar(task, "backorderPath")
def orderID = Notifications.taskVar(task, "order_id")

// Honour the feature's onReady toggle (channels still gate themselves too).
try {
    def cfgRes = repositorySession.getResource("/etc/commerce/config/backorder.yml")
    if (cfgRes != null && cfgRes.exists()) {
        def cfg = YAML.parse(cfgRes)
        if (cfg?.notify?.onReady != null && cfg.notify.onReady.toString().toLowerCase() == "false") {
            return
        }
    }
} catch (Exception e) {
    log.warn("notifyBackorderReady: could not read backorder.yml: ${e.message}")
}

// Read backorder details from the record (best-effort).
def title = null
def variantTitle = null
def quantity = null
def reason = null
def orderNumber = null
def customerEmail = null
try {
    if (backorderPath) {
        def resource = repositorySession.getResource(backorderPath)
        if (resource != null && resource.exists()) {
            def record = JSON.parse(resource.content.toString())
            if (record != null) {
                title = record.title?.toString()
                variantTitle = record.variant_title?.toString()
                quantity = record.quantity
                reason = record.reason?.toString()
                orderNumber = record.order_number?.toString()
                customerEmail = record.customer_email?.toString()
            }
        }
    }
} catch (Exception e) {
    log.warn("notifyBackorderReady: could not read backorder record: ${e.message}")
}

def configNode
try {
    configNode = repositorySession.getResource("/etc/commerce/config/notifications.yml")
} catch (Exception e) {
    log.info("notifyBackorderReady: notifications.yml not accessible - skipping notification")
    return
}
if (configNode == null || !configNode.exists()) {
    log.info("notifyBackorderReady: notifications.yml not found - skipping notification")
    return
}

def config
try {
    config = YAML.parse(configNode)
} catch (Exception e) {
    log.warn("notifyBackorderReady: failed to parse notifications.yml: ${e.message}")
    return
}

def named = { t -> t != null && !t.toString().trim().isEmpty() && t.toString() != "Default Title" }
def orderLabel = orderNumber ? "Order #${orderNumber}" : (orderID ? "Order ${orderID}" : "an order")

def message = NotificationMessage.create()
    .title("📦", "Backorder workflow")
    .status("✅", "Backorder ready to release")
    .text(orderLabel)
    .field("Item", title ?: "a product")
if (named(variantTitle)) {
    message.field("Variant", variantTitle)
}
message
    .field("Quantity", quantity)
    .field("Reason", reason)
    .field("Customer", customerEmail)
    .footer(taskName, assignee)

Notifications.dispatch(log, "notifyBackorderReady", config, message)
