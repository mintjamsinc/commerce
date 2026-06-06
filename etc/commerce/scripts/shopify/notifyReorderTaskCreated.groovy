// Task listener: notify configured destinations when an "Approve Reorder" task is
// created in the replenishment workflow.
//
// Attached as a Camunda "create" task listener via CmsDelegate. Reads the reorder
// proposal record (reorderPath) for the suggested quantity, current stock and
// velocity, and dispatches a channel-agnostic message to every enabled channel
// (see commerce.Notifications). A notification failure never breaks the workflow.

import commerce.Notifications
import commerce.NotificationMessage

def taskName = task?.getName() ?: "Approve Reorder"
def assignee = task?.getAssignee()

def reorderPath = Notifications.taskVar(task, "reorderPath")
def productTitle = Notifications.taskVar(task, "productTitle")
def variantTitle = Notifications.taskVar(task, "variantTitle")

// Read proposal details from the reorder record (best-effort).
def suggestedQty = null
def currentStock = null
def velocity = null
try {
    if (reorderPath) {
        def resource = repositorySession.getResource(reorderPath)
        if (resource != null && resource.exists()) {
            def record = JSON.parse(resource.content.toString())
            if (record != null) {
                suggestedQty = record.suggestedQty
                currentStock = record.currentStock
                velocity = record.velocity
                if (!productTitle && record.title) {
                    productTitle = record.title.toString()
                }
            }
        }
    }
} catch (Exception e) {
    log.warn("notifyReorderTaskCreated: could not read reorder record: ${e.message}")
}

def configNode
try {
    configNode = repositorySession.getResource("/etc/commerce/config/notifications.yml")
} catch (Exception e) {
    log.info("notifyReorderTaskCreated: notifications.yml not accessible - skipping notification")
    return
}
if (configNode == null || !configNode.exists()) {
    log.info("notifyReorderTaskCreated: notifications.yml not found - skipping notification")
    return
}

def config
try {
    config = YAML.parse(configNode)
} catch (Exception e) {
    log.warn("notifyReorderTaskCreated: failed to parse notifications.yml: ${e.message}")
    return
}

def named = { t -> t != null && !t.toString().trim().isEmpty() && t.toString() != "Default Title" }

def message = NotificationMessage.create()
    .title("🛒", "Replenishment workflow")
    .status("📦", "Reorder approval required")
    .field("Product", productTitle ?: "a product")
if (named(variantTitle)) {
    message.field("Variant", variantTitle)
}
message
    .field("Suggested qty", suggestedQty)
    .field("In stock", currentStock)
    .field("Velocity", velocity == null ? null : "${velocity}/day")
    .footer(taskName, assignee)

Notifications.dispatch(log, "notifyReorderTaskCreated", config, message)
