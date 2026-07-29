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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VoiceGateActivity extends Activity {
    private static final int AUDIO_PERMISSION = 61;
    private static final int SAMPLE_RATE = 16000;
    private static final int MAX_RECORD_SECONDS = 4;
    private static final int REQUIRED_ENROLLMENT_SAMPLES = 3;

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

    private VoiceProfileStore profileStore;
    private String personName;
    private boolean enrollmentMode;
    private boolean openingMain;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        personName = getIntent().getStringExtra("aurea_recognized_person");
        if (personName == null || personName.trim().isEmpty()) {
            openMainActivity(null);
            return;
        }
        personName = personName.trim();

        profileStore = new VoiceProfileStore(this);
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
        continueButton.setText("Continua senza verifica vocale");
        continueButton.setOnClickListener(v -> openMainActivity(personName));
        root.addView(continueButton, buttonParams());

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
        if (profileStore.hasProfile(personName)) {
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
            "Premi il pulsante e ripeti la frase tre volte con voce naturale."
        );
        primaryButton.setText("Registra frase 1/3");
        primaryButton.setEnabled(true);
        primaryButton.setOnClickListener(v -> startCapture());
        resetButton.setVisibility(View.GONE);
        continueButton.setVisibility(View.VISIBLE);
    }

    private void enterVerificationMode() {
        enrollmentMode = false;
        enrollmentSamples.clear();
        titleView.setText("Conferma vocale");
        phraseView.setText("«" + phrase() + "»");
        statusView.setText(
            "AUREA confronterà la voce con il profilo di " + personName + "."
        );
        primaryButton.setText("Avvia verifica vocale");
        primaryButton.setEnabled(true);
        primaryButton.setOnClickListener(v -> startCapture());
        resetButton.setVisibility(View.VISIBLE);
        continueButton.setVisibility(View.VISIBLE);

        main.postDelayed(() -> {
            if (!openingMain && !capturing.get() && !enrollmentMode) {
                startCapture();
            }
        }, 900L);
    }

    private void startCapture() {
        if (openingMain || !capturing.compareAndSet(false, true)) {
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
                float[] signature = VoiceSignature.create(pcm, SAMPLE_RATE);
                result = signature == null
                    ? CaptureResult.failure(
                        "Voce troppo bassa o frase incompleta. Riprova."
                    )
                    : CaptureResult.success(signature);
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
        short[] block = new short[Math.max(1024, bufferBytes / 2)];
        int count = 0;

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
            handleEnrollment(result.signature);
        } else {
            handleVerification(result.signature);
        }
    }

    private void handleEnrollment(float[] signature) {
        enrollmentSamples.add(signature);
        int count = enrollmentSamples.size();
        if (count < REQUIRED_ENROLLMENT_SAMPLES) {
            statusView.setText(
                "Campione " + count + "/3 acquisito. Ripeti la stessa frase."
            );
            primaryButton.setText("Registra frase " + (count + 1) + "/3");
            primaryButton.setEnabled(true);
            return;
        }

        float[] mean = VoiceSignature.mean(enrollmentSamples);
        if (mean == null) {
            enrollmentSamples.clear();
            statusView.setText("Registrazione non riuscita. Ricomincia.");
            primaryButton.setText("Ricomincia registrazione");
            primaryButton.setEnabled(true);
            return;
        }

        float minimumSimilarity = 1f;
        for (float[] sample : enrollmentSamples) {
            minimumSimilarity = Math.min(
                minimumSimilarity,
                VoiceSignature.similarity(mean, sample)
            );
        }
        float threshold = clamp(minimumSimilarity - 0.05f, 0.86f, 0.95f);
        profileStore.saveProfile(personName, mean, threshold);

        statusView.setText("Voce registrata localmente per " + personName + ".");
        primaryButton.setText("Continua");
        primaryButton.setEnabled(true);
        primaryButton.setOnClickListener(v -> openMainActivity(personName));
        Toast.makeText(
            this,
            "Registrazione vocale completata",
            Toast.LENGTH_LONG
        ).show();
        main.postDelayed(() -> openMainActivity(personName), 1200L);
    }

    private void handleVerification(float[] signature) {
        VoiceProfileStore.VoiceProfile profile =
            profileStore.loadProfile(personName);
        if (profile == null) {
            enterEnrollmentMode();
            return;
        }

        float similarity = VoiceSignature.similarity(
            profile.signature,
            signature
        );
        if (similarity >= profile.threshold) {
            statusView.setText("Voce confermata. Ciao " + personName + ".");
            primaryButton.setText("Confermato");
            main.postDelayed(() -> openMainActivity(personName), 750L);
        } else {
            statusView.setText(
                "Voce non riconosciuta. Riprova pronunciando la frase completa."
            );
            primaryButton.setText("Riprova verifica vocale");
            primaryButton.setEnabled(true);
        }
    }

    private void openMainActivity(String recognizedName) {
        if (openingMain) {
            return;
        }
        openingMain = true;
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
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
                "Consenti il microfono oppure continua senza verifica vocale."
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
        audioExecutor.shutdownNow();
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

    private static final class CaptureResult {
        final boolean success;
        final float[] signature;
        final String message;

        private CaptureResult(
                boolean success,
                float[] signature,
                String message) {
            this.success = success;
            this.signature = signature;
            this.message = message;
        }

        static CaptureResult success(float[] signature) {
            return new CaptureResult(true, signature, "");
        }

        static CaptureResult failure(String message) {
            return new CaptureResult(false, null, message);
        }
    }
}
