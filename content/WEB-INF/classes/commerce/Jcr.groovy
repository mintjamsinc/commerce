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
