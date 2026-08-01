package it.creativemaker3d.aurea;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Configurazione locale di AUREA Brain.
 *
 * Permette di selezionare un conversation agent di Home Assistant senza
 * mostrare o modificare il token. Le conversazioni e il registro restano
 * esclusivamente sul tablet.
 */
public final class AureaBrainActivity extends Activity {
    private static final String HA_PREFS = "aurea";
    private static final String KEY_HA_URL = "ha_url";
    private static final String KEY_HA_TOKEN = "ha_token";

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private AureaBrainStore brainStore;
    private String currentPerson;
    private CheckBox enabled;
    private EditText agentId;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        currentPerson = RegisteredUserAccess.currentPerson(this);
        if (currentPerson.isEmpty()) {
            denyAccess();
            return;
        }

        brainStore = new AureaBrainStore(this);
        buildInterface();
        refreshStatus();
        hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        currentPerson = RegisteredUserAccess.currentPerson(this);
        if (currentPerson.isEmpty()) {
            denyAccess();
        }
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(2, 7, 13));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(32), dp(20), dp(32), dp(20));
        scroll.addView(root, fullWidth());

        TextView title = text("AUREA Brain 1.0", 29, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidth());

        TextView identity = text(
            "Memoria personale: " + currentPerson,
            16,
            Color.rgb(124, 220, 255)
        );
        identity.setGravity(Gravity.CENTER);
        identity.setPadding(0, dp(5), 0, dp(16));
        root.addView(identity, fullWidth());

        LinearLayout configuration = card();
        configuration.addView(text("Cervello conversazionale", 23, Color.WHITE));

        TextView description = text(
            "AUREA mantiene conversazioni separate per persona e può usare un agente AI "
                + "configurato in Home Assistant. Lascia vuoto l'ID per usare l'agente "
                + "predefinito di Home Assistant.",
            15,
            Color.rgb(190, 210, 225)
        );
        description.setPadding(0, dp(6), 0, dp(10));
        configuration.addView(description, fullWidth());

        enabled = new CheckBox(this);
        enabled.setText("Attiva memoria e conversazioni continue");
        enabled.setTextColor(Color.WHITE);
        enabled.setChecked(brainStore.isEnabled());
        configuration.addView(enabled, fullWidth());

        agentId = new EditText(this);
        agentId.setHint("Esempio: conversation.openai_conversation");
        agentId.setText(brainStore.agentId());
        agentId.setSingleLine(true);
        agentId.setTextColor(Color.WHITE);
        agentId.setHintTextColor(Color.rgb(125, 145, 160));
        configuration.addView(agentId, fullWidthWithTop(dp(6)));

        Button detect = button("Rileva agenti disponibili in Home Assistant");
        detect.setOnClickListener(view -> detectAgents());
        configuration.addView(detect, fullWidthWithTop(dp(8)));

        Button save = button("Salva configurazione Brain");
        save.setOnClickListener(view -> saveConfiguration());
        configuration.addView(save, fullWidthWithTop(dp(6)));

        root.addView(configuration, fullWidthWithBottom(dp(14)));

        LinearLayout memory = card();
        memory.addView(text("Memoria e controllo", 23, Color.WHITE));

        status = text("", 15, Color.rgb(190, 210, 225));
        status.setPadding(0, dp(6), 0, dp(10));
        memory.addView(status, fullWidth());

        Button resetConversation = button("Azzera conversazione di " + currentPerson);
        resetConversation.setOnClickListener(view -> {
            brainStore.clearConversation(currentPerson);
            refreshStatus();
            Toast.makeText(
                this,
                "Conversazione personale azzerata",
                Toast.LENGTH_SHORT
            ).show();
        });
        memory.addView(resetConversation, fullWidthWithTop(dp(6)));

        Button clearLog = button("Cancella registro decisioni locale");
        clearLog.setOnClickListener(view -> confirmClearLog());
        memory.addView(clearLog, fullWidthWithTop(dp(6)));

        root.addView(memory, fullWidthWithBottom(dp(14)));

        TextView privacy = text(
            "Il token Home Assistant non viene mostrato né copiato. Il registro resta sul tablet. "
                + "AUREA non controlla dispositivi non esposti ad Assist.",
            14,
            Color.rgb(150, 172, 190)
        );
        privacy.setGravity(Gravity.CENTER);
        privacy.setPadding(dp(12), dp(2), dp(12), dp(14));
        root.addView(privacy, fullWidth());

        Button close = button("Torna a Strumenti AUREA");
        close.setOnClickListener(view -> finish());
        root.addView(close, fullWidth());

        setContentView(scroll);
    }

    private void saveConfiguration() {
        String previousAgent = brainStore.agentId();
        String selectedAgent = agentId.getText().toString().trim();

        brainStore.setEnabled(enabled.isChecked());
        brainStore.setAgentId(selectedAgent);
        if (!previousAgent.equals(selectedAgent)) {
            brainStore.clearAllConversations();
        }

        refreshStatus();
        Toast.makeText(
            this,
            selectedAgent.isEmpty()
                ? "Brain salvato: agente predefinito Home Assistant"
                : "Brain salvato: " + selectedAgent,
            Toast.LENGTH_LONG
        ).show();
    }

    private void detectAgents() {
        Toast.makeText(
            this,
            "Cerco gli agenti conversazionali...",
            Toast.LENGTH_SHORT
        ).show();

        io.execute(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences(
                    HA_PREFS,
                    MODE_PRIVATE
                );
                String haUrl = trimSlash(
                    prefs.getString(KEY_HA_URL, "http://192.168.178.72:8123")
                );
                String token = clean(prefs.getString(KEY_HA_TOKEN, ""));
                if (token.isEmpty()) {
                    throw new IllegalStateException("Token Home Assistant assente");
                }

                URL endpoint = new URL(haUrl + "/api/states");
                HttpURLConnection connection =
                    (HttpURLConnection) endpoint.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer " + token
                );

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
                String body = readAll(stream);
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("HTTP " + code);
                }

                JSONArray states = new JSONArray(body);
                List<String> ids = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                labels.add("Agente predefinito Home Assistant");
                ids.add("");

                for (int index = 0; index < states.length(); index++) {
                    JSONObject state = states.optJSONObject(index);
                    if (state == null) {
                        continue;
                    }
                    String entityId = clean(state.optString("entity_id", ""));
                    if (!entityId.startsWith("conversation.")) {
                        continue;
                    }
                    JSONObject attributes = state.optJSONObject("attributes");
                    String friendlyName = attributes == null
                        ? ""
                        : clean(attributes.optString("friendly_name", ""));
                    ids.add(entityId);
                    labels.add(
                        friendlyName.isEmpty()
                            ? entityId
                            : friendlyName + "\n" + entityId
                    );
                }

                runOnUiThread(() -> showAgentDialog(ids, labels));
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(
                    this,
                    "Non riesco a leggere gli agenti di Home Assistant",
                    Toast.LENGTH_LONG
                ).show());
            }
        });
    }

    private void showAgentDialog(List<String> ids, List<String> labels) {
        String[] choices = labels.toArray(new String[0]);
        new AlertDialog.Builder(this)
            .setTitle("Scegli il cervello di AUREA")
            .setItems(choices, (dialog, which) -> {
                agentId.setText(ids.get(which));
                Toast.makeText(
                    this,
                    ids.get(which).isEmpty()
                        ? "Selezionato agente predefinito"
                        : "Selezionato " + ids.get(which),
                    Toast.LENGTH_LONG
                ).show();
            })
            .setNegativeButton("Annulla", null)
            .show();
    }

    private void confirmClearLog() {
        new AlertDialog.Builder(this)
            .setTitle("Cancellare il registro?")
            .setMessage(
                "Verranno eliminate le richieste e le risposte memorizzate localmente. "
                    + "Profili, voce, volto e preferenze non cambieranno."
            )
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Cancella", (dialog, which) -> {
                brainStore.clearDecisionLog();
                refreshStatus();
            })
            .show();
    }

    private void refreshStatus() {
        if (status == null || brainStore == null) {
            return;
        }
        String agent = brainStore.agentId();
        status.setText(
            "Stato: " + (brainStore.isEnabled() ? "attivo" : "disattivato")
                + "\nAgente: " + (agent.isEmpty() ? "predefinito Home Assistant" : agent)
                + "\nConversazione personale: "
                + (brainStore.hasActiveConversation(currentPerson) ? "attiva" : "vuota")
                + "\nDecisioni registrate: " + brainStore.decisionCount()
        );
    }

    private void denyAccess() {
        Toast.makeText(
            this,
            "AUREA Brain è disponibile dopo il riconoscimento personale",
            Toast.LENGTH_LONG
        ).show();
        finish();
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(16), dp(22), dp(16));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(12, 25, 37));
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), Color.rgb(45, 78, 101));
        card.setBackground(background);
        return card;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        return button;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams fullWidthWithBottom(int bottom) {
        LinearLayout.LayoutParams params = fullWidth();
        params.bottomMargin = bottom;
        return params;
    }

    private LinearLayout.LayoutParams fullWidthWithTop(int top) {
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = top;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimSlash(String value) {
        String result = clean(value);
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.post(() -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                }
            } else {
                decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
            }
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
