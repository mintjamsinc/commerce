// Process a reorder decision at the end of replenishment-flow.bpmn.
//
// Reads the decision the operator recorded on the reorder record (via the
// approval form): reorder:decision (approved|rejected), reorder:approved_qty,
// reorder:note. On rejection the proposal is marked rejected. On approval the
// purchase order is recorded and sent to the supplier per reorder.yml:
//   delivery none    → recorded only (status "approved"; order manually)
//   delivery email   → emailed to the supplier via the notifications.yml SMTP
//                      transport (status "ordered" / "order_failed")
//   delivery webhook → POSTed as JSON to the supplier endpoint
//
// Best-effort sending: a delivery failure is recorded on the PO (order_failed),
// never thrown, so the workflow always completes.
//
// Required process variable:
//   reorderPath : repository path to the reorder/PO record

import commerce.SmtpClient
import commerce.Replenishment
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import com.fasterxml.jackson.databind.ObjectMapper

if (!reorderPath) {
    throw new IllegalArgumentException("Required variable 'reorderPath' is missing")
}

def resource = repositorySession.getResource(reorderPath)
if (resource == null || !resource.exists()) {
    log.warn("purchaseOrder: reorder record not found: ${reorderPath} - skipping")
    return
}

def mapper = new ObjectMapper()
def record
try {
    record = mapper.readValue(resource.content.toString(), Map.class)
} catch (Exception e) {
    record = [:]
}

def decision = prop(resource, "reorder:decision")
def approvedQty = intOrNull(prop(resource, "reorder:approved_qty"))
if (approvedQty == null) {
    approvedQty = intOrNull(record.suggestedQty)
}
def note = prop(resource, "reorder:note")

// --- Rejected ---------------------------------------------------------------
if (decision == "rejected") {
    finalize(resource, record, "rejected", [decidedAt: java.time.Instant.now().toString(), note: note], mapper, log, repositorySession)
    log.info("purchaseOrder: reorder ${record.id} rejected")
    return
}

// --- Approved: build the purchase order -------------------------------------
record.approvedQty = approvedQty
record.note = note
record.approvedAt = java.time.Instant.now().toString()

def po = [
    poId        : record.id,
    productId   : record.productId,
    productTitle: record.title,
    variantId   : record.variantId,
    variantTitle: record.variantTitle,
    quantity    : approvedQty,
    note        : note,
    createdAt   : record.approvedAt,
]

// Supplier delivery config (reorder.yml). The email transport is reused from
// notifications.yml's `email` block; only the recipient lives in reorder.yml.
def reorderCfg = loadYaml(Replenishment.CONFIG_PATH)
def supplier = reorderCfg?.supplier ?: [:]
def delivery = (supplier.delivery ?: "none").toString().trim().toLowerCase()

def status = "approved"
def deliveryInfo = [:]
try {
    if (delivery == "email") {
        def transport = (loadYaml("/etc/commerce/config/notifications.yml")?.email) ?: [:]
        def to = supplier.email?.toString()
        if (!transport.smtpHost || !to || to.startsWith("REPLACE")) {
            throw new RuntimeException("supplier email or SMTP transport not configured")
        }
        SmtpClient.send([
            host    : transport.smtpHost,
            port    : (transport.smtpPort ?: "587").toString(),
            security: (transport.security ?: "starttls").toString(),
            username: transport.username,
            password: transport.password,
            from    : transport.from,
            to      : to,
            subject : "Purchase Order ${record.id} - ${record.title ?: record.variantId}",
            body    : poText(po),
        ])
        status = "ordered"
        deliveryInfo = [via: "email", to: to]
    } else if (delivery == "webhook") {
        def url = supplier.webhookUrl?.toString()
        if (!url || url.startsWith("REPLACE")) {
            throw new RuntimeException("supplier webhookUrl not configured")
        }
        def res = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(po)))
                .build(),
            HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new RuntimeException("supplier webhook returned ${res.statusCode()}")
        }
        status = "ordered"
        deliveryInfo = [via: "webhook", url: url, statusCode: res.statusCode()]
    } else {
        // delivery == none: recorded only, to be ordered manually.
        deliveryInfo = [via: "none"]
    }
} catch (Exception e) {
    status = "order_failed"
    deliveryInfo = [via: delivery, error: e.message]
    log.warn("purchaseOrder: supplier delivery failed for ${record.id}: ${e.message}")
}

record.delivery = deliveryInfo
finalize(resource, record, status, [:], mapper, log, repositorySession)
log.info("purchaseOrder: reorder ${record.id} → ${status} (qty ${approvedQty}, delivery ${delivery})")

// --- Helpers -----------------------------------------------------------------

void finalize(resource, Map record, String status, Map extra, mapper, log, session) {
    try {
        record.status = status
        extra.each { k, v -> if (v != null) record[k] = v }
        resource.write(mapper.writeValueAsString(record))
        resource.setProperty("commerce:status", status)
        session.commit()
    } catch (Exception e) {
        try { session.rollback() } catch (Exception ignore) {}
        log.warn("purchaseOrder: could not finalize record as '${status}': ${e.message}")
    }
}

String poText(Map po) {
    def sb = new StringBuilder()
    sb.append("Purchase Order ").append(po.poId ?: "").append("\n\n")
    sb.append("Product: ").append(po.productTitle ?: po.productId ?: "").append("\n")
    if (po.variantTitle && po.variantTitle != "Default Title") {
        sb.append("Variant: ").append(po.variantTitle).append("\n")
    }
    sb.append("Variant ID: ").append(po.variantId ?: "").append("\n")
    sb.append("Quantity: ").append(po.quantity).append("\n")
    if (po.note) {
        sb.append("Note: ").append(po.note).append("\n")
    }
    sb.append("\nCreated: ").append(po.createdAt ?: "")
    return sb.toString()
}

def loadYaml(String path) {
    try {
        def node = repositorySession.getResource(path)
        if (node != null && node.exists()) {
            return YAML.parse(node)
        }
    } catch (Exception e) {
        log.warn("purchaseOrder: could not parse ${path}: ${e.message}")
    }
    return null
}

String prop(resource, String name) {
    try {
        if (resource.hasProperty(name)) {
            def v = resource.getProperty(name).getValue()
            return v == null ? null : v.toString()
        }
    } catch (Exception ignore) {}
    return null
}

Integer intOrNull(v) {
    if (v == null) return null
    if (v instanceof Number) return ((Number) v).intValue()
    try { return Integer.valueOf(v.toString().trim()) } catch (Exception e) { return null }
}
