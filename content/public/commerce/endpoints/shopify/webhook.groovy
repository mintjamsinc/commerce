import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Only accept POST requests
if (request.getMethod() != "POST") {
    response.setStatus(405)
    return
}

// Read request body
byte[] bodyBytes = request.getInputStream().readAllBytes()
String bodyString = new String(bodyBytes, "UTF-8")

// Load shared secret from JCR config
def configNode = repositorySession.getResource("/etc/commerce/config/shopify.yml")
def config = YAML.parse(configNode)
String sharedSecret = config.webhookSecret

// Verify HMAC-SHA256 signature
String hmacHeader = request.getHeader("X-Shopify-Hmac-SHA256")
if (hmacHeader == null || !verifyHmac(bodyBytes, hmacHeader, sharedSecret)) {
    log.warn("Shopify webhook HMAC verification failed")
    emitHealth("hmac_failure")
    response.setStatus(401)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"Unauthorized"}')
    return
}

// Extract Shopify headers
String topic = request.getHeader("X-Shopify-Topic")
String shopDomain = request.getHeader("X-Shopify-Shop-Domain")
String webhookID = request.getHeader("X-Shopify-Webhook-Id")

if (topic == null || topic.trim().isEmpty() || webhookID == null || webhookID.trim().isEmpty()) {
    log.warn("Shopify webhook missing topic / id headers")
    emitHealth("unhandled")
    response.setStatus(400)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"Missing topic or id"}')
    return
}

// This endpoint is the Shopify ADAPTER (#3 multi-backend): it verifies Shopify's
// signature, then hands a source-agnostic envelope to the shared ingest core
// (direct:commerce-ingest), which logs, dispatches and (when needed) normalizes
// every topic (#1) and supports replay (#4). Another backend connects by adding
// its own adapter that produces the same envelope — no change here or downstream.
try {
    IntegrationAPI.createMessageSender()
        .setEndpointURI("direct:commerce-ingest")
        .setBody(bodyString)
        .setHeader("event_source", "shopify")
        .setHeader("event_topic", topic)
        .setHeader("event_id", webhookID)
        .setHeader("event_shop_domain", shopDomain)
        .setHeader("received_at", new Date().toInstant().toString())
        // This endpoint is unauthenticated (public): explicitly run the ingest
        // pipeline as the service user. The ingest core is now caller-driven, so an
        // operator-triggered replay can instead pass its own identity.
        .setHeader("runAs", "commerce-service-user")
        .sendAsync()

    emitHealth("received")
    response.setStatus(200)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"status":"received"}')
} catch (Exception e) {
    log.error("Failed to dispatch Shopify webhook: ${e.message}", e)
    emitHealth("dispatch_error")
    response.setStatus(500)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"Internal error"}')
}

// --- Helper functions ---

// Emit a fire-and-forget webhook health counter to the privileged health route.
// The public endpoint cannot write metrics itself (it runs unauthenticated), so
// recording happens in direct:commerce-health as the service user. Never throws.
void emitHealth(String metric) {
    try {
        IntegrationAPI.createMessageSender()
            .setEndpointURI("direct:commerce-health")
            .setBody("")
            .setHeader("health_group", "webhook")
            .setHeader("health_metric", metric)
            .setHeader("received_at", new Date().toInstant().toString())
            .sendAsync()
    } catch (Exception e) {
        log.warn("Failed to emit webhook health metric '${metric}': ${e.message}")
    }
}

boolean verifyHmac(byte[] body, String expected, String secret) {
    try {
        Mac mac = Mac.getInstance("HmacSHA256")
        mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
        byte[] computed = mac.doFinal(body)
        String computedBase64 = Base64.encoder.encodeToString(computed)
        // Constant-time comparison
        return java.security.MessageDigest.isEqual(
            computedBase64.getBytes("UTF-8"),
            expected.getBytes("UTF-8")
        )
    } catch (Exception e) {
        log.error("HMAC verification error: ${e.message}", e)
        return false
    }
}
