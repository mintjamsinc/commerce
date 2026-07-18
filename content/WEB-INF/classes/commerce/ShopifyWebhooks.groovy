package commerce

import java.net.http.HttpClient

/**
 * One-click Shopify webhook subscription registration (the app owns
 * its own webhook wiring instead of relying on the operator hand-subscribing each
 * topic in the Shopify admin).
 *
 * The app RECEIVES webhooks at content/public/commerce/endpoints/shopify/webhook.groovy
 * and dispatches them by topic in etc/eip/routes/commerce/ingest.xml. Historically the
 * operator had to subscribe every one of those topics manually in Shopify's
 * Notifications UI, and bulk_operations/finish (API-only, absent from that UI) was
 * missed — so Bulk completion fell back to the watchdog. This class CREATE-or-UPDATEs
 * the full operational topic set via the Admin GraphQL API, IDEMPOTENTLY: re-running
 * with a CHANGED callback url re-points the existing subscriptions instead of creating
 * duplicates.
 *
 * Each call builds a Shopify Admin GraphQL query/mutation and runs it through
 * {@link ShopifyAdmin}. {@link #list} is NON-defensive at the transport level (a
 * failed listing must propagate so the caller can distinguish "no subs" from "query
 * failed" — and so {@link #sync} aborts BEFORE mutating rather than create-storming
 * duplicates), but tolerates an individual malformed node. {@link #sync} is defensive
 * PER TOPIC: a userErrors entry or transport error on one topic is recorded as
 * action:"error" and never aborts the rest. Caller supplies an HttpClient so it can be
 * reused across the calls.
 *
 * NOTE: the webhookSubscription* field/enum shapes are shaped for the configured Admin
 * API version (target 2026-01) and must be smoke-tested live, the same as
 * cancelOrder / createIncomingTransfer in {@link ShopifyWrite}.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.ShopifyWebhooks}.
 */
class ShopifyWebhooks {

    /**
     * The OPERATIONAL event topics the app's ingest route dispatches to bespoke
     * handlers (ingest.xml). These are the topics we CREATE-or-UPDATE subscriptions
     * for. ORDER MATTERS: kept in the ingest dispatch order for a stable Settings
     * display.
     */
    static final List<String> OPERATIONAL_TOPICS = [
        "orders/paid",
        "orders/updated",
        "order_transactions/create",
        "products/create",
        "products/update",
        "products/delete",
        "customers/create",
        "customers/update",
        "customers/enable",
        "customers/disable",
        "customers/delete",
        "refunds/create",
        // Fulfillment-hold state changes: mirrored onto the parent order so the
        // Fulfill Order task can gate its action on the hold. Creating these
        // subscriptions requires a fulfillment-order read scope (e.g.
        // read_merchant_managed_fulfillment_orders); sync() records a per-topic
        // "error" row when the app is missing it, without aborting the rest.
        "fulfillment_orders/placed_on_hold",
        "fulfillment_orders/hold_released",
        "inventory_levels/update",
        "locations/create",
        "locations/update",
        "bulk_operations/finish",
    ]

    /**
     * The GDPR COMPLIANCE topics ingest also routes. A custom app configures these in
     * its compliance webhook settings (Partner Dashboard) — they are NOT creatable via
     * webhookSubscriptionCreate — so we never touch them here; the endpoint surfaces
     * them as an informational reminder ("set these to the same url in the app's
     * compliance webhook settings").
     */
    static final List<String> COMPLIANCE_TOPICS = [
        "customers/redact",
        "customers/data_request",
        "shop/redact",
    ]

    /**
     * The Shopify WebhookSubscriptionTopic enum for an event_topic:
     * upper-case + '/' → '_' (orders/paid → ORDERS_PAID, inventory_levels/update →
     * INVENTORY_LEVELS_UPDATE, bulk_operations/finish → BULK_OPERATIONS_FINISH).
     */
    static String enumFor(String topic) {
        return topic.toUpperCase().replace('/', '_')
    }

    /**
     * The webhook subscriptions currently registered on Shopify, as a List of maps
     * { topic (enum String), id, callbackUrl }. {@code topic} is the
     * WebhookSubscriptionTopic enum exactly as Shopify returns it; {@code callbackUrl}
     * comes from the WebhookHttpEndpoint fragment and is null for a non-HTTP endpoint
     * (EventBridge / PubSub). A transport or top-level GraphQL error PROPAGATES (the
     * caller must be able to tell "no subs" from "query failed"), but an individual
     * malformed edge/node is skipped so one bad row can't sink the whole listing.
     */
    static List list(HttpClient client, String endpoint, String token) {
        def query = '''
{
  webhookSubscriptions(first: 100) {
    edges {
      node {
        id
        topic
        endpoint {
          __typename
          ... on WebhookHttpEndpoint { callbackUrl }
        }
      }
    }
  }
}
'''.trim()
        def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: query])
        def edges = resp?.data?.webhookSubscriptions?.edges
        def out = []
        if (edges instanceof List) {
            edges.each { edge ->
                try {
                    def node = edge?.node
                    if (node == null) return
                    def topic = node.topic?.toString()
                    if (topic == null || topic.trim().isEmpty()) return
                    def ep = node.endpoint
                    def callbackUrl = null
                    // Only WebhookHttpEndpoint carries a callbackUrl; other endpoint
                    // kinds (EventBridge / PubSub) leave it null.
                    if (ep instanceof Map && ep["__typename"] == "WebhookHttpEndpoint") {
                        callbackUrl = ep.callbackUrl?.toString()
                    }
                    out << [topic: topic, id: node.id?.toString(), callbackUrl: callbackUrl]
                } catch (Exception ignore) {
                    // Tolerate an individual malformed node — keep listing the rest.
                }
            }
        }
        return out
    }

    /**
     * Idempotently CREATE-or-UPDATE a webhook subscription for EVERY operational topic
     * so they all point at {@code callbackUrl}:
     *   • no existing sub for the topic → webhookSubscriptionCreate(topic, {callbackUrl, format: JSON})
     *   • existing, callbackUrl differs → webhookSubscriptionUpdate(id, {callbackUrl})  (re-point)
     *   • existing, callbackUrl matches → skip
     * Compliance topics are intentionally NOT touched (configured in the app's
     * compliance webhook settings, not creatable here).
     *
     * The existing-subs snapshot is taken via {@link #list} up front — if that throws
     * the whole sync aborts BEFORE any mutation, so a transient listing failure can't
     * create a storm of duplicate subscriptions. From there it is defensive PER TOPIC:
     * a userErrors entry or a transport error on one topic becomes action:"error" with
     * the message and does NOT abort the others.
     *
     * Returns { callbackUrl, results:[{ topic (event_topic String), enum, action:
     * "created"|"updated"|"skipped"|"error", id, error }], summary:{ created, updated,
     * skipped, error } }.
     *
     * {@code session} is accepted for parity with the audited endpoints and to keep the
     * contract stable if a future revision writes a per-sync record; this method itself
     * performs no JCR writes (the endpoint audits the summary).
     */
    static Map sync(session, log, HttpClient client, String endpoint, String token, String callbackUrl) {
        // Snapshot existing subscriptions by topic enum. A failed listing THROWS here
        // (propagated to the endpoint's try/catch) rather than silently proceeding to
        // create — otherwise a transient read error would duplicate every subscription.
        def existingByEnum = [:]
        list(client, endpoint, token).each { sub ->
            if (sub?.topic != null) existingByEnum[sub.topic] = sub
        }

        def results = []
        def summary = [created: 0, updated: 0, skipped: 0, error: 0]

        OPERATIONAL_TOPICS.each { topic ->
            def topicEnum = enumFor(topic)
            def row = [topic: topic, "enum": topicEnum, action: null, id: null, error: null]
            try {
                def existing = existingByEnum[topicEnum]
                if (existing == null) {
                    row.id = create(client, endpoint, token, topicEnum, callbackUrl)
                    row.action = "created"
                    summary.created++
                } else if (!sameUrl(existing.callbackUrl, callbackUrl)) {
                    row.id = update(client, endpoint, token, existing.id?.toString(), callbackUrl)
                    row.action = "updated"
                    summary.updated++
                } else {
                    row.id = existing.id?.toString()
                    row.action = "skipped"
                    summary.skipped++
                }
            } catch (Exception e) {
                // Per-topic isolation: record the failure and carry on with the rest.
                row.action = "error"
                row.error = e.message
                summary.error++
                try { log.warn("ShopifyWebhooks: ${topic} sync failed: ${e.message}") } catch (Exception ignore) {}
            }
            results << row
        }

        return [callbackUrl: callbackUrl, results: results, summary: summary]
    }

    /**
     * Compare two callback URLs ignoring a cosmetic trailing slash (and surrounding
     * whitespace) so a pasted "…/webhook.groovy/" does not force a needless
     * webhookSubscriptionUpdate against a stored "…/webhook.groovy". Both null → equal.
     */
    private static boolean sameUrl(a, b) {
        return norm(a) == norm(b)
    }

    private static String norm(v) {
        if (v == null) return null
        def s = v.toString().trim()
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1)
        return s
    }

    // --- Mutations -------------------------------------------------------------

    /**
     * webhookSubscriptionCreate for a WebhookSubscriptionTopic enum; the new
     * subscription points at {@code callbackUrl} in JSON format. Returns the new
     * subscription id. Raises on a userErrors entry or a transport error.
     */
    private static String create(HttpClient client, String endpoint, String token,
                                 String topicEnum, String callbackUrl) {
        def mutation = '''
mutation($topic: WebhookSubscriptionTopic!, $sub: WebhookSubscriptionInput!) {
  webhookSubscriptionCreate(topic: $topic, webhookSubscription: $sub) {
    webhookSubscription { id }
    userErrors { field message }
  }
}
'''.trim()
        def variables = [topic: topicEnum, sub: [callbackUrl: callbackUrl, format: "JSON"]]
        def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: mutation, variables: variables])
        def result = resp?.data?.webhookSubscriptionCreate
        raiseOnUserErrors("webhookSubscriptionCreate", result?.userErrors)
        return result?.webhookSubscription?.id?.toString()
    }

    /**
     * webhookSubscriptionUpdate to re-point an existing subscription at
     * {@code callbackUrl}. Returns the subscription id. Raises on a userErrors entry or
     * a transport error.
     */
    private static String update(HttpClient client, String endpoint, String token,
                                 String id, String callbackUrl) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("existing subscription has no id to update")
        }
        def mutation = '''
mutation($id: ID!, $sub: WebhookSubscriptionInput!) {
  webhookSubscriptionUpdate(id: $id, webhookSubscription: $sub) {
    webhookSubscription { id }
    userErrors { field message }
  }
}
'''.trim()
        def variables = [id: id, sub: [callbackUrl: callbackUrl]]
        def resp = ShopifyAdmin.graphql(client, endpoint, token, [query: mutation, variables: variables])
        def result = resp?.data?.webhookSubscriptionUpdate
        raiseOnUserErrors("webhookSubscriptionUpdate", result?.userErrors)
        return result?.webhookSubscription?.id?.toString()
    }

    // --- Helpers ---------------------------------------------------------------

    private static void raiseOnUserErrors(String op, userErrors) {
        if (userErrors) {
            // Groovy's own toString, NOT groovy.json.JsonOutput — its FastStringService fails to
            // load in the CMS's restricted Groovy runtime and would mask the REAL userErrors.
            throw new RuntimeException("Shopify ${op} userErrors: ${String.valueOf(userErrors)}")
        }
    }
}
