package it.creativemaker3d.aurea;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VoiceGateActivity extends Activity {
    private static final int AUDIO_PERMISSION = 61;
    private static final int SAMPLE_RATE = 16000;
    private static final int MAX_RECORD_SECONDS = 6;
    private static final int REQUIRED_ENROLLMENT_SAMPLES = 4;
    private static final String GREETING_UTTERANCE = "aurea-person-greeting";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final ArrayList<float[]> enrollmentSamples = new ArrayList<>();

    private TextView titleView;
    private TextView phraseView;
    private TextView statusView;
    private Button primaryButton;
    private Button resetButton;
    private Button continueButton;
    private Button homeAssistantRecoveryButton;

    private VoiceProfileStore profileStore;
    private PersonPreferencesStore preferencesStore;
    private TextToSpeech tts;
    private String personName;
    private boolean enrollmentMode;
    private boolean openingMain;
    private boolean ttsReady;
    private boolean greetingStarted;
    private boolean forcedEnrollment;
    private boolean returnToPeopleManager;
    private boolean adminVerification;
    private Float borderlineScore;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Intent source = getIntent();
        forcedEnrollment = source != null
            && source.getBooleanExtra("aurea_force_voice_enrollment", false);
        returnToPeopleManager = source != null
            && source.getBooleanExtra("aurea_return_to_people_manager", false);
        personName = source == null
            ? null
            : source.getStringExtra("aurea_recognized_person");
        if (personName == null || personName.trim().isEmpty()) {
            openMainActivity(null);
            return;
        }
        personName = personName.trim();
        adminVerification = new AdminAccessStore(this).isAccessRequested();

        profileStore = new VoiceProfileStore(this);
        preferencesStore = new PersonPreferencesStore(this);
        initTextToSpeech();
        buildInterface();
        hideSystemUi();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO},
                AUDIO_PERMISSION
            );
        } else {
            configureMode();
        }
    }

    private void initTextToSpeech() {
        tts = new TextToSpeech(getApplicationContext(), status -> main.post(() -> {
            if (openingMain || tts == null || status != TextToSpeech.SUCCESS) {
                return;
            }

            int languageResult = tts.setLanguage(Locale.ITALIAN);
            tts.setSpeechRate(0.94f);
            ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA
                && languageResult != TextToSpeech.LANG_NOT_SUPPORTED;

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    if (GREETING_UTTERANCE.equals(utteranceId)) {
                        main.post(() -> openMainActivity(personName));
                    }
                }

                @Override
                public void onError(String utteranceId) {
                    if (GREETING_UTTERANCE.equals(utteranceId)) {
                        main.post(() -> openMainActivity(personName));
                    }
                }
            });
        }));
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(70, 44, 70, 44);
        root.setBackgroundColor(Color.rgb(2, 7, 13));

        titleView = text(28, Color.WHITE);
        root.addView(titleView, fullWidth());

        phraseView = text(23, Color.rgb(124, 220, 255));
        phraseView.setPadding(10, 24, 10, 24);
        root.addView(phraseView, fullWidth());

        statusView = text(18, Color.rgb(210, 225, 238));
        statusView.setPadding(10, 0, 10, 24);
        root.addView(statusView, fullWidth());

        primaryButton = new Button(this);
        primaryButton.setOnClickListener(v -> startCapture());
        root.addView(primaryButton, buttonParams());

        resetButton = new Button(this);
        resetButton.setText("Registra di nuovo la voce");
        resetButton.setOnClickListener(v -> {
            profileStore.deleteProfile(personName);
            enterEnrollmentMode();
        });
        root.addView(resetButton, buttonParams());

        continueButton = new Button(this);
        continueButton.setText(
            returnToPeopleManager
                ? "Annulla e torna ai profili"
                : "Continua senza verifica vocale"
        );
        continueButton.setOnClickListener(v -> openMainActivity(personName));
        root.addView(continueButton, buttonParams());

        homeAssistantRecoveryButton = new Button(this);
        homeAssistantRecoveryButton.setText("Recupera tramite Home Assistant");
        homeAssistantRecoveryButton.setOnClickListener(v -> openHomeAssistantRecovery());
        homeAssistantRecoveryButton.setVisibility(View.GONE);
        root.addView(homeAssistantRecoveryButton, buttonParams());

        TextView privacy = text(13, Color.rgb(150, 172, 190));
        privacy.setText(
            "L'audio non viene salvato. AUREA conserva soltanto una firma numerica locale."
        );
        privacy.setPadding(10, 24, 10, 0);
        root.addView(privacy, fullWidth());

        setContentView(root);
    }

    private TextView text(int size, int color) {
        TextView view = new TextView(this);
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

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = 10;
        return params;
    }

    private String phrase() {
        return "Ciao Aurea, sono " + personName;
    }

    private void configureMode() {
        if (forcedEnrollment) {
            enterEnrollmentMode();
        } else if (profileStore.hasProfile(personName)) {
            enterVerificationMode();
        } else {
            enterEnrollmentMode();
        }
    }

    private void enterEnrollmentMode() {
        enrollmentMode = true;
        enrollmentSamples.clear();
        titleView.setText("Registra la voce di " + personName);
        phraseView.setText("«" + phrase() + "»");
        statusView.setText(
            "Premi il pulsante e ripeti la frase quattro volte con voce naturale. "
                + "AUREA fermerà automaticamente la registrazione."
        );
        primaryButton.setText("Registra frase 1/4");
        primaryButton.setEnabled(true);
        primaryButton.setOnClickListener(v -> startCapture());
        resetButton.setVisibility(View.GONE);
        continueButton.setVisibility(View.VISIBLE);
        homeAssistantRecoveryButton.setVisibility(View.GONE);
    }

    private void enterVerificationMode() {
        enrollmentMode = false;
        enrollmentSamples.clear();
        borderlineScore = null;
        titleView.setText(adminVerification ? "Conferma amministratore" : "Conferma vocale");
        phraseView.setText("«" + phrase() + "»");
        VoiceProfileStore.VoiceProfile profile = profileStore.loadProfile(personName);
        boolean calibrated = profile != null && profile.calibratedV2;
        if (!calibrated) {
            statusView.setText(
                "Il vecchio profilo vocale va ricalibrato con il nuovo motore. "
                    + (adminVerification
                        ? "Usa il recupero Home Assistant, poi registra di nuovo la voce."
                        : "Tocca «Registra di nuovo la voce».")
            );
        } else {
            statusView.setText(
                adminVerification
                    ? "Giuseppe, conferma la tua voce per aprire Gestione persone."
                    : "AUREA confronterà più caratteristiche della voce di "
                        + personName + "."
            );
        }
        primaryButton.setText("Avvia verifica vocale");
        primaryButton.setEnabled(calibrated);
        primaryButton.setOnClickListener(v -> startCapture());
        resetButton.setVisibility(adminVerification ? View.GONE : View.VISIBLE);
        continueButton.setVisibility(View.VISIBLE);
        homeAssistantRecoveryButton.setVisibility(
            adminVerification ? View.VISIBLE : View.GONE
        );

        main.postDelayed(() -> {
            if (!openingMain && !capturing.get() && !enrollmentMode && calibrated) {
                startCapture();
            }
        }, 900L);
    }

    private void openHomeAssistantRecovery() {
        if (openingMain || !adminVerification
                || !new AdminAccessStore(this).isAccessRequested()) return;
        openingMain = true;
        capturing.set(false);
        Intent recovery = new Intent(this, AureaAdminRecoveryActivity.class);
        startActivity(recovery);
        finish();
    }

    private void startCapture() {
        if (openingMain || greetingStarted || !capturing.compareAndSet(false, true)) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            capturing.set(false);
            requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO},
                AUDIO_PERMISSION
            );
            return;
        }

        primaryButton.setEnabled(false);
        resetButton.setEnabled(false);
        statusView.setText("Parla ora: «" + phrase() + "»");

        audioExecutor.execute(() -> {
            CaptureResult result;
            try {
                short[] pcm = capturePcm();
                VoiceSignature.Analysis analysis = VoiceSignature.analyze(
                    pcm,
                    SAMPLE_RATE
                );
                result = analysis.accepted()
                    ? CaptureResult.success(analysis)
                    : CaptureResult.failure(analysis.message);
            } catch (Exception error) {
                result = CaptureResult.failure(
                    "Microfono non disponibile: " + safeMessage(error)
                );
            }
            CaptureResult finalResult = result;
            main.post(() -> handleCaptureResult(finalResult));
        });
    }

    private short[] capturePcm() {
        int minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        );
        if (minimum <= 0) {
            minimum = 4096;
        }
        int bufferBytes = Math.max(minimum * 2, 8192);

        AudioRecord recorder = new AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .build();

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new IllegalStateException("registratore non inizializzato");
        }

        int targetSamples = SAMPLE_RATE * MAX_RECORD_SECONDS;
        short[] output = new short[targetSamples];
        short[] block = new short[1024];
        int count = 0;
        int preludeBlocks = 0;
        double preludeEnergy = 0d;
        double noiseEstimate = 120d;
        double peakEnergy = 0d;
        boolean heardVoice = false;
        int trailingSilence = 0;

        try {
            recorder.startRecording();
            while (count < targetSamples && !openingMain) {
                int requested = Math.min(block.length, targetSamples - count);
                int read = recorder.read(
                    block,
                    0,
                    requested,
                    AudioRecord.READ_BLOCKING
                );
                if (read < 0) {
                    throw new IllegalStateException(
                        "lettura microfono non riuscita: " + read
                    );
                }
                if (read == 0) {
                    continue;
                }
                System.arraycopy(block, 0, output, count, read);
                count += read;
                double energy = blockRms(block, read);
                peakEnergy = Math.max(peakEnergy, energy);
                if (preludeBlocks < 4) {
                    preludeEnergy += energy;
                    preludeBlocks++;
                    noiseEstimate = preludeEnergy / preludeBlocks;
                } else {
                    double voiceThreshold = Math.max(190d, noiseEstimate * 2.0d);
                    if (energy >= voiceThreshold) heardVoice = true;
                    if (heardVoice) {
                        double silenceThreshold = Math.max(
                            145d,
                            Math.max(noiseEstimate * 1.45d, peakEnergy * 0.13d)
                        );
                        if (energy < silenceThreshold) {
                            trailingSilence += read;
                        } else {
                            trailingSilence = 0;
                        }
                        if (count >= SAMPLE_RATE * 3 / 2
                                && trailingSilence >= SAMPLE_RATE * 4 / 5) {
                            break;
                        }
                    }
                }
            }
        } finally {
            try {
                if (recorder.getRecordingState()
                        == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop();
                }
            } catch (Exception ignored) {
            }
            recorder.release();
        }

        short[] exact = new short[count];
        System.arraycopy(output, 0, exact, 0, count);
        return exact;
    }

    private void handleCaptureResult(CaptureResult result) {
        capturing.set(false);
        if (openingMain) {
            return;
        }
        resetButton.setEnabled(true);

        if (!result.success) {
            statusView.setText(result.message);
            primaryButton.setEnabled(true);
            return;
        }

        if (enrollmentMode) {
            handleEnrollment(result.analysis);
        } else {
            handleVerification(result.analysis);
        }
    }

    private void handleEnrollment(VoiceSignature.Analysis analysis) {
        enrollmentSamples.add(analysis.signature);
        int count = enrollmentSamples.size();
        if (count < REQUIRED_ENROLLMENT_SAMPLES) {
            statusView.setText(
                "Campione " + count + "/4 acquisito bene (segnale "
                    + Math.round(analysis.snrDb) + " dB). Ripeti la stessa frase."
            );
            primaryButton.setText("Registra frase " + (count + 1) + "/4");
            primaryButton.setEnabled(true);
            return;
        }

        if (VoiceSignature.mean(enrollmentSamples) == null) {
            enrollmentSamples.clear();
            statusView.setText("Registrazione non riuscita. Ricomincia.");
            primaryButton.setText("Ricomincia registrazione");
            primaryButton.setEnabled(true);
            return;
        }

        float threshold = VoiceSignature.calibratedThreshold(enrollmentSamples);
        profileStore.saveProfileV2(personName, enrollmentSamples, threshold);

        statusView.setText("Voce registrata localmente per " + personName + ".");
        primaryButton.setText("Continua");
        primaryButton.setEnabled(true);
        primaryButton.setOnClickListener(v -> greetAndOpenMain());
        Toast.makeText(
            this,
            "Registrazione vocale completata",
            Toast.LENGTH_LONG
        ).show();
        main.postDelayed(this::greetAndOpenMain, 1000L);
    }

    private void handleVerification(VoiceSignature.Analysis analysis) {
        VoiceProfileStore.VoiceProfile profile =
            profileStore.loadProfile(personName);
        if (profile == null) {
            enterEnrollmentMode();
            return;
        }

        if (!profile.calibratedV2) {
            enterVerificationMode();
            return;
        }
        float similarity = profileStore.matchScore(profile, analysis.signature);
        AureaRecognitionDiagnostics.recordVoice(
            this,
            similarity,
            profile.threshold,
            similarity >= profile.threshold,
            analysis
        );
        if (similarity >= profile.threshold) {
            borderlineScore = null;
            if (adminVerification) {
                statusView.setText("Amministratore confermato.");
                primaryButton.setText("Confermato");
                primaryButton.setEnabled(false);
                continueButton.setEnabled(false);
                main.postDelayed(() -> openMainActivity(personName), 450L);
                return;
            }

            String greeting = personalGreeting();
            statusView.setText(
                greeting.isEmpty()
                    ? "Voce confermata."
                    : "Voce confermata. " + greeting
            );
            primaryButton.setText("Confermato");
            main.postDelayed(this::greetAndOpenMain, 450L);
        } else if (similarity >= profile.threshold - 0.045f) {
            if (borderlineScore != null
                    && (borderlineScore + similarity) / 2f
                        >= profile.threshold - 0.018f) {
                borderlineScore = null;
                if (adminVerification) {
                    statusView.setText("Amministratore confermato su due campioni.");
                    primaryButton.setEnabled(false);
                    continueButton.setEnabled(false);
                    main.postDelayed(() -> openMainActivity(personName), 450L);
                } else {
                    statusView.setText("Voce confermata su due campioni.");
                    main.postDelayed(this::greetAndOpenMain, 450L);
                }
                return;
            }
            borderlineScore = similarity;
            statusView.setText(
                "Campione quasi sufficiente e pulito. Ripeti una seconda volta per confermare."
            );
            primaryButton.setText("Ripeti una volta");
            primaryButton.setEnabled(true);
        } else {
            borderlineScore = null;
            statusView.setText(
                "Voce non riconosciuta. Pronuncia la frase completa con tono naturale."
            );
            primaryButton.setText("Riprova verifica vocale");
            primaryButton.setEnabled(true);
        }
    }

    private void greetAndOpenMain() {
        if (openingMain || greetingStarted) {
            return;
        }
        greetingStarted = true;
        capturing.set(false);

        primaryButton.setEnabled(false);
        resetButton.setEnabled(false);
        continueButton.setEnabled(false);
        String greeting = personalGreeting();

        if (greeting.isEmpty()) {
            statusView.setText("Identità confermata.");
            main.postDelayed(() -> openMainActivity(personName), 300L);
            return;
        }

        statusView.setText(greeting);
        if (!ttsReady || tts == null) {
            main.postDelayed(() -> openMainActivity(personName), 500L);
            return;
        }

        int result = tts.speak(
            greeting,
            TextToSpeech.QUEUE_FLUSH,
            null,
            GREETING_UTTERANCE
        );
        if (result == TextToSpeech.ERROR) {
            main.postDelayed(() -> openMainActivity(personName), 350L);
            return;
        }

        main.postDelayed(() -> {
            if (!openingMain) {
                openMainActivity(personName);
            }
        }, 4500L);
    }

    private String personalGreeting() {
        return preferencesStore == null
            ? "Ciao " + personName + ", che piacere vederti."
            : preferencesStore.buildGreeting(personName);
    }

    private void openMainActivity(String recognizedName) {
        if (openingMain) {
            return;
        }
        openingMain = true;

        if (returnToPeopleManager) {
            Intent manager = new Intent(this, PeopleManagerActivity.class);
            manager.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(manager);
            finish();
            return;
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (recognizedName != null && !recognizedName.trim().isEmpty()) {
            intent.putExtra(
                "aurea_recognized_person",
                recognizedName.trim()
            );
        }
        startActivity(intent);
        finish();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != AUDIO_PERMISSION) {
            return;
        }
        boolean granted = grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            configureMode();
        } else {
            titleView.setText("Verifica vocale non disponibile");
            phraseView.setText("");
            statusView.setText(
                adminVerification
                    ? "Il microfono è obbligatorio per l'accesso amministratore."
                    : "Consenti il microfono oppure continua senza verifica vocale."
            );
            primaryButton.setVisibility(View.GONE);
            resetButton.setVisibility(View.GONE);
            continueButton.setVisibility(View.VISIBLE);
        }
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller =
                decor.getWindowInsetsController();
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
    }

    @Override
    protected void onDestroy() {
        openingMain = true;
        capturing.set(false);
        audioExecutor.shutdownNow();

        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }

        super.onDestroy();
    }

    private float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
            ? "errore sconosciuto"
            : message.trim();
    }

    private double blockRms(short[] samples, int length) {
        int end = Math.min(samples.length, Math.max(0, length));
        if (end == 0) return 0d;
        double sum = 0d;
        for (int index = 0; index < end; index++) {
            double value = samples[index];
            sum += value * value;
        }
        return Math.sqrt(sum / end);
    }

    private static final class CaptureResult {
        final boolean success;
        final VoiceSignature.Analysis analysis;
        final String message;

        private CaptureResult(
                boolean success,
                VoiceSignature.Analysis analysis,
                String message) {
            this.success = success;
            this.analysis = analysis;
            this.message = message;
        }

        static CaptureResult success(VoiceSignature.Analysis analysis) {
            return new CaptureResult(true, analysis, "");
        }

        static CaptureResult failure(String message) {
            return new CaptureResult(false, null, message);
        }
    }
}
