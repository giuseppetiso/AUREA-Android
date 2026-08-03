package it.creativemaker3d.aurea;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
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

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Installazione amministrativa delle bozze Routine Studio.
 *
 * Ogni operazione richiede Giuseppe come profilo attivo, un nuovo controllo
 * Routine Guard senza avvisi e due conferme esplicite. Le automazioni vengono
 * create disattivate e non viene mai sovrascritta una configurazione esistente.
 */
public final class AureaRoutineInstallerActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private String currentPerson;
    private AureaRoutineDraftStore draftStore;
    private AureaRoutineInstallationStore installationStore;
    private AureaRoutineInstallerClient installerClient;
    private TextView status;
    private LinearLayout draftsContainer;
    private boolean operationRunning;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        currentPerson = RegisteredUserAccess.currentPerson(this);
        if (!isAdministrator()) {
            denyAccess();
            return;
        }

        draftStore = new AureaRoutineDraftStore(this);
        installationStore = new AureaRoutineInstallationStore(this);
        installerClient = new AureaRoutineInstallerClient(this);
        buildInterface();
        refreshDrafts();
        hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        currentPerson = RegisteredUserAccess.currentPerson(this);
        if (!isAdministrator()) {
            denyAccess();
            return;
        }
        if (draftsContainer != null && !operationRunning) {
            refreshDrafts();
        }
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(2, 7, 13));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(30), dp(18), dp(30), dp(22));
        root.setBackgroundColor(Color.rgb(2, 7, 13));
        scroll.addView(root, fullWidth());

        TextView title = text("AUREA Routine Installer 1.0", 29, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidth());

        TextView identity = text(
            "Amministratore riconosciuto: " + currentPerson,
            16,
            Color.rgb(124, 220, 255)
        );
        identity.setGravity(Gravity.CENTER);
        identity.setPadding(0, dp(5), 0, dp(12));
        root.addView(identity, fullWidth());

        LinearLayout policyCard = card();
        policyCard.addView(text("Protezione obbligatoria", 22, Color.WHITE), fullWidth());
        TextView policy = text(
            "L'installazione è consentita soltanto dopo un nuovo Routine Guard SUPERATA e "
                + "due conferme di Giuseppe. AUREA non sovrascrive automazioni esistenti e "
                + "crea ogni routine disattivata: l'attivazione resta manuale in Home Assistant.",
            15,
            Color.rgb(190, 210, 225)
        );
        policy.setPadding(0, dp(6), 0, 0);
        policyCard.addView(policy, fullWidth());
        root.addView(policyCard, fullWidthWithBottom(dp(14)));

        LinearLayout summaryCard = card();
        summaryCard.addView(text("Stato Installer", 22, Color.WHITE), fullWidth());
        status = text("", 15, Color.rgb(171, 205, 224));
        status.setPadding(0, dp(7), 0, 0);
        summaryCard.addView(status, fullWidth());
        root.addView(summaryCard, fullWidthWithBottom(dp(14)));

        draftsContainer = new LinearLayout(this);
        draftsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(draftsContainer, fullWidthWithBottom(dp(14)));

        Button studio = button("Torna a Routine Studio");
        studio.setOnClickListener(view -> startActivity(
            new Intent(this, AureaRoutineStudioActivity.class)
        ));
        root.addView(studio, fullWidthWithBottom(dp(8)));

        Button close = button("Torna agli strumenti AUREA");
        close.setOnClickListener(view -> finish());
        root.addView(close, fullWidth());

        setContentView(scroll);
    }

    private void refreshDrafts() {
        if (draftStore == null || installationStore == null
                || draftsContainer == null || status == null) {
            return;
        }

        List<AureaRoutineDraftStore.Draft> drafts =
            AureaRoutineDraftAccess.listForPerson(draftStore, currentPerson);
        status.setText(
            "Bozze personali disponibili: " + drafts.size()
                + " · installazioni registrate: "
                + installationStore.countForPerson(currentPerson)
                + "\nNessuna installazione parte senza un controllo Guard eseguito adesso."
        );
        draftsContainer.removeAllViews();

        if (drafts.isEmpty()) {
            LinearLayout empty = card();
            TextView message = text(
                "Nessuna bozza personale. Crea prima una bozza in Routine Studio.",
                16,
                Color.rgb(190, 210, 225)
            );
            message.setGravity(Gravity.CENTER);
            empty.addView(message, fullWidth());
            draftsContainer.addView(empty, fullWidth());
            return;
        }

        for (AureaRoutineDraftStore.Draft draft : drafts) {
            draftsContainer.addView(
                draftCard(draft),
                fullWidthWithBottom(dp(10))
            );
        }
    }

    private View draftCard(AureaRoutineDraftStore.Draft draft) {
        LinearLayout card = card();
        card.addView(text(draft.alias, 20, Color.WHITE), fullWidth());

        TextView summary = text(
            draftStore.summary(draft),
            15,
            Color.rgb(155, 205, 229)
        );
        summary.setPadding(0, dp(4), 0, dp(3));
        card.addView(summary, fullWidth());

        TextView entity = text(
            "Entità: " + draft.entityId,
            13,
            Color.rgb(150, 172, 190)
        );
        entity.setPadding(0, 0, 0, dp(5));
        card.addView(entity, fullWidth());

        AureaRoutineInstallationStore.Record record =
            installationStore.findByDraft(draft.id);
        TextView installed = text(
            installationLabel(record),
            14,
            record == null
                ? Color.rgb(150, 172, 190)
                : Color.rgb(120, 230, 170)
        );
        installed.setPadding(0, 0, 0, dp(9));
        card.addView(installed, fullWidth());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button preview = button("Mostra configurazione");
        preview.setOnClickListener(view -> showPreview(draft));
        actions.addView(preview, weighted());

        Button install = button("Controlla e installa");
        install.setOnClickListener(view -> runGuardAndPrepare(draft));
        actions.addView(install, weightedWithStart(dp(7)));
        card.addView(actions, fullWidth());
        return card;
    }

    private String installationLabel(AureaRoutineInstallationStore.Record record) {
        if (record == null) {
            return "Routine Installer: non ancora installata";
        }
        String when = record.installedAt <= 0L
            ? "data non disponibile"
            : DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT
            ).format(new Date(record.installedAt));
        return "Installata disattivata · " + record.automationId + " · " + when;
    }

    private void showPreview(AureaRoutineDraftStore.Draft draft) {
        try {
            String config = installerClient.previewDisabled(draft).toString(2);
            new AlertDialog.Builder(this)
                .setTitle("Configurazione che verrà installata")
                .setMessage(
                    "ID: " + installerClient.automationId(draft)
                        + "\nStato iniziale: DISATTIVATA\n\n" + config
                )
                .setPositiveButton("Chiudi", null)
                .show();
        } catch (Exception error) {
            Toast.makeText(
                this,
                "Anteprima non disponibile: " + safeMessage(error),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void runGuardAndPrepare(AureaRoutineDraftStore.Draft draft) {
        if (operationRunning || !ensureAdministrator()) {
            return;
        }
        operationRunning = true;
        status.setText(
            "Routine Guard sta controllando nuovamente “" + draft.alias + "”..."
        );
        Toast.makeText(
            this,
            "Controllo di sicurezza in corso",
            Toast.LENGTH_SHORT
        ).show();

        List<AureaRoutineDraftStore.Draft> profileDrafts =
            AureaRoutineDraftAccess.listForPerson(draftStore, currentPerson);
        io.execute(() -> {
            AureaRoutineGuard.Report report = new AureaRoutineGuard(this).inspect(
                draft,
                profileDrafts
            );
            runOnUiThread(() -> {
                operationRunning = false;
                handleGuardResult(draft, report);
            });
        });
    }

    private void handleGuardResult(
            AureaRoutineDraftStore.Draft draft,
            AureaRoutineGuard.Report report) {
        status.setText(
            "Ultimo controllo: " + draft.alias + " · Routine Guard "
                + report.statusLabel()
        );

        if (report.isBlocked()) {
            new AlertDialog.Builder(this)
                .setTitle("Installazione bloccata da Routine Guard")
                .setMessage(report.formatted())
                .setPositiveButton("Chiudi", null)
                .show();
            return;
        }

        if (report.hasWarnings()) {
            new AlertDialog.Builder(this)
                .setTitle("Installazione non consentita con avvisi")
                .setMessage(
                    report.formatted()
                        + "\n\nRoutine Installer richiede un esito completamente SUPERATA. "
                        + "Correggi la bozza oppure usa la copia manuale di Routine Studio."
                )
                .setPositiveButton("Chiudi", null)
                .show();
            return;
        }

        showFirstConfirmation(draft, report);
    }

    private void showFirstConfirmation(
            AureaRoutineDraftStore.Draft draft,
            AureaRoutineGuard.Report report) {
        new AlertDialog.Builder(this)
            .setTitle("Conferma 1 di 2 · Routine Guard SUPERATA")
            .setMessage(
                report.formatted()
                    + "\n\nAutomazione: " + draft.alias
                    + "\nEntità: " + draft.entityId
                    + "\nOrario: " + draft.time
                    + "\nGiorni: " + draftStore.weekdayLabel(draft.weekdays)
                    + "\n\nLa routine verrà installata DISATTIVATA."
            )
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Ho controllato · continua", (dialog, which) ->
                showFinalConfirmation(draft)
            )
            .show();
    }

    private void showFinalConfirmation(AureaRoutineDraftStore.Draft draft) {
        if (!ensureAdministrator()) {
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Conferma 2 di 2 · Giuseppe")
            .setMessage(
                "Stai autorizzando la scrittura di una nuova automazione in Home Assistant."
                    + "\n\nID: " + installerClient.automationId(draft)
                    + "\nNome: " + draft.alias
                    + "\nEntità: " + draft.entityId
                    + "\n\nAUREA non sovrascriverà un'automazione esistente. "
                    + "Dopo l'installazione dovrai controllarla e attivarla manualmente in Home Assistant."
            )
            .setNegativeButton("Non installare", null)
            .setPositiveButton("Installa disattivata", (dialog, which) ->
                installDraft(draft)
            )
            .show();
    }

    private void installDraft(AureaRoutineDraftStore.Draft draft) {
        if (operationRunning || !ensureAdministrator()) {
            return;
        }
        operationRunning = true;
        status.setText("Installazione controllata in Home Assistant...");

        io.execute(() -> {
            AureaRoutineInstallerClient.Result result =
                installerClient.installDisabled(draft);
            if (result.success) {
                installationStore.record(draft, result.automationId, currentPerson);
            }
            runOnUiThread(() -> {
                operationRunning = false;
                showInstallResult(draft, result);
                refreshDrafts();
            });
        });
    }

    private void showInstallResult(
            AureaRoutineDraftStore.Draft draft,
            AureaRoutineInstallerClient.Result result) {
        if (result.success) {
            status.setText(
                "Installazione completata: " + result.automationId
                    + " · automazione disattivata"
            );
            new AlertDialog.Builder(this)
                .setTitle("Installazione completata")
                .setMessage(
                    result.message
                        + "\n\nControlla “" + draft.alias
                        + "” nella sezione Automazioni di Home Assistant prima di attivarla."
                )
                .setPositiveButton("Chiudi", null)
                .show();
            return;
        }

        status.setText(
            result.alreadyExists
                ? "Installazione evitata: automazione già presente"
                : "Installazione non riuscita"
        );
        new AlertDialog.Builder(this)
            .setTitle(
                result.alreadyExists
                    ? "Automazione già esistente"
                    : "Installazione non riuscita"
            )
            .setMessage(result.message)
            .setPositiveButton("Chiudi", null)
            .show();
    }

    private boolean ensureAdministrator() {
        currentPerson = RegisteredUserAccess.currentPerson(this);
        if (isAdministrator()) {
            return true;
        }
        denyAccess();
        return false;
    }

    private boolean isAdministrator() {
        return AdminAccessStore.ADMIN_NAME.equalsIgnoreCase(clean(currentPerson));
    }

    private void denyAccess() {
        Toast.makeText(
            this,
            "Routine Installer è riservato al profilo amministratore Giuseppe",
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

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        );
    }

    private LinearLayout.LayoutParams weightedWithStart(int start) {
        LinearLayout.LayoutParams params = weighted();
        params.leftMargin = start;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String safeMessage(Throwable error) {
        if (error == null) {
            return "errore non specificato";
        }
        String message = clean(error.getMessage());
        return message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
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
