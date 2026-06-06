// Publish content-commerce landing pages (category F, #22).
//
// Invoked by the commerce-pages-publish timer (as the service user) and on demand.
// Reads the CMS-authored block pages under /content/commerce/pages/, resolves their
// product blocks against the published catalog cards (catalog/index.json, #20), and
// writes a sanitized public projection under /content/public/commerce/pages/ that
// the ichigo.js landing renderer consumes. Prunes pages that were removed/unpublished.
//
// Runs after the catalog publisher (both on 5-minute timers); a product referenced
// before the catalog has published simply resolves on the next cycle. Best-effort.

import commerce.Pages
import commerce.Catalog
import commerce.Jcr

def SOURCE = Pages.SOURCE_DIR
def OUT = Pages.PUBLIC_DIR

try {
    def cfg = readYaml("/etc/commerce/config/storefront.yml")
    if (cfg == null || cfg.enabled?.toString()?.toLowerCase() == "false") {
        return
    }

    // Published catalog cards to resolve product blocks against.
    def index = Jcr.readMap(repositorySession, "${Catalog.PUBLIC_DIR}/index.json")
    def allCards = (index.products instanceof List) ? index.products : []

    def liveSlugs = [] as Set
    def entries = []

    def dir = repositorySession.getResource(SOURCE)
    if (dir != null && dir.exists()) {
        def it = dir.list()
        while (it.hasNext()) {
            def child = it.next()
            try {
                def name = child.getName()
                if (!name.endsWith(".json")) continue
                def source = JSON.parse(child.content.toString())
                if (!(source instanceof Map)) continue
                if (!source.slug) source.slug = name.replace(".json", "")

                def page = Pages.publicPage(source, allCards)
                if (page == null) continue   // draft / not publishable

                writeJson("${OUT}/${page.slug}.json", page)
                entries << Pages.indexEntry(page)
                liveSlugs << page.slug.toString()
            } catch (Exception e) {
                log.warn("publishPages: page ${child?.getName()} failed: ${e.message}")
            }
        }
    }

    entries.sort { a, b -> (a.title?.toString() ?: "") <=> (b.title?.toString() ?: "") }
    writeJson("${OUT}/index.json", [
        meta : [generatedAt: java.time.Instant.now().toString(), count: entries.size()],
        pages: entries,
    ])

    prune("${OUT}", liveSlugs)
    log.info("publishPages: published ${entries.size()} landing page(s)")
} catch (Exception e) {
    try { log.warn("publishPages: ${e.message}") } catch (Exception ignore) {}
}

// --- Helpers -----------------------------------------------------------------

void writeJson(String path, Object value) {
    def res = Jcr.getOrCreateFile(repositorySession, path)
    res.write(Pages.toJson(value))
    try { res.setProperty("jcr:mimeType", "application/json") } catch (Exception ignore) {}
    repositorySession.commit()
}

// Remove published page files (except index.json) whose slug is no longer live.
void prune(String dirPath, Set liveSlugs) {
    try {
        def dir = repositorySession.getResource(dirPath)
        if (dir == null || !dir.exists()) return
        def victims = []
        def it = dir.list()
        while (it.hasNext()) {
            def c = it.next()
            def n = c.getName()
            if (n.endsWith(".json") && n != "index.json") {
                def slug = n.replace(".json", "")
                if (!liveSlugs.contains(slug)) victims << c.getPath()
            }
        }
        if (!victims.isEmpty()) {
            victims.each { p -> try { def r = repositorySession.getResource(p); if (r != null && r.exists()) r.remove() } catch (Exception ignore) {} }
            repositorySession.commit()
            log.info("publishPages: pruned ${victims.size()} stale page(s)")
        }
    } catch (Exception e) {
        try { repositorySession.rollback() } catch (Exception ignore) {}
        log.warn("publishPages: prune failed: ${e.message}")
    }
}

def readYaml(String path) {
    try {
        def res = repositorySession.getResource(path)
        if (res != null && res.exists()) return YAML.parse(res)
    } catch (Exception e) {
        log.warn("publishPages: could not read ${path}: ${e.message}")
    }
    return null
}
