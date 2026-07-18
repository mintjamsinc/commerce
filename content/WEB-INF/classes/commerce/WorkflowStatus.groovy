package commerce

/**
 * Shared plumbing for the "set commerce:status" scripts that are wired as
 * CmsDelegate task / execution listeners across the product, order and refund
 * workflows. Each of those scripts still owns its own element-to-status mapping
 * (the business part); this class centralizes the mechanics they share:
 * resolving the process variable that points at the resource, and writing
 * commerce:status defensively.
 *
 * A status-update failure must never break the business process, so write() logs
 * and swallows repository errors. `session` is the script's repositorySession and
 * `log` is its logger binding. Lives under /content/WEB-INF/classes; use via
 * `import commerce.WorkflowStatus`.
 */
class WorkflowStatus {

    /**
     * The BPMN element the listener fired on: a user task's definition key, or
     * else the current activity id of the execution. Null when neither a task nor
     * an execution is available.
     */
    static String elementId(task, execution) {
        if (task != null) {
            return task.getTaskDefinitionKey()
        }
        if (execution != null) {
            return execution.getCurrentActivityId()
        }
        return null
    }

    /**
     * Resolve a path-like process variable. Prefers the `inputs`-mapped context
     * attribute, then falls back to the task and execution variable scopes, so the
     * script keeps working even if the inputs mapping is omitted. Returns the raw
     * value (or null); the caller decides how to handle absence.
     */
    static Object pathVariable(context, task, execution, String name) {
        def value = context.hasAttribute(name) ? context.getAttribute(name) : null
        if (value == null && task != null) {
            value = task.getVariable(name)
        }
        if (value == null && execution != null) {
            value = execution.getVariable(name)
        }
        return value
    }

    /**
     * Read a two-valued operator decision off the resource at `path`: returns
     * `altValue` when the property holds it (trimmed, case-insensitive), else
     * `defaultValue`. This is the shared mechanics of the "read decision"
     * service-task scripts (order review approve/reject, fulfillment
     * fulfill/close): an absent/unknown/unreadable decision always falls back
     * to the default, which each flow picks as its pre-decision behaviour.
     */
    static String readDecision(session, log, String source, String path,
                               String propName, String altValue, String defaultValue) {
        try {
            def resource = session.getResource(path)
            if (resource != null && resource.exists() && resource.hasProperty(propName)) {
                def v = resource.getProperty(propName).getValue()?.toString()?.trim()?.toLowerCase()
                if (v == altValue) return altValue
            }
        } catch (Exception e) {
            log.warn("${source}: ${path}: ${e.message} - defaulting to ${defaultValue}")
        }
        return defaultValue
    }

    /**
     * Set commerce:status on the resource at `path`, committing the change. A
     * missing resource or any repository error is logged (prefixed with `source`)
     * and swallowed - the workflow continues regardless. When `elementId` is
     * given it is appended to the success log line, matching the order/refund
     * scripts; pass null (the default) for the product script's shorter line.
     */
    static void write(session, log, String source, String path, String status, String elementId = null) {
        try {
            def resource = session.getResource(path)
            if (resource == null || !resource.exists()) {
                log.warn("${source}: resource not found: ${path} - skipping status update")
                return
            }
            resource.setProperty("commerce:status", status)
            // Lifecycle rule: every state mutation records
            // WHEN it happened — the audit/event logs already do, and status
            // transitions must too (typed Date, queryable like created_at).
            resource.setProperty("commerce:status_updated_at", new java.util.Date())
            session.commit()
            def suffix = elementId != null ? " (element ${elementId})" : ""
            log.info("${source}: ${path} commerce:status -> ${status}${suffix}")
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
            // Defensive: never let a status-update failure break the workflow.
            log.warn("${source}: failed to update commerce:status to '${status}' for ${path}: ${e.message}")
        }
    }
}
