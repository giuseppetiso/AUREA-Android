package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Applica soltanto correzioni AUREA riconoscibili, verificabili e ripristinabili. */
final class AureaHomeAssistantMaintenance {
    private static final String APP_PREFS = "aurea";
    private static final String KEY_HA_URL = "ha_url";
    private static final String KEY_HA_TOKEN = "ha_token";
    private static final String DEFAULT_HA_URL = "http://192.168.178.72:8123";

    private static final String MAINTENANCE_PREFS = "aurea_maintenance";
    private static final String KEY_PRESENCE_PATCH_VERSION = "presence_audit_patch_version";
    private static final String KEY_PRESENCE_BACKUP = "presence_audit_backup_v1";
    private static final int PRESENCE_PATCH_VERSION = 1;

    private static final String KEY_WATCHDOG_PATCH_VERSION = "tablet_watchdog_patch_version";
    private static final String KEY_WATCHDOG_BACKUP = "tablet_watchdog_backup_v2";
    private static final int WATCHDOG_PATCH_VERSION = 1;

    private static final String AUTOMATION_ID = "1787567247522";
    private static final String AUTOMATION_ALIAS = "AUREA - Audit presenza casa";
    private static final String NOISY_TRIGGER_ID = "tracker_giuseppe_discordanti";
    private static final String CONFIG_PATH =
        "/api/config/automation/config/" + AUTOMATION_ID;
    private static final String WATCHDOG_AUTOMATION_ID = "1787567496756";
    private static final String WATCHDOG_CONFIG_PATH =
        "/api/config/automation/config/" + WATCHDOG_AUTOMATION_ID;
    private static final String RELOAD_PATH = "/api/services/automation/reload";

    static final class Result {
        final boolean success;
        final boolean changed;
        final String message;

        Result(boolean success, boolean changed, String message) {
            this.success = success;
            this.changed = changed;
            this.message = cleanStatic(message);
        }
    }

    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }

        boolean successful() {
            return code >= 200 && code < 300;
        }
    }

    private final Context context;
    private final AureaDiagnosticsLog log;

    AureaHomeAssistantMaintenance(Context context) {
        this.context = context.getApplicationContext();
        this.log = new AureaDiagnosticsLog(this.context);
    }

    Result applyPresenceAuditPatch() {
        SharedPreferences maintenance = context.getSharedPreferences(
            MAINTENANCE_PREFS,
            Context.MODE_PRIVATE
        );
        if (maintenance.getInt(KEY_PRESENCE_PATCH_VERSION, 0)
                >= PRESENCE_PATCH_VERSION) {
            return new Result(true, false, "Correzione audit presenza già applicata");
        }

        SharedPreferences app = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
        String haUrl = trimSlash(app.getString(KEY_HA_URL, DEFAULT_HA_URL));
        String token = clean(app.getString(KEY_HA_TOKEN, ""));
        if (haUrl.isEmpty() || token.isEmpty()) {
            return failure("Home Assistant non configurato");
        }

        try {
            HttpResult current = request("GET", haUrl + CONFIG_PATH, token, null);
            if (!current.successful()) {
                return failure("Audit presenza non leggibile: HTTP " + current.code);
            }
            JSONObject config = new JSONObject(current.body);
            if (!AUTOMATION_ALIAS.equals(clean(config.optString("alias", "")))) {
                return failure("Audit presenza non riconosciuto: nessuna modifica eseguita");
            }

            boolean hasTrigger = containsTrigger(config);
            boolean hasChoice = containsChoice(config);
            if (!hasTrigger && !hasChoice) {
                reload(haUrl, token);
                markCompleted(maintenance);
                return new Result(true, false, "Audit presenza già privo dell'avviso rumoroso");
            }
            if (!hasTrigger || !hasChoice) {
                return failure("Audit presenza incoerente: nessuna modifica eseguita");
            }

            String backup = config.toString();
            if (!removeTrigger(config) || !removeChoice(config)) {
                return failure("Correzione audit presenza non verificabile");
            }

            maintenance.edit().putString(KEY_PRESENCE_BACKUP, backup).apply();
            HttpResult saved = request(
                "POST",
                haUrl + CONFIG_PATH,
                token,
                config.toString()
            );
            if (!saved.successful()) {
                return failure("Salvataggio audit presenza rifiutato: HTTP " + saved.code);
            }

            HttpResult verified = request("GET", haUrl + CONFIG_PATH, token, null);
            boolean valid = verified.successful();
            if (valid) {
                JSONObject result = new JSONObject(verified.body);
                valid = AUTOMATION_ALIAS.equals(clean(result.optString("alias", "")))
                    && !containsTrigger(result)
                    && !containsChoice(result);
            }
            if (!valid) {
                restore(haUrl, token, backup);
                return failure("Verifica audit presenza fallita; configurazione ripristinata");
            }

            reload(haUrl, token);
            markCompleted(maintenance);
            log.info(
                "Manutenzione Home Assistant",
                "Rimosso avviso tracker discordanti; controlli presenza critici conservati"
            );
            return new Result(true, true, "Audit presenza corretto e verificato");
        } catch (Exception error) {
            log.error(
                "Manutenzione Home Assistant",
                "Correzione audit presenza non riuscita",
                error
            );
            return failure(safeMessage(error));
        }
    }

    Result applyTabletWatchdogPatch() {
        SharedPreferences maintenance = context.getSharedPreferences(
            MAINTENANCE_PREFS,
            Context.MODE_PRIVATE
        );
        if (maintenance.getInt(KEY_WATCHDOG_PATCH_VERSION, 0)
                >= WATCHDOG_PATCH_VERSION) {
            return new Result(true, false, "Watchdog tablet AUREA già aggiornato");
        }

        SharedPreferences app = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
        String haUrl = trimSlash(app.getString(KEY_HA_URL, DEFAULT_HA_URL));
        String token = clean(app.getString(KEY_HA_TOKEN, ""));
        if (haUrl.isEmpty() || token.isEmpty()) {
            return failure("Home Assistant non configurato");
        }

        try {
            HttpResult current = request("GET", haUrl + WATCHDOG_CONFIG_PATH, token, null);
            if (!current.successful()) {
                return failure("Watchdog tablet non leggibile: HTTP " + current.code);
            }
            JSONObject config = new JSONObject(current.body);
            if (!AureaWatchdogPatch.AUTOMATION_ALIAS.equals(
                    clean(config.optString("alias", "")))) {
                return failure("Watchdog tablet non riconosciuto: nessuna modifica eseguita");
            }

            if (AureaWatchdogPatch.isTarget(config)) {
                markWatchdogCompleted(maintenance);
                return new Result(true, false, "Watchdog tablet AUREA già aggiornato");
            }
            if (!AureaWatchdogPatch.isLegacy(config)) {
                return failure("Watchdog tablet modificato o incoerente: nessuna modifica eseguita");
            }

            JSONObject template = readRawJson(R.raw.aurea_watchdog_tablet_v2);
            JSONObject updated = AureaWatchdogPatch.apply(config, template);
            if (!AureaWatchdogPatch.isTarget(updated)) {
                return failure("Nuovo watchdog tablet non verificabile");
            }

            String backup = config.toString();
            maintenance.edit().putString(KEY_WATCHDOG_BACKUP, backup).apply();
            HttpResult saved = request(
                "POST",
                haUrl + WATCHDOG_CONFIG_PATH,
                token,
                updated.toString()
            );
            if (!saved.successful()) {
                return failure("Salvataggio watchdog tablet rifiutato: HTTP " + saved.code);
            }

            HttpResult verified = request("GET", haUrl + WATCHDOG_CONFIG_PATH, token, null);
            boolean valid = verified.successful();
            if (valid) {
                valid = AureaWatchdogPatch.isTarget(new JSONObject(verified.body));
            }
            if (!valid) {
                boolean restored = restoreAt(
                    haUrl,
                    token,
                    WATCHDOG_CONFIG_PATH,
                    backup
                );
                return failure(restored
                    ? "Verifica watchdog fallita; configurazione precedente ripristinata"
                    : "Verifica watchdog fallita e ripristino non confermato");
            }

            try {
                reload(haUrl, token);
            } catch (Exception reloadError) {
                boolean restored = restoreAt(
                    haUrl,
                    token,
                    WATCHDOG_CONFIG_PATH,
                    backup
                );
                return failure(restored
                    ? "Ricaricamento watchdog fallito; configurazione precedente ripristinata"
                    : "Ricaricamento watchdog fallito e ripristino non confermato");
            }

            markWatchdogCompleted(maintenance);
            log.info(
                "Manutenzione Home Assistant",
                "Watchdog tablet aggiornato: AUREA primaria, P90 fallback, heartbeat 75 minuti"
            );
            return new Result(true, true, "Watchdog tablet aggiornato e verificato");
        } catch (Exception error) {
            log.error(
                "Manutenzione Home Assistant",
                "Aggiornamento watchdog tablet non riuscito",
                error
            );
            return failure(safeMessage(error));
        }
    }

    private boolean containsTrigger(JSONObject config) {
        JSONArray triggers = array(config, "triggers", "trigger");
        if (triggers == null) return false;
        for (int index = 0; index < triggers.length(); index++) {
            JSONObject trigger = triggers.optJSONObject(index);
            if (trigger != null
                    && NOISY_TRIGGER_ID.equals(clean(trigger.optString("id", "")))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsChoice(JSONObject config) {
        JSONArray actions = array(config, "actions", "action");
        if (actions == null) return false;
        for (int actionIndex = 0; actionIndex < actions.length(); actionIndex++) {
            JSONObject action = actions.optJSONObject(actionIndex);
            JSONArray choices = action == null ? null : action.optJSONArray("choose");
            if (choices == null) continue;
            for (int choiceIndex = 0; choiceIndex < choices.length(); choiceIndex++) {
                JSONObject choice = choices.optJSONObject(choiceIndex);
                if (choice != null && choice.toString().contains(NOISY_TRIGGER_ID)) return true;
            }
        }
        return false;
    }

    private boolean removeTrigger(JSONObject config) throws Exception {
        String key = config.optJSONArray("triggers") != null ? "triggers" : "trigger";
        JSONArray source = config.optJSONArray(key);
        if (source == null) return false;
        JSONArray filtered = new JSONArray();
        boolean removed = false;
        for (int index = 0; index < source.length(); index++) {
            JSONObject trigger = source.optJSONObject(index);
            if (trigger != null
                    && NOISY_TRIGGER_ID.equals(clean(trigger.optString("id", "")))) {
                removed = true;
                continue;
            }
            filtered.put(source.get(index));
        }
        if (removed) config.put(key, filtered);
        return removed;
    }

    private boolean removeChoice(JSONObject config) throws Exception {
        String key = config.optJSONArray("actions") != null ? "actions" : "action";
        JSONArray actions = config.optJSONArray(key);
        if (actions == null) return false;
        boolean removed = false;
        for (int actionIndex = 0; actionIndex < actions.length(); actionIndex++) {
            JSONObject action = actions.optJSONObject(actionIndex);
            JSONArray choices = action == null ? null : action.optJSONArray("choose");
            if (choices == null) continue;
            JSONArray filtered = new JSONArray();
            for (int choiceIndex = 0; choiceIndex < choices.length(); choiceIndex++) {
                JSONObject choice = choices.optJSONObject(choiceIndex);
                if (choice != null && choice.toString().contains(NOISY_TRIGGER_ID)) {
                    removed = true;
                    continue;
                }
                filtered.put(choices.get(choiceIndex));
            }
            if (removed) action.put("choose", filtered);
        }
        return removed;
    }

    private JSONArray array(JSONObject config, String preferred, String fallback) {
        JSONArray value = config.optJSONArray(preferred);
        return value == null ? config.optJSONArray(fallback) : value;
    }

    private void reload(String haUrl, String token) throws Exception {
        HttpResult reloaded = request("POST", haUrl + RELOAD_PATH, token, "{}");
        if (!reloaded.successful()) {
            throw new IllegalStateException(
                "Ricaricamento automazioni rifiutato: HTTP " + reloaded.code
            );
        }
    }

    private void restore(String haUrl, String token, String backup) {
        try {
            request("POST", haUrl + CONFIG_PATH, token, backup);
            request("POST", haUrl + RELOAD_PATH, token, "{}");
        } catch (Exception ignored) {
        }
    }

    private boolean restoreAt(
            String haUrl,
            String token,
            String configPath,
            String backup) {
        try {
            HttpResult restored = request("POST", haUrl + configPath, token, backup);
            if (!restored.successful()) return false;
            HttpResult reloaded = request("POST", haUrl + RELOAD_PATH, token, "{}");
            return reloaded.successful();
        } catch (Exception ignored) {
            return false;
        }
    }

    private JSONObject readRawJson(int resourceId) throws Exception {
        return new JSONObject(readAll(context.getResources().openRawResource(resourceId)));
    }

    private void markCompleted(SharedPreferences maintenance) {
        maintenance.edit()
            .putInt(KEY_PRESENCE_PATCH_VERSION, PRESENCE_PATCH_VERSION)
            .apply();
    }

    private void markWatchdogCompleted(SharedPreferences maintenance) {
        maintenance.edit()
            .putInt(KEY_WATCHDOG_PATCH_VERSION, WATCHDOG_PATCH_VERSION)
            .apply();
    }

    private HttpResult request(
            String method,
            String address,
            String token,
            String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(12000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(data.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(data);
            }
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
            ? connection.getInputStream()
            : connection.getErrorStream();
        String response = readAll(stream);
        connection.disconnect();
        return new HttpResult(code, response);
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private Result failure(String message) {
        return new Result(false, false, message);
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? "" : clean(error.getMessage());
        if (message.isEmpty()) {
            return error == null ? "errore sconosciuto" : error.getClass().getSimpleName();
        }
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private String trimSlash(String value) {
        String result = clean(value);
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String clean(String value) {
        return cleanStatic(value);
    }

    private static String cleanStatic(String value) {
        return value == null ? "" : value.trim();
    }
}
