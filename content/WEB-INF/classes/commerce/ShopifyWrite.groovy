package commerce

import java.net.http.HttpClient

/**
 * Outbound CMS → Shopify writes (category A, #2: bidirectional sync).
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
     * Set (upsert) metafields on a product (the PIM push, #23). {@code metafields}
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

    // --- Helpers ---------------------------------------------------------------

    /** Wrap a raw numeric id as a Shopify gid; pass through ids that already are one. */
    static String gid(String type, id) {
        if (id == null) {
            throw new IllegalArgumentException("${type} id is required")
        }
        def s = id.toString().trim()
        if (s.isEmpty()) {
            throw new IllegalArgumentException("${type} id is required")
        }
        return s.startsWith("gid://") ? s : "gid://shopify/${type}/${s}"
    }

    private static String req(v, String what) {
        if (v == null || v.toString().trim().isEmpty()) {
            throw new IllegalArgumentException("${what} is required")
        }
        return v.toString()
    }

    private static void raiseOnUserErrors(String op, userErrors) {
        if (userErrors) {
            throw new RuntimeException("Shopify ${op} userErrors: ${groovy.json.JsonOutput.toJson(userErrors)}")
        }
    }
}
