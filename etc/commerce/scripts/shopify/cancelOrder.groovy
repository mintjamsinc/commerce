// Cancel a rejected order in Shopify (the reject branch of order-review-flow).
//
// The operator rejected the order with a required reason
// (commerce:cancel_reason on the order node). This service kicks the Shopify
// Admin API Order Cancel (restock + refund; the reason rides as the staff
// note). The cancellation result flows back through the normal webhook path
// (one-way data flow) — this script only writes the outbound call + outcome:
//
//   commerce:cancel_writeback : ok / failed / skipped   (typed like the
//   commerce:cancel_error     : detail on failure        fulfillment write-back)
//   commerce:cancelled_at     : Date on success
//
// plus an outbound audit record (commerce.SyncAudit). A failed write never
// breaks the workflow — the ops console surfaces it for retry by hand.
//
// Process variables (mapped in via `inputs`): orderPath, order_id.

import java.net.http.HttpClient
import commerce.ShopifyAdmin
import commerce.ShopifyWrite
import commerce.SyncAudit

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }
def path = hv("orderPath")?.toString()
def orderId = hv("order_id")?.toString()
if (!path || !orderId) {
    log.warn("cancelOrder: missing orderPath/order_id - skipping")
    return
}
def resource = repositorySession.getResource(path)
if (resource == null || !resource.exists()) {
    log.warn("cancelOrder: order not found: ${path} - skipping")
    return
}

def reason = null
try {
    if (resource.hasProperty("commerce:cancel_reason")) {
        reason = resource.getProperty("commerce:cancel_reason").getValue()?.toString()
    }
} catch (Exception ignore) {}

// WHO: the operator who rejected the order on the review form captured their id
// onto the node (commerce:reviewed_by). Service tasks have no completing user,
// so fall back to "workflow". WHAT-TARGET: this order (audit).
def actor = null
try {
    if (resource.hasProperty("commerce:reviewed_by")) {
        actor = resource.getProperty("commerce:reviewed_by").getValue()?.toString()
    }
} catch (Exception ignore) {}
actor = actor ?: "workflow"

def request = [orderId: orderId, reason: reason]
def outcome = "skipped"
def error = null
try {
    def cfgNode = repositorySession.getResource("/etc/commerce/config/shopify.yml")
    def config = YAML.parse(cfgNode)
    if (!ShopifyAdmin.adminApiEnabled(config)) {
        error = "Admin API not configured"
        log.warn("cancelOrder: Admin API not configured - order ${orderId} NOT cancelled in Shopify")
    } else {
        def adminApi = config.adminApi
        def endpoint = ShopifyAdmin.endpoint(adminApi)
        def token = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
        def client = HttpClient.newHttpClient()
        boolean notifyCustomer = adminApi.notifyCustomer?.toString()?.toLowerCase() == "true"
        def result = ShopifyWrite.cancelOrder(client, endpoint, token, orderId, "OTHER", reason, notifyCustomer)
        outcome = "ok"
        SyncAudit.record(repositorySession, log, "order_cancel", request, "ok", result, null, actor, "order", orderId)
        log.info("cancelOrder: order ${orderId} cancelled in Shopify (reason: ${reason})")
    }
} catch (Exception e) {
    outcome = "failed"
    error = e.message
    SyncAudit.record(repositorySession, log, "order_cancel", request, "failed", null, e.message, actor, "order", orderId)
    log.warn("cancelOrder: Shopify cancel failed for order ${orderId}: ${e.message}")
}
if (outcome == "skipped" && error != null) {
    SyncAudit.record(repositorySession, log, "order_cancel", request, "failed", null, error, actor, "order", orderId)
    outcome = "failed"
}

// Record the outcome on the order (defensive).
try {
    resource.setProperty("commerce:cancel_writeback", outcome)
    if (error != null) {
        resource.setProperty("commerce:cancel_error", error.length() > 2048 ? error.substring(0, 2048) : error)
    } else if (outcome == "ok") {
        resource.setProperty("commerce:cancelled_at", new java.util.Date())
    }
    repositorySession.commit()
} catch (Exception e) {
    try { repositorySession.rollback() } catch (Exception ignore) {}
    log.warn("cancelOrder: could not record outcome on ${path}: ${e.message}")
}
