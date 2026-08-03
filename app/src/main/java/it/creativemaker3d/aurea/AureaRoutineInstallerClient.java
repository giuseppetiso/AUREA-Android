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
import java.util.Locale;

/**
 * Installa una bozza Routine Studio tramite l'API usata dall'editor Home Assistant.
 *
 * Il client non chiama servizi, non cambia stati di entità e non sovrascrive
 * automazioni già esistenti. Ogni automazione viene creata disattivata.
 */
final class AureaRoutineInstallerClient {
    private static final String PREFS = "aurea";
    private static final String KEY_HA_URL = "ha_url";
    private static final String KEY_HA_TOKEN = "ha_token";
    private static final String DEFAULT_HA_URL = "http://192.168.178.72:8123";

    static final class Result {
        final boolean success;
        final boolean alreadyExists;
        final String automationId;
        final String message;

        Result(
                boolean success,
                boolean alreadyExists,
                String automationId,
                String message) {
            this.success = success;
            this.alreadyExists = alreadyExists;
            this.automationId = cleanStatic(automationId);
            this.message = cleanStatic(message);
        }
    }

    private static final class ActionSpec {
        final String action;
        final String dataKey;
        final String dataValue;

        ActionSpec(String action, String dataKey, String dataValue) {
            this.action = action;
            this.dataKey = dataKey;
            this.dataValue = dataValue;
        }
    }

    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    private final Context context;
    private final AureaDiagnosticsLog diagnosticsLog;

    AureaRoutineInstallerClient(Context context) {
        this.context = context.getApplicationContext();
        diagnosticsLog = new AureaDiagnosticsLog(this.context);
    }

    JSONObject previewDisabled(AureaRoutineDraftStore.Draft draft) {
        return buildConfig(draft);
    }

    String automationId(AureaRoutineDraftStore.Draft draft) {
        if (draft == null) {
            return "";
        }
        String source = clean(draft.id).toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_]", "_");
        if (source.isEmpty()) {
            source = Integer.toHexString(
                (draft.entityId + "|" + draft.time + "|" + draft.alias).hashCode()
            );
        }
        return "aurea_" + source;
    }

    Result installDisabled(AureaRoutineDraftStore.Draft draft) {
        if (draft == null) {
            return failure("", "Bozza non disponibile");
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String haUrl = trimSlash(prefs.getString(KEY_HA_URL, DEFAULT_HA_URL));
        String token = clean(prefs.getString(KEY_HA_TOKEN, ""));
        String automationId = automationId(draft);
        if (haUrl.isEmpty() || token.isEmpty()) {
            return failure(
                automationId,
                "URL o token Home Assistant non configurati sul tablet"
            );
        }

        JSONObject config;
        try {
            config = buildConfig(draft);
        } catch (Exception error) {
            diagnosticsLog.error(
                "Routine Installer",
                "Configurazione automazione non valida",
                error
            );
            return failure(automationId, safeMessage(error));
        }

        String path = "/api/config/automation/config/" + automationId;
        try {
            Response existing = request("GET", haUrl + path, token, null);
            if (existing.code >= 200 && existing.code < 300) {
                diagnosticsLog.warning(
                    "Routine Installer",
                    "Installazione evitata: automazione già presente " + automationId
                );
                return new Result(
                    false,
                    true,
                    automationId,
                    "Esiste già un'automazione AUREA con questo ID. Nessun dato è stato sovrascritto."
                );
            }
            if (existing.code != 404) {
                return httpFailure(automationId, "Controllo automazione esistente", existing);
            }

            Response created = request("POST", haUrl + path, token, config.toString());
            if (created.code < 200 || created.code >= 300) {
                return httpFailure(automationId, "Installazione Home Assistant", created);
            }

            Response verified = request("GET", haUrl + path, token, null);
            if (verified.code < 200 || verified.code >= 300) {
                return httpFailure(automationId, "Verifica dopo installazione", verified);
            }
            if (!verifyResponse(verified.body, draft)) {
                diagnosticsLog.error(
                    "Routine Installer",
                    "Verifica incoerente per " + automationId,
                    null
                );
                return failure(
                    automationId,
                    "Home Assistant ha salvato una configurazione non verificabile. Controllala manualmente."
                );
            }

            diagnosticsLog.info(
                "Routine Installer",
                "Automazione disattivata installata: " + automationId
            );
            return new Result(
                true,
                false,
                automationId,
                "Automazione installata e verificata. È disattivata in Home Assistant."
            );
        } catch (Exception error) {
            diagnosticsLog.error(
                "Routine Installer",
                "Connessione o installazione non riuscita",
                error
            );
            return failure(automationId, safeMessage(error));
        }
    }

    private JSONObject buildConfig(AureaRoutineDraftStore.Draft draft) {
        if (draft == null) {
            throw new IllegalArgumentException("Bozza assente");
        }
        ActionSpec actionSpec = actionSpec(draft.entityId, draft.targetState);
        if (actionSpec == null) {
            throw new IllegalArgumentException("Azione non supportata per questa entità");
        }

        try {
            JSONObject config = new JSONObject();
            config.put("alias", draft.alias);
            config.put(
                "description",
                "Installata disattivata da AUREA Routine Installer per "
                    + safeActor(draft.actor)
                    + ". Routine Guard superato e doppia conferma amministrativa."
            );
            config.put("initial_state", false);

            JSONObject timeTrigger = new JSONObject();
            timeTrigger.put("trigger", "time");
            timeTrigger.put("at", draft.time + ":00");
            if (!draft.weekdays.isEmpty()) {
                JSONArray weekdays = new JSONArray();
                for (String day : draft.weekdays) {
                    weekdays.put(day);
                }
                timeTrigger.put("weekday", weekdays);
            }
            JSONArray triggers = new JSONArray();
            triggers.put(timeTrigger);
            config.put("triggers", triggers);
            config.put("conditions", new JSONArray());

            JSONObject action = new JSONObject();
            action.put("action", actionSpec.action);
            JSONObject target = new JSONObject();
            target.put("entity_id", draft.entityId);
            action.put("target", target);
            if (!actionSpec.dataKey.isEmpty()) {
                JSONObject data = new JSONObject();
                data.put(actionSpec.dataKey, actionSpec.dataValue);
                action.put("data", data);
            }
            JSONArray actions = new JSONArray();
            actions.put(action);
            config.put("actions", actions);
            config.put("mode", "single");
            return config;
        } catch (Exception error) {
            throw new IllegalArgumentException("Impossibile creare la configurazione", error);
        }
    }

    private ActionSpec actionSpec(String entityId, String targetState) {
        String domain = domainOf(entityId);
        String state = clean(targetState).toLowerCase(Locale.ROOT);

        if (domain.equals("light")
                || domain.equals("switch")
                || domain.equals("fan")
                || domain.equals("input_boolean")) {
            if (state.equals("on")) {
                return new ActionSpec(domain + ".turn_on", "", "");
            }
            if (state.equals("off")) {
                return new ActionSpec(domain + ".turn_off", "", "");
            }
            return null;
        }

        if (domain.equals("media_player")) {
            if (state.equals("on")) {
                return new ActionSpec("media_player.turn_on", "", "");
            }
            if (state.equals("off")) {
                return new ActionSpec("media_player.turn_off", "", "");
            }
            if (state.equals("playing")) {
                return new ActionSpec("media_player.media_play", "", "");
            }
            if (state.equals("paused")) {
                return new ActionSpec("media_player.media_pause", "", "");
            }
            return null;
        }

        if (domain.equals("climate")) {
            String mode = normalizeClimateMode(state);
            if (mode.equals("heat")
                    || mode.equals("cool")
                    || mode.equals("auto")
                    || mode.equals("off")) {
                return new ActionSpec(
                    "climate.set_hvac_mode",
                    "hvac_mode",
                    mode
                );
            }
        }
        return null;
    }

    private boolean verifyResponse(
            String body,
            AureaRoutineDraftStore.Draft draft) {
        try {
            JSONObject saved = new JSONObject(body == null ? "{}" : body);
            String alias = clean(saved.optString("alias", ""));
            return alias.equals(draft.alias)
                && saved.toString().contains(draft.entityId)
                && !saved.optBoolean("initial_state", true);
        } catch (Exception ignored) {
            return false;
        }
    }

    private Response request(
            String method,
            String endpoint,
            String token,
            String body) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "AUREA-Routine-Installer/1.0");

            if (body != null) {
                connection.setDoOutput(true);
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(data);
                }
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
            return new Response(code, readAll(stream));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Result httpFailure(String automationId, String phase, Response response) {
        String detail = responseMessage(response.body);
        String message = phase + " non riuscita: HTTP " + response.code;
        if (!detail.isEmpty()) {
            message += " · " + detail;
        }
        diagnosticsLog.error("Routine Installer", message, null);
        return failure(automationId, message);
    }

    private String responseMessage(String body) {
        String value = clean(body);
        if (value.isEmpty()) {
            return "";
        }
        try {
            JSONObject json = new JSONObject(value);
            String message = clean(json.optString("message", ""));
            if (!message.isEmpty()) {
                return limit(message, 220);
            }
        } catch (Exception ignored) {
        }
        return limit(value.replace('\n', ' ').replace('\r', ' '), 220);
    }

    private Result failure(String automationId, String message) {
        return new Result(false, false, automationId, message);
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line);
            }
        }
        return text.toString();
    }

    private String normalizeClimateMode(String value) {
        String state = clean(value).toLowerCase(Locale.ROOT);
        if (state.equals("heating")) {
            return "heat";
        }
        if (state.equals("cooling")) {
            return "cool";
        }
        return state;
    }

    private String domainOf(String entityId) {
        String value = clean(entityId);
        int separator = value.indexOf('.');
        return separator <= 0 ? "" : value.substring(0, separator);
    }

    private String safeActor(String actor) {
        String value = clean(actor);
        return value.isEmpty() ? "Giuseppe" : value;
    }

    private String safeMessage(Throwable error) {
        if (error == null) {
            return "Errore non specificato";
        }
        String message = clean(error.getMessage());
        return message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private String trimSlash(String value) {
        String result = clean(value);
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String limit(String value, int maximum) {
        String clean = clean(value);
        return clean.length() <= maximum ? clean : clean.substring(0, maximum);
    }

    private String clean(String value) {
        return cleanStatic(value);
    }

    private static String cleanStatic(String value) {
        return value == null ? "" : value.trim();
    }
}
