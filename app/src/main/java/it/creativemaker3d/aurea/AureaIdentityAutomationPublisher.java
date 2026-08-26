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

/** Pubblica gli eventi Identity Automation senza dati biometrici o autorizzazioni. */
final class AureaIdentityAutomationPublisher {
    static final String AUTOMATION_ENTITY = "sensor.aurea_tablet_identity_automation";
    static final String UNKNOWN_ENTITY = "binary_sensor.aurea_tablet_unknown_person";
    static final String PROFILE_ENTITY = "sensor.aurea_tablet_active_profile";

    private static final String APP_PREFS = "aurea";
    private static final String KEY_HA_URL = "ha_url";
    private static final String KEY_HA_TOKEN = "ha_token";
    private static final String DEFAULT_HA_URL = "http://192.168.178.72:8123";
    private static final long REFRESH_MS = 5L * 60L * 1000L;

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AureaDiagnosticsLog log;
    private String queuedFingerprint = "";
    private long queuedAt;

    AureaIdentityAutomationPublisher(Context context) {
        this.context = context.getApplicationContext();
        this.log = new AureaDiagnosticsLog(this.context);
    }

    synchronized void publish(AureaIdentityAutomation.Snapshot snapshot) {
        if (snapshot == null) return;
        String fingerprint = snapshot.state + "|" + snapshot.identity
            + "|" + snapshot.activeProfile + "|" + snapshot.present
            + "|" + snapshot.cameraActive + "|" + snapshot.greetingsEnabled
            + "|" + snapshot.recognizedEvents + "|" + snapshot.unknownEvents
            + "|" + snapshot.greetingEvents;
        long now = System.currentTimeMillis();
        if (fingerprint.equals(queuedFingerprint) && now - queuedAt < REFRESH_MS) return;
        queuedFingerprint = fingerprint;
        queuedAt = now;
        io.execute(() -> publishNow(snapshot));
    }

    private void publishNow(AureaIdentityAutomation.Snapshot snapshot) {
        SharedPreferences app = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
        String haUrl = trimSlash(app.getString(KEY_HA_URL, DEFAULT_HA_URL));
        String token = clean(app.getString(KEY_HA_TOKEN, ""));
        if (haUrl.isEmpty() || token.isEmpty()) return;

        try {
            postState(haUrl, token, AUTOMATION_ENTITY, automationState(snapshot));
            postState(haUrl, token, UNKNOWN_ENTITY, unknownState(snapshot));
            postState(haUrl, token, PROFILE_ENTITY, profileState(snapshot));
            context.getSharedPreferences(
                AureaIdentityAutomation.PREFS_NAME,
                Context.MODE_PRIVATE
            ).edit()
                .putLong(AureaIdentityAutomation.KEY_LAST_HA_PUBLISH, snapshot.now)
                .remove(AureaIdentityAutomation.KEY_LAST_HA_ERROR)
                .apply();
        } catch (Exception error) {
            context.getSharedPreferences(
                AureaIdentityAutomation.PREFS_NAME,
                Context.MODE_PRIVATE
            ).edit()
                .putString(
                    AureaIdentityAutomation.KEY_LAST_HA_ERROR,
                    safeMessage(error)
                )
                .apply();
            log.warning(
                "AUREA Identity Automation",
                "Pubblicazione Home Assistant non riuscita: " + safeMessage(error)
            );
        }
    }

    private JSONObject automationState(
            AureaIdentityAutomation.Snapshot snapshot) throws Exception {
        JSONObject attributes = common(snapshot);
        attributes.put("friendly_name", "AUREA Identity Automation");
        attributes.put("icon", "mdi:account-reactivate-outline");
        attributes.put("identity", snapshot.identity);
        attributes.put("active_profile", emptyAsNone(snapshot.activeProfile));
        attributes.put("greetings_enabled", snapshot.greetingsEnabled);
        attributes.put("greeting_sent_for_event", snapshot.greeted);
        attributes.put("recognized_events", snapshot.recognizedEvents);
        attributes.put("unknown_events", snapshot.unknownEvents);
        attributes.put("greeting_events", snapshot.greetingEvents);
        attributes.put("last_recognized", timeOrNever(snapshot.lastRecognizedAt));
        attributes.put("last_unknown", timeOrNever(snapshot.lastUnknownAt));
        attributes.put("last_greeting", timeOrNever(snapshot.lastGreetingAt));
        attributes.put(
            "last_greeting_profile",
            emptyAsNone(snapshot.lastGreetingPerson)
        );

        JSONObject payload = new JSONObject();
        payload.put("state", snapshot.state);
        payload.put("attributes", attributes);
        return payload;
    }

    private JSONObject unknownState(
            AureaIdentityAutomation.Snapshot snapshot) throws Exception {
        boolean unknown = AureaIdentityAutomationPolicy.UNKNOWN.equals(snapshot.state);
        JSONObject attributes = common(snapshot);
        attributes.put("friendly_name", "AUREA Persona sconosciuta");
        attributes.put("icon", unknown
            ? "mdi:account-alert-outline" : "mdi:account-check-outline");
        attributes.put("device_class", "occupancy");
        attributes.put("confirmed_events", snapshot.unknownEvents);
        attributes.put("last_confirmed", timeOrNever(snapshot.lastUnknownAt));
        attributes.put("email_notifications", false);
        attributes.put("requires_home_assistant_confirmation", true);

        JSONObject payload = new JSONObject();
        payload.put("state", unknown ? "on" : "off");
        payload.put("attributes", attributes);
        return payload;
    }

    private JSONObject profileState(
            AureaIdentityAutomation.Snapshot snapshot) throws Exception {
        JSONObject attributes = common(snapshot);
        attributes.put("friendly_name", "AUREA Tablet Active Profile");
        attributes.put("icon", snapshot.activeProfile.isEmpty()
            ? "mdi:account-off-outline" : "mdi:account-circle");
        attributes.put("source", "riconoscimento facciale locale passivo");
        attributes.put("recognition_state", snapshot.state);
        attributes.put("privacy", "nessuna immagine o firma biometrica pubblicata");

        JSONObject payload = new JSONObject();
        payload.put("state", emptyAsNone(snapshot.activeProfile));
        payload.put("attributes", attributes);
        return payload;
    }

    private JSONObject common(AureaIdentityAutomation.Snapshot snapshot) throws Exception {
        JSONObject attributes = new JSONObject();
        attributes.put("confidence", Math.round(snapshot.confidence * 1000f) / 1000f);
        attributes.put("presence", snapshot.present);
        attributes.put("camera_active", snapshot.cameraActive);
        attributes.put("local_processing", true);
        attributes.put("images_saved", false);
        attributes.put("audio_saved", false);
        attributes.put("security_authorization", false);
        attributes.put("last_home_assistant_publish", timeOrNever(
            context.getSharedPreferences(
                AureaIdentityAutomation.PREFS_NAME,
                Context.MODE_PRIVATE
            ).getLong(AureaIdentityAutomation.KEY_LAST_HA_PUBLISH, 0L)
        ));
        attributes.put("last_update", iso(snapshot.now));
        return attributes;
    }

    private void postState(
            String haUrl,
            String token,
            String entityId,
            JSONObject payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
            haUrl + "/api/states/" + entityId
        ).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(10000);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8"
            );
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
            drain(stream);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException(entityId + " HTTP " + code);
            }
        } finally {
            connection.disconnect();
        }
    }

    void close() {
        io.shutdownNow();
    }

    private void drain(InputStream input) throws Exception {
        if (input == null) return;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
            }
        }
    }

    private String emptyAsNone(String value) {
        String clean = clean(value);
        return clean.isEmpty() ? AureaIdentityAutomationPolicy.NONE_IDENTITY : clean;
    }

    private String timeOrNever(long value) {
        return value > 0L ? iso(value) : "mai";
    }

    private String safeMessage(Throwable error) {
        String value = error == null ? "" : clean(error.getMessage());
        if (value.isEmpty()) return error == null
            ? "errore sconosciuto" : error.getClass().getSimpleName();
        return value.length() > 160 ? value.substring(0, 160) : value;
    }

    private String trimSlash(String value) {
        String result = clean(value);
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String iso(long value) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            .format(new Date(value));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
