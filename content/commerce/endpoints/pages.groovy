// Content-commerce landing pages endpoint (admin). Category F (#22).
//
// CRUD over the CMS-authored block pages under /content/commerce/pages/{slug}.json
// that the publisher (publishPages.groovy) projects to the public storefront. Backs
// the Commerce Publishing app's landing-page editor.
//
//   GET                — list source pages (+ which slugs are currently published)
//   GET  ?slug=welcome  — the full source page document
//   POST {slug, page:{…}}        — create/replace a source page (stamped updatedAt/By)
//   POST {slug, delete:true}     — delete a source page
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs. The
// public projection is rebuilt separately via the storefront publish trigger.

import commerce.Jcr
import com.fasterxml.jackson.databind.ObjectMapper

def SOURCE_DIR = "/content/commerce/pages"
def PUBLIC_INDEX = "/content/public/commerce/pages/index.json"
def mapper = new ObjectMapper()

try {
    if (request.getMethod() == "GET") {
        def slug = blankToNull(request.getParameter("slug"))
        if (slug != null) {
            def res = repositorySession.getResource("${SOURCE_DIR}/${safeSlug(slug)}.json")
            if (res == null || !res.exists()) { respond(404, [error: "Page not found: ${slug}".toString()]); return }
            respond(200, [slug: slug, page: JSON.parse(res.content.toString())])
            return
        }
        respond(200, [pages: listPages(SOURCE_DIR), published: publishedSlugs(PUBLIC_INDEX)])
        return
    }

    if (request.getMethod() == "POST") {
        def body = new String(request.getInputStream().readAllBytes(), "UTF-8")
        def req = body.trim().isEmpty() ? [:] : mapper.readValue(body, Map.class)
        def slug = safeSlug(req.slug?.toString())
        if (!slug) { respond(400, [error: "A valid slug (lowercase letters, digits, hyphens) is required"]); return }
        def path = "${SOURCE_DIR}/${slug}.json".toString()

        // Delete.
        if (req.delete?.toString()?.toLowerCase() == "true") {
            def res = repositorySession.getResource(path)
            if (res != null && res.exists()) { res.remove(); repositorySession.commit() }
            respond(200, [ok: true, deleted: slug])
            return
        }

        // Create / replace.
        def page = (req.page instanceof Map) ? new LinkedHashMap(req.page) : [:]
        page.slug = slug
        page.updatedAt = java.time.Instant.now().toString()
        page.updatedBy = currentUserName()
        try {
            def res = Jcr.getOrCreateFile(repositorySession, path)
            res.write(Jcr.toJson(page))
            try { res.setProperty("jcr:mimeType", "application/json") } catch (Exception ignore) {}
            repositorySession.commit()
            respond(200, [ok: true, slug: slug, page: page])
        } catch (Exception e) {
            try { repositorySession.rollback() } catch (Exception ignore) {}
            respond(400, [ok: false, error: e.message])
        }
        return
    }

    response.setStatus(405)
} catch (Exception e) {
    log.error("pages endpoint error: ${e.message}", e)
    respond(500, [error: "Internal error"])
}

// --- Helpers -----------------------------------------------------------------

List listPages(String dir) {
    def out = []
    def folder = repositorySession.getResource(dir)
    if (folder == null || !folder.exists()) return out
    def it = folder.list()
    while (it.hasNext()) {
        def c = it.next()
        def n = c.getName()
        if (!n.endsWith(".json")) continue
        try {
            def doc = JSON.parse(c.content.toString())
            def slug = (doc?.slug ?: n.replace(".json", "")).toString()
            out << [
                slug     : slug,
                title    : doc?.title,
                status   : (doc?.status ?: "published").toString(),
                blocks   : (doc?.blocks instanceof List) ? doc.blocks.size() : 0,
                updatedAt: doc?.updatedAt,
            ]
        } catch (Exception ignore) {}
    }
    out.sort { a, b -> (a.title?.toString() ?: a.slug.toString()) <=> (b.title?.toString() ?: b.slug.toString()) }
    return out
}

List publishedSlugs(String indexPath) {
    try {
        def idx = Jcr.readMap(repositorySession, indexPath)
        if (idx.pages instanceof List) return idx.pages.collect { it?.slug?.toString() }.findAll { it }
    } catch (Exception ignore) {}
    return []
}

String safeSlug(String raw) {
    if (raw == null) return null
    def s = raw.trim().toLowerCase()
    return s ==~ /[a-z0-9][a-z0-9-]*/ ? s : null
}

String blankToNull(String s) { (s == null || s.trim().isEmpty()) ? null : s.trim() }

String currentUserName() {
    try { return repositorySession.getUserID()?.toString() } catch (Exception e) { return null }
}

void respond(int status, Map body) {
    response.setStatus(status)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(body))
}
