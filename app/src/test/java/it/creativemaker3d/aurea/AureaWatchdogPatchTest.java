package it.creativemaker3d.aurea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AureaWatchdogPatchTest {
    @Test
    public void replacesOnlyRecognizedLegacyWatchdog() throws Exception {
        JSONObject legacy = legacyConfig();
        JSONObject template = loadTemplate();

        JSONObject updated = AureaWatchdogPatch.apply(legacy, template);

        assertTrue(AureaWatchdogPatch.isTarget(updated));
        assertFalse(AureaWatchdogPatch.isLegacy(updated));
        assertEquals("1787567496756", updated.getString("id"));
        assertEquals("queued", updated.getString("mode"));
        assertEquals(10, updated.getInt("max"));
        assertTrue(AureaWatchdogPatch.isLegacy(legacy));
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesUnknownOrManuallyChangedConfiguration() throws Exception {
        JSONObject changed = legacyConfig();
        changed.getJSONArray("triggers").getJSONObject(0).put("id", "personalizzato");

        AureaWatchdogPatch.apply(changed, loadTemplate());
    }

    @Test
    public void officialTemplateContainsPrimaryAndFallbackSources() throws Exception {
        JSONObject template = loadTemplate();

        assertTrue(AureaWatchdogPatch.isTarget(template));
        String serialized = template.toString();
        assertTrue(serialized.contains("sensor.aurea_tablet_system"));
        assertTrue(serialized.contains("sensor.aurea_tablet_heartbeat"));
        assertTrue(serialized.contains("sensor.aurea_tablet_battery"));
        assertTrue(serialized.contains("sensor.p90_battery_level"));
        assertTrue(serialized.contains("device_tracker.console"));
    }

    private JSONObject legacyConfig() throws Exception {
        JSONObject result = new JSONObject();
        result.put("id", "1787567496756");
        result.put("alias", AureaWatchdogPatch.AUTOMATION_ALIAS);
        result.put("description", "Vecchio watchdog P90");

        JSONArray triggers = new JSONArray();
        triggers.put(trigger("batteria_critica", "sensor.p90_battery_level"));
        JSONObject power = trigger("alimentazione_assente", "sensor.p90_charger_type");
        power.put("value_template", "sensor.p90_battery_state sensor.p90_battery_level");
        triggers.put(power);
        triggers.put(trigger("tablet_unavailable", "device_tracker.console"));
        triggers.put(trigger("tablet_unknown", "device_tracker.console"));
        triggers.put(trigger("batteria_unavailable", "sensor.p90_battery_level"));
        result.put("triggers", triggers);
        result.put("conditions", new JSONArray());

        JSONObject action = new JSONObject();
        action.put("action", "script.aurea_registra_anomalia");
        result.put("actions", new JSONArray().put(action));
        result.put("mode", "queued");
        result.put("max", 10);
        return result;
    }

    private JSONObject trigger(String id, String entityId) throws Exception {
        JSONObject result = new JSONObject();
        result.put("trigger", "state");
        result.put("id", id);
        result.put("entity_id", entityId);
        return result;
    }

    private JSONObject loadTemplate() throws Exception {
        Path path = Path.of("app/src/main/res/raw/aurea_watchdog_tablet_v2.json");
        if (!Files.exists(path)) {
            path = Path.of("src/main/res/raw/aurea_watchdog_tablet_v2.json");
        }
        return new JSONObject(Files.readString(path, StandardCharsets.UTF_8));
    }
}
