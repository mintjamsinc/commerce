// Verify an incoming Shopify webhook's HMAC-SHA256 signature.
//
// WHY THIS RUNS HERE (and not in the public endpoint): the webhook endpoint at
// content/public/commerce/endpoints/shopify/webhook.groovy is UNAUTHENTICATED, so
// its session is the anonymous user, which is denied read on the secret config
// (/etc/commerce/config/shopify.yml has jcr:all = deny for anonymous). This script
// is invoked through direct:commerce-webhook-verify as commerce-service-user
// (runAs), so it can read the shared secret while the endpoint never touches it.
// The endpoint receives only a yes/no verdict and gates its HTTP response on it,
// so unverified payloads never reach the ingest core (replay-attack safe).
//
// Inputs (script attributes, mapped from the exchange by the verify route):
//   rawBody    : byte[]  — the exact raw request body (HMAC is computed over bytes)
//   hmacHeader : String  — the X-Shopify-Hmac-SHA256 header value
//
// Outputs (script attributes, mapped back to exchange headers by the verify route):
//   webhookVerified    : boolean — true only when the signature matches
//   webhookVerifyError : String  — set ONLY when verification could not run for a
//                        server-side reason (secret missing/unreadable). Lets the
//                        endpoint answer 500 (server) vs 401 (signature mismatch).
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

boolean verified = false
String verifyError = null
try {
    def configNode = repositorySession.getResource("/etc/commerce/config/shopify.yml")
    def config = YAML.parse(configNode)

    // The webhook secret is INDEPENDENT of the Admin API credentials and lives at
    // the top level of shopify.yml (webhooks work even when the Admin API is off).
    String sharedSecret = config.webhookSecret
    if (sharedSecret == null || sharedSecret.trim().isEmpty()) {
        // Not a client error: the platform is not configured to verify webhooks.
        verifyError = "webhookSecret is not configured in /etc/commerce/config/shopify.yml"
    } else {
        byte[] body = (rawBody instanceof byte[]) ? rawBody : rawBody.toString().getBytes("UTF-8")

        Mac mac = Mac.getInstance("HmacSHA256")
        mac.init(new SecretKeySpec(sharedSecret.getBytes("UTF-8"), "HmacSHA256"))
        byte[] computed = mac.doFinal(body)
        String computedBase64 = Base64.encoder.encodeToString(computed)

        String expected = (hmacHeader == null) ? "" : hmacHeader
        // Constant-time comparison to avoid leaking timing information.
        verified = java.security.MessageDigest.isEqual(
            computedBase64.getBytes("UTF-8"),
            expected.getBytes("UTF-8"))
    }
} catch (Exception e) {
    // Reading or parsing the secret failed (e.g. config missing): a server-side
    // condition, surfaced as verify_error so the endpoint can answer 500.
    verifyError = e.message
    log.error("verifyWebhook: HMAC verification could not run: ${e.message}", e)
}

context.setAttribute("webhookVerified", verified)
if (verifyError != null) {
    context.setAttribute("webhookVerifyError", verifyError)
}
