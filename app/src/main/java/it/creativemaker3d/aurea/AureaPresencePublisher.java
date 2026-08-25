package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pubblica soltanto l'esito locale del rilevamento, mai immagini o firme. */
final class AureaPresencePublisher {
    static final String ENTITY_ID = "binary_sensor.aurea_tablet_person_in_front";

    private static final String APP_PREFS = "aurea";
    private static final String KEY_HA_URL = "ha_url";
    private static final String KEY_HA_TOKEN = "ha_token";
    private static final String DEFAULT_HA_URL = "http://192.168.178.72:8123";
    private static final String PRESENCE_PREFS = "aurea_presence";
    private static final String KEY_LAST_PUBLISH = "last_publish";
    private static final String KEY_LAST_STATE = "last_state";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final long REFRESH_MS = 5L * 60L * 1000L;

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AureaDiagnosticsLog log;
    private String queuedFingerprint = "";
    private long queuedAt;

    AureaPresencePublisher(Context context) {
        this.context = context.getApplicationContext();
        this.log = new AureaDiagnosticsLog(this.context);
    }

    synchronized void publish(
            boolean detected,
            boolean cameraActive,
            boolean thermalPaused,
            long lastSeenAt) {
        long now = System.currentTimeMillis();
        String fingerprint = detected + "|" + cameraActive + "|" + thermalPaused;
        if (fingerprint.equals(queuedFingerprint) && now - queuedAt < REFRESH_MS) return;
        queuedFingerprint = fingerprint;
        queuedAt = now;

        io.execute(() -> publishNow(
            detected,
            cameraActive,
            thermalPaused,
            lastSeenAt,
            now
        ));
    }

    private void publishNow(
            boolean detected,
            boolean cameraActive,
            boolean thermalPaused,
            long lastSeenAt,
            long now) {
        SharedPreferences app = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
        String haUrl = trimSlash(app.getString(KEY_HA_URL, DEFAULT_HA_URL));
        String token = clean(app.getString(KEY_HA_TOKEN, ""));
        if (haUrl.isEmpty() || token.isEmpty()) return;

        try {
            JSONObject attributes = new JSONObject();
            attributes.put("friendly_name", "AUREA Persona davanti al tablet");
            attributes.put("icon", detected ? "mdi:account-eye" : "mdi:account-off-outline");
            attributes.put("device_class", "occupancy");
            attributes.put("camera_active", cameraActive);
            attributes.put("thermal_guard", thermalPaused);
            attributes.put("local_processing", true);
            attributes.put("images_saved", false);
            attributes.put("active_profile", profile());
            attributes.put("last_seen", lastSeenAt > 0L ? iso(lastSeenAt) : "mai");
            attributes.put("last_update", iso(now));

            JSONObject payload = new JSONObject();
            payload.put("state", detected ? "on" : "off");
            payload.put("attributes", attributes);
            int code = post(haUrl + "/api/states/" + ENTITY_ID, token, payload);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            context.getSharedPreferences(PRESENCE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_PUBLISH, now)
                .putString(KEY_LAST_STATE, detected ? "presente" : "assente")
                .remove(KEY_LAST_ERROR)
                .apply();
        } catch (Exception error) {
            String message = safeMessage(error);
            context.getSharedPreferences(PRESENCE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_ERROR, message)
                .apply();
            log.warning("AUREA Presence", "Pubblicazione Home Assistant non riuscita: " + message);
        }
    }

    static String summary(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
            PRESENCE_PREFS,
            Context.MODE_PRIVATE
        );
        long time = prefs.getLong(KEY_LAST_PUBLISH, 0L);
        String state = cleanStatic(prefs.getString(KEY_LAST_STATE, ""));
        String error = cleanStatic(prefs.getString(KEY_LAST_ERROR, ""));
        if (!error.isEmpty()) return "Ultimo invio non riuscito: " + error + ".";
        if (time <= 0L) return "Primo rilevamento in attesa.";
        return "Ultimo stato: " + (state.isEmpty() ? "sconosciuto" : state)
            + " · aggiornato " + isoStatic(time) + ".";
    }

    void close() {
        io.shutdownNow();
    }

    private int post(String address, String token, JSONObject payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(10000);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
            drain(stream);
            return code;
        } finally {
            connection.disconnect();
        }
    }

    private void drain(InputStream input) throws Exception {
        if (input == null) return;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
            }
        }
    }

    private String profile() {
        String person = RegisteredUserAccess.currentPerson(context);
        return person.isEmpty() ? "nessuno" : person;
    }

    private String trimSlash(String value) {
        String result = clean(value);
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? "" : clean(error.getMessage());
        if (message.isEmpty()) return error == null
            ? "errore sconosciuto" : error.getClass().getSimpleName();
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    private String clean(String value) {
        return cleanStatic(value);
    }

    private String iso(long time) {
        return isoStatic(time);
    }

    private static String isoStatic(long time) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            .format(new Date(time));
    }

    private static String cleanStatic(String value) {
        return value == null ? "" : value.trim();
    }
}
