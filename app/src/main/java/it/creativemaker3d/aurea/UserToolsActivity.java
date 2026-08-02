package it.creativemaker3d.aurea;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
import android.widget.TextView;
import android.widget.Toast;

/**
 * Area delle funzioni comuni di AUREA.
 *
 * È accessibile a qualunque persona registrata e riconosciuta. Le operazioni
 * amministrative sui profili non sono presenti qui e restano protette dal
 * pulsante persona+ riservato a Giuseppe.
 */
public final class UserToolsActivity extends Activity {
    private String currentPerson;
    private UpdateManager updateManager;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        currentPerson = RegisteredUserAccess.currentPerson(this);
        if (currentPerson.isEmpty()) {
            denyAccess();
            return;
        }

        updateManager = new UpdateManager(this);
        buildInterface();
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(32), dp(20), dp(32), dp(20));
        root.setBackgroundColor(Color.rgb(2, 7, 13));

        TextView title = text("Strumenti AUREA", 29, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidth());

        TextView identity = text(
            "Identità confermata: " + currentPerson,
            16,
            Color.rgb(124, 220, 255)
        );
        identity.setGravity(Gravity.CENTER);
        identity.setPadding(0, dp(5), 0, dp(16));
        root.addView(identity, fullWidth());

        AureaBrainStore brainStore = new AureaBrainStore(this);
        LinearLayout brainCard = card();
        TextView brainTitle = text("AUREA Brain 1.1", 23, Color.WHITE);
        brainCard.addView(brainTitle, fullWidth());

        String selectedAgent = brainStore.agentId();
        TextView brainDescription = text(
            "Conversazioni continue, memoria separata per persona e preferenze apprese. Agente: "
                + (selectedAgent.isEmpty()
                    ? "predefinito Home Assistant"
                    : selectedAgent)
                + ".",
            15,
            Color.rgb(190, 210, 225)
        );
        brainDescription.setPadding(0, dp(6), 0, dp(12));
        brainCard.addView(brainDescription, fullWidth());

        Button brain = button("Apri configurazione AUREA Brain");
        brain.setOnClickListener(view -> startActivity(
            new Intent(this, AureaBrainActivity.class)
        ));
        brainCard.addView(brain, fullWidthWithTop(dp(6)));
        root.addView(brainCard, fullWidthWithBottom(dp(14)));

        AureaInsightsStore insightsStore = new AureaInsightsStore(this);
        LinearLayout insightsCard = card();
        TextView insightsTitle = text("AUREA Insights 1.0", 23, Color.WHITE);
        insightsCard.addView(insightsTitle, fullWidth());

        TextView insightsDescription = text(
            "Osserva soltanto le entità scelte e riconosce possibili abitudini. "
                + "Routine proposte: " + insightsStore.suggestionCount()
                + ". Nessuna automazione viene creata da sola.",
            15,
            Color.rgb(190, 210, 225)
        );
        insightsDescription.setPadding(0, dp(6), 0, dp(12));
        insightsCard.addView(insightsDescription, fullWidth());

        Button insights = button("Apri osservazione abitudini");
        insights.setOnClickListener(view -> startActivity(
            new Intent(this, AureaInsightsActivity.class)
        ));
        insightsCard.addView(insights, fullWidthWithTop(dp(6)));
        root.addView(insightsCard, fullWidthWithBottom(dp(14)));

        LinearLayout actionsCard = card();
        TextView actionsTitle = text("Azioni rapide", 23, Color.WHITE);
        actionsCard.addView(actionsTitle, fullWidth());

        TextView actionsDescription = text(
            "Queste funzioni sono disponibili a tutte le persone registrate.",
            15,
            Color.rgb(190, 210, 225)
        );
        actionsDescription.setPadding(0, dp(6), 0, dp(12));
        actionsCard.addView(actionsDescription, fullWidth());

        Button update = button("Controlla aggiornamenti AUREA");
        update.setOnClickListener(view -> {
            if (updateManager != null) {
                updateManager.check(true);
            }
        });
        actionsCard.addView(update, fullWidthWithTop(dp(6)));

        Button reload = button("Ricarica Casa Tablet");
        reload.setOnClickListener(view -> reloadDashboard());
        actionsCard.addView(reload, fullWidthWithTop(dp(6)));

        Button lock = button("Blocca il profilo corrente");
        lock.setOnClickListener(view -> confirmLockProfile());
        actionsCard.addView(lock, fullWidthWithTop(dp(6)));

        root.addView(actionsCard, fullWidthWithBottom(dp(14)));

        LinearLayout backupCard = card();
        TextView backupTitle = text("Backup e ripristino", 23, Color.WHITE);
        backupCard.addView(backupTitle, fullWidth());

        TextView backupDescription = text(
            "Salva profili, firme vocali e preferenze confermate in un file protetto da password. "
                + "Le osservazioni temporanee Insights e il token Home Assistant restano esclusi.",
            15,
            Color.rgb(190, 210, 225)
        );
        backupDescription.setPadding(0, dp(6), 0, dp(12));
        backupCard.addView(backupDescription, fullWidth());

        Button backup = button("Apri backup e ripristino");
        backup.setOnClickListener(view -> startActivity(
            new Intent(this, BackupRestoreActivity.class)
        ));
        backupCard.addView(backup, fullWidthWithTop(dp(6)));
        root.addView(backupCard, fullWidthWithBottom(dp(14)));

        TextView policy = text(
            "Aggiunta, nuova registrazione ed eliminazione delle persone restano "
                + "riservate esclusivamente a Giuseppe tramite persona+.",
            14,
            Color.rgb(150, 172, 190)
        );
        policy.setGravity(Gravity.CENTER);
        policy.setPadding(dp(12), dp(4), dp(12), dp(14));
        root.addView(policy, fullWidth());

        Button close = button("Torna a Casa Tablet");
        close.setOnClickListener(view -> finish());
        root.addView(close, fullWidth());

        setContentView(root);
    }

    private void confirmLockProfile() {
        new AlertDialog.Builder(this)
            .setTitle("Bloccare il profilo?")
            .setMessage(
                "AUREA dimenticherà soltanto la sessione corrente. "
                    + "Volto, voce e preferenze resteranno registrati."
            )
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Blocca", (dialog, which) -> {
                new IdentitySessionStore(this).clearTrust();
                new AdminAccessStore(this).revoke();
                Toast.makeText(
                    this,
                    "Profilo bloccato. AUREA richiederà nuovamente il riconoscimento.",
                    Toast.LENGTH_LONG
                ).show();
                reloadDashboard();
            })
            .show();
    }

    private void reloadDashboard() {
        Intent launcher = new Intent(this, HomeLauncherActivity.class);
        launcher.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION
        );
        startActivity(launcher);
        finishAffinity();
        overridePendingTransition(0, 0);
    }

    private void denyAccess() {
        Toast.makeText(
            this,
            "Funzione disponibile dopo il riconoscimento di una persona registrata",
            Toast.LENGTH_LONG
        ).show();
        finish();
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
