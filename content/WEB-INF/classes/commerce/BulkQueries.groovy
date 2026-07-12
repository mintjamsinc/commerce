package commerce

/**
 * Shopify Bulk Operation query strings.
 *
 * Bulk queries use connections WITHOUT pagination arguments (no first:/after:); Shopify
 * streams every record into a JSONL file where each nested connection node is a separate
 * line carrying __parentId. (Verify field availability against the configured API version.)
 *
 * NB: the query strings are sent to Shopify VERBATIM, so comments inside them must use
 * GraphQL `#` syntax — a `//` comment is an invalid token and fails the whole bulk query.
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

    /**
     * Orders BACKFILL (historical initial import) — TEMPLATE, not a ready-to-run query: the
     * `%FILTER%` placeholder is replaced per job by forJob() with an operator-supplied created_at
     * date range (buildOrdersFilter). Unlike inventory-full this CANNOT be a static string because
     * each backfill run targets a different From/To window.
     *
     * Root is orders (Bulk streams every match with no first:/after:). lineItems is nested so the
     * JSONL carries one child line per item (__parentId = order gid) for reports/editor. The node
     * fields are the GRAPHQL shape (camelCase / edges-nodes / UPPERCASE enums); the CMS consumer
     * normalizes each node to the REST (webhook) body shape the mirror consumers expect.
     *
     * REFUND FLAGGING (ids only): each order also carries its `refunds { id }` (a plain LIST on
     * Order) so the consumer knows WHICH orders have refunds. The refund DETAIL (line items /
     * adjustments / shipping lines / transactions) CANNOT ride this export — live-verified
     * 2026-07-11: Bulk rejects "a connection field within a list field" and caps a query at 5
     * connections — so the consumer fetches the details per refund-bearing order via the
     * foreground Admin API (RefundMirror.refundsQuery) and only for orders whose refund ids are
     * not yet mirrored.
     *
     * JSONL lines:
     *   {"id":"gid://shopify/Order/111", ...order node fields...}                    // order (no __parentId)
     *   {"id":"gid://shopify/LineItem/222", ..., "__parentId":"gid://shopify/Order/111"} // line item
     *   {"id":"gid://shopify/Refund/333", "__parentId":"gid://shopify/Order/111"}        // refund id flag
     *
     * (Whether a refund id emits as its own JSONL line, as above, or inlines on the order line as
     * a refunds:[{id}] list is version-sensitive — the consumer tolerates BOTH.)
     *
     * The filter is injected as a bare search string inside orders(query: "%FILTER%"); the whole
     * query is GraphQL-string-escaped by ShopifyAdmin.startBulk (gqlString) before it is sent, and
     * buildOrdersFilter admits only digits+dashes into the dates, so there is no injection surface.
     */
    static final String ORDERS_BACKFILL_TEMPLATE = '''
{
  orders(query: "%FILTER%") {
    edges {
      node {
        id
        legacyResourceId
        name
        email
        note
        tags
        createdAt
        processedAt
        updatedAt
        cancelledAt
        displayFinancialStatus
        displayFulfillmentStatus
        totalPriceSet {
          shopMoney { amount currencyCode }
          presentmentMoney { amount currencyCode }
        }
        # Sales-component money sets: tax / shipping / tips / duties at order grain, so a
        # backfilled order decomposes to components_complete just like a webhook body. Each is a
        # MoneyBag (native presentment + base shopMoney) that INLINES on the order root line — NO new
        # JSONL child kind. Shipping is taken from this order-level total (not a shippingLines
        # connection), so no gid-type child classifier is needed. currentTotalDutiesSet is null on
        # non-cross-border orders → omitted. NB verify each field against the pinned Admin API version.
        totalTaxSet {
          shopMoney { amount currencyCode }
          presentmentMoney { amount currencyCode }
        }
        totalShippingPriceSet {
          shopMoney { amount currencyCode }
          presentmentMoney { amount currencyCode }
        }
        totalTipReceivedSet {
          shopMoney { amount currencyCode }
          presentmentMoney { amount currencyCode }
        }
        currentTotalDutiesSet {
          shopMoney { amount currencyCode }
          presentmentMoney { amount currencyCode }
        }
        customAttributes { key value }
        customer {
          legacyResourceId
          email
        }
        shippingAddress {
          firstName
          lastName
          name
          company
          address1
          address2
          city
          province
          provinceCode
          country
          countryCodeV2
          zip
          phone
        }
        billingAddress {
          firstName
          lastName
          name
          company
          address1
          address2
          city
          province
          provinceCode
          country
          countryCodeV2
          zip
          phone
        }
        lineItems {
          edges {
            node {
              id
              name
              title
              variantTitle
              sku
              quantity
              originalUnitPriceSet {
                shopMoney { amount currencyCode }
                presentmentMoney { amount currencyCode }
              }
              # Per-line sales components: product/variant ids for the line-grain product
              # facet, and the line discount/tax breakdown. All are LISTS/objects that INLINE on the
              # lineItem child line (no new JSONL child kind, so the streaming accumulator is
              # unchanged).
              product { legacyResourceId }
              variant { legacyResourceId }
              discountAllocations {
                allocatedAmountSet {
                  shopMoney { amount currencyCode }
                  presentmentMoney { amount currencyCode }
                }
              }
              taxLines {
                title
                rate
                priceSet {
                  shopMoney { amount currencyCode }
                  presentmentMoney { amount currencyCode }
                }
              }
            }
          }
        }
        # Refund FLAG only. Bulk rejects a connection field inside a list field (and >5
        # connections per query), so the refund detail cannot ride this export — the consumer
        # fetches it per refund-bearing order via the foreground Admin API. Only scalar id here.
        refunds {
          id
        }
      }
    }
  }
}
'''.trim()

    /**
     * Customers BACKFILL (historical initial import) — TEMPLATE, not a ready-to-run query: the
     * `%FILTER%` placeholder is replaced per job by forJob() with an operator-supplied created_at
     * date range (buildDateFilter). Like orders-backfill this CANNOT be a static string because each
     * backfill run targets a different From/To window.
     *
     * Root is customers (Bulk streams every match with no first:/after:). Unlike orders, Customer
     * exposes NO nested connection here — `addresses` is a plain LIST field that INLINES on the
     * customer node line — so the JSONL is FLAT: one line per customer, NO __parentId children. The
     * node fields are the GRAPHQL shape (camelCase / UPPERCASE enums / MoneyV2); the CMS consumer
     * (importCustomerBulkResult) normalizes each node to the REST (webhook) body shape the customer
     * mirror + editor + crm expect.
     *
     * JSONL lines:
     *   {"id":"gid://shopify/Customer/111","legacyResourceId":"111", ...customer node fields...}   // customer (no __parentId)
     *
     * The filter is injected as a bare search string inside customers(query: "%FILTER%"); the whole
     * query is GraphQL-string-escaped by ShopifyAdmin.startBulk (gqlString) before it is sent, and
     * buildDateFilter admits only digits+dashes into the dates, so there is no injection surface.
     *
     * PROTECTED CUSTOMER DATA: email / phone / firstName / lastName / addresses are Shopify Protected
     * Customer Data — this Bulk query only returns them if the app holds the approved data-protection
     * grant (same requirement as the orders backfill's customer/address fields).
     */
    static final String CUSTOMERS_BACKFILL_TEMPLATE = '''
{
  customers(query: "%FILTER%") {
    edges {
      node {
        id
        legacyResourceId
        email
        firstName
        lastName
        phone
        note
        tags
        taxExempt
        state
        createdAt
        updatedAt
        verifiedEmail
        numberOfOrders
        amountSpent { amount currencyCode }
        emailMarketingConsent { marketingState marketingOptInLevel }
        defaultAddress {
          id
          firstName
          lastName
          name
          company
          address1
          address2
          city
          province
          provinceCode
          country
          countryCodeV2
          zip
          phone
        }
        addresses {
          id
          firstName
          lastName
          name
          company
          address1
          address2
          city
          province
          provinceCode
          country
          countryCodeV2
          zip
          phone
        }
      }
    }
  }
}
'''.trim()

    /**
     * Products BACKFILL (historical initial import) — TEMPLATE, not a ready-to-run query: the
     * `%FILTER%` placeholder is replaced per job by forJob() with an operator-supplied created_at
     * date range (buildDateFilter). Like orders/customers-backfill this CANNOT be a static string
     * because each backfill run targets a different From/To window.
     *
     * Root is products (Bulk streams every match with no first:/after:). Unlike the FLAT customer
     * export, a product fans out into TWO nested connections — variants and media — so the JSONL is
     * a 3-LEVEL stream: the product node line, then one line per variant AND one line per media
     * item, BOTH carrying __parentId = the product gid. The consumer (importProductBulkResult)
     * distinguishes the two child kinds by the gid TYPE in each child's id (ProductVariant → a
     * variant, MediaImage → an image) and normalizes the product + variants + images to the REST
     * (webhook) body shape the mirror + product editor + Pim.browse expect.
     *
     * The node fields are the GRAPHQL shape (camelCase / connections / UPPERCASE enums); the CMS
     * consumer maps them to REST (snake_case / lowercase status / comma-joined tags). A variant's
     * `selectedOptions` / `inventoryItem` (and each MediaImage's `image`) are PLAIN nested objects,
     * not connections, so they INLINE on their child line. Only `variants` and `media` are
     * connections → child lines.
     *
     * media selects id / alt / status / mediaContentType at the Media INTERFACE level (mirroring the
     * proven product-media.groovy query) and only `image { url width height }` inside the
     * `... on MediaImage` fragment. Every media node therefore carries an id (used for __parentId
     * classification); NON-MediaImage media (video / 3d model) have no `image` and are ignored by the
     * consumer (nothing to mirror).
     *
     * JSONL lines:
     *   {"id":"gid://shopify/Product/111","legacyResourceId":"111", ...}                            // product (no __parentId)
     *   {"id":"gid://shopify/ProductVariant/222", ..., "__parentId":"gid://shopify/Product/111"}   // variant child
     *   {"id":"gid://shopify/MediaImage/333","image":{ ... },"__parentId":"gid://shopify/Product/111"} // image child
     *
     * The filter is injected as a bare search string inside products(query: "%FILTER%"); the whole
     * query is GraphQL-string-escaped by ShopifyAdmin.startBulk (gqlString) before it is sent, and
     * buildDateFilter admits only digits+dashes into the dates, so there is no injection surface.
     *
     * FIELD NAMES ARE API-VERSION SENSITIVE (verify against the configured Admin API version, esp.
     * 2026-01): descriptionHtml, productType, status(ACTIVE/ARCHIVED/DRAFT); variant
     * selectedOptions{name value}, inventoryQuantity, inventoryItem{legacyResourceId}; media node
     * id/alt/status/mediaContentType + ... on MediaImage { image { url width height } }.
     */
    static final String PRODUCTS_BACKFILL_TEMPLATE = '''
{
  products(query: "%FILTER%") {
    edges {
      node {
        id
        legacyResourceId
        title
        handle
        descriptionHtml
        vendor
        productType
        tags
        status
        createdAt
        updatedAt
        variants {
          edges {
            node {
              id
              legacyResourceId
              title
              sku
              price
              position
              inventoryQuantity
              inventoryItem {
                legacyResourceId
              }
              selectedOptions {
                name
                value
              }
            }
          }
        }
        media {
          edges {
            node {
              id
              alt
              status
              mediaContentType
              ... on MediaImage {
                image {
                  url
                  width
                  height
                }
              }
            }
          }
        }
      }
    }
  }
}
'''.trim()

    /**
     * SINGLE SOURCE OF TRUTH for bulk job types: each row pairs a type's bulk query with the data
     * domains that type touches, so forType()/forJob() and domainsForType() CANNOT drift out of sync.
     * A STATIC type carries its bulk query string directly and is resolved by forType(). A DYNAMIC
     * type (query built per job from a template + job params, e.g. a created_at date range) carries
     * query:null + dynamic:true and is resolved by forJob(job) — forType() has no static string for
     * it and will throw. A future backfill type adds ONE row here (query + domains together), e.g.
     *   "products-full":   [ query: PRODUCTS_FULL, domains: ["products"] ],             // static
     *   "orders-backfill": [ query: null, domains: ["orders"], dynamic: true ],         // dynamic
     *
     * Declared AFTER INVENTORY_FULL / ORDERS_BACKFILL_TEMPLATE so the fields are initialized when
     * this map references them.
     */
    static final Map TYPES = [
        "inventory-full":     [ query: INVENTORY_FULL, domains: ["inventory"] ],
        // Operator-triggered full inventory import (commerce-import "Backfill"). Same snapshot query
        // and "inventory" domain as the scheduled inventory-full reconcile, so it reuses the same
        // result handler (reconcileBulkResult) and serializes against the reconcile on the shared
        // domain. A distinct type (vs reusing inventory-full) keeps it out of the reconcile schedule's
        // idempotency and surfaces it in the backfill job list (which keys off the "-backfill" suffix).
        "inventory-backfill": [ query: INVENTORY_FULL, domains: ["inventory"] ],
        "orders-backfill":    [ query: null, domains: ["orders"], dynamic: true ],
        "customers-backfill": [ query: null, domains: ["customers"], dynamic: true ],
        "products-backfill":  [ query: null, domains: ["products"], dynamic: true ],
    ]

    /** The bulk query for a job type, or throws for an unknown type. */
    static String forType(String type) {
        def q = TYPES[type]?.query
        if (q != null) return q
        throw new RuntimeException("BulkQueries: unknown job type ${type}")
    }

    /**
     * The data domains a job TYPE touches — used by the broker to serialize per-domain (Shopify
     * producer singleton + CMS consumer domain-parallel) rather than through one global lane.
     *
     * A KNOWN type always resolves to its declared domains (paired with the query in TYPES). Only a
     * genuinely-unknown / mis-typed type falls back to [] (a WILDCARD that conservatively overlaps
     * every domain), so it serializes safely instead of racing an unrelated ingest.
     */
    static List<String> domainsForType(String type) {
        return TYPES[type]?.domains ?: []
    }

    /**
     * The bulk query for a specific JOB (not merely its type). A STATIC type delegates to
     * forType(job.type). A DYNAMIC type (dynamic:true in TYPES, e.g. orders-backfill) BUILDS its
     * query per job from a template + the job's params, because a single static string cannot carry
     * the operator's From/To window. Only a genuinely-unknown type throws. The producer lane calls
     * this (not forType) so the dynamic date filter reaches Shopify.
     */
    static String forJob(Map job) {
        def type = job?.type?.toString()
        def row = TYPES[type]
        if (row == null) {
            throw new RuntimeException("BulkQueries: unknown job type ${type}")
        }
        if (!row.dynamic) {
            return forType(type)
        }
        // Dynamic types: build the query from a template + this job's params.
        def params = (job?.params instanceof Map) ? (Map) job.params : [:]
        if (type == "orders-backfill") {
            return ORDERS_BACKFILL_TEMPLATE.replace("%FILTER%", buildOrdersFilter(params))
        }
        if (type == "customers-backfill") {
            return CUSTOMERS_BACKFILL_TEMPLATE.replace("%FILTER%", buildDateFilter("created_at", params))
        }
        if (type == "products-backfill") {
            return PRODUCTS_BACKFILL_TEMPLATE.replace("%FILTER%", buildDateFilter("created_at", params))
        }
        throw new RuntimeException("BulkQueries: dynamic job type ${type} has no query builder")
    }

    /**
     * Assemble a Bulk search filter over one DATE field from an operator-supplied range. Emits
     * `<field>:>=<from>` and/or `<field>:<<to+1d>` (space-joined = AND); a bound is DROPPED when
     * absent OR not a strict yyyy-MM-dd (the ==~ full-match admits only 4-2-2 digits with dashes, so
     * the string that reaches the GraphQL query can never carry a quote/space/operator = no
     * injection). Both bounds absent/invalid yields an EMPTY filter, which selects ALL records. Pure
     * String builder — never throws. Shared by the orders (created_at) and customers (created_at)
     * backfills; the field is caller-supplied so a future backfill can filter on its own axis.
     */
    static String buildDateFilter(String field, Map params) {
        def parts = []
        def from = params?.from?.toString()?.trim()
        def to   = params?.to?.toString()?.trim()
        if (from && from ==~ /\d{4}-\d{2}-\d{2}/) parts << "${field}:>=${from}".toString()
        if (to && to ==~ /\d{4}-\d{2}-\d{2}/) {
            // Make the To day INCLUSIVE: a bare `<field>:<=<to>` is evaluated at midnight and would
            // drop records dated later on that day. Use next-day-exclusive (<field>:<<to+1d>) so the
            // whole To day is covered regardless of Shopify's bare-date boundary semantics.
            try {
                def next = java.time.LocalDate.parse(to).plusDays(1).toString()
                parts << "${field}:<${next}".toString()
            } catch (Exception ignore) { /* format-valid but not a real calendar date -> drop the bound */ }
        }
        return parts.join(" ")
    }

    /** The orders backfill filters on created_at — a thin delegate so the orders path is unchanged. */
    static String buildOrdersFilter(Map params) {
        return buildDateFilter("created_at", params)
    }
}
