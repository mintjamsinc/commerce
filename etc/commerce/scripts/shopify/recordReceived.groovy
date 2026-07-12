// Completion service of the "Receive Confirmation" task — one flow PER inventory item.
// The reorder recorded as INCOMING by createIncomingTransfer has physically arrived; the
// operator confirmed it on the receiving form (arrival date + received qty), which saved it into the
// product node's commerce:reorder_receipt map (a PER-ITEM map; a NODE property, not a process
// variable, because a form's setProcessVariables has REPLACE semantics and would drop the
// sweep's process variables).
//
// Two things happen here:
//  1) CMS record — merge the receipt (arrival date + optional qty) into the durable
//     commerce:reorder_last_orders map, on the SAME per-item entry createIncomingTransfer
//     wrote its {at, qty, transferId}. The next Inventory & Reorder Review of this item then
//     shows the previous LEAD TIME (received − ordered). Notes stay in the shared internal_memo.
//  2) Shopify RECEIVE — reflect the receipt to Shopify via ShopifyWrite.receiveInventoryTransfer,
//     moving the received units from Incoming to Available at the destination (they become
//     sellable). Receipt is SHIPMENT-based (there is no inventoryTransferReceive): resolve/create
//     the transfer's shipment, mark it in-transit if DRAFT, then inventoryShipmentReceive. Best-
//     effort: a Shopify failure is audited and NEVER blocks completion. This closes the reorder →
//     receive loop in the CMS (no Shopify-admin step). Needs the transferId createIncomingTransfer
//     stored on last_orders[itemId].
//
// Process variables (mapped in via `inputs`): productPath, productID, inventoryItemId,
// variantTitle. Best-effort: a failure is logged and NEVER blocks completion.

import java.net.http.HttpClient
import commerce.Api
import commerce.ShopifyAdmin
import commerce.ShopifyWrite
import commerce.SyncAudit

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }

def path = hv("productPath")?.toString()
if (!path) {
    log.warn("recordReceived: no productPath - skipping")
    return
}
def resource = repositorySession.getResource(path)
if (resource == null || !resource.exists()) {
    log.warn("recordReceived: product not found: ${path} - skipping")
    return
}

def itemId = hv("inventoryItemId")?.toString()
if (!itemId || itemId.trim().isEmpty()) {
    log.warn("recordReceived: no inventoryItemId - cannot record receipt")
    return
}

def prop = { String name ->
    try {
        if (resource.hasProperty(name)) return resource.getProperty(name).getValue()?.toString()
    } catch (Exception ignore) {}
    return null
}

// This item's receipt, from the product-node commerce:reorder_receipt map.
def receipt = [:]
def rRaw = prop("commerce:reorder_receipt")
if (rRaw) {
    try {
        def m = api.util.JSON.parse(rRaw)
        if (m instanceof Map && m[itemId] instanceof Map) receipt = m[itemId]
    } catch (Exception ignore) {}
}
int receivedQty = 0
try { receivedQty = (receipt.qty != null ? receipt.qty.toString() : "0").trim() as int } catch (Exception ignore) {}
def receivedBy = receipt.by?.toString() ?: "workflow"
// Arrival date — the operator's chosen received date, already a ms-precision UTC instant.
def receivedAt = receipt.at?.toString()
if (receivedAt == null || receivedAt.trim().isEmpty()) receivedAt = Api.now()

// --- 1) CMS record: merge the receipt into commerce:reorder_last_orders[itemId] AND clear the
// item's receipt entry, in ONE read-modify-write. Also capture the transferId (stored by
// createIncomingTransfer) for the Shopify receive below. Per-item flows can commit these shared
// properties at once — retry on conflict. Best-effort.
def transferId = null
final int WRITE_RETRIES = 6
for (int attempt = 0; attempt < WRITE_RETRIES; attempt++) {
    try {
        def res = repositorySession.getResource(path)
        def loRaw = (res != null && res.hasProperty("commerce:reorder_last_orders")) ? res.getProperty("commerce:reorder_last_orders").getValue()?.toString() : null
        def lastOrders = [:]
        if (loRaw) { try { lastOrders = api.util.JSON.parse(loRaw) ?: [:] } catch (Exception ignore) {} }
        def entry = (lastOrders[itemId] instanceof Map) ? lastOrders[itemId] : [:]
        transferId = entry.transferId
        entry.receivedAt = receivedAt
        if (receivedQty > 0) entry.receivedQty = receivedQty
        if (receivedBy != null && !receivedBy.trim().isEmpty()) entry.receivedBy = receivedBy
        lastOrders[itemId] = entry
        res.setProperty("commerce:reorder_last_orders", api.util.JSON.stringify(lastOrders))
        // remove receipt[itemId]
        def recRaw = (res != null && res.hasProperty("commerce:reorder_receipt")) ? res.getProperty("commerce:reorder_receipt").getValue()?.toString() : null
        if (recRaw) {
            def rm = [:]
            try { rm = api.util.JSON.parse(recRaw) ?: [:] } catch (Exception ignore) {}
            rm.remove(itemId)
            res.setProperty("commerce:reorder_receipt", api.util.JSON.stringify(rm))
        }
        repositorySession.commit()
        log.info("recordReceived: recorded receipt for item ${itemId} (qty ${receivedQty}) on product ${hv('productID')}")
        break
    } catch (Exception e) {
        try { repositorySession.rollback() } catch (Exception ignore) {}
        if (attempt == WRITE_RETRIES - 1) {
            log.warn("recordReceived: could not record receipt for item ${itemId}: ${e.message}")
        } else {
            try { Thread.sleep(20L * (attempt + 1)) } catch (Exception ignore) {}
        }
    }
}

// --- 2) Shopify receive: move the received units Incoming -> Available on the transfer.
// Best-effort: a failure is audited and NEVER blocks completion (same as createIncomingTransfer).
def receiveRequest = [inventoryItemId: itemId, transferId: transferId, quantity: receivedQty,
                      receivedAt: receivedAt, productId: hv("productID")?.toString()]
if (transferId && receivedQty > 0) {
    try {
        def cfgNode = repositorySession.getResource("/etc/commerce/config/shopify.yml")
        def config = YAML.parse(cfgNode)
        if (!ShopifyAdmin.adminApiEnabled(config)) {
            log.warn("recordReceived: Admin API not configured - receipt NOT reflected in Shopify")
            SyncAudit.record(repositorySession, log, "receive_transfer", receiveRequest, "failed", null, "Admin API not configured", receivedBy, "inventory_item", itemId)
        } else {
            def adminApi = config.adminApi
            def endpoint = ShopifyAdmin.endpoint(adminApi)
            def token = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
            def client = HttpClient.newHttpClient()
            def result = ShopifyWrite.receiveInventoryTransfer(client, endpoint, token,
                transferId, [[inventoryItemId: itemId, quantity: receivedQty]], receivedAt)
            SyncAudit.record(repositorySession, log, "receive_transfer", receiveRequest, "ok", result, null, receivedBy, "inventory_item", itemId)
            log.info("recordReceived: received ${receivedQty} on transfer ${transferId} for item ${itemId} (${result.status})")
        }
    } catch (Exception e) {
        SyncAudit.record(repositorySession, log, "receive_transfer", receiveRequest, "failed", null, e.message, receivedBy, "inventory_item", itemId)
        log.warn("recordReceived: Shopify receive failed: ${e.message} - receipt recorded in CMS, see the sync audit trail")
    }
} else if (receivedQty > 0 && !transferId) {
    // The order predates transferId storage (or the create failed), so there is no transfer to
    // receive. The CMS receipt is still recorded; the operator can receive in Shopify manually.
    log.warn("recordReceived: no transferId on last_orders[${itemId}] - receipt NOT reflected in Shopify (receive it in the Shopify admin)")
    SyncAudit.record(repositorySession, log, "receive_transfer", receiveRequest, "failed", null, "no transferId on the reorder record", receivedBy, "inventory_item", itemId)
}
