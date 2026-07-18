package commerce

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Small JCR helpers shared by the commerce operational features (health monitor,
 * task SLA, alerting) for reading and writing JSON documents under /content.
 *
 * Kept dependency-light (jackson only, like the rest of the commerce classes) so
 * classes under WEB-INF/classes can persist small state files without repeating
 * the mkdir-p / parse / serialize boilerplate.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.Jcr}.
 */
class Jcr {

    private static final ObjectMapper MAPPER = new ObjectMapper()

    private static final int COMMIT_RETRIES = 6

    /** Resolve a resource, returning null on any error (path missing / no access). */
    static safeGet(session, String path) {
        try {
            return session.getResource(path)
        } catch (Exception e) {
            return null
        }
    }

    /**
     * Resolve a file resource, creating it and any missing parent folders from the
     * repository root (mkdir -p style). The file is not written until the caller
     * calls write() on it.
     */
    static getOrCreateFile(session, String path) {
        def existing = safeGet(session, path)
        if (existing != null && existing.exists()) {
            return existing
        }
        def parts = path.split("/").findAll { it }
        def cur = session.getResource("/")
        for (int i = 0; i < parts.size() - 1; i++) {
            cur = cur.getOrCreateFolder(parts[i])
        }
        return cur.getOrCreateFile(parts[parts.size() - 1])
    }

    /**
     * Write a small JSON document to a file (creating it and any missing parents)
     * and commit, retrying the transient races a hot marker path sees under
     * concurrent writers: a lost same-path create race (another session created
     * the node between the existence check and the create) and a row-lock wait
     * against a concurrent removal of the same node. Rolls back and backs off
     * between attempts so the session stays usable; the last failure is rethrown
     * for the caller to log.
     */
    static void commitJson(session, String path, Map doc) {
        Exception last = null
        for (int attempt = 0; attempt < COMMIT_RETRIES; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(50L << (attempt - 1)) } catch (Exception ignore) {}
            }
            try {
                def res = getOrCreateFile(session, path)
                res.write(toJson(doc))
                session.commit()
                return
            } catch (Exception e) {
                last = e
                try { session.rollback() } catch (Exception ignore) {}
            }
        }
        throw last
    }

    /** Read and parse a JSON document into a Map, or an empty map if absent/blank/invalid. */
    static Map readMap(session, String path) {
        def res = safeGet(session, path)
        if (res == null || !res.exists()) {
            return [:]
        }
        try {
            def content = res.content?.toString()
            if (content == null || content.trim().isEmpty()) {
                return [:]
            }
            return MAPPER.readValue(content, Map.class)
        } catch (Exception e) {
            return [:]
        }
    }

    /** Serialize a value to a JSON string. */
    static String toJson(value) {
        return MAPPER.writeValueAsString(value)
    }

    /** Parse a JSON string to a Map (empty map for null/blank/invalid). */
    static Map parseMap(String json) {
        if (json == null || json.trim().isEmpty()) {
            return [:]
        }
        try {
            return MAPPER.readValue(json, Map.class)
        } catch (Exception e) {
            return [:]
        }
    }
}
