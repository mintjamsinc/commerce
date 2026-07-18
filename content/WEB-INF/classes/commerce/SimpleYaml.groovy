package commerce

/**
 * Minimal YAML reader for the commerce config files that are deliberately a
 * controlled two-level structure: top-level scalars and one level of nested
 * scalars (e.g. planning.yml defaults, health.yml rule groups). It is NOT a
 * general YAML parser — it is the server-side counterpart of the Webtop Commerce
 * app's parseSimpleYaml, kept dependency-free so classes under WEB-INF/classes
 * (which cannot use the script `YAML` binding) can read those files without a
 * YAML library. For files nested deeper than two levels (e.g. notifications.yml
 * with its per-category channel sets) use api.util.YAML.parse instead — the full
 * snakeyaml-engine parser that also backs the script binding.
 *
 * Coercion mirrors the editor: true/false → Boolean, integers → Long, decimals →
 * Double, quoted strings are unquoted, everything else is a String. Comments
 * (`#`) and blank lines are ignored. A value containing ':' (e.g. a URL) is kept
 * intact because the key is matched on a restricted charset and the value is the
 * rest of the line.
 *
 * Lives under /content/WEB-INF/classes; use via {@code import commerce.SimpleYaml}.
 */
class SimpleYaml {

    /** Parse YAML text into a nested Map. Returns an empty map for null/blank. */
    static Map parse(String text) {
        def root = [:]
        Map parent = null
        if (text == null) {
            return root
        }
        for (String rawLine : text.split(/\r?\n/)) {
            def trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue
            }
            def m = (trimmed =~ /^([A-Za-z0-9_.\-]+)\s*:\s*(.*)$/)
            if (!m.find()) {
                continue
            }
            def key = m.group(1)
            def val = m.group(2)
            // Strip a trailing inline comment for unquoted values.
            int indent = rawLine.length() - rawLine.replaceAll(/^\s+/, "").length()
            if (indent == 0) {
                if (val.isEmpty()) {
                    def child = [:]
                    root[key] = child
                    parent = child
                } else {
                    root[key] = coerce(val)
                    parent = null
                }
            } else if (parent != null) {
                parent[key] = coerce(val)
            }
        }
        return root
    }

    private static Object coerce(String raw) {
        def v = (raw ?: "").trim()
        if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.length() >= 2 ? v.substring(1, v.length() - 1) : ""
        }
        if (v == "true") return Boolean.TRUE
        if (v == "false") return Boolean.FALSE
        if (v ==~ /-?\d+/) {
            try { return Long.valueOf(v) } catch (Exception ignore) {}
        }
        if (v ==~ /-?\d+\.\d+/) {
            try { return Double.valueOf(v) } catch (Exception ignore) {}
        }
        return v
    }
}
