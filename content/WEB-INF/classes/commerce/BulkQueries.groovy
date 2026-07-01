package commerce

/**
 * Shopify Bulk Operation query strings.
 *
 * Bulk queries use connections WITHOUT pagination arguments (no first:/after:); Shopify
 * streams every record into a JSONL file where each nested connection node is a separate
 * line carrying __parentId. (Verify field availability against the configured API version.)
 *
 * Lives under /content/WEB-INF/classes; use via `import commerce.BulkQueries`.
 */
class BulkQueries {

    /**
     * Full inventory snapshot: every inventory item with its per-location "available".
     * Root is inventoryItems (not products) — the reconcile compares per inventory_item_id,
     * so this 2-level shape (item -> levels) keeps the JSONL trivial to reassemble.
     *
     * JSONL lines:
     *   {"id":"gid://shopify/InventoryItem/111"}                                  // item (no __parentId)
     *   {"location":{"id":"gid://shopify/Location/22"},
     *    "quantities":[{"name":"available","quantity":5}],
     *    "__parentId":"gid://shopify/InventoryItem/111"}                          // level
     */
    static final String INVENTORY_FULL = '''
{
  inventoryItems {
    edges {
      node {
        id
        inventoryLevels {
          edges {
            node {
              location { id }
              quantities(names: ["available"]) { name quantity }
            }
          }
        }
      }
    }
  }
}
'''.trim()

    /** The bulk query for a job type, or throws for an unknown type. */
    static String forType(String type) {
        if (type == "inventory-full") return INVENTORY_FULL
        throw new RuntimeException("BulkQueries: unknown job type ${type}")
    }
}
