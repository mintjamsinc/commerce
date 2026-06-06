// Open tasks + SLA status endpoint (admin).
//
// Returns the open human tasks of the commerce workflows with their computed SLA
// status (ok / unclaimed / open / overdue, per /etc/commerce/config/sla.yml).
// Intended for operators and the Commerce dashboard (a future Webtop app).
//
// This script lives OUTSIDE /content/public, so the CGI enforces authentication
// and ACLs - only authorized users can call it.
//
//   GET /bin/cms.cgi/{workspace}/content/commerce/endpoints/tasks.groovy
//
// (Read-only: it never escalates or mutates tasks; that is the SLA scanner's job.)

import commerce.SimpleYaml
import commerce.TaskSla
import com.fasterxml.jackson.databind.ObjectMapper

if (request.getMethod() != "GET") {
    response.setStatus(405)
    return
}

// Our BPMN flows that raise human tasks.
def PROCESS_KEYS = ["order-review-flow", "refund-review-flow", "product-update-flow", "backorder-release-flow"]

try {
    def cfg = [:]
    def cfgRes = repositorySession.getResource("/etc/commerce/config/sla.yml")
    if (cfgRes != null && cfgRes.exists()) {
        cfg = SimpleYaml.parse(cfgRes.content?.toString())
    }

    def engine = ProcessAPI.getEngine()
    def taskService = engine.getTaskService()
    long now = System.currentTimeMillis()

    def out = []
    PROCESS_KEYS.each { key ->
        def found
        try {
            found = taskService.createTaskQuery().processDefinitionKey(key).active().list()
        } catch (Exception e) {
            log.warn("tasks endpoint: query failed for ${key}: ${e.message}")
            return
        }
        found.each { t ->
            def createTime = t.getCreateTime()
            def dueDate = t.getDueDate()
            long createMs = createTime == null ? now : createTime.getTime()
            def taskMap = [
                id          : t.getId(),
                name        : t.getName(),
                assignee    : t.getAssignee(),
                createTimeMs : createMs,
                dueDateMs   : dueDate == null ? null : dueDate.getTime(),
                priority    : t.getPriority(),
                processKey  : key,
            ]
            def slaStatus = TaskSla.status(cfg, taskMap, now) ?: "ok"
            out << [
                id           : t.getId(),
                name         : t.getName(),
                assignee     : t.getAssignee(),
                unassigned   : (t.getAssignee() == null || t.getAssignee().trim().isEmpty()),
                priority     : t.getPriority(),
                processKey   : key,
                processInstanceId: t.getProcessInstanceId(),
                createTime   : createTime == null ? null : createTime.toInstant().toString(),
                dueDate      : dueDate == null ? null : dueDate.toInstant().toString(),
                ageMinutes   : Math.max(0L, (now - createMs) / 60000L as long),
                slaStatus    : slaStatus,
            ]
        }
    }

    // Surface the most urgent first: overdue, then unclaimed, then open, then ok;
    // tie-break by age (oldest first).
    def rank = [overdue: 0, unclaimed: 1, open: 2, ok: 3]
    out.sort { a, b ->
        int r = (rank[a.slaStatus] ?: 9) <=> (rank[b.slaStatus] ?: 9)
        r != 0 ? r : (b.ageMinutes <=> a.ageMinutes)
    }

    response.setStatus(200)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write(new ObjectMapper().writeValueAsString([now: java.time.Instant.now().toString(), count: out.size(), tasks: out]))
} catch (Exception e) {
    log.error("tasks endpoint error: ${e.message}", e)
    response.setStatus(500)
    response.setHeader("Content-Type", "application/json")
    response.getWriter().write('{"error":"Internal error"}')
}
