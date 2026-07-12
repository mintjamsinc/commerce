// Only accept POST requests
if (request.getMethod() != "POST") {
    response.setStatus(405)
    return
}

// Read request body
byte[] bodyBytes = request.getInputStream().readAllBytes()
String bodyString = new String(bodyBytes, "UTF-8")

// Verify the HMAC-SHA256 signature. This endpoint is PUBLIC (unauthenticated), so
// its session cannot read the webhook secret (/etc/commerce/config/shopify.yml is
// jcr:all = deny for anonymous). Delegate verification to the privileged route
// direct:commerce-webhook-verify, which reads the secret and checks the signature
// as the service user, then returns a verdict. We call it synchronously (.send(),
// InOut) so the verdict gates the response and unverified payloads never reach the
// ingest core.
String hmacHeader = request.getHeader("X-Shopify-Hmac-SHA256")
def verifyReply
try {
    verifyReply = IntegrationAPI.createMessageSender()
        .setEndpointURI("direct:commerce-webhook-verify")
        .setBody(bodyBytes)
        .setHeader("webhook_hmac", hmacHeader == null ? "" : hmacHeader)
        .send()
} catch (Exception e) {
    // The verification route could not be reached/executed: a server-side failure.
    // Fail closed with 500 (not 401) so it is not mistaken for an unauthorized caller.
    log.error("Shopify webhook verification could not run: ${e.message}", e)
    emitHealth("verify_error")
    response.setStatus(500)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"Internal error"}')
    return
}

// A server-side failure (secret missing/unreadable) is distinct from a signature
// mismatch: answer 500 so it is not mistaken for an unauthorized caller, and so
// Shopify's retries can succeed once the configuration is fixed.
def verifyError = verifyReply.getHeader("webhook_verify_error")
if (verifyError != null) {
    log.error("Shopify webhook verification could not run: ${verifyError}")
    emitHealth("verify_error")
    response.setStatus(500)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"Internal error"}')
    return
}

if (!Boolean.TRUE.equals(verifyReply.getHeader("webhook_verified"))) {
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

// This endpoint is the Shopify ADAPTER: it verifies Shopify's
// signature, then hands a source-agnostic envelope to the shared ingest core
// (direct:commerce-ingest), which logs, dispatches and (when needed) normalizes
// every topic and supports replay. Another backend connects by adding
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
