// Task SLA scanner.
//
// Invoked periodically by the commerce-task-sla timer route (as the service
// user). Gathers the open human tasks from the BPMN engine, evaluates them
// against the SLA rules in /etc/commerce/config/sla.yml, and escalates any
// breaches: an alert through the notification channels (commerce.Alerts, the
// same plumbing as the health monitor) plus an optional engine-side action
// (priority bump / candidate group) so the task surfaces in operators' lists.
//
// The escalation rules and debounce live in commerce.TaskSla (pure logic); this
// script is the thin adapter that reads the live engine and applies engine-side
// actions. Best-effort throughout: a failure is logged, never thrown.

import commerce.SimpleYaml
import commerce.TaskSla

// Cluster guard: the timer fires on every node of a cluster, so only the
// node that wins this lease runs the task; the others skip this tick.
// Manual triggers are asynchronous fire-and-forget, so skipping while a
// run is already in flight on another node is correct for them as well.
// In a standalone deployment the lease is always granted immediately.
def __clusterLease = cluster.tryLock("commerce-task-sla", 900000)
if (__clusterLease == null) {
    log.info("scanTaskSla: another cluster node is running this task - skipping")
    return
}
try {
    // Our BPMN flows that raise human tasks (see etc/bpm/processes/commerce/shopify).
    def PROCESS_KEYS = ["order-review-flow", "refund-review-flow", "product-update-flow", "backorder-release-flow"]
    def SLA_CONFIG = "/etc/commerce/config/sla.yml"

    try {
        def cfgRes = repositorySession.getResource(SLA_CONFIG)
        if (cfgRes == null || !cfgRes.exists()) {
            return
        }
        def cfg = SimpleYaml.parse(cfgRes.content?.toString())
        if (cfg == null || cfg.enabled?.toString()?.toLowerCase() == "false") {
            return
        }

        def engine = ProcessAPI.getEngine()
        def taskService = engine.getTaskService()

        // --- Gather open human tasks for our flows --------------------------------
        def tasks = []
        def openIds = []
        PROCESS_KEYS.each { key ->
            def found
            try {
                found = taskService.createTaskQuery().processDefinitionKey(key).active().list()
            } catch (Exception e) {
                log.warn("scanTaskSla: query failed for ${key}: ${e.message}")
                return
            }
            found.each { t ->
                try {
                    def id = t.getId()
                    openIds << id
                    def createTime = t.getCreateTime()
                    def dueDate = t.getDueDate()
                    tasks << [
                        id          : id,
                        name        : t.getName(),
                        assignee    : t.getAssignee(),
                        createTimeMs : createTime == null ? System.currentTimeMillis() : createTime.getTime(),
                        dueDateMs   : dueDate == null ? null : dueDate.getTime(),
                        priority    : t.getPriority(),
                        processKey  : key,
                        context     : context(taskService, id),
                    ]
                } catch (Exception e) {
                    log.warn("scanTaskSla: could not read task ${t?.getId()}: ${e.message}")
                }
            }
        }

        // --- Evaluate + escalate (alerts handled inside TaskSla) -------------------
        def fired = TaskSla.evaluate(repositorySession, log, cfg, tasks, System.currentTimeMillis())

        // --- Optional engine-side escalation actions on breached tasks ------------
        def esc = cfg.escalation
        if (esc != null && !fired.isEmpty()) {
            def bumpPriority = esc.priority != null
            int newPriority = bumpPriority ? (esc.priority.toString().trim() as int) : 0
            def candidateGroup = esc.candidateGroup?.toString()?.trim()
            fired.each { f ->
                def taskId = f.taskId
                if (bumpPriority) {
                    try { taskService.setPriority(taskId, newPriority) }
                    catch (Exception e) { log.warn("scanTaskSla: setPriority(${taskId}) failed: ${e.message}") }
                }
                if (candidateGroup) {
                    try { taskService.addCandidateGroup(taskId, candidateGroup) }
                    catch (Exception e) { log.warn("scanTaskSla: addCandidateGroup(${taskId}) failed: ${e.message}") }
                }
            }
        }

        if (!fired.isEmpty()) {
            log.info("scanTaskSla: escalated ${fired.size()} task(s)")
        }

        // --- Drop cooldown state for tasks that have since completed ---------------
        TaskSla.prune(repositorySession, log, openIds)
    } catch (Exception e) {
        try { log.warn("scanTaskSla: ${e.message}") } catch (Exception ignore) {}
    }
} finally {
    __clusterLease.close()
}


// Build a small human-readable context map from known process variables.
Map context(taskService, String taskId) {
    def ctx = [:]
    def add = { String label, String var ->
        try {
            def v = taskService.getVariable(taskId, var)
            if (v != null && !v.toString().trim().isEmpty()) {
                ctx[label] = v.toString()
            }
        } catch (Exception ignore) {}
    }
    add("Order ID", "order_id")
    add("Product ID", "productID")
    add("Refund ID", "refund_id")
    return ctx
}
