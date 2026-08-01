package it.creativemaker3d.aurea;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Schermata locale di registrazione/riconoscimento del volto.
 *
 * Non salva fotografie: conserva soltanto una firma numerica normalizzata.
 * Il riconoscimento serve alla personalizzazione domestica e non sostituisce
 * un'autenticazione di sicurezza per portoni, allarmi o pagamenti.
 */
public final class FaceGateActivity extends ComponentActivity {
    private static final int CAMERA_PERMISSION = 52;
    private static final int TARGET_SAMPLES = 10;
    private static final long SAMPLE_INTERVAL_MS = 320L;
    private static final int REQUIRED_MATCHES = 3;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final ArrayList<float[]> enrollmentSamples = new ArrayList<>();

    private PreviewView previewView;
    private TextView titleView;
    private TextView statusView;
    private EditText nameInput;
    private Button primaryButton;
    private Button addPersonButton;
    private Button continueButton;

    private ProcessCameraProvider cameraProvider;
    private FaceDetector faceDetector;
    private FaceTemplateStore templateStore;
    private List<FaceProfile> profiles = new ArrayList<>();

    private boolean enrollmentMode;
    private boolean enrollmentActive;
    private boolean recognitionActive;
    private boolean openingMain;
    private boolean forcedEnrollment;
    private long lastSampleAt;
    private String candidateName;
    private int candidateMatches;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        templateStore = new FaceTemplateStore();
        profiles = templateStore.loadProfiles();
        faceDetector = createFaceDetector();
        buildInterface();
        hideSystemUi();

        forcedEnrollment = getIntent() != null
            && getIntent().getBooleanExtra("aurea_force_enrollment", false);
        if (forcedEnrollment || profiles.isEmpty()) {
            enterEnrollmentMode();
        } else {
            enterRecognitionMode();
        }

        if (checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
    }

    private FaceDetector createFaceDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.28f)
            .build();
        return FaceDetection.getClient(options);
    }

    private void buildInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(2, 7, 13));

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        View shade = new View(this);
        shade.setBackgroundColor(Color.argb(70, 0, 0, 0));
        root.addView(shade, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        top.setPadding(36, 40, 36, 22);
        top.setBackgroundColor(Color.argb(180, 2, 7, 13));

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(25);
        titleView.setGravity(Gravity.CENTER);
        titleView.setText("AUREA · Riconoscimento locale");
        top.addView(titleView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(210, 225, 238));
        statusView.setTextSize(17);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 12, 0, 0);
        top.addView(statusView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP);
        root.addView(top, topParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(34, 24, 34, 34);
        controls.setBackgroundColor(Color.argb(215, 2, 7, 13));

        nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setTextColor(Color.WHITE);
        nameInput.setHintTextColor(Color.GRAY);
        nameInput.setHint("Nome della persona, per esempio Giuseppe");
        nameInput.setTextSize(17);
        nameInput.setPadding(18, 16, 18, 16);
        controls.addView(nameInput, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        primaryButton = new Button(this);
        primaryButton.setText("Inizia registrazione volto");
        primaryButton.setOnClickListener(v -> beginEnrollment());
        controls.addView(primaryButton, buttonParams());

        addPersonButton = new Button(this);
        addPersonButton.setText("Registra un'altra persona");
        addPersonButton.setOnClickListener(v -> enterEnrollmentMode());
        controls.addView(addPersonButton, buttonParams());

        continueButton = new Button(this);
        continueButton.setText("Continua senza riconoscimento");
        continueButton.setOnClickListener(v -> openMainActivity(null));
        controls.addView(continueButton, buttonParams());

        TextView privacy = new TextView(this);
        privacy.setText(
            "Le immagini non vengono salvate. AUREA conserva soltanto una firma numerica locale."
        );
        privacy.setTextColor(Color.rgb(160, 180, 195));
        privacy.setTextSize(13);
        privacy.setGravity(Gravity.CENTER);
        privacy.setPadding(4, 14, 4, 0);
        controls.addView(privacy, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM);
        root.addView(controls, controlsParams);
        setContentView(root);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = 10;
        return params;
    }

    private void enterEnrollmentMode() {
        enrollmentMode = true;
        enrollmentActive = false;
        recognitionActive = false;
        enrollmentSamples.clear();
        candidateName = null;
        candidateMatches = 0;

        titleView.setText("Registra il volto");
        statusView.setText(
            "Inserisci il nome, poi guarda la fotocamera e muovi lentamente il viso."
        );
        nameInput.setVisibility(View.VISIBLE);
        nameInput.setEnabled(true);
        primaryButton.setVisibility(View.VISIBLE);
        primaryButton.setEnabled(true);
        addPersonButton.setVisibility(View.GONE);
        continueButton.setVisibility(View.VISIBLE);
    }

    private void beginEnrollment() {
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Inserisci il nome della persona", Toast.LENGTH_LONG).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }

        enrollmentSamples.clear();
        enrollmentActive = true;
        recognitionActive = false;
        lastSampleAt = 0L;
        nameInput.setEnabled(false);
        primaryButton.setEnabled(false);
        statusView.setText(
            "Guarda dritto e muovi lentamente il viso. Campioni acquisiti: 0/"
                + TARGET_SAMPLES
        );
    }

    private void enterRecognitionMode() {
        enrollmentMode = false;
        enrollmentActive = false;
        recognitionActive = true;
        candidateName = null;
        candidateMatches = 0;

        titleView.setText("AUREA ti sta riconoscendo");
        statusView.setText("Guarda la fotocamera per un momento.");
        nameInput.setVisibility(View.GONE);
        primaryButton.setVisibility(View.GONE);
        addPersonButton.setVisibility(View.VISIBLE);
        continueButton.setVisibility(View.VISIBLE);

        main.postDelayed(() -> {
            if (!openingMain && recognitionActive && candidateMatches < REQUIRED_MATCHES) {
                statusView.setText(
                    "Non ti ho ancora riconosciuto. Guarda dritto oppure continua senza riconoscimento."
                );
            }
        }, 8000L);
    }

    private void startCamera() {
        if (openingMain) {
            return;
        }
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception error) {
                statusView.setText("Fotocamera non disponibile: " + safeMessage(error));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null || openingMain) {
            return;
        }

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
            .setTargetResolution(new Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();
        analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_FRONT_CAMERA,
            preview,
            analysis
        );
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        if (openingMain || (!enrollmentActive && !recognitionActive)) {
            imageProxy.close();
            return;
        }
        if (!processing.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }

        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            processing.set(false);
            imageProxy.close();
            return;
        }

        Bitmap original = null;
        Bitmap rotated = null;
        try {
            original = grayscaleBitmap(imageProxy);
            rotated = rotateBitmap(
                original,
                imageProxy.getImageInfo().getRotationDegrees()
            );
        } catch (Exception error) {
            recycle(original);
            recycle(rotated);
            processing.set(false);
            imageProxy.close();
            return;
        }

        final Bitmap frameBitmap = rotated;
        final Bitmap sourceBitmap = original;
        InputImage input = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.getImageInfo().getRotationDegrees()
        );

        faceDetector.process(input)
            .addOnSuccessListener(cameraExecutor, faces -> {
                Face face = largestFace(faces);
                if (face == null) {
                    main.post(() -> statusForNoFace());
                    return;
                }
                if (!isUsableFace(face, frameBitmap)) {
                    main.post(() -> statusView.setText(
                        "Avvicinati leggermente e guarda dritto verso la fotocamera."
                    ));
                    return;
                }

                float[] signature = FaceSignature.create(
                    frameBitmap,
                    face.getBoundingBox()
                );
                if (signature == null) {
                    return;
                }

                if (enrollmentActive) {
                    processEnrollmentSample(signature);
                } else if (recognitionActive) {
                    processRecognitionSample(signature);
                }
            })
            .addOnFailureListener(cameraExecutor, error -> main.post(() ->
                statusView.setText("Rilevamento volto non disponibile: " + safeMessage(error))
            ))
            .addOnCompleteListener(cameraExecutor, task -> {
                recycle(frameBitmap);
                if (sourceBitmap != frameBitmap) {
                    recycle(sourceBitmap);
                }
                processing.set(false);
                imageProxy.close();
            });
    }

    private void statusForNoFace() {
        if (openingMain) {
            return;
        }
        if (enrollmentActive) {
            statusView.setText("Posiziona un solo volto al centro dello schermo.");
        } else if (recognitionActive) {
            statusView.setText("Avvicinati e guarda la fotocamera.");
        }
    }

    private Face largestFace(List<Face> faces) {
        Face largest = null;
        int largestArea = 0;
        for (Face face : faces) {
            Rect box = face.getBoundingBox();
            int area = Math.max(0, box.width()) * Math.max(0, box.height());
            if (area > largestArea) {
                largest = face;
                largestArea = area;
            }
        }
        return largest;
    }

    private boolean isUsableFace(Face face, Bitmap bitmap) {
        Rect box = face.getBoundingBox();
        if (box.width() < 120 || box.height() < 120) {
            return false;
        }
        if (Math.abs(face.getHeadEulerAngleY()) > 28f
                || Math.abs(face.getHeadEulerAngleZ()) > 20f) {
            return false;
        }
        Float leftEye = face.getLeftEyeOpenProbability();
        Float rightEye = face.getRightEyeOpenProbability();
        if (leftEye != null && rightEye != null
                && (leftEye < 0.35f || rightEye < 0.35f)) {
            return false;
        }
        return box.right > 0
            && box.bottom > 0
            && box.left < bitmap.getWidth()
            && box.top < bitmap.getHeight();
    }

    private void processEnrollmentSample(float[] signature) {
        long now = System.currentTimeMillis();
        synchronized (enrollmentSamples) {
            if (!enrollmentActive || now - lastSampleAt < SAMPLE_INTERVAL_MS) {
                return;
            }
            lastSampleAt = now;
            enrollmentSamples.add(signature);
            int count = enrollmentSamples.size();
            main.post(() -> statusView.setText(
                "Muovi lentamente il viso. Campioni acquisiti: "
                    + count + "/" + TARGET_SAMPLES
            ));
            if (count < TARGET_SAMPLES) {
                return;
            }
            enrollmentActive = false;
        }

        float[] mean = FaceSignature.mean(enrollmentSamples);
        if (mean == null) {
            main.post(() -> resetEnrollmentAfterError(
                "Registrazione non riuscita. Riprova con luce uniforme."
            ));
            return;
        }

        float minimumSimilarity = 1f;
        for (float[] sample : enrollmentSamples) {
            minimumSimilarity = Math.min(
                minimumSimilarity,
                FaceSignature.similarity(mean, sample)
            );
        }
        float threshold = clamp(minimumSimilarity - 0.08f, 0.74f, 0.88f);
        String name = nameInput.getText().toString().trim();
        templateStore.saveProfile(name, mean, threshold);
        profiles = templateStore.loadProfiles();

        main.post(() -> {
            statusView.setText("Volto registrato localmente: " + name + ".");
            Toast.makeText(
                this,
                "Registrazione completata per " + name,
                Toast.LENGTH_LONG
            ).show();
            main.postDelayed(() -> openMainActivity(name), 1200L);
        });
    }

    private void resetEnrollmentAfterError(String message) {
        enrollmentSamples.clear();
        enrollmentActive = false;
        nameInput.setEnabled(true);
        primaryButton.setEnabled(true);
        statusView.setText(message);
    }

    private void processRecognitionSample(float[] signature) {
        FaceProfile best = null;
        float bestScore = -1f;
        for (FaceProfile profile : profiles) {
            float score = FaceSignature.similarity(profile.vector, signature);
            if (score > bestScore) {
                best = profile;
                bestScore = score;
            }
        }

        if (best == null || bestScore < best.threshold) {
            candidateName = null;
            candidateMatches = 0;
            main.post(() -> statusView.setText("Sto confrontando il volto…"));
            return;
        }

        if (best.name.equals(candidateName)) {
            candidateMatches++;
        } else {
            candidateName = best.name;
            candidateMatches = 1;
        }

        final String matchedName = best.name;
        final int matches = candidateMatches;
        main.post(() -> {
            if (matches < REQUIRED_MATCHES) {
                statusView.setText("Riconoscimento in corso…");
                return;
            }
            recognitionActive = false;
            statusView.setText("Ciao " + matchedName + ".");
            main.postDelayed(() -> openMainActivity(matchedName), 700L);
        });
    }

    private Bitmap grayscaleBitmap(ImageProxy imageProxy) {
        ImageProxy.PlaneProxy plane = imageProxy.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int[] pixels = new int[width * height];

        for (int y = 0; y < height; y++) {
            int rowOffset = y * rowStride;
            for (int x = 0; x < width; x++) {
                int index = rowOffset + x * pixelStride;
                int luminance = index < buffer.limit() ? buffer.get(index) & 0xff : 0;
                pixels[y * width + x] = Color.rgb(luminance, luminance, luminance);
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private Bitmap rotateBitmap(Bitmap source, int degrees) {
        if (degrees == 0) {
            return source;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.getWidth(),
            source.getHeight(),
            matrix,
            true
        );
    }

    private void openMainActivity(String recognizedName) {
        if (openingMain) {
            return;
        }
        openingMain = true;
        enrollmentActive = false;
        recognitionActive = false;

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        if (forcedEnrollment
                && recognizedName != null
                && !recognizedName.trim().isEmpty()) {
            Intent voice = new Intent(this, VoiceGateActivity.class);
            voice.putExtra("aurea_recognized_person", recognizedName.trim());
            voice.putExtra("aurea_identity_overlay", true);
            startActivity(voice);
            finish();
            return;
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (recognizedName != null && !recognizedName.trim().isEmpty()) {
            intent.putExtra("aurea_recognized_person", recognizedName.trim());
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
        if (requestCode != CAMERA_PERMISSION) {
            return;
        }
        boolean granted = grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            startCamera();
        } else {
            statusView.setText(
                "Consenti la fotocamera per registrare o riconoscere il volto."
            );
            enrollmentActive = false;
            recognitionActive = false;
        }
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
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
    }

    @Override
    protected void onDestroy() {
        openingMain = true;
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        if (faceDetector != null) {
            faceDetector.close();
            faceDetector = null;
        }
        cameraExecutor.shutdownNow();
        super.onDestroy();
    }

    private void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
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

    private final class FaceTemplateStore {
        private static final String PREFS = "aurea_face_profiles";
        private static final String KEY_PROFILES = "profiles";

        List<FaceProfile> loadProfiles() {
            ArrayList<FaceProfile> result = new ArrayList<>();
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String raw = prefs.getString(KEY_PROFILES, "{}");
            try {
                JSONObject root = new JSONObject(raw);
                JSONArray names = root.names();
                if (names == null) {
                    return result;
                }
                for (int i = 0; i < names.length(); i++) {
                    String name = names.optString(i, "").trim();
                    if (name.isEmpty()) {
                        continue;
                    }
                    JSONObject stored = root.optJSONObject(name);
                    if (stored == null) {
                        continue;
                    }
                    float[] vector = decodeVector(stored.optString("vector", ""));
                    float threshold = (float) stored.optDouble("threshold", 0.80);
                    if (vector != null && vector.length == FaceSignature.VECTOR_SIZE) {
                        result.add(new FaceProfile(name, vector, threshold));
                    }
                }
            } catch (Exception ignored) {
            }
            return result;
        }

        void saveProfile(String name, float[] vector, float threshold) {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String raw = prefs.getString(KEY_PROFILES, "{}");
            try {
                JSONObject root = new JSONObject(raw);
                JSONObject value = new JSONObject();
                value.put("vector", encodeVector(vector));
                value.put("threshold", threshold);
                root.put(name.trim(), value);
                prefs.edit().putString(KEY_PROFILES, root.toString()).apply();
            } catch (Exception error) {
                throw new IllegalStateException("Impossibile salvare il volto", error);
            }
        }

        private String encodeVector(float[] vector) {
            ByteBuffer bytes = ByteBuffer
                .allocate(vector.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
            for (float value : vector) {
                bytes.putFloat(value);
            }
            return Base64.encodeToString(bytes.array(), Base64.NO_WRAP);
        }

        private float[] decodeVector(String encoded) {
            try {
                byte[] data = Base64.decode(encoded, Base64.NO_WRAP);
                if (data.length % 4 != 0) {
                    return null;
                }
                ByteBuffer bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                float[] vector = new float[data.length / 4];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = bytes.getFloat();
                }
                return vector;
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static final class FaceProfile {
        final String name;
        final float[] vector;
        final float threshold;

        FaceProfile(String name, float[] vector, float threshold) {
            this.name = name;
            this.vector = vector;
            this.threshold = threshold;
        }
    }

    private static final class FaceSignature {
        private static final int IMAGE_SIZE = 64;
        private static final int PIXEL_GRID = 16;
        private static final int LBP_BLOCKS = 4;
        private static final int LBP_BINS = 16;
        static final int VECTOR_SIZE =
            PIXEL_GRID * PIXEL_GRID + LBP_BLOCKS * LBP_BLOCKS * LBP_BINS;

        static float[] create(Bitmap source, Rect detectedBounds) {
            if (source == null || detectedBounds == null) {
                return null;
            }

            int centerX = detectedBounds.centerX();
            int centerY = detectedBounds.centerY();
            int square = Math.round(
                Math.max(detectedBounds.width(), detectedBounds.height()) * 1.22f
            );
            square = Math.min(square, Math.min(source.getWidth(), source.getHeight()));
            int left = clampInt(centerX - square / 2, 0, source.getWidth() - square);
            int top = clampInt(centerY - square / 2, 0, source.getHeight() - square);
            if (square < 80) {
                return null;
            }

            Bitmap crop = null;
            Bitmap scaled = null;
            try {
                crop = Bitmap.createBitmap(source, left, top, square, square);
                scaled = Bitmap.createScaledBitmap(crop, IMAGE_SIZE, IMAGE_SIZE, true);
                int[] pixels = new int[IMAGE_SIZE * IMAGE_SIZE];
                scaled.getPixels(
                    pixels,
                    0,
                    IMAGE_SIZE,
                    0,
                    0,
                    IMAGE_SIZE,
                    IMAGE_SIZE
                );
                float[] gray = equalizedGray(pixels);
                float[] vector = new float[VECTOR_SIZE];
                appendPixelGrid(gray, vector, 0);
                appendLbp(gray, vector, PIXEL_GRID * PIXEL_GRID);
                normalize(vector);
                return vector;
            } catch (Exception ignored) {
                return null;
            } finally {
                if (scaled != null && scaled != crop && !scaled.isRecycled()) {
                    scaled.recycle();
                }
                if (crop != null && !crop.isRecycled()) {
                    crop.recycle();
                }
            }
        }

        static float[] mean(List<float[]> samples) {
            if (samples == null || samples.isEmpty()) {
                return null;
            }
            float[] mean = new float[VECTOR_SIZE];
            int valid = 0;
            for (float[] sample : samples) {
                if (sample == null || sample.length != VECTOR_SIZE) {
                    continue;
                }
                valid++;
                for (int i = 0; i < VECTOR_SIZE; i++) {
                    mean[i] += sample[i];
                }
            }
            if (valid == 0) {
                return null;
            }
            for (int i = 0; i < VECTOR_SIZE; i++) {
                mean[i] /= valid;
            }
            normalize(mean);
            return mean;
        }

        static float similarity(float[] first, float[] second) {
            if (first == null || second == null || first.length != second.length) {
                return -1f;
            }
            double dot = 0d;
            double firstNorm = 0d;
            double secondNorm = 0d;
            for (int i = 0; i < first.length; i++) {
                dot += first[i] * second[i];
                firstNorm += first[i] * first[i];
                secondNorm += second[i] * second[i];
            }
            if (firstNorm <= 0d || secondNorm <= 0d) {
                return -1f;
            }
            return (float) (dot / Math.sqrt(firstNorm * secondNorm));
        }

        private static float[] equalizedGray(int[] pixels) {
            int[] histogram = new int[256];
            int[] raw = new int[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                int value = Color.red(pixels[i]);
                raw[i] = value;
                histogram[value]++;
            }

            int[] cumulative = new int[256];
            int running = 0;
            for (int i = 0; i < 256; i++) {
                running += histogram[i];
                cumulative[i] = running;
            }
            int firstNonZero = 0;
            while (firstNonZero < 255 && histogram[firstNonZero] == 0) {
                firstNonZero++;
            }
            int minimum = cumulative[firstNonZero];
            int denominator = Math.max(1, pixels.length - minimum);
            float[] result = new float[pixels.length];
            for (int i = 0; i < raw.length; i++) {
                result[i] = Math.max(
                    0f,
                    Math.min(255f, (cumulative[raw[i]] - minimum) * 255f / denominator)
                );
            }
            return result;
        }

        private static void appendPixelGrid(float[] gray, float[] vector, int offset) {
            int cell = IMAGE_SIZE / PIXEL_GRID;
            float[] values = new float[PIXEL_GRID * PIXEL_GRID];
            float mean = 0f;
            int position = 0;
            for (int gridY = 0; gridY < PIXEL_GRID; gridY++) {
                for (int gridX = 0; gridX < PIXEL_GRID; gridX++) {
                    float sum = 0f;
                    for (int y = 0; y < cell; y++) {
                        int row = (gridY * cell + y) * IMAGE_SIZE;
                        for (int x = 0; x < cell; x++) {
                            sum += gray[row + gridX * cell + x];
                        }
                    }
                    float value = sum / (cell * cell);
                    values[position++] = value;
                    mean += value;
                }
            }
            mean /= values.length;
            float variance = 0f;
            for (float value : values) {
                float delta = value - mean;
                variance += delta * delta;
            }
            float standardDeviation = (float) Math.sqrt(
                variance / Math.max(1, values.length - 1)
            );
            standardDeviation = Math.max(8f, standardDeviation);
            for (int i = 0; i < values.length; i++) {
                vector[offset + i] = (values[i] - mean) / standardDeviation;
            }
        }

        private static void appendLbp(float[] gray, float[] vector, int offset) {
            int blockSize = IMAGE_SIZE / LBP_BLOCKS;
            for (int y = 1; y < IMAGE_SIZE - 1; y++) {
                for (int x = 1; x < IMAGE_SIZE - 1; x++) {
                    float center = gray[y * IMAGE_SIZE + x];
                    int code = 0;
                    code |= gray[(y - 1) * IMAGE_SIZE + (x - 1)] >= center ? 1 : 0;
                    code |= gray[(y - 1) * IMAGE_SIZE + x] >= center ? 2 : 0;
                    code |= gray[(y - 1) * IMAGE_SIZE + (x + 1)] >= center ? 4 : 0;
                    code |= gray[y * IMAGE_SIZE + (x + 1)] >= center ? 8 : 0;
                    code |= gray[(y + 1) * IMAGE_SIZE + (x + 1)] >= center ? 16 : 0;
                    code |= gray[(y + 1) * IMAGE_SIZE + x] >= center ? 32 : 0;
                    code |= gray[(y + 1) * IMAGE_SIZE + (x - 1)] >= center ? 64 : 0;
                    code |= gray[y * IMAGE_SIZE + (x - 1)] >= center ? 128 : 0;

                    int blockX = Math.min(LBP_BLOCKS - 1, x / blockSize);
                    int blockY = Math.min(LBP_BLOCKS - 1, y / blockSize);
                    int bin = code >>> 4;
                    int index = offset
                        + (blockY * LBP_BLOCKS + blockX) * LBP_BINS
                        + bin;
                    vector[index] += 1f;
                }
            }

            for (int block = 0; block < LBP_BLOCKS * LBP_BLOCKS; block++) {
                int start = offset + block * LBP_BINS;
                float sum = 0f;
                for (int bin = 0; bin < LBP_BINS; bin++) {
                    sum += vector[start + bin];
                }
                if (sum <= 0f) {
                    continue;
                }
                for (int bin = 0; bin < LBP_BINS; bin++) {
                    vector[start + bin] /= sum;
                }
            }
        }

        private static void normalize(float[] vector) {
            double norm = 0d;
            for (float value : vector) {
                norm += value * value;
            }
            if (norm <= 0d) {
                return;
            }
            float scale = (float) (1d / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
        }

        private static int clampInt(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }
    }
}
