package it.creativemaker3d.aurea;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Preferenze personali dei profili AUREA.
 *
 * L'accesso eredita la sessione amministratore verificata di Giuseppe. Questa
 * schermata non legge né modifica firme biometriche.
 */
public final class PeoplePreferencesActivity extends Activity {
    private static final String FACE_PREFS = "aurea_face_profiles";
    private static final String FACE_KEY = "profiles";
    private static final int MAX_CUSTOM_GREETING = 180;

    private AdminAccessStore adminStore;
    private PersonPreferencesStore preferencesStore;
    private LinearLayout profilesContainer;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        adminStore = new AdminAccessStore(this);
        if (!adminStore.hasValidGrant()) {
            Toast.makeText(
                this,
                "Accesso riservato a Giuseppe",
                Toast.LENGTH_LONG
            ).show();
            finish();
            return;
        }

        adminStore.touch();
        preferencesStore = new PersonPreferencesStore(this);
        buildInterface();
        hideSystemUi();
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(20), dp(28), dp(20));
        root.setBackgroundColor(Color.rgb(2, 7, 13));

        TextView title = text("Preferenze persone", 28, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, fullWidth());

        TextView subtitle = text(
            "Scegli come AUREA deve chiamare e salutare ogni persona.",
            15,
            Color.rgb(176, 201, 220)
        );
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(5), 0, dp(14));
        root.addView(subtitle, fullWidth());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        profilesContainer = new LinearLayout(this);
        profilesContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(profilesContainer, fullWidth());
        root.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ));

        Button close = button("Torna a Gestione persone");
        close.setOnClickListener(view -> finish());
        root.addView(close, fullWidthWithTop(dp(12)));

        setContentView(root);
        refreshProfiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (!ensureAdminAccess()) {
            return;
        }
        refreshProfiles();
    }

    private void refreshProfiles() {
        if (profilesContainer == null || !ensureAdminAccess()) {
            return;
        }

        profilesContainer.removeAllViews();
        List<String> names = loadFaceProfileNames();
        if (names.isEmpty()) {
            TextView empty = text(
                "Nessuna persona registrata.",
                18,
                Color.rgb(210, 225, 238)
            );
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(70), dp(20), dp(20));
            profilesContainer.addView(empty, fullWidth());
            return;
        }

        for (String name : names) {
            profilesContainer.addView(
                createProfileCard(name),
                fullWidthWithBottom(dp(10))
            );
        }
    }

    private View createProfileCard(String name) {
        PersonPreferencesStore.Profile profile = preferencesStore.load(name);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(12, 25, 37));
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), Color.rgb(45, 78, 101));
        card.setBackground(background);

        TextView person = text(name, 22, Color.WHITE);
        card.addView(person, fullWidth());

        String status = "Nome pronunciato: " + profile.spokenName;
        status += profile.greetingEnabled
            ? " · Saluto attivo"
            : " · Saluto disattivato";
        TextView summary = text(status, 14, Color.rgb(155, 205, 229));
        summary.setPadding(0, dp(3), 0, dp(6));
        card.addView(summary, fullWidth());

        String previewText = preferencesStore.buildGreeting(name);
        if (previewText.isEmpty()) {
            previewText = "Anteprima: nessun saluto vocale";
        } else {
            previewText = "Anteprima: “" + previewText + "”";
        }
        TextView preview = text(previewText, 14, Color.rgb(205, 218, 230));
        preview.setPadding(0, 0, 0, dp(10));
        card.addView(preview, fullWidth());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button edit = button("Modifica preferenze");
        edit.setOnClickListener(view -> editPreferences(name));
        actions.addView(edit, weightedButton());

        Button reset = button("Ripristina");
        reset.setOnClickListener(view -> confirmReset(name));
        actions.addView(reset, weightedButtonWithStart(dp(8)));

        card.addView(actions, fullWidth());
        return card;
    }

    private void editPreferences(String name) {
        if (!ensureAdminAccess()) {
            return;
        }

        PersonPreferencesStore.Profile current = preferencesStore.load(name);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);

        TextView spokenLabel = text("Nome che AUREA deve pronunciare", 14, Color.DKGRAY);
        form.addView(spokenLabel, fullWidth());

        EditText spokenName = new EditText(this);
        spokenName.setSingleLine(true);
        spokenName.setText(current.spokenName);
        spokenName.setHint(name);
        form.addView(spokenName, fullWidth());

        CheckBox greetingEnabled = new CheckBox(this);
        greetingEnabled.setText("Pronuncia un saluto dopo il riconoscimento");
        greetingEnabled.setChecked(current.greetingEnabled);
        form.addView(greetingEnabled, fullWidthWithTop(dp(8)));

        CheckBox timeGreeting = new CheckBox(this);
        timeGreeting.setText("Adatta automaticamente il saluto all’orario");
        timeGreeting.setChecked(current.timeGreeting);
        form.addView(timeGreeting, fullWidth());

        TextView customLabel = text(
            "Frase personalizzata facoltativa",
            14,
            Color.DKGRAY
        );
        customLabel.setPadding(0, dp(8), 0, 0);
        form.addView(customLabel, fullWidth());

        EditText customGreeting = new EditText(this);
        customGreeting.setText(current.customGreeting);
        customGreeting.setHint("Per esempio: Bentornato {nome}, la casa è pronta");
        customGreeting.setMinLines(2);
        customGreeting.setMaxLines(4);
        customGreeting.setGravity(Gravity.TOP | Gravity.START);
        customGreeting.setInputType(
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
        );
        form.addView(customGreeting, fullWidth());

        TextView note = text(
            "Usa {nome} nella frase per inserire il nome pronunciato. "
                + "La frase personalizzata ha priorità sul saluto automatico.",
            12,
            Color.DKGRAY
        );
        note.setPadding(0, dp(6), 0, 0);
        form.addView(note, fullWidth());

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Preferenze di " + name)
            .setView(form)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva", null)
            .create();

        dialog.setOnShowListener(ignored -> dialog
            .getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(view -> {
                String spoken = spokenName.getText().toString().trim();
                String custom = customGreeting.getText().toString().trim();
                if (spoken.isEmpty()) {
                    spokenName.setError("Inserisci il nome da pronunciare");
                    return;
                }
                if (custom.length() > MAX_CUSTOM_GREETING) {
                    customGreeting.setError(
                        "Massimo " + MAX_CUSTOM_GREETING + " caratteri"
                    );
                    return;
                }

                preferencesStore.save(
                    name,
                    new PersonPreferencesStore.Profile(
                        spoken,
                        greetingEnabled.isChecked(),
                        timeGreeting.isChecked(),
                        custom
                    )
                );
                adminStore.touch();
                Toast.makeText(
                    this,
                    "Preferenze salvate per " + name,
                    Toast.LENGTH_SHORT
                ).show();
                dialog.dismiss();
                refreshProfiles();
            }));
        dialog.show();
    }

    private void confirmReset(String name) {
        if (!ensureAdminAccess()) {
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Ripristinare " + name + "?")
            .setMessage(
                "AUREA tornerà a usare il nome originale e il saluto automatico per orario."
            )
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Ripristina", (dialog, which) -> {
                preferencesStore.delete(name);
                adminStore.touch();
                refreshProfiles();
            })
            .show();
    }

    private boolean ensureAdminAccess() {
        if (adminStore != null && adminStore.hasValidGrant()) {
            adminStore.touch();
            return true;
        }
        Toast.makeText(
            this,
            "Sessione amministratore scaduta",
            Toast.LENGTH_LONG
        ).show();
        finish();
        return false;
    }

    private List<String> loadFaceProfileNames() {
        ArrayList<String> result = new ArrayList<>();
        try {
            SharedPreferences prefs = getSharedPreferences(FACE_PREFS, MODE_PRIVATE);
            String raw = prefs.getString(FACE_KEY, "{}");
            JSONObject root = new JSONObject(raw == null ? "{}" : raw);
            JSONArray names = root.names();
            if (names != null) {
                for (int index = 0; index < names.length(); index++) {
                    String name = names.optString(index, "").trim();
                    if (!name.isEmpty()) {
                        result.add(name);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    @Override
    public void onBackPressed() {
        finish();
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
}
