package commerce

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Shopify Admin API access for the commerce workflows: Client Credentials Grant
 * token handling (with a JCR-cached token) and GraphQL calls.
 *
 * Shared by getAccessToken.groovy, recordFulfillment.groovy and
 * getMetafields.groovy. `session` is the script's repositorySession and `log` is
 * the script's logger binding. JSON is handled with jackson's ObjectMapper (the
 * same library api.util.JSON uses), so this class needs no JSON binding.
 *
 * Lives under /content/WEB-INF/classes; use via `import commerce.ShopifyAdmin`.
 */
class ShopifyAdmin {

    // Reuse a cached token for up to 23h, leaving margin under Shopify's ~24h life.
    private static final long TOKEN_TTL_MILLIS = 23L * 60 * 60 * 1000

    /**
     * True when the Shopify Admin API is CONFIGURED — all four connection fields
     * (shopDomain, apiVersion, clientID, clientSecret) are filled in the already-parsed
     * shopify.yml `config`. The Admin API is REQUIRED by the commerce integration (metafield
     * enrichment, the inventory mirror/reconcile, and fulfillment write-back depend on it),
     * so there is no on/off toggle: it is active whenever it is configured. A deployment that
     * has not yet filled the credentials is treated as not-configured, so callers degrade with
     * a clear warning instead of calling Shopify with empty/placeholder credentials.
     */
    static boolean adminApiEnabled(config) {
        def a = config?.adminApi
        if (!(a instanceof Map)) {
            return false
        }
        return ["shopDomain", "apiVersion", "clientID", "clientSecret"].every { k ->
            def v = a[k]
            v != null && !v.toString().trim().isEmpty()
        }
    }

    /** GraphQL endpoint for the configured shop + API version. */
    static String endpoint(adminApi) {
        def shopDomain = adminApi?.shopDomain
        def apiVersion = adminApi?.apiVersion
        if (!shopDomain || !apiVersion) {
            throw new RuntimeException("adminApi.shopDomain / apiVersion are not configured")
        }
        return "https://${shopDomain}/admin/api/${apiVersion}/graphql.json"
    }

    /**
     * Obtain a Shopify Admin API access token via the Client Credentials Grant,
     * reusing the JCR-cached token (/etc/commerce/config/access_token) while it is
     * still fresh. Caching is best-effort: a valid token is always returned even
     * if it could not be persisted.
     */
    static String accessToken(session, log, adminApi) {
        def node = session.getResource("/etc/commerce/config/access_token")
        if (node != null && node.exists()) {
            boolean fresh = node.getLastModified().getTime() + TOKEN_TTL_MILLIS >= System.currentTimeMillis()
            def cached = node.content
            if (fresh && cached != null && !cached.toString().trim().isEmpty()) {
                log.info("ShopifyAdmin: reusing cached Shopify access token")
                return cached.toString().trim()
            }
        }

        def mapper = new ObjectMapper()
        def body = mapper.writeValueAsString([
            client_id    : adminApi.clientID,
            client_secret: adminApi.clientSecret,
            grant_type   : "client_credentials",
        ])
        def client = HttpClient.newHttpClient()
        def request = HttpRequest.newBuilder()
            .uri(URI.create("https://${adminApi.shopDomain}/admin/oauth/access_token"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to obtain Shopify access token: ${response.statusCode()} - ${response.body()}")
        }
        def token = mapper.readValue(response.body(), Map.class)?.access_token
        if (token == null || token.toString().trim().isEmpty()) {
            throw new RuntimeException("Shopify returned an empty access token")
        }
        token = token.toString().trim()

        // Cache for reuse (best-effort - a cache miss must not fail the caller).
        try {
            if (node.exists()) {
                node.write(token)
                node.setProperty("jcr:lastModified", new Date())
            } else {
                node.createFile().write(token)
            }
            session.commit()
            log.info("ShopifyAdmin: obtained and cached a new Shopify access token")
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            log.warn("ShopifyAdmin: could not cache access token: ${e.message}")
        }
        return token
    }

    /**
     * POST a GraphQL request and return the parsed response, throwing on a
     * transport error or a top-level GraphQL `errors` array. `payload` may be a
     * Map (serialized here) or a pre-serialized JSON String. The HttpClient is
     * supplied by the caller so it can be reused across calls.
     */
    static Object graphql(HttpClient client, String endpoint, String accessToken, payload) {
        def mapper = new ObjectMapper()
        def body = (payload instanceof CharSequence) ? payload.toString() : mapper.writeValueAsString(payload)
        def request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("X-Shopify-Access-Token", accessToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw new RuntimeException("Shopify GraphQL API error: ${response.statusCode()} - ${response.body()}")
        }
        def json = mapper.readValue(response.body(), Object.class)
        if (json?.errors != null) {
            throw new RuntimeException("Shopify GraphQL errors: ${mapper.writeValueAsString(json.errors)}")
        }
        return json
    }

    // --- Bulk Operations (async full exports; OFF the foreground cost bucket) --
    // Bulk execution does not consume the GraphQL
    // cost bucket, so a full-catalog export never throttles foreground Admin API calls.
    // (Shapes follow the standard Shopify bulk GraphQL; verify against the configured API
    //  version with a live smoke test.)

    /** Encode a string as a GraphQL string literal (reuses JSON string escaping). */
    private static String gqlString(String s) {
        return new ObjectMapper().writeValueAsString(s == null ? "" : s)
    }

    /** Start a Bulk Operation for the given GraphQL query; returns the bulk operation gid. */
    static String startBulk(HttpClient client, String endpoint, String accessToken, String bulkQuery) {
        def mutation = """
mutation {
  bulkOperationRunQuery(query: ${gqlString(bulkQuery)}) {
    bulkOperation { id status }
    userErrors { field message }
  }
}
""".trim()
        def resp = graphql(client, endpoint, accessToken, [query: mutation])
        def r = resp?.data?.bulkOperationRunQuery
        def errs = r?.userErrors
        if (errs != null && !errs.isEmpty()) {
            throw new RuntimeException("bulkOperationRunQuery userErrors: ${new ObjectMapper().writeValueAsString(errs)}")
        }
        def gid = r?.bulkOperation?.id
        if (gid == null) {
            throw new RuntimeException("bulkOperationRunQuery returned no bulkOperation id")
        }
        return gid.toString()
    }

    /** The current Bulk Operation (most recent), or null: [id, status, url, errorCode]. */
    static Map currentBulk(HttpClient client, String endpoint, String accessToken) {
        def resp = graphql(client, endpoint, accessToken,
            [query: "{ currentBulkOperation { id status url errorCode objectCount } }"])
        def c = resp?.data?.currentBulkOperation
        if (c == null) return null
        return [id: c.id?.toString(), status: c.status?.toString(),
                url: c.url?.toString(), errorCode: c.errorCode?.toString()]
    }

    /** True when a Bulk Operation is CREATED or RUNNING (lane pre-check / singleton guard). */
    static boolean currentBulkRunning(HttpClient client, String endpoint, String accessToken) {
        def s = currentBulk(client, endpoint, accessToken)?.status
        return s == "CREATED" || s == "RUNNING"
    }

    /**
     * Status + downloadable result URL for a Bulk Operation by gid, or null.
     *
     * `node(id:)` returns null for a BulkOperation on newer API versions, so:
     *   • 2026-01+ : the dedicated `bulkOperation(id:)` query;
     *   • older    : `currentBulkOperation` — the broker runs ONE bulk at a time, so the shop's
     *                current (query) op is this job's; the id is verified.
     * Tries the new query first and falls back when the field is unavailable.
     */
    static Map bulkByGid(HttpClient client, String endpoint, String accessToken, String gid) {
        if (gid != null) {
            try {
                def resp = graphql(client, endpoint, accessToken,
                    [query: "{ bulkOperation(id: ${gqlString(gid)}) { id status url errorCode objectCount } }".toString()])
                def b = resp?.data?.bulkOperation
                if (b != null) {
                    return [id: b.id?.toString(), status: b.status?.toString(),
                            url: b.url?.toString(), errorCode: b.errorCode?.toString()]
                }
            } catch (Exception ignore) {
                // `bulkOperation(id:)` is not available on this API version — fall back below.
            }
        }
        def c = currentBulk(client, endpoint, accessToken)
        if (c == null) return null
        if (gid != null && c.id != null && c.id.toString() != gid.toString()) {
            return null   // a different (newer) bulk is current — not this job's
        }
        return c
    }
}
