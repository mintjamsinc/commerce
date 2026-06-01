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
    response.setStatus(401)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"Unauthorized"}')
    return
}

// Extract Shopify headers
String topic = request.getHeader("X-Shopify-Topic")
String shopDomain = request.getHeader("X-Shopify-Shop-Domain")
String webhookID = request.getHeader("X-Shopify-Webhook-Id")

// Route to Camel based on topic
String endpointURI = resolveEndpoint(topic)
if (endpointURI == null) {
    log.warn("Unhandled Shopify webhook topic: ${topic}")
    response.setStatus(200)
    response.getWriter().write('{"status":"ignored"}')
    return
}

// Send to Camel endpoint asynchronously (fire-and-forget)
try {
    IntegrationAPI.createMessageSender()
        .setEndpointURI(endpointURI)
        .setBody(bodyString)
        .setHeader("shopify_topic", topic)
        .setHeader("shopify_shop_domain", shopDomain)
        .setHeader("shopify_webhook_id", webhookID)
        .setHeader("received_at", new Date().toInstant().toString())
        .sendAsync()

    response.setStatus(200)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"status":"received"}')
} catch (Exception e) {
    log.error("Failed to dispatch Shopify webhook: ${e.message}", e)
    response.setStatus(500)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"Internal error"}')
}

// --- Helper functions ---

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

String resolveEndpoint(String topic) {
    switch (topic) {
        case "orders/paid":
            return "direct:shopify-order-paid"
        case "products/create":
        case "products/update":
            return "direct:shopify-product-update"
        case "products/delete":
            return "direct:shopify-product-delete"
        default:
            return null
    }
}
