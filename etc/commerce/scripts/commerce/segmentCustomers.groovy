// Customer segmentation + behaviour-change alerts (category D, #13 / #15).
//
// Invoked by the commerce-crm-segment timer (as the service user) and on demand from
// the CRM endpoint. Rolls up each customer's purchase history from the stored orders,
// classifies them into a segment, persists the CRM record, and notifies operators
// when customers cross an important behaviour boundary — newly VIP, newly at-risk,
// newly dormant (#15).
//
// Best-effort throughout: a failure is logged, never thrown. Settings:
// /etc/commerce/config/crm.yml.

import commerce.Customers
import commerce.Notifications
import commerce.NotificationMessage

// Cluster guard: the timer fires on every node of a cluster, so only the
// node that wins this lease runs the task; the others skip this tick.
// Manual triggers are asynchronous fire-and-forget, so skipping while a
// run is already in flight on another node is correct for them as well.
// In a standalone deployment the lease is always granted immediately.
def __clusterLease = cluster.tryLock("commerce-crm-segment", 3600000)
if (__clusterLease == null) {
    log.info("segmentCustomers: another cluster node is running this task - skipping")
    return
}
try {
    try {
        def cfg = readYaml("/etc/commerce/config/crm.yml")
        if (cfg == null || cfg.enabled?.toString()?.toLowerCase() == "false") {
            return
        }
        def segCfg = (cfg.segments instanceof Map) ? cfg.segments : [:]
        boolean alertEnabled = !(cfg.alert?.enabled?.toString()?.toLowerCase() == "false")
        long now = System.currentTimeMillis()

        def agg = Customers.aggregate(repositorySession)
        if (agg.isEmpty()) {
            return
        }

        def newlyVip = []
        def newlyDormant = []
        def newlyAtRisk = []
        int updated = 0

        agg.values().each { stats ->
            try {
                def cls = Customers.segment(stats, segCfg, now)
                def prev = Customers.write(repositorySession, log, stats, cls)
                updated++

                // Transition detection for alerting.
                if (cls.vip && prev.vip != true) {
                    newlyVip << label(stats)
                }
                def prevRecency = prev.recency?.toString()
                if (cls.recency == "dormant" && prevRecency != "dormant") {
                    newlyDormant << label(stats)
                } else if (cls.recency == "at_risk" && prevRecency != "at_risk" && prevRecency != "dormant") {
                    newlyAtRisk << label(stats)
                }
            } catch (Exception e) {
                log.warn("segmentCustomers: customer ${stats?.key} failed: ${e.message}")
            }
        }

        log.info("segmentCustomers: classified ${updated} customer(s); newly vip=${newlyVip.size()} at_risk=${newlyAtRisk.size()} dormant=${newlyDormant.size()}")

        // --- Alert operators on behaviour changes (#15) --------------------------
        if (alertEnabled && (!newlyVip.isEmpty() || !newlyDormant.isEmpty() || !newlyAtRisk.isEmpty())) {
            notifyTransitions(newlyVip, newlyAtRisk, newlyDormant)
        }
    } catch (Exception e) {
        try { log.warn("segmentCustomers: ${e.message}") } catch (Exception ignore) {}
    }
} finally {
    __clusterLease.close()
}


// --- Helpers -----------------------------------------------------------------

String label(stats) {
    def who = stats.name ?: stats.email ?: stats.customerId ?: stats.key
    def spent = stats.totalSpent?.toString()
    def cur = stats.currency ?: ""
    return "${who} (${stats.orders} orders, ${spent} ${cur})".trim()
}

void notifyTransitions(List vip, List atRisk, List dormant) {
    try {
        def configNode = repositorySession.getResource("/etc/commerce/config/notifications.yml")
        if (configNode == null || !configNode.exists()) return
        def config = YAML.parse(configNode)

        def message = NotificationMessage.create()
            .title("👥", "Customer CRM")
            .status("📈", "Customer behaviour changes")
            .field("Newly VIP", vip.size())
            .field("Newly at-risk", atRisk.size())
            .field("Newly dormant", dormant.size())
        if (!vip.isEmpty()) message.bullets("VIP", vip.take(10))
        if (!dormant.isEmpty()) message.bullets("Dormant", dormant.take(10))
        if (!atRisk.isEmpty()) message.bullets("At risk", atRisk.take(10))

        Notifications.dispatch(log, "segmentCustomers", config, message)
    } catch (Exception e) {
        log.warn("segmentCustomers: notification failed: ${e.message}")
    }
}

def readYaml(String path) {
    try {
        def res = repositorySession.getResource(path)
        if (res != null && res.exists()) return YAML.parse(res)
    } catch (Exception e) {
        log.warn("segmentCustomers: could not read ${path}: ${e.message}")
    }
    return null
}
