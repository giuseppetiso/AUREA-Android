package it.creativemaker3d.aurea;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gestisce bozze di automazione generate dalle proposte AUREA Insights.
 *
 * Routine Guard controlla ogni copia YAML tramite lettura in sola lettura di
 * Home Assistant, validazione locale e ricerca di conflitti tra bozze.
 */
public final class AureaRoutineStudioActivity extends Activity {
    static final String EXTRA_SUGGESTION_ID = "aurea_routine_suggestion_id";

    private final ExecutorService guardIo = Executors.newSingleThreadExecutor();
    private final Map<String, AureaRoutineGuard.Report> guardReports = new HashMap<>();

    private AureaRoutineDraftStore draftStore;
    private AureaInsightsStore insightsStore;
    private String currentPerson;
    private TextView status;
    private LinearLayout draftsContainer;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        currentPerson = RegisteredUserAccess.currentPerson(this);
        if (currentPerson.isEmpty()) {
            denyAccess();
            return;
        }

        draftStore = new AureaRoutineDraftStore(this);
        insightsStore = new AureaInsightsStore(this);
        createIncomingDraft();
        buildInterface();
        refreshDrafts();
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
        refreshDrafts();
    }

    private void createIncomingDraft() {
        String suggestionId = getIntent() == null
            ? ""
            : clean(getIntent().getStringExtra(EXTRA_SUGGESTION_ID));
        if (suggestionId.isEmpty()) {
            return;
        }

        AureaInsightsStore.Suggestion suggestion = insightsStore.findSuggestion(suggestionId);
        if (suggestion == null) {
            Toast.makeText(
                this,
                "La proposta Insights non è più disponibile",
                Toast.LENGTH_LONG
            ).show();
            return;
        }

        AureaRoutineDraftStore.Draft draft = draftStore.createFromSuggestion(
            suggestion,
            currentPerson
        );
        Toast.makeText(
            this,
            draft == null
                ? draftStore.unsupportedReason(suggestion)
                : "Bozza creata in Routine Studio",
            Toast.LENGTH_LONG
        ).show();
        getIntent().removeExtra(EXTRA_SUGGESTION_ID);
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(2, 7, 13));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(30), dp(18), dp(30), dp(18));
        scroll.addView(root, fullWidth());

        TextView title = text("AUREA Routine Studio 1.1", 29, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidth());

        TextView identity = text(
            "Bozze controllate · profilo attivo: " + currentPerson,
            15,
            Color.rgb(124, 220, 255)
        );
        identity.setGravity(Gravity.CENTER);
        identity.setPadding(0, dp(5), 0, dp(12));
        root.addView(identity, fullWidth());

        TextView explanation = text(
            "Routine Guard verifica entità, azione, orario, YAML e conflitti prima della copia. "
                + "Le bozze bloccate non possono essere copiate. AUREA non installa e non attiva "
                + "automazioni da sola.",
            15,
            Color.rgb(190, 210, 225)
        );
        explanation.setGravity(Gravity.CENTER);
        explanation.setPadding(dp(8), 0, dp(8), dp(14));
        root.addView(explanation, fullWidth());

        LinearLayout summaryCard = card();
        summaryCard.addView(text("Stato delle bozze", 22, Color.WHITE), fullWidth());
        status = text("", 15, Color.rgb(171, 205, 224));
        status.setPadding(0, dp(7), 0, 0);
        summaryCard.addView(status, fullWidth());
        root.addView(summaryCard, fullWidthWithBottom(dp(14)));

        draftsContainer = new LinearLayout(this);
        draftsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(draftsContainer, fullWidthWithBottom(dp(14)));

        Button clear = button("Cancella tutte le mie bozze Routine Studio");
        clear.setOnClickListener(view -> confirmClear());
        root.addView(clear, fullWidthWithBottom(dp(8)));

        Button close = button("Torna agli strumenti AUREA");
        close.setOnClickListener(view -> finish());
        root.addView(close, fullWidth());

        setContentView(scroll);
    }

    private void refreshDrafts() {
        if (draftStore == null || draftsContainer == null || status == null) {
            return;
        }

        List<AureaRoutineDraftStore.Draft> drafts =
            AureaRoutineDraftAccess.listForPerson(draftStore, currentPerson);
        status.setText(
            "Bozze personali salvate: " + drafts.size()
                + "\nLa copia YAML richiede sempre un controllo Routine Guard aggiornato."
        );
        draftsContainer.removeAllViews();

        if (drafts.isEmpty()) {
            LinearLayout empty = card();
            TextView message = text(
                "Nessuna bozza personale. Apri AUREA Insights e premi “Crea bozza automazione” "
                    + "su una routine compatibile.",
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

        TextView alias = text(draft.alias, 20, Color.WHITE);
        card.addView(alias, fullWidth());

        TextView summary = text(
            draftStore.summary(draft),
            15,
            Color.rgb(155, 205, 229)
        );
        summary.setPadding(0, dp(4), 0, dp(4));
        card.addView(summary, fullWidth());

        TextView entity = text(
            "Entità: " + draft.entityId,
            13,
            Color.rgb(150, 172, 190)
        );
        entity.setPadding(0, 0, 0, dp(5));
        card.addView(entity, fullWidth());

        AureaRoutineGuard.Report report = guardReports.get(draft.id);
        TextView guardState = text(
            report == null
                ? "Routine Guard: non ancora controllata"
                : "Routine Guard: " + report.statusLabel(),
            14,
            guardColor(report)
        );
        guardState.setPadding(0, 0, 0, dp(9));
        card.addView(guardState, fullWidth());

        LinearLayout firstRow = new LinearLayout(this);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);

        Button edit = button("Modifica orario e giorni");
        edit.setOnClickListener(view -> editDraft(draft));
        firstRow.addView(edit, weighted());

        Button guard = button("Controlla sicurezza");
        guard.setOnClickListener(view -> runGuard(draft, false));
        firstRow.addView(guard, weightedWithStart(dp(7)));
        card.addView(firstRow, fullWidth());

        LinearLayout secondRow = new LinearLayout(this);
        secondRow.setOrientation(LinearLayout.HORIZONTAL);

        Button preview = button("Mostra YAML");
        preview.setOnClickListener(view -> showYaml(draft));
        secondRow.addView(preview, weighted());

        Button copy = button("Controlla e copia YAML");
        copy.setOnClickListener(view -> copyYaml(draft));
        secondRow.addView(copy, weightedWithStart(dp(7)));
        card.addView(secondRow, fullWidthWithTop(dp(7)));

        Button delete = button("Elimina bozza");
        delete.setOnClickListener(view -> confirmDelete(draft));
        card.addView(delete, fullWidthWithTop(dp(7)));

        return card;
    }

    private int guardColor(AureaRoutineGuard.Report report) {
        if (report == null) {
            return Color.rgb(150, 172, 190);
        }
        switch (report.overallLevel()) {
            case BLOCKED:
                return Color.rgb(255, 120, 120);
            case WARNING:
                return Color.rgb(255, 205, 110);
            default:
                return Color.rgb(120, 230, 170);
        }
    }

    private void runGuard(AureaRoutineDraftStore.Draft draft, boolean copyAfter) {
        Toast.makeText(
            this,
            "Routine Guard sta controllando la bozza...",
            Toast.LENGTH_SHORT
        ).show();
        List<AureaRoutineDraftStore.Draft> profileDrafts =
            AureaRoutineDraftAccess.listForPerson(draftStore, currentPerson);

        guardIo.execute(() -> {
            AureaRoutineGuard.Report report = new AureaRoutineGuard(this).inspect(
                draft,
                profileDrafts
            );
            runOnUiThread(() -> {
                guardReports.put(draft.id, report);
                refreshDrafts();
                if (copyAfter) {
                    handleCopyAfterGuard(draft, report);
                } else {
                    showGuardReport(draft, report);
                }
            });
        });
    }

    private void showGuardReport(
            AureaRoutineDraftStore.Draft draft,
            AureaRoutineGuard.Report report) {
        new AlertDialog.Builder(this)
            .setTitle("Routine Guard · " + report.statusLabel())
            .setMessage(report.formatted())
            .setPositiveButton("Chiudi", null)
            .show();
    }

    private void handleCopyAfterGuard(
            AureaRoutineDraftStore.Draft draft,
            AureaRoutineGuard.Report report) {
        if (report.isBlocked()) {
            new AlertDialog.Builder(this)
                .setTitle("Copia bloccata da Routine Guard")
                .setMessage(report.formatted())
                .setPositiveButton("Chiudi", null)
                .show();
            return;
        }

        if (report.hasWarnings()) {
            new AlertDialog.Builder(this)
                .setTitle("Bozza con avvisi")
                .setMessage(
                    report.formatted()
                        + "\n\nPuoi copiarla soltanto dopo questa conferma. "
                        + "Controlla nuovamente il YAML in Home Assistant."
                )
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Copia comunque", (dialog, which) ->
                    copyValidatedYaml(draft)
                )
                .show();
            return;
        }

        copyValidatedYaml(draft);
    }

    private void editDraft(AureaRoutineDraftStore.Draft draft) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);

        TextView aliasLabel = dialogLabel("Nome della bozza");
        form.addView(aliasLabel, fullWidth());

        EditText aliasInput = new EditText(this);
        aliasInput.setSingleLine(true);
        aliasInput.setText(draft.alias);
        aliasInput.setInputType(
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );
        form.addView(aliasInput, fullWidthWithBottom(dp(8)));

        TextView timeLabel = dialogLabel("Orario di esecuzione");
        form.addView(timeLabel, fullWidth());

        final String[] selectedTime = {draft.time};
        Button timeButton = button("Orario: " + selectedTime[0]);
        timeButton.setOnClickListener(view -> {
            int[] values = parseTime(selectedTime[0]);
            new TimePickerDialog(
                this,
                (picker, hour, minute) -> {
                    selectedTime[0] = String.format(
                        Locale.ITALIAN,
                        "%02d:%02d",
                        hour,
                        minute
                    );
                    timeButton.setText("Orario: " + selectedTime[0]);
                },
                values[0],
                values[1],
                true
            ).show();
        });
        form.addView(timeButton, fullWidthWithBottom(dp(10)));

        TextView daysLabel = dialogLabel("Giorni della settimana");
        form.addView(daysLabel, fullWidth());

        Set<String> initialDays = new HashSet<>(draft.weekdays);
        if (initialDays.isEmpty()) {
            initialDays.addAll(AureaRoutineDraftStore.WEEKDAY_CODES);
        }
        ArrayList<CheckBox> dayChecks = new ArrayList<>();
        form.addView(dayRow(0, 4, initialDays, dayChecks), fullWidth());
        form.addView(dayRow(4, 7, initialDays, dayChecks), fullWidth());

        TextView note = dialogLabel(
            "Ogni modifica annulla il precedente esito Routine Guard."
        );
        note.setTextSize(12);
        note.setPadding(0, dp(8), 0, 0);
        form.addView(note, fullWidth());

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Modifica bozza")
            .setView(form)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva", null)
            .create();

        dialog.setOnShowListener(ignored -> dialog
            .getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(view -> {
                String alias = clean(aliasInput.getText().toString());
                if (alias.isEmpty()) {
                    aliasInput.setError("Inserisci un nome");
                    return;
                }

                ArrayList<String> selectedDays = new ArrayList<>();
                for (int index = 0; index < dayChecks.size(); index++) {
                    if (dayChecks.get(index).isChecked()) {
                        selectedDays.add(AureaRoutineDraftStore.WEEKDAY_CODES.get(index));
                    }
                }
                if (selectedDays.isEmpty()) {
                    Toast.makeText(
                        this,
                        "Seleziona almeno un giorno",
                        Toast.LENGTH_LONG
                    ).show();
                    return;
                }
                if (selectedDays.size() == 7) {
                    selectedDays.clear();
                }

                draftStore.save(draft.withEditing(alias, selectedTime[0], selectedDays));
                guardReports.remove(draft.id);
                dialog.dismiss();
                refreshDrafts();
                Toast.makeText(
                    this,
                    "Bozza aggiornata. Esegui nuovamente Routine Guard.",
                    Toast.LENGTH_LONG
                ).show();
            }));
        dialog.show();
    }

    private LinearLayout dayRow(
            int start,
            int end,
            Set<String> selected,
            List<CheckBox> output) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int index = start; index < end; index++) {
            String code = AureaRoutineDraftStore.WEEKDAY_CODES.get(index);
            CheckBox check = new CheckBox(this);
            check.setText(dayShortLabel(code));
            check.setChecked(selected.contains(code));
            output.add(check);
            row.addView(check, weighted());
        }
        return row;
    }

    private void showYaml(AureaRoutineDraftStore.Draft draft) {
        String yaml = draftStore.buildYaml(draft);
        if (yaml.isEmpty()) {
            Toast.makeText(this, "YAML non disponibile", Toast.LENGTH_LONG).show();
            return;
        }

        TextView code = new TextView(this);
        code.setText(yaml);
        code.setTextSize(14);
        code.setTextColor(Color.rgb(20, 28, 34));
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        code.setPadding(dp(18), dp(14), dp(18), dp(14));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(code, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT
        ));

        new AlertDialog.Builder(this)
            .setTitle(draft.alias)
            .setView(scroll)
            .setNegativeButton("Chiudi", null)
            .setPositiveButton("Controlla e copia", (dialog, which) -> copyYaml(draft))
            .show();
    }

    private void copyYaml(AureaRoutineDraftStore.Draft draft) {
        runGuard(draft, true);
    }

    private void copyValidatedYaml(AureaRoutineDraftStore.Draft draft) {
        String yaml = draftStore.buildYaml(draft);
        if (yaml.isEmpty()) {
            Toast.makeText(this, "YAML non disponibile", Toast.LENGTH_LONG).show();
            return;
        }
        copyText(yaml);
    }

    private void copyText(String yaml) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(
            Context.CLIPBOARD_SERVICE
        );
        if (clipboard == null) {
            Toast.makeText(this, "Appunti Android non disponibili", Toast.LENGTH_LONG).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("AUREA automation YAML", yaml));
        Toast.makeText(
            this,
            "YAML controllato e copiato. Verificalo ancora prima di attivarlo in Home Assistant.",
            Toast.LENGTH_LONG
        ).show();
    }

    private void confirmDelete(AureaRoutineDraftStore.Draft draft) {
        new AlertDialog.Builder(this)
            .setTitle("Eliminare questa bozza?")
            .setMessage(draft.alias)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Elimina", (dialog, which) -> {
                draftStore.delete(draft.id);
                guardReports.remove(draft.id);
                refreshDrafts();
            })
            .show();
    }

    private void confirmClear() {
        if (AureaRoutineDraftAccess.countForPerson(draftStore, currentPerson) == 0) {
            Toast.makeText(
                this,
                "Non ci sono bozze personali da cancellare",
                Toast.LENGTH_SHORT
            ).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Cancellare tutte le tue bozze?")
            .setMessage(
                "Verranno eliminate soltanto le bozze del profilo " + currentPerson
                    + ". Insights, preferenze, altre persone e Home Assistant non cambieranno."
            )
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Cancella", (dialog, which) -> {
                AureaRoutineDraftAccess.clearForPerson(draftStore, currentPerson);
                guardReports.clear();
                refreshDrafts();
            })
            .show();
    }

    private int[] parseTime(String value) {
        String clean = clean(value);
        if (clean.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) {
            String[] parts = clean.split(":");
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        }
        return new int[]{12, 0};
    }

    private String dayShortLabel(String code) {
        switch (code) {
            case "mon":
                return "Lun";
            case "tue":
                return "Mar";
            case "wed":
                return "Mer";
            case "thu":
                return "Gio";
            case "fri":
                return "Ven";
            case "sat":
                return "Sab";
            case "sun":
                return "Dom";
            default:
                return code;
        }
    }

    private void denyAccess() {
        Toast.makeText(
            this,
            "Routine Studio è disponibile dopo il riconoscimento personale",
            Toast.LENGTH_LONG
        ).show();
        finish();
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

    private TextView dialogLabel(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(14);
        view.setTextColor(Color.DKGRAY);
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

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        );
    }

    private LinearLayout.LayoutParams weightedWithStart(int start) {
        LinearLayout.LayoutParams params = weighted();
        params.setMarginStart(start);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        guardIo.shutdownNow();
        super.onDestroy();
    }
}
