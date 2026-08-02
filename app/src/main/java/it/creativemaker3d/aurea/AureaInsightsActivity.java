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
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pannello di osservazione controllata delle abitudini domestiche.
 *
 * Ogni entità deve essere scelta esplicitamente. Le proposte possono essere
 * salvate come preferenze personali oppure ignorate, ma non vengono mai
 * trasformate automaticamente in automazioni Home Assistant.
 */
public final class AureaInsightsActivity extends Activity {
    private static final String HA_PREFS = "aurea";
    private static final String KEY_HA_URL = "ha_url";
    private static final String KEY_HA_TOKEN = "ha_token";

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private AureaInsightsStore store;
    private String currentPerson;
    private CheckBox enabled;
    private TextView status;
    private LinearLayout suggestionsContainer;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        currentPerson = RegisteredUserAccess.currentPerson(this);
        if (currentPerson.isEmpty()) {
            denyAccess();
            return;
        }

        store = new AureaInsightsStore(this);
        buildInterface();
        refreshAll();
        hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        currentPerson = RegisteredUserAccess.currentPerson(this);
        if (currentPerson.isEmpty()) {
            denyAccess();
            return;
        }
        refreshAll();
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(2, 7, 13));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(30), dp(18), dp(30), dp(18));
        scroll.addView(root, fullWidth());

        TextView title = text("AUREA Insights 1.0", 29, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidth());

        TextView identity = text(
            "Osservazione controllata · profilo attivo: " + currentPerson,
            15,
            Color.rgb(124, 220, 255)
        );
        identity.setGravity(Gravity.CENTER);
        identity.setPadding(0, dp(5), 0, dp(14));
        root.addView(identity, fullWidth());

        LinearLayout configuration = card();
        configuration.addView(text("Cosa può osservare AUREA", 22, Color.WHITE));

        TextView explanation = text(
            "Scegli tu luci, interruttori, clima, TV o persone da osservare. "
                + "AUREA registra soltanto i cambi di stato e l'orario. Una possibile routine "
                + "compare dopo almeno tre giorni distinti con un comportamento simile.",
            15,
            Color.rgb(190, 210, 225)
        );
        explanation.setPadding(0, dp(6), 0, dp(10));
        configuration.addView(explanation, fullWidth());

        enabled = new CheckBox(this);
        enabled.setText("Attiva osservazione locale delle abitudini");
        enabled.setTextColor(Color.WHITE);
        enabled.setChecked(store.isEnabled());
        enabled.setOnCheckedChangeListener((button, checked) -> {
            store.setEnabled(checked);
            refreshStatus();
        });
        configuration.addView(enabled, fullWidth());

        Button choose = button("Scegli dispositivi e persone da osservare");
        choose.setOnClickListener(view -> loadEntityChoices());
        configuration.addView(choose, fullWidthWithTop(dp(7)));

        Button pollNow = button("Esegui un controllo adesso");
        pollNow.setOnClickListener(view -> pollNow());
        configuration.addView(pollNow, fullWidthWithTop(dp(6)));

        status = text("", 15, Color.rgb(171, 205, 224));
        status.setPadding(0, dp(10), 0, 0);
        configuration.addView(status, fullWidth());

        root.addView(configuration, fullWidthWithBottom(dp(14)));

        LinearLayout suggestionsCard = card();
        suggestionsCard.addView(text("Routine possibili", 22, Color.WHITE));
        TextView suggestionsHelp = text(
            "Salvare una proposta la trasforma soltanto in una preferenza personale per Gemini. "
                + "Non viene creata alcuna automazione.",
            14,
            Color.rgb(170, 193, 210)
        );
        suggestionsHelp.setPadding(0, dp(5), 0, dp(9));
        suggestionsCard.addView(suggestionsHelp, fullWidth());

        suggestionsContainer = new LinearLayout(this);
        suggestionsContainer.setOrientation(LinearLayout.VERTICAL);
        suggestionsCard.addView(suggestionsContainer, fullWidth());
        root.addView(suggestionsCard, fullWidthWithBottom(dp(14)));

        Button clear = button("Cancella osservazioni e suggerimenti");
        clear.setOnClickListener(view -> confirmClear());
        root.addView(clear, fullWidthWithBottom(dp(8)));

        Button close = button("Torna ad AUREA Brain");
        close.setOnClickListener(view -> finish());
        root.addView(close, fullWidth());

        setContentView(scroll);
    }

    private void refreshAll() {
        if (store == null) {
            return;
        }
        if (enabled != null) {
            enabled.setChecked(store.isEnabled());
        }
        refreshStatus();
        refreshSuggestions();
    }

    private void refreshStatus() {
        if (status == null || store == null) {
            return;
        }
        status.setText(
            "Stato: " + (store.isEnabled() ? "attivo" : "disattivato")
                + "\nEntità selezionate: " + store.selectedEntities().size()
                + "\nCambi di stato osservati: " + store.observationCount()
                + "\nRoutine proposte: " + store.suggestionCount()
        );
    }

    private void refreshSuggestions() {
        if (suggestionsContainer == null || store == null) {
            return;
        }
        suggestionsContainer.removeAllViews();
        List<AureaInsightsStore.Suggestion> suggestions = store.suggestions();
        if (suggestions.isEmpty()) {
            TextView empty = text(
                "Nessuna routine rilevata. Dopo la selezione servono almeno tre giorni distinti "
                    + "con cambi di stato simili.",
                16,
                Color.rgb(188, 209, 224)
            );
            empty.setPadding(dp(4), dp(12), dp(4), dp(12));
            suggestionsContainer.addView(empty, fullWidth());
            return;
        }

        for (AureaInsightsStore.Suggestion suggestion : suggestions) {
            suggestionsContainer.addView(
                suggestionCard(suggestion),
                fullWidthWithBottom(dp(9))
            );
        }
    }

    private View suggestionCard(AureaInsightsStore.Suggestion suggestion) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(12), dp(15), dp(12));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(20, 37, 51));
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), Color.rgb(55, 91, 116));
        card.setBackground(background);

        TextView description = text(suggestion.description, 17, Color.WHITE);
        card.addView(description, fullWidth());

        String lastSeen = suggestion.lastTime > 0L
            ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(suggestion.lastTime))
            : "non disponibile";
        TextView details = text(
            "Ultima osservazione: " + lastSeen
                + " · giorni distinti: " + suggestion.distinctDays,
            13,
            Color.rgb(150, 190, 215)
        );
        details.setPadding(0, dp(4), 0, dp(8));
        card.addView(details, fullWidth());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button save = button("Salva come preferenza");
        save.setOnClickListener(view -> confirmAccept(suggestion));
        actions.addView(save, weightedButton());

        Button ignore = button("Ignora");
        ignore.setOnClickListener(view -> {
            store.dismissSuggestion(suggestion.id);
            refreshAll();
        });
        actions.addView(ignore, weightedButtonWithStart(dp(7)));

        card.addView(actions, fullWidth());
        return card;
    }

    private void confirmAccept(AureaInsightsStore.Suggestion suggestion) {
        new AlertDialog.Builder(this)
            .setTitle("Salvare questa preferenza?")
            .setMessage(
                suggestion.memoryText
                    + "\n\nVerrà aggiunta soltanto alla memoria personale di "
                    + currentPerson + "."
            )
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva", (dialog, which) -> {
                boolean saved = store.acceptSuggestion(suggestion.id, currentPerson);
                Toast.makeText(
                    this,
                    saved
                        ? "Preferenza salvata per " + currentPerson
                        : "La preferenza era già presente",
                    Toast.LENGTH_LONG
                ).show();
                refreshAll();
            })
            .show();
    }

    private void loadEntityChoices() {
        Toast.makeText(this, "Leggo le entità di Home Assistant...", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                JSONArray states = readStates();
                ArrayList<EntityChoice> choices = new ArrayList<>();
                for (int index = 0; index < states.length(); index++) {
                    JSONObject item = states.optJSONObject(index);
                    if (item == null) {
                        continue;
                    }
                    String entityId = clean(item.optString("entity_id", ""));
                    if (!isSupportedEntity(entityId)) {
                        continue;
                    }
                    JSONObject attributes = item.optJSONObject("attributes");
                    String name = attributes == null
                        ? entityId
                        : clean(attributes.optString("friendly_name", entityId));
                    if (name.isEmpty()) {
                        name = entityId;
                    }
                    choices.add(new EntityChoice(entityId, name));
                }
                choices.sort(Comparator.comparing(
                    choice -> choice.label.toLowerCase(java.util.Locale.ROOT)
                ));
                runOnUiThread(() -> showEntityDialog(choices));
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(
                    this,
                    "Non riesco a leggere le entità di Home Assistant",
                    Toast.LENGTH_LONG
                ).show());
            }
        });
    }

    private void showEntityDialog(List<EntityChoice> choices) {
        if (choices.isEmpty()) {
            Toast.makeText(this, "Nessuna entità compatibile trovata", Toast.LENGTH_LONG).show();
            return;
        }

        Set<String> selected = store.selectedEntities();
        HashSet<String> pending = new HashSet<>(selected);
        String[] labels = new String[choices.size()];
        boolean[] checked = new boolean[choices.size()];
        for (int index = 0; index < choices.size(); index++) {
            EntityChoice choice = choices.get(index);
            labels[index] = choice.label + "\n" + choice.entityId;
            checked[index] = selected.contains(choice.entityId);
        }

        new AlertDialog.Builder(this)
            .setTitle("Entità da osservare")
            .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                String entityId = choices.get(which).entityId;
                if (isChecked) {
                    pending.add(entityId);
                } else {
                    pending.remove(entityId);
                }
            })
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva selezione", (dialog, which) -> {
                store.setSelectedEntities(pending);
                refreshAll();
                Toast.makeText(
                    this,
                    "Entità selezionate: " + pending.size(),
                    Toast.LENGTH_LONG
                ).show();
            })
            .show();
    }

    private void pollNow() {
        if (!store.isEnabled()) {
            Toast.makeText(this, "Attiva prima l'osservazione", Toast.LENGTH_LONG).show();
            return;
        }
        if (store.selectedEntities().isEmpty()) {
            Toast.makeText(this, "Seleziona prima almeno un'entità", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "Controllo degli stati in corso...", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences(HA_PREFS, MODE_PRIVATE);
                AureaInsightsObserver observer = new AureaInsightsObserver(
                    this,
                    prefs.getString(KEY_HA_URL, "http://192.168.178.72:8123"),
                    prefs.getString(KEY_HA_TOKEN, "")
                );
                AureaInsightsStore.IngestResult result = observer.poll(currentPerson);
                runOnUiThread(() -> {
                    refreshAll();
                    Toast.makeText(
                        this,
                        result.changes == 0
                            ? "Stati acquisiti: nessun nuovo cambiamento"
                            : "Cambiamenti registrati: " + result.changes,
                        Toast.LENGTH_LONG
                    ).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(
                    this,
                    "Controllo Home Assistant non riuscito",
                    Toast.LENGTH_LONG
                ).show());
            }
        });
    }

    private JSONArray readStates() throws Exception {
        SharedPreferences prefs = getSharedPreferences(HA_PREFS, MODE_PRIVATE);
        String haUrl = trimSlash(
            prefs.getString(KEY_HA_URL, "http://192.168.178.72:8123")
        );
        String token = clean(prefs.getString(KEY_HA_TOKEN, ""));
        if (token.isEmpty()) {
            throw new IllegalStateException("Token Home Assistant assente");
        }

        URL endpoint = new URL(haUrl + "/api/states");
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Authorization", "Bearer " + token);

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
            ? connection.getInputStream()
            : connection.getErrorStream();
        String body = readAll(stream);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code);
        }
        return new JSONArray(body);
    }

    private boolean isSupportedEntity(String entityId) {
        int separator = entityId.indexOf('.');
        if (separator <= 0) {
            return false;
        }
        String domain = entityId.substring(0, separator);
        return domain.equals("light")
            || domain.equals("switch")
            || domain.equals("climate")
            || domain.equals("media_player")
            || domain.equals("fan")
            || domain.equals("person")
            || domain.equals("input_boolean");
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
            .setTitle("Cancellare le osservazioni?")
            .setMessage(
                "Verranno cancellati cambi di stato, suggerimenti e routine ignorate. "
                    + "La selezione delle entità e le preferenze già confermate resteranno intatte."
            )
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Cancella", (dialog, which) -> {
                store.clearHistory();
                refreshAll();
            })
            .show();
    }

    private void denyAccess() {
        Toast.makeText(
            this,
            "AUREA Insights è disponibile dopo il riconoscimento personale",
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
        card.setPadding(dp(20), dp(15), dp(20), dp(15));
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
        button.setTextSize(14);
        return button;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams fullWidthWithTop(int top) {
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = top;
        return params;
    }

    private LinearLayout.LayoutParams fullWidthWithBottom(int bottom) {
        LinearLayout.LayoutParams params = fullWidth();
        params.bottomMargin = bottom;
        return params;
    }

    private LinearLayout.LayoutParams weightedButton() {
        return new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        );
    }

    private LinearLayout.LayoutParams weightedButtonWithStart(int start) {
        LinearLayout.LayoutParams params = weightedButton();
        params.setMarginStart(start);
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

    private static final class EntityChoice {
        final String entityId;
        final String label;

        EntityChoice(String entityId, String label) {
            this.entityId = entityId;
            this.label = label;
        }
    }
}
