// Reconciliation endpoint (admin). Category G (#24).
//
//   GET  — the latest drift report + reconciliation state (cursor / last run).
//   POST — trigger a reconciliation run now (fire-and-forget; the batch runs as the
//          service user via direct:commerce-reconcile).
//
// Lives OUTSIDE /content/public, so the CGI enforces authentication and ACLs.
//
//   GET  /bin/cms.cgi/{workspace}/content/commerce/endpoints/reconcile.groovy
//   POST /bin/cms.cgi/{workspace}/content/commerce/endpoints/reconcile.groovy

import commerce.Jcr
import com.fasterxml.jackson.databind.ObjectMapper

def RECON_DIR = "/content/commerce/reconciliation"
def mapper = new ObjectMapper()

try {
    if (request.getMethod() == "GET") {
        def out = [
            generatedAt: java.time.Instant.now().toString(),
            state      : Jcr.readMap(repositorySession, "${RECON_DIR}/state.json"),
            latest     : latestReport(RECON_DIR),
        ]
        respond(200, out)
        return
    }

    if (request.getMethod() == "POST") {
        try {
            // Run the on-demand reconciliation as the operator who triggered it, so
            // the reports / healed products written under /content/commerce carry
            // their identity (jcr:createdBy etc.). The route falls back to the
            // service user if this is blank.
            IntegrationAPI.createMessageSender()
                .setEndpointURI("direct:commerce-reconcile")
                .setBody("")
                .setHeader("runAs", repositorySession.getUserID())
                .sendAsync()
            respond(202, [triggered: true])
        } catch (Exception e) {
            log.warn("reconcile: could not trigger run: ${e.message}")
            respond(500, [triggered: false, error: e.message])
        }
        return
    }

    response.setStatus(405)
} catch (Exception e) {
    log.error("reconcile endpoint error: ${e.message}", e)
    respond(500, [error: "Internal error"])
}

// --- Helpers -----------------------------------------------------------------

void respond(int status, Map body) {
    response.setStatus(status)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString(body))
}

// Newest recon_*.json across this + last month (reports are named with epoch ms).
Map latestReport(String dir) {
    def ymf = new java.text.SimpleDateFormat("yyyy/MM")
    def cal = java.util.Calendar.getInstance()
    String best = null
    String bestPath = null
    for (int i = 0; i <= 1; i++) {
        def folder = repositorySession.getResource("${dir}/${ymf.format(cal.getTime())}")
        if (folder != null && folder.exists()) {
            def it = folder.list()
            while (it.hasNext()) {
                def c = it.next()
                def n = c.getName()
                if (n.startsWith("recon_") && n.endsWith(".json")) {
                    if (best == null || n > best) { best = n; bestPath = c.getPath() }
                }
            }
        }
        cal.add(java.util.Calendar.MONTH, -1)
    }
    return bestPath == null ? [:] : Jcr.readMap(repositorySession, bestPath)
}
