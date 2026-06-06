package commerce

import java.time.Instant

/**
 * Shared alert dispatch with per-key cooldown, used by the operational monitors
 * (health monitor, task SLA). Centralises the "don't alert about the same thing
 * again within N minutes" debounce and the delivery through the pluggable
 * {@link Notifications} channels, so every monitor behaves consistently.
 *
 * Cooldown state is a small JSON document chosen by the caller (so each monitor
 * keeps its own), shaped as { "alerts": { "<key>": "<iso timestamp>" } }. The
 * cooldown is armed BEFORE the notification is sent, so a delivery failure can
 * never cause an alert storm.
 *
 * Defensive: a failure here is logged, never thrown — alerting must not break the
 * process it guards.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.Alerts}.
 */
class Alerts {

    static final String NOTIFICATIONS_PATH = "/etc/commerce/config/notifications.yml"

    /**
     * Fire an alert for {@code key} unless it fired within {@code cooldownMs}.
     * Returns true when the alert was sent (cooldown armed), false when suppressed.
     *
     * @param statePath JCR path of the caller's cooldown state document
     */
    static boolean fire(session, log, String statePath, String key, long cooldownMs, NotificationMessage message) {
        try {
            Map state = Jcr.readMap(session, statePath)
            Map alerts = (Map) state.computeIfAbsent("alerts", { [:] })
            long now = System.currentTimeMillis()
            def last = alerts[key]
            if (last != null) {
                try {
                    long lastMs = Instant.parse(last.toString()).toEpochMilli()
                    if (now - lastMs < cooldownMs) {
                        return false
                    }
                } catch (Exception ignore) { /* malformed → alert anyway */ }
            }
            // Arm the cooldown first so a notification failure cannot cause a storm.
            alerts[key] = Instant.ofEpochMilli(now).toString()
            save(session, statePath, state)

            def notif = loadNotifications(session)
            if (notif != null) {
                Notifications.dispatch(log, "alerts", notif, message)
            }
            return true
        } catch (Exception e) {
            warn(log, "fire(${key}) failed: ${e.message}")
            return false
        }
    }

    /**
     * Drop cooldown entries whose key no longer matters, keeping only those for
     * which {@code keep(key)} returns true. Used to stop the state file growing
     * unbounded (e.g. entries for tasks that have since completed).
     */
    static void pruneState(session, log, String statePath, Closure keep) {
        try {
            Map state = Jcr.readMap(session, statePath)
            def alerts = state.alerts
            if (!(alerts instanceof Map) || alerts.isEmpty()) {
                return
            }
            def removed = []
            alerts.keySet().each { k -> if (!keep.call(k)) { removed << k } }
            if (removed.isEmpty()) {
                return
            }
            removed.each { alerts.remove(it) }
            save(session, statePath, state)
        } catch (Exception e) {
            warn(log, "pruneState failed: ${e.message}")
        }
    }

    private static void save(session, String statePath, Map state) {
        try {
            def res = Jcr.getOrCreateFile(session, statePath)
            res.write(Jcr.toJson(state))
            session.commit()
        } catch (Exception e) {
            try { session.rollback() } catch (Exception ignore) {}
        }
    }

    private static Map loadNotifications(session) {
        def res = Jcr.safeGet(session, NOTIFICATIONS_PATH)
        if (res == null || !res.exists()) {
            return null
        }
        return SimpleYaml.parse(res.content?.toString())
    }

    private static void warn(log, String msg) {
        try { log?.warn("alerts: ${msg}") } catch (Exception ignore) {}
    }
}
