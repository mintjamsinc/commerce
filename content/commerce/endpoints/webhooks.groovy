// Shopify webhook registration endpoint (admin).
//
// One-click registration for the app's webhook topics: the operator pastes the public
// webhook callback URL (…/content/public/commerce/endpoints/shopify/webhook.groovy) and
// this endpoint CREATE-or-UPDATEs every OPERATIONAL topic subscription via the Admin
// GraphQL API — IDEMPOTENTLY, so re-running with a CHANGED url re-points the existing
// subscriptions instead of creating duplicates. This removes the manual per-topic
// subscribe step in Shopify's Notifications UI and covers bulk_operations/finish
// (API-only, absent from that UI), which was previously missed and fell back to the
// watchdog.
//
// The GDPR COMPLIANCE topics (customers/redact, customers/data_request, shop/redact) are
// ALSO routed by ingest, but a custom app configures those in its compliance webhook
// settings (Partner Dashboard) — they are NOT creatable here, so they are returned as an
// informational reminder only.
//
// Gated on adminApi.enabled (same switch as the sync endpoint).
//
//   GET  — capability/status + current subscription state:
//     { enabled, operational:[{ topic, enum, subscribed, callbackUrl }],
//       compliance:[topic strings], suggestedCallbackUrl }
//     (Admin API disabled → 200 with enabled:false and an unsubscribed view; the UI degrades.)
//   POST — register / re-point:
//     { "callbackUrl":"https://…/webhook.groovy" }
//     → 200 { ok:true, callbackUrl, results:[...], summary:{created,updated,skipped,error} }
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.
//
//   GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/webhooks.groovy
//   POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/webhooks.groovy

import java.net.http.HttpClient
import commerce.ShopifyAdmin
import commerce.ShopifyWebhooks
import com.fasterxml.jackson.databind.ObjectMapper

def mapper = new ObjectMapper()

// --- Resolve Admin API config (shared with the rest of the integration) ------
def config = null
def adminApi = null
boolean enabled = false
try {
    def cfgNode = repositorySession.getResource("/etc/commerce/config/shopify.yml")
    config = YAML.parse(cfgNode)
    adminApi = config?.adminApi ?: config
    enabled = ShopifyAdmin.adminApiEnabled(config)
} catch (Exception e) {
    log.warn("webhooks: could not read shopify.yml: ${e.message}")
}

try {
    if (request.getMethod() == "GET") {
        // Default view (Admin API off): a static, all-unsubscribed list so the Settings
        // UI still renders the topic set + compliance reminder and degrades cleanly.
        def operational = ShopifyWebhooks.OPERATIONAL_TOPICS.collect { topic ->
            [topic: topic, "enum": ShopifyWebhooks.enumFor(topic), subscribed: false, callbackUrl: null]
        }
        def suggestedCallbackUrl = ""
        def listError = null

        if (enabled) {
            // Reflect live Shopify state: index current subscriptions by topic enum.
            // Reading the subscriptions is a LIVE GraphQL call that can fail on its own
            // (e.g. the app's token lacks the read_webhooks scope, or a 2026-01 shape
            // change) — isolate that failure so the Admin API STAYS reported as enabled
            // and the real error is surfaced, instead of letting the whole GET 500 and
            // the UI misreport "Admin API not configured".
            try {
                def httpEndpoint = ShopifyAdmin.endpoint(adminApi)
                def token = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
                def httpClient = HttpClient.newHttpClient()

                def subs = ShopifyWebhooks.list(httpClient, httpEndpoint, token)
                def existingByEnum = [:]
                subs.each { sub -> if (sub?.topic != null) existingByEnum[sub.topic] = sub }

                operational = ShopifyWebhooks.OPERATIONAL_TOPICS.collect { topic ->
                    def e = existingByEnum[ShopifyWebhooks.enumFor(topic)]
                    [topic     : topic,
                     "enum"    : ShopifyWebhooks.enumFor(topic),
                     subscribed: (e != null),
                     callbackUrl: e?.callbackUrl]
                }

                // Prefill: the callbackUrl of ANY existing HTTP subscription (they should
                // all share one url; take the first non-null).
                def prefill = subs.find { it?.callbackUrl }?.callbackUrl
                if (prefill) suggestedCallbackUrl = prefill.toString()
            } catch (Exception e) {
                listError = e.message
                log.warn("webhooks GET: could not list subscriptions (Admin API still enabled): ${e.message}")
            }
        }

        respond(200, [
            enabled             : enabled,
            shopDomain          : adminApi?.shopDomain,
            apiVersion          : adminApi?.apiVersion,
            operational         : operational,
            compliance          : ShopifyWebhooks.COMPLIANCE_TOPICS,
            suggestedCallbackUrl: suggestedCallbackUrl,
            listError           : listError,
        ])
        return
    }

    if (request.getMethod() == "POST") {
        // Parse first so a malformed body is a clean 400 regardless of config state.
        def req
        try {
            def body = new String(request.getInputStream().readAllBytes(), "UTF-8")
            req = mapper.readValue(body, Map.class)
        } catch (Exception e) {
            respond(400, [ok: false, error: "Invalid JSON body"])
            return
        }

        def callbackUrl = req.callbackUrl?.toString()?.trim()
        if (callbackUrl == null || callbackUrl.isEmpty() || !callbackUrl.toLowerCase().startsWith("https://")) {
            respond(400, [ok: false, error: "callbackUrl must be a non-blank https:// URL"])
            return
        }

        // Gate on the Admin API (same 409 as the sync endpoint when it is off).
        if (!enabled) {
            respond(409, [ok: false, error: "Shopify Admin API is not configured (set the adminApi connection fields in shopify.yml)"])
            return
        }

        // WHO: HTTP admin endpoints run AS the logged-in operator (same identity the
        // sync / reconcile endpoints attribute writes to), so the ops log answers
        // who performed the registration.
        String actor = null
        try { actor = repositorySession.getUserID()?.toString() } catch (Exception ignore) {}

        def httpEndpoint = ShopifyAdmin.endpoint(adminApi)
        def token = ShopifyAdmin.accessToken(repositorySession, log, adminApi)
        def httpClient = HttpClient.newHttpClient()

        try {
            def result = ShopifyWebhooks.sync(repositorySession, log, httpClient, httpEndpoint, token, callbackUrl)
            writeAudit("webhook_sync", req, "ok", result?.summary, null, actor)
            respond(200, [ok: true] + result)
        } catch (Exception e) {
            log.warn("webhooks: sync failed: ${e.message}")
            writeAudit("webhook_sync", req, "failed", null, e.message, actor)
            respond(502, [ok: false, error: e.message])
        }
        return
    }

    response.setStatus(405)
} catch (Exception e) {
    log.error("webhooks endpoint error: ${e.message}", e)
    respond(500, [error: "Internal error"])
}

// --- Helpers -----------------------------------------------------------------

void respond(int status, Map body) {
    response.setStatus(status)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(body))
}

// Best-effort audit of the webhook registration (parallels the sync endpoint's own audit logging).
// entity = "webhook", entityId = null (the sync spans all operational topics); the
// summary is stored as the result so the ops log shows created/updated/skipped/error
// counts. Never throws.
void writeAudit(String action, Map req, String status, Object result, String error, String actor) {
    commerce.SyncAudit.record(repositorySession, log, action, req, status, result, error, actor, "webhook", null)
}
