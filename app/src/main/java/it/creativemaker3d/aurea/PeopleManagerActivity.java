package it.creativemaker3d.aurea;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
 * Gestione locale dei profili AUREA.
 *
 * L'accesso è consentito soltanto dopo la verifica del volto e della voce
 * dell'amministratore Giuseppe. Fotografie e audio non vengono conservati.
 */
public final class PeopleManagerActivity extends Activity {
    private static final String FACE_PREFS = "aurea_face_profiles";
    private static final String FACE_KEY = "profiles";

    private LinearLayout profilesContainer;
    private VoiceProfileStore voiceStore;
    private IdentitySessionStore identityStore;
    private AdminAccessStore adminStore;
    private PersonPreferencesStore preferencesStore;
    private AureaLearningStore learningStore;
    private boolean interfaceReady;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        adminStore = new AdminAccessStore(this);
        if (!adminStore.hasValidGrant()) {
            startAdminVerification();
            return;
        }

        adminStore.touch();
        voiceStore = new VoiceProfileStore(this);
        identityStore = new IdentitySessionStore(this);
        preferencesStore = new PersonPreferencesStore(this);
        learningStore = new AureaLearningStore(this);
        buildInterface();
        interfaceReady = true;
        hideSystemUi();
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(20), dp(28), dp(20));
        root.setBackgroundColor(Color.rgb(2, 7, 13));

        TextView title = text("Gestione persone", 28, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, fullWidth());

        TextView subtitle = text(
            "Accesso amministratore verificato: Giuseppe",
            15,
            Color.rgb(124, 220, 255)
        );
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(5), 0, dp(3));
        root.addView(subtitle, fullWidth());

        TextView privacy = text(
            "Volti e voci restano salvati soltanto su questo tablet.",
            14,
            Color.rgb(176, 201, 220)
        );
        privacy.setGravity(Gravity.CENTER_HORIZONTAL);
        privacy.setPadding(0, 0, 0, dp(14));
        root.addView(privacy, fullWidth());

        LinearLayout topActions = new LinearLayout(this);
        topActions.setOrientation(LinearLayout.HORIZONTAL);

        Button addPerson = button("Aggiungi una nuova persona");
        addPerson.setOnClickListener(view -> startNewPersonEnrollment());
        topActions.addView(addPerson, weightedButton());

        Button preferences = button("Preferenze nomi e saluti");
        preferences.setOnClickListener(view -> openPeoplePreferences());
        topActions.addView(preferences, weightedButtonWithStart(dp(8)));

        root.addView(topActions, fullWidthWithBottom(dp(12)));

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

        Button close = button("Blocca e torna a Casa Tablet");
        close.setOnClickListener(view -> closeManager());
        root.addView(close, fullWidthWithTop(dp(12)));

        setContentView(root);
        refreshProfiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();

        if (!interfaceReady) {
            return;
        }
        if (!adminStore.hasValidGrant()) {
            Toast.makeText(
                this,
                "Sessione amministratore scaduta",
                Toast.LENGTH_LONG
            ).show();
            startAdminVerification();
            return;
        }

        adminStore.touch();
        if (profilesContainer != null) {
            refreshProfiles();
        }
    }

    private void refreshProfiles() {
        if (!ensureAdminAccess() || profilesContainer == null) {
            return;
        }

        profilesContainer.removeAllViews();
        List<String> names = loadFaceProfileNames();

        if (names.isEmpty()) {
            TextView empty = text(
                "Nessuna persona registrata. Premi “Aggiungi una nuova persona”.",
                18,
                Color.rgb(210, 225, 238)
            );
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(70), dp(20), dp(20));
            profilesContainer.addView(empty, fullWidth());
            return;
        }

        String trusted = identityStore.trustedPerson();
        for (String name : names) {
            profilesContainer.addView(
                createProfileCard(name, name.equalsIgnoreCase(trusted)),
                fullWidthWithBottom(dp(10))
            );
        }
    }

    private View createProfileCard(String name, boolean trusted) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(12, 25, 37));
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), Color.rgb(45, 78, 101));
        card.setBackground(background);

        boolean administrator = AdminAccessStore.ADMIN_NAME.equalsIgnoreCase(name);
        TextView person = text(
            administrator ? name + " · Amministratore" : name,
            22,
            Color.WHITE
        );
        card.addView(person, fullWidth());

        boolean hasVoice = voiceStore.hasProfile(name);
        String status = hasVoice
            ? "Volto registrato · Voce registrata"
            : "Volto registrato · Voce da registrare";
        if (trusted) {
            status += " · Profilo attivo";
        }

        PersonPreferencesStore.Profile preferences = preferencesStore.load(name);
        if (!preferences.spokenName.equals(name)) {
            status += " · Chiamato “" + preferences.spokenName + "”";
        }

        int learned = learningStore.count(name);
        if (learned > 0) {
            status += " · " + learned + " preferenze apprese";
        }

        TextView profileStatus = text(status, 14, Color.rgb(155, 205, 229));
        profileStatus.setPadding(0, dp(3), 0, dp(10));
        card.addView(profileStatus, fullWidth());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        Button face = button("Nuovo volto");
        face.setOnClickListener(view -> startFaceEnrollment(name));
        actions.addView(face, weightedButton());

        Button voice = button(hasVoice ? "Nuova voce" : "Registra voce");
        voice.setOnClickListener(view -> startVoiceEnrollment(name));
        actions.addView(voice, weightedButtonWithStart(dp(8)));

        Button delete = button(administrator ? "Protetto" : "Elimina");
        delete.setEnabled(!administrator);
        if (!administrator) {
            delete.setOnClickListener(view -> confirmDelete(name));
        }
        actions.addView(delete, weightedButtonWithStart(dp(8)));

        card.addView(actions, fullWidth());
        return card;
    }

    private void openPeoplePreferences() {
        if (!ensureAdminAccess()) {
            return;
        }
        startActivity(new Intent(this, PeoplePreferencesActivity.class));
    }

    private void startNewPersonEnrollment() {
        if (!ensureAdminAccess()) {
            return;
        }
        Intent intent = new Intent(this, FaceGateActivity.class);
        intent.putExtra("aurea_force_enrollment", true);
        intent.putExtra("aurea_return_to_people_manager", true);
        intent.putExtra("aurea_identity_overlay", true);
        startActivity(intent);
    }

    private void startFaceEnrollment(String name) {
        if (!ensureAdminAccess()) {
            return;
        }
        Intent intent = new Intent(this, FaceGateActivity.class);
        intent.putExtra("aurea_force_enrollment", true);
        intent.putExtra("aurea_enrollment_name", name);
        intent.putExtra("aurea_lock_enrollment_name", true);
        intent.putExtra("aurea_face_only", true);
        intent.putExtra("aurea_return_to_people_manager", true);
        intent.putExtra("aurea_identity_overlay", true);
        startActivity(intent);
    }

    private void startVoiceEnrollment(String name) {
        if (!ensureAdminAccess()) {
            return;
        }
        Intent intent = new Intent(this, VoiceGateActivity.class);
        intent.putExtra("aurea_recognized_person", name);
        intent.putExtra("aurea_force_voice_enrollment", true);
        intent.putExtra("aurea_return_to_people_manager", true);
        intent.putExtra("aurea_identity_overlay", true);
        startActivity(intent);
    }

    private void confirmDelete(String name) {
        if (!ensureAdminAccess()
                || AdminAccessStore.ADMIN_NAME.equalsIgnoreCase(name)) {
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Eliminare " + name + "?")
            .setMessage(
                "Verranno eliminate le firme locali di volto e voce, le preferenze personali "
                    + "e i ricordi appresi di " + name
                    + ". Gli altri profili e Home Assistant non cambieranno."
            )
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Elimina", (dialog, which) -> deletePerson(name))
            .show();
    }

    private void deletePerson(String name) {
        if (!ensureAdminAccess()
                || AdminAccessStore.ADMIN_NAME.equalsIgnoreCase(name)) {
            return;
        }
        removeFaceProfile(name);
        voiceStore.deleteProfile(name);
        preferencesStore.delete(name);
        learningStore.clear(name);
        new AureaBrainStore(this).clearConversation(name);
        if (name.equalsIgnoreCase(identityStore.trustedPerson())) {
            identityStore.clearTrust();
        }
        Toast.makeText(
            this,
            "Profilo eliminato: " + name,
            Toast.LENGTH_LONG
        ).show();
        refreshProfiles();
    }

    private boolean ensureAdminAccess() {
        if (adminStore != null && adminStore.hasValidGrant()) {
            adminStore.touch();
            return true;
        }
        Toast.makeText(
            this,
            "Accesso riservato a Giuseppe",
            Toast.LENGTH_LONG
        ).show();
        startAdminVerification();
        return false;
    }

    private void startAdminVerification() {
        if (adminStore == null) {
            adminStore = new AdminAccessStore(this);
        }
        adminStore.requestAccess();
        Intent face = new Intent(this, FaceGateActivity.class);
        face.putExtra("aurea_identity_overlay", true);
        startActivity(face);
        finish();
    }

    private void closeManager() {
        if (adminStore != null) {
            adminStore.revoke();
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        closeManager();
    }

    private List<String> loadFaceProfileNames() {
        ArrayList<String> result = new ArrayList<>();
        try {
            String raw = facePrefs().getString(FACE_KEY, "{}");
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

    private void removeFaceProfile(String name) {
        try {
            SharedPreferences prefs = facePrefs();
            String raw = prefs.getString(FACE_KEY, "{}");
            JSONObject root = new JSONObject(raw == null ? "{}" : raw);
            root.remove(name);
            prefs.edit().putString(FACE_KEY, root.toString()).apply();
        } catch (Exception error) {
            Toast.makeText(
                this,
                "Non è stato possibile eliminare il volto di " + name,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private SharedPreferences facePrefs() {
        return getSharedPreferences(FACE_PREFS, MODE_PRIVATE);
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
