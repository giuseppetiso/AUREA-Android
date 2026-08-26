package it.creativemaker3d.aurea;

import org.json.JSONArray;
import org.json.JSONObject;

/** Trasformazione pura e verificabile del watchdog tablet Home Assistant. */
final class AureaWatchdogPatch {
    static final String AUTOMATION_ALIAS = "AUREA - Watchdog tablet";
    static final String TARGET_MARKER = "Soglia heartbeat: 75 minuti";

    private static final String[] LEGACY_TRIGGER_IDS = {
        "batteria_critica",
        "alimentazione_assente",
        "tablet_unavailable",
        "tablet_unknown",
        "batteria_unavailable"
    };

    private static final String[] TARGET_TRIGGER_IDS = {
        "aurea_batteria_critica",
        "aurea_alimentazione_assente",
        "aurea_heartbeat_scaduto",
        "aurea_sistema_offline",
        "aurea_core_unavailable",
        "aurea_core_unknown",
        "aurea_batteria_unavailable",
        "aurea_batteria_unknown",
        "fallback_tracker_unavailable",
        "fallback_tracker_unknown",
        "fallback_batteria_critica",
        "fallback_alimentazione_assente"
    };

    private AureaWatchdogPatch() {
    }

    static boolean isLegacy(JSONObject config) {
        if (!hasExpectedAlias(config)) return false;
        JSONArray triggers = array(config, "triggers", "trigger");
        JSONArray actions = array(config, "actions", "action");
        if (triggers == null || triggers.length() != LEGACY_TRIGGER_IDS.length) return false;
        if (actions == null || !actions.toString().contains("script.aurea_registra_anomalia")) {
            return false;
        }
        for (String id : LEGACY_TRIGGER_IDS) {
            if (!hasTrigger(triggers, id)) return false;
        }
        String serialized = config.toString();
        return serialized.contains("sensor.p90_battery_level")
            && serialized.contains("sensor.p90_battery_state")
            && serialized.contains("sensor.p90_charger_type")
            && serialized.contains("device_tracker.console")
            && !serialized.contains("sensor.aurea_tablet_heartbeat");
    }

    static boolean isTarget(JSONObject config) {
        if (!hasExpectedAlias(config)) return false;
        if (!config.optString("description", "").contains(TARGET_MARKER)) return false;
        JSONArray triggers = array(config, "triggers", "trigger");
        JSONArray actions = array(config, "actions", "action");
        if (triggers == null || triggers.length() != TARGET_TRIGGER_IDS.length) return false;
        if (actions == null || !actions.toString().contains("script.aurea_registra_anomalia")) {
            return false;
        }
        for (String id : TARGET_TRIGGER_IDS) {
            if (!hasTrigger(triggers, id)) return false;
        }
        String serialized = config.toString();
        return serialized.contains("sensor.aurea_tablet_system")
            && serialized.contains("sensor.aurea_tablet_heartbeat")
            && serialized.contains("sensor.aurea_tablet_battery")
            && serialized.contains("sensor.p90_battery_level")
            && serialized.contains("device_tracker.console");
    }

    static JSONObject apply(JSONObject current, JSONObject template) throws Exception {
        if (!isLegacy(current)) {
            throw new IllegalArgumentException("Configurazione watchdog corrente non riconosciuta");
        }
        if (!isTarget(template)) {
            throw new IllegalArgumentException("Modello watchdog AUREA non valido");
        }

        JSONObject result = new JSONObject(current.toString());
        result.put("alias", template.getString("alias"));
        result.put("description", template.getString("description"));
        replaceArray(result, current, template, "triggers", "trigger");
        replaceArray(result, current, template, "conditions", "condition");
        replaceArray(result, current, template, "actions", "action");
        result.put("mode", template.optString("mode", "queued"));
        result.put("max", template.optInt("max", 10));

        if (!isTarget(result)) {
            throw new IllegalStateException("Configurazione watchdog prodotta non verificabile");
        }
        return result;
    }

    private static void replaceArray(
            JSONObject result,
            JSONObject current,
            JSONObject template,
            String plural,
            String singular) throws Exception {
        JSONArray source = array(template, plural, singular);
        if (source == null) throw new IllegalArgumentException("Sezione assente: " + plural);
        String destination = current.optJSONArray(plural) != null ? plural : singular;
        result.remove(plural);
        result.remove(singular);
        result.put(destination, new JSONArray(source.toString()));
    }

    private static boolean hasExpectedAlias(JSONObject config) {
        return config != null
            && AUTOMATION_ALIAS.equals(config.optString("alias", "").trim());
    }

    private static boolean hasTrigger(JSONArray triggers, String id) {
        for (int index = 0; index < triggers.length(); index++) {
            JSONObject trigger = triggers.optJSONObject(index);
            if (trigger != null && id.equals(trigger.optString("id", "").trim())) return true;
        }
        return false;
    }

    private static JSONArray array(JSONObject config, String preferred, String fallback) {
        if (config == null) return null;
        JSONArray value = config.optJSONArray(preferred);
        return value == null ? config.optJSONArray(fallback) : value;
    }
}
