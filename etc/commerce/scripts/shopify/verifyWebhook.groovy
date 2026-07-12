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

    // A webhook's HMAC signing key depends on HOW the subscription was created:
    //   • APP / Admin-API-registered subscriptions (webhookSubscriptionCreate — how
    //     this app self-registers its topics) are signed with the APP'S CLIENT SECRET
    //     (API secret key). This is the PRIMARY key.
    //   • Subscriptions created manually in the Shopify admin (Settings > Notifications
    //     > Webhooks) are signed with that page's shop webhook secret — kept as a
    //     FALLBACK (config.webhookSecret) so hand-created webhooks still verify.
    // We accept the payload if EITHER key produces a matching signature.
    def adminApi = config?.adminApi
    def candidates = []
    def clientSecret = (adminApi instanceof Map) ? adminApi.clientSecret?.toString() : null
    if (clientSecret != null && !clientSecret.trim().isEmpty()) candidates << clientSecret.trim()
    def webhookSecret = config.webhookSecret?.toString()
    if (webhookSecret != null && !webhookSecret.trim().isEmpty()) candidates << webhookSecret.trim()

    if (candidates.isEmpty()) {
        // Not a client error: the platform has no key to verify against.
        verifyError = "no webhook verification secret configured (set adminApi.clientSecret, or webhookSecret for admin-created webhooks) in /etc/commerce/config/shopify.yml"
    } else {
        byte[] body = (rawBody instanceof byte[]) ? rawBody : rawBody.toString().getBytes("UTF-8")
        String expected = (hmacHeader == null) ? "" : hmacHeader
        for (secret in candidates) {
            Mac mac = Mac.getInstance("HmacSHA256")
            mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
            String computedBase64 = Base64.encoder.encodeToString(mac.doFinal(body))
            // Constant-time comparison to avoid leaking timing information.
            if (java.security.MessageDigest.isEqual(
                    computedBase64.getBytes("UTF-8"), expected.getBytes("UTF-8"))) {
                verified = true
                break
            }
        }
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
