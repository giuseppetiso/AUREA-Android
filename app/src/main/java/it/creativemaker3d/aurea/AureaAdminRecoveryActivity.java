package it.creativemaker3d.aurea;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Recupero amministratore mediante una prova di possesso di Home Assistant.
 * Il codice vive solo in memoria, viene mostrato esclusivamente in HA e scade.
 */
public final class AureaAdminRecoveryActivity extends Activity {
    private static final String APP_PREFS = "aurea";
    private static final String KEY_HA_URL = "ha_url";
    private static final String KEY_HA_TOKEN = "ha_token";
    private static final String DEFAULT_HA_URL = "http://192.168.178.72:8123";
    private static final String NOTIFICATION_ID = "aurea_admin_recovery";
    private static final long CODE_LIFETIME_MS = 5L * 60L * 1000L;
    private static final int MAX_ATTEMPTS = 5;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SecureRandom random = new SecureRandom();

    private AdminAccessStore adminStore;
    private EditText codeInput;
    private TextView statusView;
    private Button verifyButton;
    private String code = "";
    private long expiresAt;
    private int attempts;
    private boolean completing;

    private final Runnable expiry = () -> {
        if (completing || System.currentTimeMillis() < expiresAt) return;
        statusView.setText("Codice scaduto. Riapri persona+ per generarne uno nuovo.");
        verifyButton.setEnabled(false);
        adminStore.revoke();
        dismissNotification();
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        adminStore = new AdminAccessStore(this);
        if (!adminStore.isAccessRequested()) {
            finish();
            return;
        }

        buildInterface();
        code = String.format("%06d", 100000 + random.nextInt(900000));
        expiresAt = System.currentTimeMillis() + CODE_LIFETIME_MS;
        main.postDelayed(expiry, CODE_LIFETIME_MS + 250L);
        publishCode();
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(90, 55, 90, 55);
        root.setBackgroundColor(Color.rgb(2, 7, 13));

        TextView title = text("Recupero sicuro AUREA", 29, Color.WHITE);
        root.addView(title, fullWidth());

        TextView instructions = text(
            "Apri Home Assistant dal computer e premi la campanella Notifiche. "
                + "Troverai un codice temporaneo di sei cifre; inseriscilo qui.",
            18,
            Color.rgb(200, 220, 234)
        );
        instructions.setPadding(8, 20, 8, 24);
        root.addView(instructions, fullWidth());

        codeInput = new EditText(this);
        codeInput.setHint("Codice di 6 cifre");
        codeInput.setTextColor(Color.WHITE);
        codeInput.setHintTextColor(Color.GRAY);
        codeInput.setTextSize(25);
        codeInput.setGravity(Gravity.CENTER);
        codeInput.setSingleLine(true);
        codeInput.setInputType(
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
        );
        root.addView(codeInput, fullWidth());

        verifyButton = new Button(this);
        verifyButton.setText("Verifica codice");
        verifyButton.setEnabled(false);
        verifyButton.setOnClickListener(view -> verifyCode());
        LinearLayout.LayoutParams buttonParams = fullWidth();
        buttonParams.topMargin = 14;
        root.addView(verifyButton, buttonParams);

        statusView = text(
            "Invio del codice a Home Assistant…",
            16,
            Color.rgb(130, 210, 245)
        );
        statusView.setPadding(8, 18, 8, 8);
        root.addView(statusView, fullWidth());

        TextView safety = text(
            "Il codice non appare sul tablet, non viene salvato e funziona una sola volta. "
                + "Dopo cinque tentativi il recupero viene annullato.",
            13,
            Color.rgb(150, 172, 190)
        );
        safety.setPadding(8, 18, 8, 0);
        root.addView(safety, fullWidth());

        setContentView(root);
    }

    private void publishCode() {
        io.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("title", "AUREA · Recupero amministratore");
                data.put(
                    "message",
                    "Codice temporaneo: " + code
                        + ". Inseriscilo sul tablet entro 5 minuti."
                );
                data.put("notification_id", NOTIFICATION_ID);
                int response = postService("persistent_notification/create", data);
                if (response < 200 || response >= 300) {
                    throw new IllegalStateException("HTTP " + response);
                }
                main.post(() -> {
                    if (completing) return;
                    statusView.setText(
                        "Codice inviato. Controlla le Notifiche di Home Assistant."
                    );
                    verifyButton.setEnabled(true);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (completing) return;
                    statusView.setText(
                        "Invio non riuscito: " + safeMessage(error)
                            + ". Verifica che Home Assistant sia raggiungibile."
                    );
                    verifyButton.setEnabled(false);
                });
            }
        });
    }

    private void verifyCode() {
        if (completing) return;
        if (System.currentTimeMillis() >= expiresAt) {
            expiry.run();
            return;
        }
        attempts++;
        String entered = codeInput.getText().toString().trim();
        if (!code.equals(entered)) {
            int remaining = MAX_ATTEMPTS - attempts;
            codeInput.setText("");
            if (remaining <= 0) {
                statusView.setText("Troppi tentativi. Recupero annullato.");
                verifyButton.setEnabled(false);
                adminStore.revoke();
                dismissNotification();
                return;
            }
            statusView.setText("Codice errato. Tentativi rimasti: " + remaining + ".");
            return;
        }

        completing = true;
        verifyButton.setEnabled(false);
        codeInput.setEnabled(false);
        if (!adminStore.grant(AdminAccessStore.ADMIN_NAME)) {
            completing = false;
            statusView.setText("Impossibile concedere l'accesso amministratore.");
            return;
        }
        statusView.setText("Codice verificato. Apertura Gestione persone…");
        io.execute(() -> {
            dismissNotificationNow();
            main.post(() -> {
                Toast.makeText(
                    this,
                    "Accesso amministratore recuperato",
                    Toast.LENGTH_SHORT
                ).show();
                Intent manager = new Intent(this, PeopleManagerActivity.class);
                manager.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(manager);
                finish();
            });
        });
    }

    private int postService(String service, JSONObject data) throws Exception {
        SharedPreferences prefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        String haUrl = trimSlash(prefs.getString(KEY_HA_URL, DEFAULT_HA_URL));
        String token = clean(prefs.getString(KEY_HA_TOKEN, ""));
        if (haUrl.isEmpty() || token.isEmpty()) {
            throw new IllegalStateException("configurazione Home Assistant incompleta");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(
            haUrl + "/api/services/" + service
        ).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(10000);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = data.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int response = connection.getResponseCode();
            InputStream stream = response >= 200 && response < 300
                ? connection.getInputStream() : connection.getErrorStream();
            drain(stream);
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private void dismissNotification() {
        io.execute(this::dismissNotificationNow);
    }

    private void dismissNotificationNow() {
        try {
            JSONObject data = new JSONObject();
            data.put("notification_id", NOTIFICATION_ID);
            postService("persistent_notification/dismiss", data);
        } catch (Exception ignored) {
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

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
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
        return message.length() > 120 ? message.substring(0, 120) : message;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(expiry);
        if (!completing) {
            adminStore.revoke();
            dismissNotification();
        }
        io.shutdown();
        super.onDestroy();
    }
}
