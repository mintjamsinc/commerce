package commerce

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Helpers for the inventory alert workflow: reading the per-variant alert
 * thresholds an operator configured on a product resource.
 *
 * Thresholds are stored as JSON on the product resource's `inventory_level_config`
 * property (written by the threshold form): { "variants": [ { "id": ...,
 * "inventory_alert_threshold": N }, ... ] }. Parsing is shared here; callers keep
 * their own error policy (checkInventoryLevel lets a malformed config propagate;
 * checkThresholdConfig / notifyTaskCreated wrap the call and treat it leniently).
 *
 * Lives under /content/WEB-INF/classes; use via `import commerce.Inventory`.
 */
class Inventory {

    /**
     * Map of variant id (String) -> configured alert threshold (int) read from the
     * product resource's `inventory_level_config`. Returns an empty map when the
     * property is absent. Only variants that carry both an id and a numeric
     * threshold are included. Throws if the property value is not valid JSON.
     */
    static Map thresholdsByVariantId(resource) {
        def result = [:]
        if (resource == null || !resource.hasProperty("inventory_level_config")) {
            return result
        }
        def raw = resource.getProperty("inventory_level_config").getValue()
        def config = new ObjectMapper().readValue(raw.toString(), Object.class)
        config?.variants?.each { tv ->
            if (tv.id != null && tv.inventory_alert_threshold != null) {
                result[tv.id.toString()] = tv.inventory_alert_threshold as int
            }
        }
        return result
    }

    /**
     * True when the product has at least one variant with a usable alert
     * threshold. Throws if the property value is not valid JSON (callers that want
     * to treat malformed config as "not configured" should catch).
     */
    static boolean hasThresholdConfig(resource) {
        return !thresholdsByVariantId(resource).isEmpty()
    }
}
