package commerce

/**
 * Task-lock helper for the guarded commerce scripts (see docs/clustering.md).
 *
 * A guarded task acquires a session-scoped JCR lock on its lock resource
 * under /var/locks. The lock excludes overlapping executions on one node and
 * across cluster nodes alike (lock state lives in the workspace database),
 * is released automatically when the script's session ends, and the timeout
 * bounds how long a crashed owner can keep it.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.Locks}.
 */
class Locks {

    private static final String ROOT = "/var/locks"

    /**
     * Acquire the named task lock: returns the locked resource, or null when
     * another execution (this node or any other) already holds it. The lock
     * resource /var/locks/<name> is created on first use; losing a concurrent
     * create race is harmless because the winner's folder is used.
     */
    static tryLock(session, String name, long ttlSeconds) {
        String path = ROOT + "/" + name
        def resource = Jcr.safeGet(session, path)
        if (resource == null || !resource.exists()) {
            try {
                session.getResource("/")
                        .getOrCreateFolder("var")
                        .getOrCreateFolder("locks")
                        .getOrCreateFolder(name)
                session.commit()
            } catch (Exception e) {
                try { session.rollback() } catch (Exception ignore) {}
            }
            resource = session.getResource(path)
        }
        return resource.tryLock(false, true, ttlSeconds)
    }

    /**
     * Release a lock returned by tryLock. Null-safe and never throws: the
     * session close releases a session-scoped lock anyway, so a failed early
     * release must not turn a completed task into a failure.
     */
    static void unlock(lock) {
        if (lock == null) {
            return
        }
        try {
            lock.unlock()
        } catch (Exception ignore) {}
    }
}
