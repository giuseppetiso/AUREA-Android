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

/** Pubblica solo il nome risultante dal confronto locale, mai immagini o firme. */
final class AureaIdentityPublisher {
    static final String ENTITY_ID = "sensor.aurea_tablet_recognized_person";
    static final String NONE = "nessuno";
    static final String UNKNOWN = "sconosciuto";
    static final String DISABLED = "disattivato";

    private static final String APP_PREFS = "aurea";
    private static final String KEY_HA_URL = "ha_url";
    private static final String KEY_HA_TOKEN = "ha_token";
    private static final String DEFAULT_HA_URL = "http://192.168.178.72:8123";
    private static final String PRESENCE_PREFS = "aurea_presence";
    private static final String KEY_LAST_IDENTITY = "last_identity";
    private static final String KEY_LAST_IDENTITY_PUBLISH = "last_identity_publish";
    private static final String KEY_LAST_IDENTITY_ERROR = "last_identity_error";
    private static final long REFRESH_MS = 5L * 60L * 1000L;

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AureaDiagnosticsLog log;
    private String queuedFingerprint = "";
    private long queuedAt;

    AureaIdentityPublisher(Context context) {
        this.context = context.getApplicationContext();
        this.log = new AureaDiagnosticsLog(this.context);
    }

    synchronized void publish(
            String identity,
            float confidence,
            boolean present,
            boolean cameraActive,
            boolean recognitionEnabled,
            int profileCount,
            long lastRecognizedAt) {
        String state = clean(identity);
        if (state.isEmpty()) state = NONE;
        long now = System.currentTimeMillis();
        String fingerprint = state + "|" + present + "|" + cameraActive
            + "|" + recognitionEnabled + "|" + profileCount;
        if (fingerprint.equals(queuedFingerprint) && now - queuedAt < REFRESH_MS) return;
        queuedFingerprint = fingerprint;
        queuedAt = now;
        String finalState = state;
        io.execute(() -> publishNow(
            finalState,
            confidence,
            present,
            cameraActive,
            recognitionEnabled,
            profileCount,
            lastRecognizedAt,
            now
        ));
    }

    private void publishNow(
            String identity,
            float confidence,
            boolean present,
            boolean cameraActive,
            boolean recognitionEnabled,
            int profileCount,
            long lastRecognizedAt,
            long now) {
        SharedPreferences app = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
        String haUrl = trimSlash(app.getString(KEY_HA_URL, DEFAULT_HA_URL));
        String token = clean(app.getString(KEY_HA_TOKEN, ""));
        if (haUrl.isEmpty() || token.isEmpty()) return;

        try {
            JSONObject attributes = new JSONObject();
            attributes.put("friendly_name", "AUREA Persona riconosciuta");
            attributes.put("icon", icon(identity));
            attributes.put("confidence", Math.round(confidence * 1000f) / 1000f);
            attributes.put("presence", present);
            attributes.put("camera_active", cameraActive);
            attributes.put("recognition_enabled", recognitionEnabled);
            attributes.put("local_profiles", profileCount);
            attributes.put("local_processing", true);
            attributes.put("images_saved", false);
            attributes.put("security_authorization", false);
            attributes.put(
                "last_recognized",
                lastRecognizedAt > 0L ? iso(lastRecognizedAt) : "mai"
            );
            attributes.put("last_update", iso(now));

            JSONObject payload = new JSONObject();
            payload.put("state", identity);
            payload.put("attributes", attributes);
            int code = post(haUrl + "/api/states/" + ENTITY_ID, token, payload);
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            context.getSharedPreferences(PRESENCE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_IDENTITY, identity)
                .putLong(KEY_LAST_IDENTITY_PUBLISH, now)
                .remove(KEY_LAST_IDENTITY_ERROR)
                .apply();
        } catch (Exception error) {
            String message = safeMessage(error);
            context.getSharedPreferences(PRESENCE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_IDENTITY_ERROR, message).apply();
            log.warning(
                "AUREA Identity",
                "Pubblicazione Home Assistant non riuscita: " + message
            );
        }
    }

    static String summary(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
            PRESENCE_PREFS, Context.MODE_PRIVATE
        );
        String error = clean(prefs.getString(KEY_LAST_IDENTITY_ERROR, ""));
        if (!error.isEmpty()) return "Ultimo invio non riuscito: " + error + ".";
        long time = prefs.getLong(KEY_LAST_IDENTITY_PUBLISH, 0L);
        if (time <= 0L) return "Primo riconoscimento in attesa.";
        String identity = clean(prefs.getString(KEY_LAST_IDENTITY, NONE));
        return "Ultimo stato: " + identity + " · aggiornato " + iso(time) + ".";
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

    private String icon(String identity) {
        if (DISABLED.equals(identity)) return "mdi:account-cancel-outline";
        if (UNKNOWN.equals(identity)) return "mdi:account-question-outline";
        if (NONE.equals(identity)) return "mdi:account-off-outline";
        return "mdi:account-check-outline";
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

    private static String iso(long time) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            .format(new Date(time));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
