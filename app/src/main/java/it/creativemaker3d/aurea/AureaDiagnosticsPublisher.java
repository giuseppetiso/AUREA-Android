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
import java.security.MessageDigest;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pubblica uno stato diagnostico sanificato in Home Assistant e usa il canale
 * email già configurato in Home Assistant. Nessuna credenziale email risiede
 * sul tablet.
 */
final class AureaDiagnosticsPublisher {
    static final String DIAGNOSTICS_ENTITY = "sensor.aurea_tablet_diagnostics";
    static final String HEARTBEAT_ENTITY = "sensor.aurea_tablet_heartbeat";
    static final String SYSTEM_ENTITY = "sensor.aurea_tablet_system";
    static final String BATTERY_ENTITY = "sensor.aurea_tablet_battery";
    static final String SCREEN_ENTITY = "binary_sensor.aurea_tablet_screen";
    static final String PROFILE_ENTITY = "sensor.aurea_tablet_active_profile";

    private static final String APP_PREFS = "aurea";
    private static final String KEY_HA_URL = "ha_url";
    private static final String KEY_HA_TOKEN = "ha_token";
    private static final String DEFAULT_HA_URL = "http://192.168.178.72:8123";

    private static final String MONITOR_PREFS = "aurea_diagnostics_monitor";
    private static final String KEY_LAST_PUBLISH_TIME = "last_publish_time";
    private static final String KEY_LAST_PUBLISH_STATUS = "last_publish_status";
    private static final String KEY_LAST_DELIVERY = "last_delivery";
    private static final String KEY_LAST_ALERT_FINGERPRINT = "last_alert_fingerprint";
    private static final String KEY_FINGERPRINT_VERSION = "fingerprint_version";
    private static final String KEY_LAST_ALERT_TIME = "last_alert_time";
    private static final String KEY_LAST_DAILY_DAY = "last_daily_day";
    private static final String KEY_PREVIOUS_STATUS = "previous_status";

    private static final String ANOMALY_SCRIPT = "/api/services/script/aurea_registra_anomalia";
    private static final String EMAIL_ENTITY =
        "notify.home_assistant_casa_giuseppe_tiso";
    private static final String MODERN_EMAIL_SERVICE = "/api/services/notify/send_message";
    private static final String LEGACY_EMAIL_SERVICE =
        "/api/services/notify/home_assistant_casa_giuseppe_tiso";
    private static final long SAME_ANOMALY_REMINDER_MS = 12L * 60L * 60L * 1000L;
    private static final int FINGERPRINT_VERSION = 2;
    private static final Object PUBLISH_LOCK = new Object();

    static final class PublishResult {
        final boolean success;
        final boolean retryable;
        final boolean notificationSent;
        final String message;

        PublishResult(
                boolean success,
                boolean retryable,
                boolean notificationSent,
                String message) {
            this.success = success;
            this.retryable = retryable;
            this.notificationSent = notificationSent;
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

    AureaDiagnosticsPublisher(Context context) {
        this.context = context.getApplicationContext();
        this.log = new AureaDiagnosticsLog(this.context);
    }

    PublishResult publish(AureaDiagnosticsProbe.Snapshot snapshot) {
        synchronized (PUBLISH_LOCK) {
            return publishLocked(snapshot);
        }
    }

    private PublishResult publishLocked(AureaDiagnosticsProbe.Snapshot snapshot) {
        if (snapshot == null) {
            return new PublishResult(false, false, false, "Rapporto assente");
        }

        SharedPreferences app = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
        String haUrl = trimSlash(app.getString(KEY_HA_URL, DEFAULT_HA_URL));
        String token = clean(app.getString(KEY_HA_TOKEN, ""));
        if (haUrl.isEmpty() || token.isEmpty()) {
            saveFailure("Home Assistant non configurato");
            return new PublishResult(
                false,
                false,
                false,
                "URL o token Home Assistant non configurati"
            );
        }

        long now = System.currentTimeMillis();
        String status = statusOf(snapshot);
        String fingerprint = fingerprintIssues(snapshot.checks);
        SharedPreferences monitor = context.getSharedPreferences(
            MONITOR_PREFS,
            Context.MODE_PRIVATE
        );

        try {
            AureaTabletTelemetry.Snapshot tablet = AureaTabletTelemetry.capture(context);
            postState(haUrl, token, DIAGNOSTICS_ENTITY, diagnosticsState(snapshot, status));
            postState(haUrl, token, HEARTBEAT_ENTITY, heartbeatState(snapshot, status));
            postState(haUrl, token, SYSTEM_ENTITY, systemState(tablet));
            postState(haUrl, token, BATTERY_ENTITY, batteryState(tablet));
            postState(haUrl, token, SCREEN_ENTITY, screenState(tablet));
            postState(haUrl, token, PROFILE_ENTITY, profileState(tablet));

            String previousStatus = clean(monitor.getString(KEY_PREVIOUS_STATUS, ""));
            String lastFingerprint = clean(monitor.getString(
                KEY_LAST_ALERT_FINGERPRINT,
                ""
            ));
            long lastAlertTime = monitor.getLong(KEY_LAST_ALERT_TIME, 0L);
            int fingerprintVersion = monitor.getInt(KEY_FINGERPRINT_VERSION, 1);
            boolean notificationSent = false;
            String delivery = "Home Assistant aggiornato · nessuna nuova email";

            if (!"ok".equals(status)) {
                boolean newOrChanged = !fingerprint.equals(lastFingerprint);
                boolean migratingFingerprint = fingerprintVersion < FINGERPRINT_VERSION
                    && lastAlertTime > 0L
                    && status.equals(previousStatus);
                if (migratingFingerprint) {
                    newOrChanged = false;
                    monitor.edit()
                        .putString(KEY_LAST_ALERT_FINGERPRINT, fingerprint)
                        .putInt(KEY_FINGERPRINT_VERSION, FINGERPRINT_VERSION)
                        .apply();
                }
                boolean reminderDue = now - lastAlertTime >= SAME_ANOMALY_REMINDER_MS;
                if (newOrChanged || reminderDue) {
                    sendAnomaly(haUrl, token, snapshot);
                    notificationSent = true;
                    delivery = newOrChanged
                        ? "Anomalia comunicata a Home Assistant ed email"
                        : "Promemoria anomalia inviato dopo 12 ore";
                    monitor.edit()
                        .putString(KEY_LAST_ALERT_FINGERPRINT, fingerprint)
                        .putInt(KEY_FINGERPRINT_VERSION, FINGERPRINT_VERSION)
                        .putLong(KEY_LAST_ALERT_TIME, now)
                        .apply();
                } else {
                    delivery = "Anomalia invariata già comunicata · antispam attivo";
                }
            } else {
                boolean recovered = !previousStatus.isEmpty()
                    && !"ok".equals(previousStatus);
                String today = dayKey(now);
                String lastDailyDay = clean(monitor.getString(KEY_LAST_DAILY_DAY, ""));
                if (recovered) {
                    sendEmail(
                        haUrl,
                        token,
                        "[AUREA] Diagnostica tablet ripristinata",
                        healthyMessage(snapshot, true)
                    );
                    notificationSent = true;
                    delivery = "Ripristino comunicato a Home Assistant ed email";
                    monitor.edit().putString(KEY_LAST_DAILY_DAY, today).apply();
                } else if (!today.equals(lastDailyDay)) {
                    sendEmail(
                        haUrl,
                        token,
                        "[AUREA] Rapporto diagnostico giornaliero",
                        healthyMessage(snapshot, false)
                    );
                    notificationSent = true;
                    delivery = "Rapporto giornaliero inviato via email";
                    monitor.edit().putString(KEY_LAST_DAILY_DAY, today).apply();
                }
                monitor.edit()
                    .remove(KEY_LAST_ALERT_FINGERPRINT)
                    .remove(KEY_LAST_ALERT_TIME)
                    .apply();
            }

            monitor.edit()
                .putLong(KEY_LAST_PUBLISH_TIME, now)
                .putString(KEY_LAST_PUBLISH_STATUS, status)
                .putString(KEY_PREVIOUS_STATUS, status)
                .putString(KEY_LAST_DELIVERY, delivery)
                .apply();
            log.info("Monitor diagnostico", delivery);
            return new PublishResult(true, false, notificationSent, delivery);
        } catch (Exception error) {
            String message = safeMessage(error);
            saveFailure("Invio non riuscito: " + message);
            log.error("Monitor diagnostico", "Invio Home Assistant non riuscito", error);
            return new PublishResult(false, true, false, message);
        }
    }

    static String monitorSummary(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
            MONITOR_PREFS,
            Context.MODE_PRIVATE
        );
        long time = prefs.getLong(KEY_LAST_PUBLISH_TIME, 0L);
        String status = cleanStatic(prefs.getString(KEY_LAST_PUBLISH_STATUS, ""));
        String delivery = cleanStatic(prefs.getString(KEY_LAST_DELIVERY, ""));
        StringBuilder result = new StringBuilder(
            "Monitor automatico attivo · controllo ogni 30 minuti"
        );
        if (time <= 0L) {
            result.append("\nPrimo invio in attesa.");
            if (!delivery.isEmpty()) result.append(" ").append(delivery).append(".");
            return result.toString();
        }
        result.append("\nUltimo invio: ")
            .append(DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT,
                Locale.ITALIAN
            ).format(new Date(time)));
        if (!status.isEmpty()) result.append(" · stato ").append(status.toUpperCase(Locale.ITALIAN));
        if (!delivery.isEmpty()) result.append("\n").append(delivery).append(".");
        return result.toString();
    }

    private JSONObject diagnosticsState(
            AureaDiagnosticsProbe.Snapshot snapshot,
            String status) throws Exception {
        JSONObject attributes = new JSONObject();
        attributes.put("friendly_name", "AUREA Tablet Diagnostics");
        attributes.put("icon", "mdi:tablet-dashboard");
        attributes.put("errors", snapshot.errors);
        attributes.put("warnings", snapshot.warnings);
        attributes.put("installed_version", snapshot.installedVersion);
        attributes.put("signed_version", snapshot.signedVersion);
        attributes.put("last_check", isoTime(snapshot.time));
        attributes.put("monitor_interval_minutes", AureaDiagnosticsScheduler.INTERVAL_MINUTES);
        attributes.put("email_policy", "nuova anomalia, variazione, 12h, ripristino, giornaliero");
        attributes.put("issue_summary", limit(issueSummary(snapshot), 1800));

        JSONObject payload = new JSONObject();
        payload.put("state", status);
        payload.put("attributes", attributes);
        return payload;
    }

    private JSONObject heartbeatState(
            AureaDiagnosticsProbe.Snapshot snapshot,
            String status) throws Exception {
        JSONObject attributes = new JSONObject();
        attributes.put("friendly_name", "AUREA Tablet Heartbeat");
        attributes.put("icon", "mdi:heart-pulse");
        attributes.put("diagnostics_status", status);
        attributes.put("installed_version", snapshot.installedVersion);
        attributes.put("interval_minutes", AureaDiagnosticsScheduler.INTERVAL_MINUTES);

        JSONObject payload = new JSONObject();
        payload.put("state", isoTime(snapshot.time));
        payload.put("attributes", attributes);
        return payload;
    }

    private JSONObject systemState(AureaTabletTelemetry.Snapshot tablet) throws Exception {
        JSONObject attributes = new JSONObject();
        attributes.put("friendly_name", "AUREA Tablet System");
        attributes.put("icon", "mdi:tablet-dashboard");
        attributes.put("network_type", tablet.networkType);
        attributes.put("app_foreground", tablet.appForeground);
        attributes.put("screen_interactive", tablet.screenInteractive);
        attributes.put("screen_brightness", tablet.screenBrightness);
        attributes.put("media_volume_percent", tablet.mediaVolumePercent);
        attributes.put("microphone_permission", tablet.microphoneAllowed);
        attributes.put("camera_permission", tablet.cameraAllowed);
        attributes.put("free_storage_mb", tablet.freeStorageMb);
        attributes.put("uptime_minutes", tablet.uptimeMinutes);
        attributes.put("installed_version", BuildConfig.VERSION_NAME
            + " (" + BuildConfig.VERSION_CODE + ")");
        attributes.put("last_update", isoTime(tablet.time));

        JSONObject payload = new JSONObject();
        payload.put("state", tablet.networkConnected ? "online" : "offline");
        payload.put("attributes", attributes);
        return payload;
    }

    private JSONObject batteryState(AureaTabletTelemetry.Snapshot tablet) throws Exception {
        JSONObject attributes = new JSONObject();
        attributes.put("friendly_name", "AUREA Tablet Battery");
        attributes.put("icon", tablet.charging ? "mdi:battery-charging" : "mdi:battery");
        attributes.put("device_class", "battery");
        attributes.put("state_class", "measurement");
        attributes.put("unit_of_measurement", "%");
        attributes.put("charging", tablet.charging);
        attributes.put("power_source", tablet.powerSource);
        if (tablet.temperatureC > 0f) {
            attributes.put("temperature_c", Math.round(tablet.temperatureC * 10f) / 10f);
        }
        attributes.put("last_update", isoTime(tablet.time));

        JSONObject payload = new JSONObject();
        payload.put("state", tablet.batteryPercent < 0 ? "unknown" : tablet.batteryPercent);
        payload.put("attributes", attributes);
        return payload;
    }

    private JSONObject screenState(AureaTabletTelemetry.Snapshot tablet) throws Exception {
        JSONObject attributes = new JSONObject();
        attributes.put("friendly_name", "AUREA Tablet Screen");
        attributes.put("icon", tablet.screenInteractive
            ? "mdi:tablet-dashboard" : "mdi:tablet-off");
        attributes.put("brightness", tablet.screenBrightness);
        attributes.put("app_foreground", tablet.appForeground);
        attributes.put("last_update", isoTime(tablet.time));

        JSONObject payload = new JSONObject();
        payload.put("state", tablet.screenInteractive ? "on" : "off");
        payload.put("attributes", attributes);
        return payload;
    }

    private JSONObject profileState(AureaTabletTelemetry.Snapshot tablet) throws Exception {
        String verifiedSession = RegisteredUserAccess.currentPerson(context);
        JSONObject attributes = new JSONObject();
        attributes.put("friendly_name", "AUREA Tablet Active Profile");
        attributes.put("icon", tablet.activeProfile.isEmpty()
            ? "mdi:account-off-outline" : "mdi:account-circle");
        attributes.put("recognition", tablet.activeProfile.isEmpty()
            ? "nessuna persona riconosciuta davanti al tablet"
            : "profilo locale riconosciuto passivamente");
        attributes.put("verified_session", verifiedSession.isEmpty()
            ? "nessuna" : verifiedSession);
        attributes.put("security_authorization", false);
        attributes.put("privacy", "nessuna immagine o firma biometrica pubblicata");
        attributes.put("last_update", isoTime(tablet.time));

        JSONObject payload = new JSONObject();
        payload.put("state", tablet.activeProfile.isEmpty()
            ? "nessuno" : tablet.activeProfile);
        payload.put("attributes", attributes);
        return payload;
    }

    private void postState(
            String haUrl,
            String token,
            String entityId,
            JSONObject payload) throws Exception {
        HttpResult result = post(
            haUrl + "/api/states/" + entityId,
            token,
            payload
        );
        if (!result.successful()) {
            throw new IllegalStateException(
                "Pubblicazione " + entityId + " rifiutata (HTTP " + result.code + ")"
            );
        }
    }

    private void sendAnomaly(
            String haUrl,
            String token,
            AureaDiagnosticsProbe.Snapshot snapshot) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("titolo", anomalyTitle(snapshot));
        payload.put("origine", "Tablet P90 · AUREA Diagnostics automatico");
        payload.put("gravita", snapshot.errors > 0 ? "critica" : "attenzione");
        payload.put("entita", DIAGNOSTICS_ENTITY);
        payload.put("dettagli", limit(issueSummary(snapshot), 3500));
        HttpResult result = post(haUrl + ANOMALY_SCRIPT, token, payload);
        if (!result.successful()) {
            throw new IllegalStateException(
                "Script anomalie rifiutato (HTTP " + result.code + ")"
            );
        }
    }

    private void sendEmail(
            String haUrl,
            String token,
            String title,
            String message) throws Exception {
        JSONObject modern = new JSONObject();
        modern.put("entity_id", EMAIL_ENTITY);
        modern.put("title", title);
        modern.put("message", limit(message, 3500));
        HttpResult result = post(haUrl + MODERN_EMAIL_SERVICE, token, modern);
        if (result.successful()) return;

        JSONObject legacy = new JSONObject();
        legacy.put("title", title);
        legacy.put("message", limit(message, 3500));
        result = post(haUrl + LEGACY_EMAIL_SERVICE, token, legacy);
        if (!result.successful()) {
            throw new IllegalStateException(
                "Canale email rifiutato (HTTP " + result.code + ")"
            );
        }
    }

    private HttpResult post(
            String address,
            String token,
            JSONObject payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(12000);
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
            ? connection.getInputStream()
            : connection.getErrorStream();
        String response = readAll(stream);
        connection.disconnect();
        return new HttpResult(code, response);
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        }
        return body.toString();
    }

    private String issueSummary(AureaDiagnosticsProbe.Snapshot snapshot) {
        StringBuilder report = new StringBuilder();
        report.append(snapshot.headline())
            .append("\nVersione: ").append(snapshot.installedVersion)
            .append("\nControllo: ").append(isoTime(snapshot.time));
        for (AureaDiagnosticsProbe.Check check : snapshot.checks) {
            if (check.status != AureaDiagnosticsProbe.Status.ERROR
                    && check.status != AureaDiagnosticsProbe.Status.WARNING) continue;
            report.append("\n[")
                .append(check.status == AureaDiagnosticsProbe.Status.ERROR
                    ? "ERRORE" : "AVVISO")
                .append("] ").append(check.title)
                .append(": ").append(check.detail);
        }
        if (snapshot.errors == 0 && snapshot.warnings == 0) {
            report.append("\nTutti i controlli automatici sono stati superati.");
        }
        report.append("\nIl rapporto completo resta disponibile sul tablet in AUREA Diagnostics.");
        return report.toString();
    }

    private String healthyMessage(
            AureaDiagnosticsProbe.Snapshot snapshot,
            boolean recovery) {
        return (recovery
            ? "Il monitor AUREA segnala che il tablet è tornato regolare."
            : "Rapporto automatico giornaliero del tablet AUREA.")
            + "\n\n" + issueSummary(snapshot)
            + "\n\nProssimo controllo automatico entro 30 minuti.";
    }

    private String anomalyTitle(AureaDiagnosticsProbe.Snapshot snapshot) {
        if (snapshot.errors > 0) {
            return "AUREA Diagnostics · " + snapshot.errors + " problemi bloccanti";
        }
        return "AUREA Diagnostics · " + snapshot.warnings + " avvisi";
    }

    private String fingerprintIssues(List<AureaDiagnosticsProbe.Check> checks) {
        StringBuilder source = new StringBuilder();
        for (AureaDiagnosticsProbe.Check check : checks) {
            if (check.status != AureaDiagnosticsProbe.Status.ERROR
                    && check.status != AureaDiagnosticsProbe.Status.WARNING) continue;
            source.append(check.status).append('|')
                .append(check.title).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                source.toString().getBytes(StandardCharsets.UTF_8)
            );
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 16 && index < digest.length; index++) {
                result.append(String.format(Locale.US, "%02x", digest[index] & 0xff));
            }
            return result.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(source.toString().hashCode());
        }
    }

    private String statusOf(AureaDiagnosticsProbe.Snapshot snapshot) {
        if (snapshot.errors > 0) return "error";
        if (snapshot.warnings > 0) return "warning";
        return "ok";
    }

    private void saveFailure(String message) {
        context.getSharedPreferences(MONITOR_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DELIVERY, limit(message, 180))
            .apply();
    }

    private String isoTime(long time) {
        return new SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            Locale.US
        ).format(new Date(time));
    }

    private String dayKey(long time) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(time));
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? "" : clean(error.getMessage());
        if (message.isEmpty()) {
            return error == null ? "errore sconosciuto" : error.getClass().getSimpleName();
        }
        return limit(message, 180);
    }

    private String trimSlash(String value) {
        String result = clean(value);
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String clean(String value) {
        return cleanStatic(value);
    }

    private static String limit(String value, int maximum) {
        String clean = cleanStatic(value);
        if (clean.length() <= maximum) return clean;
        return clean.substring(0, Math.max(0, maximum - 1)) + "…";
    }

    private static String cleanStatic(String value) {
        return value == null ? "" : value.trim();
    }
}
