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
     * True only when the Shopify Admin API integration is explicitly enabled
     * (adminApi.enabled == true) in the already-parsed shopify.yml `config`.
     * Anything else (flag absent / config missing) is treated as disabled.
     */
    static boolean adminApiEnabled(config) {
        def enabled = config?.adminApi?.enabled
        return enabled != null && enabled.toString().trim().toLowerCase() == "true"
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
}
