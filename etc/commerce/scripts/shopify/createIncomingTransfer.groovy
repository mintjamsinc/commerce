// Completion service of the "Inventory & Reorder Review" task — one flow PER
// inventory item (variant). The operator entered the CONFIRMED quantity (+ destination
// + order date) on the review form, which saved it into the product node's
// commerce:reorder_draft map (a PER-ITEM map like commerce:reorder_last_orders — reorder
// flows are per item, so several variants of one product can be drafted at once without
// colliding). The handoff is a NODE property, NOT a process variable, because a form's
// setProcessVariables has REPLACE semantics (it would drop the sweep's process variables).
//
// This service records that quantity in Shopify as INCOMING stock, moves the draft into the
// durable commerce:reorder_last_orders map (so the NEXT review shows previous order date / quantity / arrival date / lead time),
// and clears the item's draft entry. Receiving happens in the Shopify admin and flows back via
// inventory_levels/update — the one-way data flow holds.
//
// A confirmed quantity of 0 / no entry means "reviewed, no reorder needed" (the form already
// cleared the draft). Best-effort: a Shopify failure is recorded in the outbound audit trail
// (commerce.SyncAudit) and NEVER blocks workflow completion.
//
// Process variables (mapped in via `inputs`): productPath, productID, inventoryItemId,
// variantId, variantTitle.

import java.net.http.HttpClient
import commerce.Api
import commerce.ShopifyAdmin
import commerce.ShopifyWrite
import commerce.SyncAudit

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }

// Publish the gateway signal FIRST (default false), BEFORE any early return, so the
// exclusive gateway `${reorderPlaced == true}` can always resolve it. Refined below.
context.setAttribute("reorderPlaced", false)

def path = hv("productPath")?.toString()
if (!path) {
    log.warn("createIncomingTransfer: no productPath - skipping")
    return
}
def resource = repositorySession.getResource(path)
if (resource == null || !resource.exists()) {
    log.warn("createIncomingTransfer: product not found: ${path} - skipping")
    return
}

def prop = { String name ->
    try {
        if (resource.hasProperty(name)) return resource.getProperty(name).getValue()?.toString()
    } catch (Exception ignore) {}
    return null
}

def itemId = hv("inventoryItemId")?.toString()

// This item's confirmed reorder draft, from the product-node commerce:reorder_draft map.
def draft = [:]
if (itemId) {
    def raw = prop("commerce:reorder_draft")
    if (raw) {
        try {
            def m = api.util.JSON.parse(raw)
            if (m instanceof Map && m[itemId] instanceof Map) draft = m[itemId]
        } catch (Exception ignore) {}
    }
}
int confirmedQty = 0
try { confirmedQty = (draft.qty != null ? draft.qty.toString() : "0").trim() as int } catch (Exception ignore) {}
def destination = draft.destination?.toString()
def origin = null   // origin-location UI not implemented; optional on the Shopify transfer
def actor = draft.by?.toString() ?: "workflow"
// Auto-enable fulfillment: when the destination does not yet stock this item, activate it there
// before creating the transfer (the form's checkbox, default ON). Absent on legacy drafts → ON.
def activateLocation = true
if (draft.containsKey("activate")) {
    def a = draft.activate
    activateLocation = (a == true) || (a?.toString()?.trim()?.toLowerCase() == "true")
}
// Order date — the operator's chosen order date, already a ms-precision UTC instant. Fall back
// to now if the form did not supply one.
def orderedAt = draft.at?.toString()
if (orderedAt == null || orderedAt.trim().isEmpty()) orderedAt = Api.now()

// Refine the gateway signal now that the draft is known: a reorder was actually placed iff a
// positive quantity was confirmed for a known inventory item. The gateway routes to the
// receiving-confirmation task only when true.
context.setAttribute("reorderPlaced", confirmedQty > 0 && itemId != null && !itemId.trim().isEmpty())

if (confirmedQty <= 0) {
    log.info("createIncomingTransfer: no confirmed quantity for item ${itemId} (product ${hv('productID')}) - review completed without reorder")
    return
}

// Move the draft into the durable "last order" reference AND clear the item's draft entry, in
// ONE read-modify-write (both are maps on the SAME product node). Because reorder flows are per
// item, two sibling-variant flows can commit these shared properties at once — retry on conflict
// so neither update is lost. This does NOT depend on the Shopify write below (the operator ordered
// through their own channel regardless). Best-effort: exhausted retries are logged, never fatal.
if (itemId) {
    final int WRITE_RETRIES = 6
    for (int attempt = 0; attempt < WRITE_RETRIES; attempt++) {
        try {
            def res = repositorySession.getResource(path)
            // last_orders[itemId] = { at, qty }
            def loRaw = (res != null && res.hasProperty("commerce:reorder_last_orders")) ? res.getProperty("commerce:reorder_last_orders").getValue()?.toString() : null
            def lastOrders = [:]
            if (loRaw) { try { lastOrders = api.util.JSON.parse(loRaw) ?: [:] } catch (Exception ignore) {} }
            def entry = (lastOrders[itemId] instanceof Map) ? lastOrders[itemId] : [:]
            entry.at = orderedAt
            entry.qty = confirmedQty
            lastOrders[itemId] = entry
            res.setProperty("commerce:reorder_last_orders", api.util.JSON.stringify(lastOrders))
            // remove draft[itemId]
            def dRaw = (res != null && res.hasProperty("commerce:reorder_draft")) ? res.getProperty("commerce:reorder_draft").getValue()?.toString() : null
            if (dRaw) {
                def dm = [:]
                try { dm = api.util.JSON.parse(dRaw) ?: [:] } catch (Exception ignore) {}
                dm.remove(itemId)
                res.setProperty("commerce:reorder_draft", api.util.JSON.stringify(dm))
            }
            repositorySession.commit()
            break
        } catch (Exception e) {
            try { repositorySession.rollback() } catch (Exception ignore) {}
            if (attempt == WRITE_RETRIES - 1) {
                log.warn("createIncomingTransfer: could not persist last-order / clear draft for item ${itemId}: ${e.message}")
            } else {
                try { Thread.sleep(20L * (attempt + 1)) } catch (Exception ignore) {}
            }
        }
    }
}

def request = [
    inventoryItemId: itemId,
    variantId      : hv("variantId")?.toString(),
    variantTitle   : hv("variantTitle")?.toString(),
    productId      : hv("productID")?.toString(),
    quantity       : confirmedQty,
    destinationLocationId: destination,
    originLocationId: origin,
    orderedAt      : orderedAt,
    autoActivate   : activateLocation,
]

if (!itemId || !destination) {
    log.warn("createIncomingTransfer: missing inventoryItemId/destination - cannot record incoming stock")
    SyncAudit.record(repositorySession, log, "incoming_transfer", request, "failed", null,
        "missing inventoryItemId or destination location", actor, "inventory_item", itemId)
    return
}

try {
    def cfgNode = repositorySession.getResource("/etc/commerce/config/shopify.yml")
    def config = YAML.parse(cfgNode)
    if (!ShopifyAdmin.adminApiEnabled(config)) {
        log.warn("createIncomingTransfer: Admin API not configured - incoming stock NOT recorded in Shopify")
        SyncAudit.record(repositorySession, log, "incoming_transfer", request, "failed", null, "Admin API not configured", actor, "inventory_item", itemId)
        return
    }
    def adminApi = config.adminApi
    def endpoint = ShopifyAdmin.endpoint(adminApi)
    def token = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
    def client = HttpClient.newHttpClient()

    // Ensure the destination stocks this item (activate if OFF) so the incoming transfer is
    // accepted — the operator explicitly chose this destination and left the auto-enable checkbox
    // ON. Best-effort: a failure here is logged; the create below surfaces the real "not stocked at
    // this location" error into the audit trail if activation was actually required.
    if (activateLocation) {
        try {
            def act = ShopifyWrite.ensureStockedAt(client, endpoint, token, itemId, destination)
            if (act?.activated) log.info("createIncomingTransfer: activated item ${itemId} at location ${destination} (was not stocked)")
        } catch (Exception ae) {
            log.warn("createIncomingTransfer: could not ensure item ${itemId} is stocked at ${destination}: ${ae.message}")
        }
    }

    def reference = "CMS reorder ${hv('productID')}/${request.variantId ?: itemId}".toString()
    // Note is deliberately null — the operator keeps notes in the shared internal_memo.
    def result = ShopifyWrite.createIncomingTransfer(client, endpoint, token,
        destination, [[inventoryItemId: itemId, quantity: confirmedQty]], reference, null, origin)

    // Store the Shopify transfer id on the last-order entry so the RECEIVE task can call
    // inventoryTransferReceive against it. Best-effort (retry the shared-node write).
    if (result?.transferId && itemId) {
        for (int a = 0; a < 6; a++) {
            try {
                def r2 = repositorySession.getResource(path)
                def loRaw2 = (r2 != null && r2.hasProperty("commerce:reorder_last_orders")) ? r2.getProperty("commerce:reorder_last_orders").getValue()?.toString() : null
                def lo2 = [:]
                if (loRaw2) { try { lo2 = api.util.JSON.parse(loRaw2) ?: [:] } catch (Exception ig) {} }
                def e2 = (lo2[itemId] instanceof Map) ? lo2[itemId] : [:]
                e2.transferId = result.transferId
                lo2[itemId] = e2
                r2.setProperty("commerce:reorder_last_orders", api.util.JSON.stringify(lo2))
                repositorySession.commit()
                break
            } catch (Exception ex) {
                try { repositorySession.rollback() } catch (Exception ig) {}
                if (a == 5) log.warn("createIncomingTransfer: could not store transferId for item ${itemId}: ${ex.message}")
                else try { Thread.sleep(20L * (a + 1)) } catch (Exception ig) {}
            }
        }
    }

    SyncAudit.record(repositorySession, log, "incoming_transfer", request, "ok", result, null, actor, "inventory_item", itemId)
    log.info("createIncomingTransfer: recorded ${confirmedQty} incoming for item ${itemId} -> location ${destination} (${result.transferId})")
} catch (Exception e) {
    SyncAudit.record(repositorySession, log, "incoming_transfer", request, "failed", null, e.message, actor, "inventory_item", itemId)
    log.warn("createIncomingTransfer: Shopify write failed: ${e.message} - review completed, see the sync audit trail")
}
