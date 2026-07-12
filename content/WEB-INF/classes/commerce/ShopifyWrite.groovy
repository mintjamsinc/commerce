package commerce

import java.net.http.HttpClient

/**
 * Outbound CMS → Shopify writes.
 *
 * The platform already RECEIVES from Shopify (webhooks) and writes a fulfillment
 * back at the end of the order workflow. This class generalizes the write side into
 * the three corrections operators most need to push from the CMS:
 *
 *   setInventory   — set a variant's available quantity at a location (stock fix)
 *   updatePrice    — set a variant's price
 *   setPublished   — publish / unpublish a product (status ACTIVE / DRAFT)
 *
 * Each method builds a Shopify Admin GraphQL mutation and runs it through
 * {@link ShopifyAdmin}. They are NOT defensive: a transport error or a Shopify
 * {@code userErrors} entry is raised so the caller (the sync endpoint) can report
 * the outcome and record health — mirroring recordFulfillment's write-back policy.
 * Caller supplies an HttpClient so it can be reused.
 *
 * Ids may be passed as raw numeric ids or full gids; they are normalized to gids.
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.ShopifyWrite}.
 *
 * NOTE: the Product 360 writes — {@link #updateProduct} (base fields) and the media
 * mutations ({@link #addProductMedia}, {@link #deleteProductMedia},
 * {@link #reorderProductMedia}, {@link #updateProductMediaAlt}) — are shaped for the
 * configured Admin API version (target 2026-01). productReorderMedia is ASYNC (returns
 * a job; the mirror catches up via products/update later), and alt edits go through
 * {@code fileUpdate} because {@code productUpdateMedia} is deprecated at 2026-01. These
 * shapes must be smoke-tested live against the configured API version, the same as
 * cancelOrder / createIncomingTransfer.
 */
class ShopifyWrite {

    /**
     * Set the available quantity of an inventory item at a location (absolute set,
     * i.e. a correction). Returns a result map { name, reason, quantity }.
     */
    static Map setInventory(HttpClient client, String endpoint, String token,
                            inventoryItemId, locationId, int quantity, String reason = "correction") {
        def input = [
            name                : "available",
            reason              : (reason ?: "correction"),
            ignoreCompareQuantity: true,
            quantities          : [[
                inventoryItemId: gid("InventoryItem", inventoryItemId),
                locationId     : gid("Location", locationId),
                quantity       : quantity,
            ]],
        ]
        def mutation = '''
mutation inventorySet($input: InventorySetQuantitiesInput!) {
  inventorySetQuantities(input: $input) {
    inventoryAdjustmentGroup { reason createdAt }
    userErrors { field message }
  }
}
'''.trim()
        def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: mutation, variables: [input: input]])
        def result = resp?.data?.inventorySetQuantities
        raiseOnUserErrors("setInventory", result?.userErrors)
        return [name: "available", reason: input.reason, quantity: quantity]
    }

    /** Set a variant's price. Returns { productId, variantId, price }. */
    static Map updatePrice(HttpClient client, String endpoint, String token,
                           productId, variantId, String price) {
        def variables = [
            productId: gid("Product", productId),
            variants : [[id: gid("ProductVariant", variantId), price: price]],
        ]
        def mutation = '''
mutation priceUpdate($productId: ID!, $variants: [ProductVariantsBulkInput!]!) {
  productVariantsBulkUpdate(productId: $productId, variants: $variants) {
    productVariants { id price }
    userErrors { field message }
  }
}
'''.trim()
        def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: mutation, variables: variables])
        def result = resp?.data?.productVariantsBulkUpdate
        raiseOnUserErrors("updatePrice", result?.userErrors)
        return [productId: variables.productId, variantId: gid("ProductVariant", variantId), price: price]
    }

    /**
     * Publish (status ACTIVE) or unpublish (status DRAFT) a product. Returns
     * { productId, status }.
     */
    static Map setPublished(HttpClient client, String endpoint, String token,
                            productId, boolean published) {
        def status = published ? "ACTIVE" : "DRAFT"
        def input = [id: gid("Product", productId), status: status]
        def mutation = '''
mutation productStatus($input: ProductInput!) {
  productUpdate(input: $input) {
    product { id status }
    userErrors { field message }
  }
}
'''.trim()
        def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: mutation, variables: [input: input]])
        def result = resp?.data?.productUpdate
        raiseOnUserErrors("setPublished", result?.userErrors)
        return [productId: input.id, status: status]
    }

    /**
     * Set (upsert) metafields on a product (the PIM push). {@code metafields}
     * is a list of { namespace, key, type, value }; {@code ownerId} is the product
     * id (numeric or gid). Returns { ownerId, count }.
     */
    static Map setMetafields(HttpClient client, String endpoint, String token,
                             ownerId, List metafields) {
        if (!metafields) {
            throw new IllegalArgumentException("no metafields to set")
        }
        def owner = gid("Product", ownerId)
        def inputs = metafields.collect { m ->
            [
                ownerId  : owner,
                namespace: req(m?.namespace, "metafield namespace"),
                key      : req(m?.key, "metafield key"),
                type     : (m?.type ?: "single_line_text_field").toString(),
                value    : (m?.value == null ? "" : m.value.toString()),
            ]
        }
        def mutation = '''
mutation metafieldsSet($metafields: [MetafieldsSetInput!]!) {
  metafieldsSet(metafields: $metafields) {
    metafields { id namespace key }
    userErrors { field message }
  }
}
'''.trim()
        def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: mutation, variables: [metafields: inputs]])
        def result = resp?.data?.metafieldsSet
        raiseOnUserErrors("setMetafields", result?.userErrors)
        return [ownerId: owner, count: inputs.size()]
    }

    /**
     * Cancel an order (the reject path of the Order Review task). Uses the Admin
     * GraphQL {@code orderCancel} mutation with restock+refund and passes the
     * operator's reason as the staff note. {@code reason} is one of Shopify's
     * OrderCancelReason enums (CUSTOMER / DECLINED / FRAUD / INVENTORY / OTHER /
     * STAFF); the free-text goes to {@code staffNote}. Returns { orderId, reason }.
     * NOTE: mutation shape needs a live smoke test against the configured API
     * version (orderCancel argument set has shifted across versions).
     */
    static Map cancelOrder(HttpClient client, String endpoint, String token,
                           orderId, String reason, String staffNote, boolean notifyCustomer) {
        def variables = [
            orderId       : gid("Order", orderId),
            reason        : (reason ?: "OTHER"),
            staffNote     : (staffNote ?: ""),
            notifyCustomer: notifyCustomer,
            refund        : true,
            restock       : true,
        ]
        def mutation = '''
mutation orderCancel($orderId: ID!, $reason: OrderCancelReason!, $staffNote: String, $notifyCustomer: Boolean, $refund: Boolean!, $restock: Boolean!) {
  orderCancel(orderId: $orderId, reason: $reason, staffNote: $staffNote, notifyCustomer: $notifyCustomer, refund: $refund, restock: $restock) {
    job { id done }
    orderCancelUserErrors { field message }
    userErrors { field message }
  }
}
'''.trim()
        def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: mutation, variables: variables])
        def result = resp?.data?.orderCancel
        raiseOnUserErrors("cancelOrder", result?.orderCancelUserErrors)
        raiseOnUserErrors("cancelOrder", result?.userErrors)
        return [orderId: variables.orderId, reason: variables.reason]
    }

    /**
     * Record confirmed replenishment as INCOMING stock in Shopify (an inventory
     * transfer to the destination location) — the outbound of the unified
     * "Inventory & Reorder Review" task: the operator purchases through
     * their own channel and the confirmed quantity is written to Shopify as an
     * expected arrival; receiving happens in the Shopify admin and flows back via
     * inventory_levels/update. {@code lineItems} = [{ inventoryItemId, quantity }].
     * Returns { transferId, destination, items }.
     * NOTE: the Inventory Transfers Admin API shape must be smoke-tested live
     * against the configured API version.
     */
    static Map createIncomingTransfer(HttpClient client, String endpoint, String token,
                                      destinationLocationId, List lineItems, String referenceName, String note,
                                      originLocationId = null) {
        if (!lineItems) {
            throw new IllegalArgumentException("no line items for the incoming transfer")
        }
        // InventoryTransferCreateAsReadyToShipInput uses FLAT location ids
        // (destinationLocationId / originLocationId), not nested { locationId }.
        def input = [
            destinationLocationId: gid("Location", destinationLocationId),
            lineItems            : lineItems.collect { [inventoryItemId: gid("InventoryItem", it.inventoryItemId), quantity: (it.quantity as int)] },
        ]
        if (originLocationId != null && !originLocationId.toString().trim().isEmpty()) {
            input.originLocationId = gid("Location", originLocationId)
        }
        if (referenceName) input.referenceName = referenceName
        if (note) input.note = note
        def mutation = '''
mutation inventoryTransferCreateAsReadyToShip($input: InventoryTransferCreateAsReadyToShipInput!) {
  inventoryTransferCreateAsReadyToShip(input: $input) {
    inventoryTransfer { id status }
    userErrors { field message }
  }
}
'''.trim()
        def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: mutation, variables: [input: input]])
        def result = resp?.data?.inventoryTransferCreateAsReadyToShip
        raiseOnUserErrors("createIncomingTransfer", result?.userErrors)
        return [transferId: result?.inventoryTransfer?.id, destination: input.destinationLocationId,
                items: input.lineItems.size()]
    }

    /**
     * RECEIVE quantities on an incoming inventory transfer — the reorder physically arrived,
     * so move the received units from Incoming to Available at the destination (they become
     * sellable). Counterpart to {@link #createIncomingTransfer}, so the reorder → receive loop
     * completes in the CMS (no Shopify-admin step). {@code lineItems} =
     * [{ inventoryItemId, quantity }] (the received quantities; a partial receipt receives
     * fewer than the ordered qty and leaves the rest Incoming).
     *
     * Shopify receives on the SHIPMENT, not the transfer ({@code inventoryTransferReceive} does
     * NOT exist). The verified flow (2026-07-09, live-tested):
     *   (1) resolve the transfer's shipment via {@code inventoryTransfer.shipments}, creating one with
     *       {@code inventoryShipmentCreate} (input {@code { movementId, lineItems }}) when the
     *       "as ready to ship" transfer has none yet — a freshly created shipment is a DRAFT;
     *   (2) read the shipment's line items + status;
     *   (3) a DRAFT shipment CANNOT be received, so mark it in transit first
     *       ({@code inventoryShipmentMarkInTransit(id)});
     *   (4) receive via {@code inventoryShipmentReceive(id,
     *       lineItems: [InventoryShipmentReceiveItemInput!], dateReceived)} — the input is per shipment
     *       line item: {@code { shipmentLineItemId, quantity, reason: ACCEPTED }} (reason is the
     *       {@code InventoryShipmentReceiveLineItemReason} enum) — stamping {@code dateReceived} with the
     *       operator's receipt date.
     * Signatures confirmed by live introspection; this API version has NO
     * {@code InventoryShipmentLineItemByFulfillmentOrderInput} / {@code inventoryTransferReceive}.
     * Returns { shipmentId, status }.
     */
    static Map receiveInventoryTransfer(HttpClient client, String endpoint, String token,
                                        transferId, List lineItems, String dateReceived = null) {
        if (!transferId) {
            throw new IllegalArgumentException("no transferId for the receive")
        }
        if (!lineItems) {
            throw new IllegalArgumentException("no line items for the receive")
        }
        def tid = gid("InventoryTransfer", transferId)
        // Requested received quantity per inventory item (GID) — usually a single item per transfer.
        def wantQty = [:]
        lineItems.each { wantQty[gid("InventoryItem", it.inventoryItemId)] = (it.quantity as int) }

        // 1) Resolve the transfer's shipment (the receive is on the shipment, not the transfer).
        def shipQuery = '''
query($id: ID!) {
  inventoryTransfer(id: $id) {
    id
    status
    shipments(first: 10) { edges { node { id status } } }
  }
}
'''.trim()
        def shipResp = ShopifyAdmin.graphql(client, endpoint, token, [query: shipQuery, variables: [id: tid]])
        def shipEdges = shipResp?.data?.inventoryTransfer?.shipments?.edges ?: []
        def shipmentId = shipEdges ? shipEdges[0]?.node?.id : null

        // 1b) A transfer created "as ready to ship" has NO shipment yet — the physical movement is
        //     a separate SHIPMENT. If none exists, create one (it starts as a DRAFT; it is marked in
        //     transit below before receiving).
        if (!shipmentId) {
            def shipItems = lineItems.collect { [inventoryItemId: gid("InventoryItem", it.inventoryItemId), quantity: (it.quantity as int)] }
            // InventoryShipmentCreateInput: movementId (the transfer/PO id) + lineItems.
            def createShip = '''
mutation inventoryShipmentCreate($input: InventoryShipmentCreateInput!) {
  inventoryShipmentCreate(input: $input) {
    inventoryShipment { id status }
    userErrors { field message }
  }
}
'''.trim()
            def csResp = ShopifyAdmin.graphql(client, endpoint, token,
                [query: createShip, variables: [input: [movementId: tid, lineItems: shipItems]]])
            def csResult = csResp?.data?.inventoryShipmentCreate
            raiseOnUserErrors("createShipment", csResult?.userErrors)
            shipmentId = csResult?.inventoryShipment?.id
        }
        if (!shipmentId) {
            throw new RuntimeException("inventory transfer ${tid}: could not resolve or create a shipment to receive")
        }

        // 2) Read the shipment's line items to build the receive input. inventoryShipmentReceive
        //    takes lineItems: [InventoryShipmentReceiveItemInput!] — accept the received quantity
        //    per shipment LINE ITEM (by its id; a partial receipt leaves the rest unreceived).
        //    (Queried via node() — the top-level inventoryShipment(id:) field may not exist.)
        def liQuery = '''
query($id: ID!) {
  node(id: $id) {
    ... on InventoryShipment {
      id
      status
      lineItems(first: 50) {
        edges { node { id quantity unreceivedQuantity inventoryItem { id } } }
      }
    }
  }
}
'''.trim()
        def liResp = ShopifyAdmin.graphql(client, endpoint, token, [query: liQuery, variables: [id: shipmentId]])
        def shipmentNode = liResp?.data?.node
        def shipmentStatus = shipmentNode?.status?.toString()
        def liEdges = shipmentNode?.lineItems?.edges ?: []
        def receiveItems = []
        liEdges.each { e ->
            def node = e?.node
            def invId = node?.inventoryItem?.id
            if (invId == null || !wantQty.containsKey(invId)) return
            int want = (wantQty[invId] as int)
            int open = (node.unreceivedQuantity != null) ? (node.unreceivedQuantity as int)
                     : ((node.quantity != null) ? (node.quantity as int) : want)
            int accept = Math.min(want, open)
            if (accept <= 0) return
            // InventoryShipmentReceiveItemInput: { shipmentLineItemId, quantity, reason }.
            // reason is the InventoryShipmentReceiveLineItemReason enum — ACCEPTED for received units.
            receiveItems << [shipmentLineItemId: node.id, quantity: accept, reason: "ACCEPTED"]
        }
        if (receiveItems.isEmpty()) {
            throw new RuntimeException("shipment ${shipmentId} has no matching line items to receive")
        }

        // 2b) A DRAFT shipment cannot be received — mark it IN TRANSIT first.
        if (shipmentStatus == "DRAFT") {
            def markMutation = '''
mutation inventoryShipmentMarkInTransit($id: ID!) {
  inventoryShipmentMarkInTransit(id: $id) {
    inventoryShipment { id status }
    userErrors { field message }
  }
}
'''.trim()
            def mResp = ShopifyAdmin.graphql(client, endpoint, token, [query: markMutation, variables: [id: shipmentId]])
            raiseOnUserErrors("markShipmentInTransit", mResp?.data?.inventoryShipmentMarkInTransit?.userErrors)
        }

        // 3) Receive the shipment (dateReceived = the operator's receipt date when provided).
        def mutation = '''
mutation inventoryShipmentReceive($id: ID!, $lineItems: [InventoryShipmentReceiveItemInput!], $dateReceived: DateTime) {
  inventoryShipmentReceive(id: $id, lineItems: $lineItems, dateReceived: $dateReceived) {
    inventoryShipment { id status }
    userErrors { field message }
  }
}
'''.trim()
        def vars = [id: shipmentId, lineItems: receiveItems]
        if (dateReceived != null && !dateReceived.toString().trim().isEmpty()) vars.dateReceived = dateReceived
        def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: mutation, variables: vars])
        def result = resp?.data?.inventoryShipmentReceive
        raiseOnUserErrors("receiveInventoryTransfer", result?.userErrors)
        return [shipmentId: result?.inventoryShipment?.id, status: result?.inventoryShipment?.status]
    }

    /**
     * Ensure an inventory item is STOCKED at a location — activate it there if it is not yet.
     *
     * A Shopify inventory item must be explicitly stocked (activated) at a location before that
     * location can hold or receive its inventory; otherwise the admin reports "this product does not
     * fulfill at this location" and an inbound transfer to it is rejected. The Inventory & Reorder
     * Review form exposes this as an "auto-enable fulfillment" checkbox (default ON): the operator
     * picking a fresh destination location should not have to hand-enable it in the Shopify admin
     * first — activating the destination they EXPLICITLY chose is a direct consequence of that
     * choice, not an autonomous decision (operator-sovereignty preserved).
     *
     * Idempotent: queries the item's inventory level at the location and only calls
     * {@code inventoryActivate} when there is none. Returns { activated (true iff we activated it
     * just now), levelId }.
     */
    static Map ensureStockedAt(HttpClient client, String endpoint, String token,
                               inventoryItemId, locationId) {
        def itemGid = gid("InventoryItem", inventoryItemId)
        def locGid = gid("Location", locationId)
        // 1) Already stocked? InventoryItem.inventoryLevel(locationId:) is null when the item is
        //    not activated at that location.
        def query = '''
query($itemId: ID!, $locationId: ID!) {
  inventoryItem(id: $itemId) {
    id
    inventoryLevel(locationId: $locationId) { id }
  }
}
'''.trim()
        def qResp = ShopifyAdmin.graphql(client, endpoint, token,
            [query: query, variables: [itemId: itemGid, locationId: locGid]])
        def existing = qResp?.data?.inventoryItem?.inventoryLevel?.id
        if (existing) {
            return [activated: false, levelId: existing]
        }
        // 2) Not stocked yet — activate it at the location (creates the InventoryLevel).
        def mutation = '''
mutation inventoryActivate($inventoryItemId: ID!, $locationId: ID!) {
  inventoryActivate(inventoryItemId: $inventoryItemId, locationId: $locationId) {
    inventoryLevel { id }
    userErrors { field message }
  }
}
'''.trim()
        def resp = ShopifyAdmin.graphql(client, endpoint, token,
            [query: mutation, variables: [inventoryItemId: itemGid, locationId: locGid]])
        def result = resp?.data?.inventoryActivate
        raiseOnUserErrors("ensureStockedAt", result?.userErrors)
        return [activated: true, levelId: result?.inventoryLevel?.id]
    }

    /**
     * Update a customer's editable profile fields (the customer editor — CRM
     * simplification). {@code fields} keys are all optional:
     *   tags (List or comma-String), note (String), taxExempt (Boolean),
     *   marketingConsent (Map { state, optInLevel } or null).
     * tags/note/taxExempt (when present) go through a single {@code customerUpdate};
     * marketingConsent (when present) ADDITIONALLY issues
     * {@code customerEmailMarketingConsentUpdate}. Address mutations are
     * intentionally NOT implemented (v1 = addresses are display-only). Non-defensive:
     * a transport error or a Shopify {@code userErrors} entry is raised so the sync
     * endpoint can report the outcome. Returns { customerId, updated: [keys applied] }.
     */
    static Map updateCustomer(HttpClient client, String endpoint, String token,
                              String customerId, Map fields) {
        def id = gid("Customer", customerId)
        def f = fields ?: [:]
        def applied = []

        def input = [id: id]
        if (f.containsKey("tags")) { input.tags = normalizeTags(f.tags); applied << "tags" }
        if (f.containsKey("note")) { input.note = (f.note == null ? "" : f.note.toString()); applied << "note" }
        if (f.containsKey("taxExempt")) { input.taxExempt = (f.taxExempt == true); applied << "taxExempt" }

        if (input.size() > 1) {
            def mutation = '''
mutation customerUpdate($input: CustomerInput!) {
  customerUpdate(input: $input) {
    customer { id }
    userErrors { field message }
  }
}
'''.trim()
            def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: mutation, variables: [input: input]])
            raiseOnUserErrors("updateCustomer", resp?.data?.customerUpdate?.userErrors)
        }

        if (f.containsKey("marketingConsent") && f.marketingConsent != null) {
            def mc = f.marketingConsent
            // Shopify's CustomerEmailMarketingState / CustomerMarketingOptInLevel are
            // case-sensitive UPPERCASE GraphQL enums (SUBSCRIBED, SINGLE_OPT_IN, ...),
            // but the mirror body / editor carry the lowercase webhook casing; normalize.
            def consent = [marketingState: req(mc?.state, "marketingConsent.state").toUpperCase()]
            if (mc?.optInLevel != null && !mc.optInLevel.toString().trim().isEmpty()) {
                consent.marketingOptInLevel = mc.optInLevel.toString().toUpperCase()
            }
            def consentInput = [customerId: id, emailMarketingConsent: consent]
            def mutation = '''
mutation customerEmailMarketingConsentUpdate($input: CustomerEmailMarketingConsentUpdateInput!) {
  customerEmailMarketingConsentUpdate(input: $input) {
    customer { id }
    userErrors { field message }
  }
}
'''.trim()
            def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: mutation, variables: [input: consentInput]])
            raiseOnUserErrors("updateCustomer", resp?.data?.customerEmailMarketingConsentUpdate?.userErrors)
            applied << "marketingConsent"
        }

        return [customerId: id, updated: applied]
    }

    /**
     * Update a product's editable base fields (Product 360 editor). {@code fields}
     * keys are all optional; only the present ones are written (a partial edit keeps the
     * rest): title (String), descriptionHtml (String), vendor (String), productType
     * (String), tags (List or comma-String → normalizeTags), handle (String), status
     * (String → uppercased ProductStatus enum ACTIVE / DRAFT / ARCHIVED). Non-defensive:
     * a transport error or a Shopify {@code userErrors} entry is raised so the sync
     * endpoint can report the outcome. Returns { productId, updated: [keys applied] }.
     */
    static Map updateProduct(HttpClient client, String endpoint, String token,
                             String productId, Map fields) {
        def id = gid("Product", productId)
        def f = fields ?: [:]
        def applied = []

        def input = [id: id]
        if (f.containsKey("title"))           { input.title = (f.title == null ? "" : f.title.toString()); applied << "title" }
        if (f.containsKey("descriptionHtml")) { input.descriptionHtml = (f.descriptionHtml == null ? "" : f.descriptionHtml.toString()); applied << "descriptionHtml" }
        if (f.containsKey("vendor"))          { input.vendor = (f.vendor == null ? "" : f.vendor.toString()); applied << "vendor" }
        if (f.containsKey("productType"))     { input.productType = (f.productType == null ? "" : f.productType.toString()); applied << "productType" }
        if (f.containsKey("tags"))            { input.tags = normalizeTags(f.tags); applied << "tags" }
        if (f.containsKey("handle"))          { input.handle = (f.handle == null ? "" : f.handle.toString()); applied << "handle" }
        if (f.containsKey("status"))          { input.status = req(f.status, "status").toUpperCase(); applied << "status" }

        // No field beyond id -> nothing to write; skip the round-trip (like updateCustomer).
        if (input.size() > 1) {
            def mutation = '''
mutation productUpdate($input: ProductInput!) {
  productUpdate(input: $input) {
    product { id }
    userErrors { field message }
  }
}
'''.trim()
            def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: mutation, variables: [input: input]])
            raiseOnUserErrors("updateProduct", resp?.data?.productUpdate?.userErrors)
        }
        return [productId: id, updated: applied]
    }

    /**
     * Update an order's editable metadata (the order 3-piece set — order editor, v1 =
     * metadata ONLY). {@code fields} keys are all optional; only the present ones are
     * written (a partial edit keeps the rest): note (String), tags (List or comma-String
     * → normalizeTags), customAttributes (List of { key, value } → the order's custom
     * attributes; entries with a blank key are skipped). Line-item / quantity editing (the
     * stateful Order Editing session) is DEFERRED and intentionally NOT implemented here —
     * Shopify remains the source of truth and the mirror follows via webhook. Non-defensive:
     * a transport error or a Shopify {@code userErrors} entry is raised so the sync endpoint
     * can report the outcome. Returns { orderId, updated: [keys applied] }.
     */
    static Map updateOrder(HttpClient client, String endpoint, String token,
                           String orderId, Map fields) {
        def id = gid("Order", orderId)
        def f = fields ?: [:]
        def applied = []

        def input = [id: id]
        if (f.containsKey("note")) { input.note = (f.note == null ? "" : f.note.toString()); applied << "note" }
        if (f.containsKey("tags")) { input.tags = normalizeTags(f.tags); applied << "tags" }
        if (f.containsKey("customAttributes")) {
            input.customAttributes = (f.customAttributes ?: [])
                .findAll { it?.key != null && !it.key.toString().trim().isEmpty() }
                .collect { [key: it.key.toString(), value: (it.value == null ? "" : it.value.toString())] }
            applied << "customAttributes"
        }

        // No field beyond id -> nothing to write; skip the round-trip (like updateCustomer).
        if (input.size() > 1) {
            def mutation = '''
mutation orderUpdate($input: OrderInput!) {
  orderUpdate(input: $input) {
    order { id }
    userErrors { field message }
  }
}
'''.trim()
            def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: mutation, variables: [input: input]])
            raiseOnUserErrors("updateOrder", resp?.data?.orderUpdate?.userErrors)
        }
        return [orderId: id, updated: applied]
    }

    /**
     * Add one image to a product's media by URL (Product 360 Media, add-by-URL;
     * no local file upload / stagedUploads in v1). {@code originalSource} is the source
     * image URL; {@code alt} is the (optional) alt text. Returns { productId, added }.
     */
    static Map addProductMedia(HttpClient client, String endpoint, String token,
                               String productId, String originalSource, String alt) {
        def id = gid("Product", productId)
        def media = [[
            originalSource  : originalSource,
            alt             : (alt ?: ""),
            mediaContentType: "IMAGE",
        ]]
        def mutation = '''
mutation productCreateMedia($productId: ID!, $media: [CreateMediaInput!]!) {
  productCreateMedia(productId: $productId, media: $media) {
    media { id status }
    mediaUserErrors { field message }
    product { id }
  }
}
'''.trim()
        def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: mutation, variables: [productId: id, media: media]])
        raiseOnUserErrors("addProductMedia", resp?.data?.productCreateMedia?.mediaUserErrors)
        return [productId: id, added: 1]
    }

    /**
     * Delete one or more media from a product (Product 360 Media). {@code mediaIds}
     * are MediaImage ids (numeric or gid). Returns { productId, deleted: [ids removed] }.
     */
    static Map deleteProductMedia(HttpClient client, String endpoint, String token,
                                  String productId, List mediaIds) {
        def id = gid("Product", productId)
        def ids = (mediaIds ?: []).collect { gid("MediaImage", it) }
        def mutation = '''
mutation productDeleteMedia($productId: ID!, $mediaIds: [ID!]!) {
  productDeleteMedia(productId: $productId, mediaIds: $mediaIds) {
    deletedMediaIds
    mediaUserErrors { field message }
    product { id }
  }
}
'''.trim()
        def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: mutation, variables: [productId: id, mediaIds: ids]])
        def result = resp?.data?.productDeleteMedia
        raiseOnUserErrors("deleteProductMedia", result?.mediaUserErrors)
        return [productId: id, deleted: result?.deletedMediaIds]
    }

    /**
     * Reorder a product's media to the given order (Product 360 Media).
     * {@code orderedMediaIds} are MediaImage ids (numeric or gid), front to back; each
     * is moved to its list index. ASYNC: Shopify runs this as a job, so the mirror
     * catches up via products/update later. Returns { productId, job: [job id] }.
     */
    static Map reorderProductMedia(HttpClient client, String endpoint, String token,
                                   String productId, List orderedMediaIds) {
        def id = gid("Product", productId)
        def moves = (orderedMediaIds ?: []).withIndex().collect { mediaId, idx ->
            [id: gid("MediaImage", mediaId), newPosition: idx.toString()]
        }
        def mutation = '''
mutation productReorderMedia($id: ID!, $moves: [MoveInput!]!) {
  productReorderMedia(id: $id, moves: $moves) {
    job { id }
    mediaUserErrors { field message }
  }
}
'''.trim()
        def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: mutation, variables: [id: id, moves: moves]])
        def result = resp?.data?.productReorderMedia
        raiseOnUserErrors("reorderProductMedia", result?.mediaUserErrors)
        return [productId: id, job: result?.job?.id]
    }

    /**
     * Edit a media's alt text (Product 360 Media). Uses {@code fileUpdate} (the
     * durable alt-edit path at 2026-01; productUpdateMedia is deprecated). {@code mediaId}
     * is a MediaImage id (numeric or gid). Returns { mediaId, alt }.
     */
    static Map updateProductMediaAlt(HttpClient client, String endpoint, String token,
                                     String mediaId, String alt) {
        def id = gid("MediaImage", mediaId)
        def files = [[id: id, alt: (alt ?: "")]]
        def mutation = '''
mutation fileUpdate($files: [FileUpdateInput!]!) {
  fileUpdate(files: $files) {
    files { ... on MediaImage { id alt } }
    userErrors { field message }
  }
}
'''.trim()
        def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: mutation, variables: [files: files]])
        raiseOnUserErrors("updateProductMediaAlt", resp?.data?.fileUpdate?.userErrors)
        return [mediaId: id, alt: alt]
    }

    // --- Helpers ---------------------------------------------------------------

    /** Normalize a tags input (List or comma-separated String) to a clean list of strings. */
    private static List normalizeTags(v) {
        if (v == null) return []
        if (v instanceof List) return v.collect { it?.toString()?.trim() }.findAll { it }
        return v.toString().split(",").collect { it.trim() }.findAll { it }
    }

    /**
     * A REQUIRED Shopify gid for a mutation argument. The one gid builder is
     * {@link Api#gid} (never concatenate "gid://" elsewhere); this wrapper only
     * adds the required-argument check the write paths need.
     */
    static String gid(String type, id) {
        def s = Api.gid(type, id)
        if (s == null) {
            throw new IllegalArgumentException("${type} id is required")
        }
        return s
    }

    private static String req(v, String what) {
        if (v == null || v.toString().trim().isEmpty()) {
            throw new IllegalArgumentException("${what} is required")
        }
        return v.toString()
    }

    private static void raiseOnUserErrors(String op, userErrors) {
        if (userErrors) {
            // Render with Groovy's own toString (list of { field, message } maps) — NOT
            // groovy.json.JsonOutput, whose FastStringService fails to load in the CMS's restricted
            // Groovy runtime, which would mask the REAL userErrors behind an "Unable to load
            // FastStringService" error.
            throw new RuntimeException("Shopify ${op} userErrors: ${String.valueOf(userErrors)}")
        }
    }
}
