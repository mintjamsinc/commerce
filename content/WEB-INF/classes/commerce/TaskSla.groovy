package commerce

/**
 * Task SLA evaluation: decides when an open human task has breached a
 * service-level rule and should be escalated. Pure logic over plain task data
 * (no Camunda dependency) so it stays testable; the scanner script
 * (scanTaskSla.groovy) gathers the live tasks from the engine and performs any
 * engine-side action (e.g. priority bump) on the breaches this returns.
 *
 * Rules (from sla.yml), evaluated per task in severity order — at most ONE
 * escalation per task per scan to avoid noise:
 *   1. overdue   : a due date is set and now is past it (+ graceMinutes)
 *   2. unclaimed : no assignee for longer than unclaimed.minutes
 *   3. open      : open (claimed or not) longer than open.minutes
 *
 * Escalations are debounced per task+rule through {@link Alerts} (cooldown), so a
 * standing breach is not re-announced on every scan.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.TaskSla}.
 */
class TaskSla {

    static final String STATE_PATH = "/content/commerce/tasks/sla-state.json"

    /**
     * Evaluate {@code tasks} against {@code cfg} and fire escalation alerts for any
     * breaches (respecting cooldown). Returns the list of fired escalations as
     * maps: [taskId, rule, ageMinutes]. {@code nowMs} is the evaluation time.
     *
     * Each task map carries: id, name, assignee, createTimeMs (long),
     * dueDateMs (Long or null), priority (int), processKey, context (Map of
     * label→value for the human-readable summary).
     */
    static List<Map> evaluate(session, log, Map cfg, List<Map> tasks, long nowMs) {
        def fired = []
        if (cfg == null || !truthy(cfg.enabled, true) || tasks == null) {
            return fired
        }
        long cooldownMs = num(cfg.cooldownMinutes, 120) * 60_000L

        tasks.each { task ->
            try {
                def breach = firstBreach(cfg, task, nowMs)
                if (breach == null) {
                    return
                }
                def taskId = task.id?.toString()
                if (taskId == null) {
                    return
                }
                long ageMin = ageMinutes(task, nowMs)
                def key = "taskSla:${breach.rule}:${taskId}"
                def message = message(breach, task, ageMin)
                if (Alerts.fire(session, log, STATE_PATH, key, cooldownMs, message)) {
                    fired << [taskId: taskId, rule: breach.rule, ageMinutes: ageMin]
                }
            } catch (Exception e) {
                try { log?.warn("taskSla: evaluate(${task?.id}) failed: ${e.message}") } catch (Exception ignore) {}
            }
        }
        return fired
    }

    /** Drop cooldown entries for tasks that are no longer open. */
    static void prune(session, log, Collection openTaskIds) {
        def ids = (openTaskIds ?: []).collect { it?.toString() } as Set
        Alerts.pruneState(session, log, STATE_PATH) { key ->
            // keys look like "taskSla:<rule>:<taskId>"
            def s = key?.toString() ?: ""
            int i = s.lastIndexOf(":")
            if (i < 0) {
                return true
            }
            return ids.contains(s.substring(i + 1))
        }
    }

    /**
     * The breached rule name for a task ("overdue" / "unclaimed" / "open") or null
     * when within SLA. Read-only (does not alert) — used by the tasks endpoint to
     * surface SLA status alongside the open tasks.
     */
    static String status(Map cfg, Map task, long nowMs) {
        if (cfg == null || task == null) {
            return null
        }
        return firstBreach(cfg, task, nowMs)?.rule
    }

    // --- Rule evaluation -------------------------------------------------------

    /** The most severe breached rule for this task, or null. */
    private static Map firstBreach(Map cfg, Map task, long nowMs) {
        long ageMs = nowMs - num(task.createTimeMs)

        def overdue = cfg.overdue
        if (overdue != null && truthy(overdue.enabled, true) && task.dueDateMs != null) {
            long graceMs = num(overdue.graceMinutes, 0) * 60_000L
            if (nowMs > num(task.dueDateMs) + graceMs) {
                return [rule: "overdue", icon: "⏰", headline: "Task overdue"]
            }
        }

        def unclaimed = cfg.unclaimed
        if (unclaimed != null && truthy(unclaimed.enabled, true) && isBlank(task.assignee)) {
            long limitMs = num(unclaimed.minutes, 60) * 60_000L
            if (ageMs >= limitMs) {
                return [rule: "unclaimed", icon: "🙋", headline: "Task unclaimed too long"]
            }
        }

        def open = cfg.open
        if (open != null && truthy(open.enabled, true)) {
            long limitMs = num(open.minutes, 1440) * 60_000L
            if (ageMs >= limitMs) {
                return [rule: "open", icon: "🕒", headline: "Task open too long"]
            }
        }

        return null
    }

    private static NotificationMessage message(Map breach, Map task, long ageMin) {
        def m = NotificationMessage.create()
            .title("🕒", "Task SLA")
            .status(breach.icon, breach.headline)
            .field("Task", task.name)
        // Human-readable business context (e.g. "Order: #1001"), in order.
        if (task.context instanceof Map) {
            ((Map) task.context).each { k, v -> m.field(k.toString(), v) }
        }
        m.field("Assignee", isBlank(task.assignee) ? "Unassigned" : task.assignee)
        m.field("Age", humanAge(ageMin))
        if (task.dueDateMs != null) {
            m.field("Due", new Date(num(task.dueDateMs)).toInstant().toString())
        }
        if (task.processKey) {
            m.field("Process", task.processKey)
        }
        return m
    }

    // --- Helpers ---------------------------------------------------------------

    private static long ageMinutes(Map task, long nowMs) {
        return Math.max(0L, (nowMs - num(task.createTimeMs)) / 60_000L as long)
    }

    private static String humanAge(long minutes) {
        if (minutes < 60) {
            return "${minutes} min"
        }
        long h = (minutes / 60) as long
        long mm = minutes % 60
        if (h < 24) {
            return mm > 0 ? "${h}h ${mm}m" : "${h}h"
        }
        long d = (h / 24) as long
        long hh = h % 24
        return hh > 0 ? "${d}d ${hh}h" : "${d}d"
    }

    private static boolean isBlank(v) {
        return v == null || v.toString().trim().isEmpty()
    }

    private static long num(v, long dflt = 0) {
        if (v == null) return dflt
        if (v instanceof Number) return ((Number) v).longValue()
        try { return Long.parseLong(v.toString().trim()) } catch (Exception e) { return dflt }
    }

    private static boolean truthy(v, boolean dflt) {
        if (v == null) return dflt
        return v.toString().trim().toLowerCase() == "true"
    }
}
