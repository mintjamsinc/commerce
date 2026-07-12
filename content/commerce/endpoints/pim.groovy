// Product Information Management endpoint (admin).
//
//   GET ?q=keyword[&limit=50]         — full-text product search (JCR jcr:contains)
//   GET ?productId=123                — unified product view (Shopify base + metafields
//                                       mirror + PIM overlay)
//   GET ?productId=123&raw=true       — the raw CMS-authored PIM overlay only
//   POST {productId, pim:{...} [,merge:true]} — write the PIM overlay (deep-merge by
//                                       default; merge:false replaces it)
//
// The PIM overlay holds extended attributes beyond Shopify (multi-language
// titles/descriptions, rich descriptions, custom attributes, metafields). It is
// stored on the product node, so it is versioned, full-text searchable and
// ACL-governed with the product. CMS-authored metafields are pushed to Shopify via
// the sync endpoint (POST {"action":"metafields","productId":...}).
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.

import commerce.Api
import commerce.Pim
import com.fasterxml.jackson.databind.ObjectMapper

def mapper = new ObjectMapper()

try {
    if (request.getMethod() == "GET") {
        // Faceted browse (the Commerce Products browser): filters over the
        // auto-indexed commerce:* properties + facet counts for drill-down.
        // sort=updated|sales|quantity ranks by the line-grain sales facts (base gross /
        // units sold) over the salesFrom/salesTo window (ISO instants; absent = all time).
        if ("browse".equalsIgnoreCase(request.getParameter("view"))) {
            respond(200, Pim.browse(repositorySession, [
                q           : request.getParameter("q"),
                vendor      : request.getParameter("vendor"),
                productType : request.getParameter("productType"),
                tag         : request.getParameter("tag"),
                sourceStatus: request.getParameter("sourceStatus"),
                status      : request.getParameter("status"),
                limit       : request.getParameter("limit"),
                offset      : request.getParameter("offset"),
                sort        : request.getParameter("sort"),
                salesFrom   : instantMs("salesFrom"),
                salesTo     : instantMs("salesTo"),
            ]))
            return
        }

        def q = request.getParameter("q")
        if (q != null && !q.trim().isEmpty()) {
            int limit = paramInt("limit", 50, 1, 500)
            respond(200, [query: q, results: Pim.search(repositorySession, q, limit)])
            return
        }

        // The wire id form is the Shopify GID — peel to the numeric storage key
        // HERE (commerce.Api), never in the client.
        def productId = Api.legacyId(request.getParameter("productId"))
        if (productId == null || productId.trim().isEmpty()) {
            respond(400, [error: "productId or q is required"])
            return
        }
        boolean raw = "true".equalsIgnoreCase(request.getParameter("raw"))
        if (raw) {
            respond(200, [id: Api.gid("Product", productId), pim: Pim.read(repositorySession, productId)])
            return
        }
        def view = Pim.view(repositorySession, productId)
        if (view == null) {
            respond(404, [error: "Product not found: ${productId}".toString()])
            return
        }
        respond(200, view)
        return
    }

    if (request.getMethod() == "POST") {
        def body = new String(request.getInputStream().readAllBytes(), "UTF-8")
        def reqMap = body.trim().isEmpty() ? [:] : mapper.readValue(body, Map.class)
        def productId = Api.legacyId(reqMap.productId)
        if (!productId) {
            respond(400, [error: "productId is required"])
            return
        }
        def overlay = (reqMap.pim instanceof Map) ? reqMap.pim : [:]
        boolean merge = reqMap.merge == null || reqMap.merge.toString().toLowerCase() != "false"
        def editor = currentUserName()
        try {
            def saved = Pim.write(repositorySession, log, productId, overlay, merge, editor)
            respond(200, [ok: true, id: Api.gid("Product", productId), pim: saved])
        } catch (Exception e) {
            respond(400, [ok: false, error: e.message])
        }
        return
    }

    response.setStatus(405)
} catch (Exception e) {
    log.error("pim endpoint error: ${e.message}", e)
    respond(500, [error: "Internal error"])
}

// --- Helpers -----------------------------------------------------------------

void respond(int status, Map body) {
    response.setStatus(status)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(body))
}

int paramInt(String name, int dflt, int lo, int hi) {
    try {
        def v = request.getParameter(name)
        if (v != null && !v.trim().isEmpty()) return Math.max(lo, Math.min(hi, v.trim() as int))
    } catch (Exception ignore) {}
    return dflt
}

String currentUserName() {
    try { return repositorySession.getUserID()?.toString() } catch (Exception e) { return null }
}

// ISO-8601 instant parameter → epoch ms (Long), or null when absent/invalid (the client
// sends new Date(...).toISOString(), per the platform wire convention).
Long instantMs(String name) {
    def v = request.getParameter(name)
    if (v == null || v.trim().isEmpty()) return null
    try { return java.time.OffsetDateTime.parse(v.trim()).toInstant().toEpochMilli() } catch (Exception ignore) { return null }
}
