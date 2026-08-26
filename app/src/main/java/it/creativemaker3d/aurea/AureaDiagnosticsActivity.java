package it.creativemaker3d.aurea;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Centro diagnostico AUREA con monitor automatico e consegna sanificata. */
public final class AureaDiagnosticsActivity extends Activity
        implements RecognitionListener, TextToSpeech.OnInitListener {
    private static final int AUDIO_PERMISSION_REQUEST = 901;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private AureaDiagnosticsLog diagnosticsLog;
    private AureaDiagnosticsProbe.Snapshot snapshot;
    private String currentPerson;
    private TextView headline;
    private TextView summary;
    private TextView monitorStatus;
    private LinearLayout checksContainer;
    private LinearLayout eventsContainer;
    private Button runButton;
    private TextToSpeech tts;
    private boolean ttsReady;
    private SpeechRecognizer recognizer;
    private boolean microphoneTestRunning;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        currentPerson = RegisteredUserAccess.currentPerson(this);
        if (currentPerson.isEmpty()) {
            denyAccess();
            return;
        }
        diagnosticsLog = new AureaDiagnosticsLog(this);
        buildInterface();
        tts = new TextToSpeech(this, this);
        hideSystemUi();
        runDiagnostics();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        currentPerson = RegisteredUserAccess.currentPerson(this);
        if (currentPerson.isEmpty()) denyAccess();
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

        TextView title = text("AUREA Diagnostics 2.0", 29, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidth());

        TextView identity = text(
            "Controllo locale · profilo attivo: " + currentPerson,
            15,
            Color.rgb(124, 220, 255)
        );
        identity.setGravity(Gravity.CENTER);
        identity.setPadding(0, dp(5), 0, dp(14));
        root.addView(identity, fullWidth());

        LinearLayout stateCard = card();
        headline = text("DIAGNOSI IN CORSO", 22, Color.rgb(255, 210, 90));
        headline.setGravity(Gravity.CENTER);
        stateCard.addView(headline, fullWidth());
        summary = text(
            "Sto controllando AUREA e Home Assistant in sola lettura.",
            15,
            Color.rgb(190, 210, 225)
        );
        summary.setGravity(Gravity.CENTER);
        summary.setPadding(0, dp(7), 0, dp(8));
        stateCard.addView(summary, fullWidth());
        runButton = button("Controlla e comunica ora");
        runButton.setOnClickListener(view -> runDiagnostics());
        stateCard.addView(runButton, fullWidthWithTop(dp(5)));
        root.addView(stateCard, fullWidthWithBottom(dp(14)));

        LinearLayout monitorCard = card();
        monitorCard.addView(text("Monitor continuo Home Assistant + email", 22, Color.WHITE), fullWidth());
        monitorCard.addView(help(
            "AUREA aggiorna Home Assistant ogni 30 minuti. Le email partono soltanto per "
                + "una nuova anomalia, una variazione, il promemoria dopo 12 ore, il ripristino "
                + "e il riepilogo giornaliero."
        ), fullWidth());
        monitorStatus = text(
            AureaDiagnosticsPublisher.monitorSummary(this),
            15,
            Color.rgb(124, 220, 255)
        );
        monitorCard.addView(monitorStatus, fullWidth());
        root.addView(monitorCard, fullWidthWithBottom(dp(14)));

        LinearLayout checksCard = card();
        checksCard.addView(text("Controlli automatici", 22, Color.WHITE), fullWidth());
        checksCard.addView(help(
            "La sonda legge soltanto. Il monitor pubblica esclusivamente i due sensori "
                + "diagnostici AUREA e richiama i canali di notifica già autorizzati."
        ), fullWidth());
        checksContainer = vertical();
        checksCard.addView(checksContainer, fullWidth());
        root.addView(checksCard, fullWidthWithBottom(dp(14)));

        LinearLayout manualCard = card();
        manualCard.addView(text("Test e ripristini sicuri", 22, Color.WHITE), fullWidth());
        manualCard.addView(help(
            "I test non memorizzano ciò che pronunci. Il riavvio ricarica soltanto AUREA."
        ), fullWidth());

        LinearLayout firstRow = horizontal();
        Button ttsTest = button("Prova voce AUREA");
        ttsTest.setOnClickListener(view -> testTts());
        firstRow.addView(ttsTest, weighted());
        Button microphoneTest = button("Prova microfono");
        microphoneTest.setOnClickListener(view -> startMicrophoneTest());
        firstRow.addView(microphoneTest, weightedWithStart(dp(7)));
        manualCard.addView(firstRow, fullWidth());

        LinearLayout secondRow = horizontal();
        Button resetGemini = button("Azzera sessione Gemini");
        resetGemini.setOnClickListener(view -> confirmResetGemini());
        secondRow.addView(resetGemini, weighted());
        Button restartWake = button("Riavvia ascolto e wake word");
        restartWake.setOnClickListener(view -> confirmRestartWakeWord());
        secondRow.addView(restartWake, weightedWithStart(dp(7)));
        manualCard.addView(secondRow, fullWidthWithTop(dp(7)));
        root.addView(manualCard, fullWidthWithBottom(dp(14)));

        LinearLayout eventsCard = card();
        eventsCard.addView(text("Ultimi eventi tecnici", 22, Color.WHITE), fullWidth());
        eventsCard.addView(help(
            "Registro limitato e sanificato: nessun token, chiave API o testo delle conversazioni."
        ), fullWidth());
        eventsContainer = vertical();
        eventsCard.addView(eventsContainer, fullWidth());
        Button clearEvents = button("Cancella soltanto il registro tecnico");
        clearEvents.setOnClickListener(view -> confirmClearEvents());
        eventsCard.addView(clearEvents, fullWidthWithTop(dp(8)));
        root.addView(eventsCard, fullWidthWithBottom(dp(14)));

        LinearLayout reportCard = card();
        reportCard.addView(text("Rapporto diagnostico", 22, Color.WHITE), fullWidth());
        reportCard.addView(help(
            "Il rapporto viene comunicato automaticamente senza esporre credenziali o dati "
                + "biometrici; resta anche consultabile e copiabile sul tablet."
        ), fullWidth());
        LinearLayout reportActions = horizontal();
        Button showReport = button("Mostra rapporto");
        showReport.setOnClickListener(view -> showReport());
        reportActions.addView(showReport, weighted());
        Button copyReport = button("Copia rapporto");
        copyReport.setOnClickListener(view -> copyReport());
        reportActions.addView(copyReport, weightedWithStart(dp(7)));
        reportCard.addView(reportActions, fullWidth());
        root.addView(reportCard, fullWidthWithBottom(dp(14)));

        Button close = button("Torna agli strumenti AUREA");
        close.setOnClickListener(view -> finish());
        root.addView(close, fullWidth());
        setContentView(scroll);
        refreshEvents();
    }

    private void runDiagnostics() {
        runButton.setEnabled(false);
        runButton.setText("Diagnosi in corso...");
        headline.setText("DIAGNOSI IN CORSO");
        headline.setTextColor(Color.rgb(255, 210, 90));
        summary.setText("Controllo Home Assistant, Gemini, audio, profili e moduli locali.");

        io.execute(() -> {
            AureaHomeAssistantMaintenance maintenance =
                new AureaHomeAssistantMaintenance(this);
            maintenance.applyPresenceAuditPatch();
            maintenance.applyTabletWatchdogPatch();
            AureaDiagnosticsProbe.Snapshot result = new AureaDiagnosticsProbe(this).run();
            AureaDiagnosticsPublisher.PublishResult delivery =
                new AureaDiagnosticsPublisher(this).publish(result);
            runOnUiThread(() -> {
                snapshot = result;
                renderSnapshot(result);
                refreshEvents();
                if (monitorStatus != null) {
                    monitorStatus.setText(AureaDiagnosticsPublisher.monitorSummary(this));
                }
                runButton.setEnabled(true);
                runButton.setText("Controlla e comunica ora");
                Toast.makeText(
                    this,
                    delivery.success
                        ? delivery.message
                        : "Diagnosi completata; invio non riuscito: " + delivery.message,
                    Toast.LENGTH_LONG
                ).show();
            });
        });
    }

    private void renderSnapshot(AureaDiagnosticsProbe.Snapshot result) {
        headline.setText(result.headline());
        headline.setTextColor(result.errors > 0
            ? Color.rgb(255, 115, 115)
            : result.warnings > 0
                ? Color.rgb(255, 210, 90)
                : Color.rgb(112, 230, 154));
        summary.setText(
            "Versione installata: " + result.installedVersion
                + " · canale firmato: "
                + (result.signedVersion.isEmpty() ? "non disponibile" : result.signedVersion)
                + "\nProblemi: " + result.errors + " · avvisi: " + result.warnings
        );
        checksContainer.removeAllViews();
        for (AureaDiagnosticsProbe.Check check : result.checks) {
            checksContainer.addView(checkView(check), fullWidthWithBottom(dp(8)));
        }
    }

    private View checkView(AureaDiagnosticsProbe.Check check) {
        LinearLayout panel = vertical();
        panel.setPadding(dp(15), dp(11), dp(15), dp(11));
        int border;
        String prefix;
        switch (check.status) {
            case OK:
                border = Color.rgb(64, 154, 102);
                prefix = "OK · ";
                break;
            case WARNING:
                border = Color.rgb(184, 137, 42);
                prefix = "AVVISO · ";
                break;
            case ERROR:
                border = Color.rgb(181, 70, 70);
                prefix = "ERRORE · ";
                break;
            default:
                border = Color.rgb(55, 105, 139);
                prefix = "INFO · ";
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(20, 37, 51));
        background.setCornerRadius(dp(11));
        background.setStroke(dp(1), border);
        panel.setBackground(background);
        panel.addView(text(prefix + check.title, 17, Color.WHITE), fullWidth());
        TextView detail = text(check.detail, 14, Color.rgb(182, 207, 224));
        detail.setPadding(0, dp(4), 0, 0);
        panel.addView(detail, fullWidth());
        return panel;
    }

    private void refreshEvents() {
        if (eventsContainer == null || diagnosticsLog == null) return;
        eventsContainer.removeAllViews();
        List<AureaDiagnosticsLog.Entry> entries = diagnosticsLog.entries();
        if (entries.isEmpty()) {
            TextView empty = text(
                "Nessun evento tecnico registrato.",
                15,
                Color.rgb(190, 210, 225)
            );
            empty.setPadding(dp(4), dp(8), dp(4), dp(8));
            eventsContainer.addView(empty, fullWidth());
            return;
        }
        for (int index = 0; index < Math.min(8, entries.size()); index++) {
            AureaDiagnosticsLog.Entry entry = entries.get(index);
            TextView event = text(
                entry.label(),
                14,
                "ERRORE".equals(entry.level)
                    ? Color.rgb(255, 160, 160)
                    : Color.rgb(190, 210, 225)
            );
            event.setPadding(dp(4), dp(7), dp(4), dp(7));
            eventsContainer.addView(event, fullWidth());
        }
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) {
            ttsReady = false;
            diagnosticsLog.error("Sintesi vocale", "Inizializzazione TTS non riuscita", null);
            return;
        }
        int language = tts.setLanguage(Locale.ITALIAN);
        ttsReady = language != TextToSpeech.LANG_MISSING_DATA
            && language != TextToSpeech.LANG_NOT_SUPPORTED;
        if (!ttsReady) {
            diagnosticsLog.warning("Sintesi vocale", "Lingua italiana non disponibile");
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {
            }
            @Override public void onDone(String utteranceId) {
                runOnUiThread(() -> Toast.makeText(
                    AureaDiagnosticsActivity.this,
                    "Test voce completato",
                    Toast.LENGTH_SHORT
                ).show());
            }
            @Override public void onError(String utteranceId) {
                diagnosticsLog.error(
                    "Sintesi vocale",
                    "Riproduzione del test TTS non riuscita",
                    null
                );
            }
        });
    }

    private void testTts() {
        if (!ttsReady || tts == null) {
            diagnosticsLog.warning("Sintesi vocale", "Test richiesto ma TTS non pronto");
            refreshEvents();
            Toast.makeText(this, "Sintesi vocale non disponibile", Toast.LENGTH_LONG).show();
            return;
        }
        int result = tts.speak(
            "Test audio AUREA riuscito.",
            TextToSpeech.QUEUE_FLUSH,
            new Bundle(),
            "aurea-diagnostics-tts"
        );
        if (result == TextToSpeech.ERROR) {
            diagnosticsLog.error("Sintesi vocale", "Avvio test TTS non riuscito", null);
            refreshEvents();
        }
    }

    private void startMicrophoneTest() {
        if (microphoneTestRunning) {
            Toast.makeText(this, "Test microfono già in corso", Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO},
                AUDIO_PERMISSION_REQUEST
            );
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            diagnosticsLog.error("Microfono", "Riconoscimento Android non disponibile", null);
            refreshEvents();
            Toast.makeText(this, "Riconoscimento vocale non disponibile", Toast.LENGTH_LONG).show();
            return;
        }

        destroyRecognizer();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            1000L
        );
        try {
            microphoneTestRunning = true;
            recognizer.startListening(intent);
            Toast.makeText(
                this,
                "Pronuncia una breve frase per provare il microfono",
                Toast.LENGTH_LONG
            ).show();
        } catch (RuntimeException error) {
            microphoneTestRunning = false;
            diagnosticsLog.error("Microfono", "Avvio test non riuscito", error);
            refreshEvents();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != AUDIO_PERMISSION_REQUEST) return;
        boolean granted = grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) startMicrophoneTest();
        else {
            diagnosticsLog.warning("Microfono", "Permesso microfono negato");
            refreshEvents();
        }
    }

    @Override public void onReadyForSpeech(Bundle params) {
    }
    @Override public void onBeginningOfSpeech() {
    }
    @Override public void onRmsChanged(float rmsdB) {
    }
    @Override public void onBufferReceived(byte[] buffer) {
    }
    @Override public void onEndOfSpeech() {
    }

    @Override
    public void onError(int error) {
        microphoneTestRunning = false;
        String description = speechError(error);
        if (error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            diagnosticsLog.warning("Microfono", "Test senza frase: " + description);
        } else {
            diagnosticsLog.error("Microfono", "Test fallito: " + description, null);
        }
        refreshEvents();
        Toast.makeText(this, description, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onResults(Bundle results) {
        microphoneTestRunning = false;
        ArrayList<String> phrases = results.getStringArrayList(
            SpeechRecognizer.RESULTS_RECOGNITION
        );
        boolean recognized = phrases != null
            && !phrases.isEmpty()
            && !phrases.get(0).trim().isEmpty();
        if (recognized) {
            diagnosticsLog.info(
                "Microfono",
                "Test riuscito; frase riconosciuta ma non memorizzata"
            );
            Toast.makeText(
                this,
                "Microfono funzionante: frase riconosciuta e non salvata",
                Toast.LENGTH_LONG
            ).show();
        } else {
            diagnosticsLog.warning("Microfono", "Nessuna frase riconosciuta nel test");
        }
        refreshEvents();
    }

    @Override public void onPartialResults(Bundle partialResults) {
    }
    @Override public void onEvent(int eventType, Bundle params) {
    }

    private String speechError(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "Errore audio del microfono";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Sessione microfono interrotta";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Permesso microfono insufficiente";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Servizio di riconoscimento non raggiungibile";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "Nessuna frase riconosciuta";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Microfono occupato da un altro ascolto";
            case SpeechRecognizer.ERROR_SERVER:
                return "Errore del motore di riconoscimento";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "Nessuna voce rilevata";
            default:
                return "Errore microfono codice " + error;
        }
    }

    private void confirmResetGemini() {
        new AlertDialog.Builder(this)
            .setTitle("Azzerare la sessione Gemini?")
            .setMessage(
                "Verrà eliminato soltanto il contesto temporaneo della conversazione di "
                    + currentPerson + ". Agente, preferenze e profili non cambieranno."
            )
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Azzera", (dialog, which) -> {
                new AureaBrainStore(this).clearConversation(currentPerson);
                diagnosticsLog.info("Gemini", "Sessione del profilo attivo azzerata");
                refreshEvents();
                runDiagnostics();
            })
            .show();
    }

    private void confirmRestartWakeWord() {
        new AlertDialog.Builder(this)
            .setTitle("Riavviare ascolto e wake word?")
            .setMessage(
                "AUREA tornerà alla dashboard e ricreerà microfono, voce e ascolto locale."
            )
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Riavvia", (dialog, which) -> restartAurea())
            .show();
    }

    private void restartAurea() {
        diagnosticsLog.info("Wake word", "Riavvio controllato richiesto");
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

    private void confirmClearEvents() {
        new AlertDialog.Builder(this)
            .setTitle("Cancellare il registro tecnico?")
            .setMessage(
                "Saranno cancellati soltanto gli eventi diagnostici. Profili e configurazione "
                    + "non cambieranno."
            )
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Cancella", (dialog, which) -> {
                diagnosticsLog.clear();
                refreshEvents();
                runDiagnostics();
            })
            .show();
    }

    private String currentReport() {
        return snapshot == null ? "" : snapshot.report(diagnosticsLog);
    }

    private void showReport() {
        String report = currentReport();
        if (report.isEmpty()) {
            Toast.makeText(this, "Completa prima la diagnosi", Toast.LENGTH_LONG).show();
            return;
        }
        TextView content = text(report, 14, Color.rgb(20, 28, 34));
        content.setTextIsSelectable(true);
        content.setPadding(dp(18), dp(14), dp(18), dp(14));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT
        ));
        new AlertDialog.Builder(this)
            .setTitle("Rapporto AUREA Diagnostics")
            .setView(scroll)
            .setNegativeButton("Chiudi", null)
            .setPositiveButton("Copia", (dialog, which) -> copyText(report))
            .show();
    }

    private void copyReport() {
        String report = currentReport();
        if (report.isEmpty()) {
            Toast.makeText(this, "Completa prima la diagnosi", Toast.LENGTH_LONG).show();
            return;
        }
        copyText(report);
    }

    private void copyText(String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(
            Context.CLIPBOARD_SERVICE
        );
        if (clipboard == null) {
            Toast.makeText(this, "Appunti Android non disponibili", Toast.LENGTH_LONG).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(
            "AUREA Diagnostics report",
            value
        ));
        Toast.makeText(
            this,
            "Rapporto copiato senza credenziali o dati biometrici",
            Toast.LENGTH_LONG
        ).show();
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {
            }
            try { recognizer.destroy(); } catch (Exception ignored) {
            }
            recognizer = null;
        }
        microphoneTestRunning = false;
    }

    private void denyAccess() {
        Toast.makeText(
            this,
            "Diagnostics è disponibile dopo il riconoscimento personale",
            Toast.LENGTH_LONG
        ).show();
        finish();
    }

    private LinearLayout card() {
        LinearLayout card = vertical();
        card.setPadding(dp(20), dp(15), dp(20), dp(15));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(12, 25, 37));
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), Color.rgb(45, 78, 101));
        card.setBackground(background);
        return card;
    }

    private LinearLayout vertical() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        return view;
    }

    private TextView help(String value) {
        TextView view = text(value, 14, Color.rgb(165, 190, 208));
        view.setPadding(0, dp(4), 0, dp(10));
        return view;
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
        button.setMinHeight(dp(48));
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

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.post(() -> {
            if (Build.VERSION.SDK_INT >= 30) {
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
        destroyRecognizer();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
        }
        io.shutdownNow();
        super.onDestroy();
    }
}
