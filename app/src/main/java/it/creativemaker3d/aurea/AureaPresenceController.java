package it.creativemaker3d.aurea;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.Image;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Rilevamento passivo locale, a bassa frequenza e senza conservare fotogrammi. */
final class AureaPresenceController {
    private static final String PREFS = "aurea_presence";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_RECOGNITION_ENABLED = "recognition_enabled";
    private static final long ANALYSIS_INTERVAL_MS = 1200L;
    private static final long PRESENCE_HOLD_MS = 18_000L;
    private static final long DIM_AFTER_MS = 50_000L;
    private static final long WATCHDOG_MS = 1000L;
    private static final long THERMAL_CHECK_MS = 60_000L;
    private static final long CAMERA_STALL_MS = 20_000L;
    private static final long CAMERA_RETRY_MS = 10_000L;
    private static final float PAUSE_TEMPERATURE_C = 45f;
    private static final float RESUME_TEMPERATURE_C = 42f;
    private static final float DIM_BRIGHTNESS = 0.08f;
    private static final int REQUIRED_IDENTITY_MATCHES = 3;
    private static final int REQUIRED_UNKNOWN_MATCHES = 4;
    private static final int REQUIRED_UNKNOWN_AFTER_IDENTITY = 8;

    private final Activity activity;
    private final LifecycleOwner lifecycleOwner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final AureaPresencePublisher publisher;
    private final AureaIdentityPublisher identityPublisher;
    private final AureaDiagnosticsLog log;
    private final FaceDetector detector;
    private final AureaPassiveFaceRecognizer recognizer;

    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis analysis;
    private boolean running;
    private boolean cameraActive;
    private boolean cameraBinding;
    private boolean thermalPaused;
    private boolean present;
    private boolean dimmed;
    private boolean destroyed;
    private int detectionStreak;
    private String currentIdentity = AureaIdentityPublisher.NONE;
    private String candidateIdentity = "";
    private int candidateIdentityMatches;
    private float currentIdentityConfidence;
    private long lastAnalyzedAt;
    private volatile long lastFrameAt;
    private long nextCameraRetryAt;
    private long lastSeenAt;
    private long lastRecognizedAt;
    private long lastInteractionAt;
    private long nextThermalCheckAt;
    private float normalBrightness = -1f;

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!running || destroyed) return;
            long now = System.currentTimeMillis();
            if (present && now - lastSeenAt > PRESENCE_HOLD_MS) {
                present = false;
                detectionStreak = 0;
                clearIdentity();
                publisher.publish(false, cameraActive, thermalPaused, lastSeenAt);
                publishIdentity();
            }
            if (now >= nextThermalCheckAt) {
                nextThermalCheckAt = now + THERMAL_CHECK_MS;
                applyThermalGuard();
            }
            if (!thermalPaused && isEnabled(activity)) {
                if (cameraActive && now - lastFrameAt >= CAMERA_STALL_MS) {
                    log.info(
                        "AUREA Presence",
                        "Flusso fotocamera fermo; riavvio automatico"
                    );
                    unbindCamera();
                    nextCameraRetryAt = now + CAMERA_RETRY_MS;
                    bindCamera();
                } else if (!cameraActive && !cameraBinding
                        && now >= nextCameraRetryAt) {
                    nextCameraRetryAt = now + CAMERA_RETRY_MS;
                    bindCamera();
                }
            }
            setDimmed(!present && now - lastInteractionAt >= DIM_AFTER_MS);
            main.postDelayed(this, WATCHDOG_MS);
        }
    };

    AureaPresenceController(Activity activity, LifecycleOwner lifecycleOwner) {
        this.activity = activity;
        this.lifecycleOwner = lifecycleOwner;
        this.publisher = new AureaPresencePublisher(activity);
        this.identityPublisher = new AureaIdentityPublisher(activity);
        this.log = new AureaDiagnosticsLog(activity);
        this.recognizer = new AureaPassiveFaceRecognizer(activity);
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.18f)
            .build();
        detector = FaceDetection.getClient(options);
    }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    static boolean isRecognitionEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RECOGNITION_ENABLED, true);
    }

    static void setRecognitionEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_RECOGNITION_ENABLED, enabled).apply();
    }

    void start() {
        if (destroyed || running) return;
        boolean enabled = isEnabled(activity);
        boolean cameraAllowed = activity.checkSelfPermission(Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
        if (!enabled || !cameraAllowed) {
            publisher.publish(false, false, false, lastSeenAt);
            currentIdentity = isRecognitionEnabled(activity)
                ? AureaIdentityPublisher.NONE : AureaIdentityPublisher.DISABLED;
            publishIdentity();
            return;
        }

        running = true;
        recognizer.reload();
        currentIdentity = isRecognitionEnabled(activity)
            ? AureaIdentityPublisher.NONE : AureaIdentityPublisher.DISABLED;
        candidateIdentity = "";
        candidateIdentityMatches = 0;
        currentIdentityConfidence = 0f;
        lastInteractionAt = System.currentTimeMillis();
        lastFrameAt = lastInteractionAt;
        nextCameraRetryAt = lastInteractionAt;
        nextThermalCheckAt = 0L;
        normalBrightness = activity.getWindow().getAttributes().screenBrightness;
        main.removeCallbacks(watchdog);
        main.post(watchdog);

        publishIdentity();

        bindCamera();
    }

    void stop() {
        if (!running) return;
        running = false;
        main.removeCallbacks(watchdog);
        present = false;
        detectionStreak = 0;
        clearIdentity();
        unbindCamera();
        setDimmed(false);
        publisher.publish(false, false, thermalPaused, lastSeenAt);
        publishIdentity();
    }

    void userActivity() {
        lastInteractionAt = System.currentTimeMillis();
        setDimmed(false);
    }

    void destroy() {
        if (destroyed) return;
        stop();
        destroyed = true;
        detector.close();
        recognizer.close();
        cameraExecutor.shutdownNow();
        publisher.close();
        identityPublisher.close();
    }

    private void bindCamera() {
        if (!running || destroyed || thermalPaused || cameraActive || cameraBinding
                || !isEnabled(activity)) return;
        cameraBinding = true;
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(activity);
        future.addListener(() -> {
            cameraBinding = false;
            if (!running || destroyed || thermalPaused) return;
            try {
                cameraProvider = future.get();
                analysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis
                );
                cameraActive = true;
                lastFrameAt = System.currentTimeMillis();
                nextCameraRetryAt = lastFrameAt + CAMERA_RETRY_MS;
                publisher.publish(present, true, false, lastSeenAt);
                publishIdentity();
            } catch (Exception error) {
                cameraActive = false;
                nextCameraRetryAt = System.currentTimeMillis() + CAMERA_RETRY_MS;
                publisher.publish(false, false, thermalPaused, lastSeenAt);
                publishIdentity();
                log.warning(
                    "AUREA Presence",
                    "Fotocamera passiva non disponibile: " + safeMessage(error)
                );
            }
        }, ContextCompat.getMainExecutor(activity));
    }

    private void unbindCamera() {
        if (cameraProvider != null && analysis != null) {
            try {
                cameraProvider.unbind(analysis);
            } catch (Exception ignored) {
            }
        }
        analysis = null;
        cameraActive = false;
    }

    private void analyzeFrame(@NonNull ImageProxy proxy) {
        long now = System.currentTimeMillis();
        lastFrameAt = now;
        if (!running || thermalPaused || now - lastAnalyzedAt < ANALYSIS_INTERVAL_MS
                || !processing.compareAndSet(false, true)) {
            proxy.close();
            return;
        }
        lastAnalyzedAt = now;
        Image image = proxy.getImage();
        if (image == null) {
            processing.set(false);
            proxy.close();
            return;
        }

        InputImage input = InputImage.fromMediaImage(
            image,
            proxy.getImageInfo().getRotationDegrees()
        );
        detector.process(input)
            .addOnSuccessListener(cameraExecutor, faces -> {
                Face face = largestFace(faces);
                AureaPassiveFaceRecognizer.Match match = null;
                boolean recognitionEnabled = isRecognitionEnabled(activity);
                int profiles = recognizer.profileCount();
                boolean evaluated = face != null && recognitionEnabled
                    && (profiles == 0 || AureaPassiveFaceRecognizer.isUsableFace(face));
                if (evaluated && profiles > 0) {
                    match = recognizer.recognize(proxy, face);
                }
                AureaPassiveFaceRecognizer.Match finalMatch = match;
                main.post(() -> handleDetection(face != null, evaluated, finalMatch));
            })
            .addOnFailureListener(cameraExecutor, error -> {
            })
            .addOnCompleteListener(cameraExecutor, task -> {
                processing.set(false);
                proxy.close();
            });
    }

    private void handleDetection(
            boolean detected,
            boolean identityEvaluated,
            AureaPassiveFaceRecognizer.Match match) {
        if (!running || destroyed) return;
        if (!detected) {
            detectionStreak = 0;
            candidateIdentity = "";
            candidateIdentityMatches = 0;
            return;
        }
        lastSeenAt = System.currentTimeMillis();
        lastInteractionAt = lastSeenAt;
        setDimmed(false);
        detectionStreak++;
        if (detectionStreak >= 2 && !present) {
            present = true;
            publisher.publish(true, cameraActive, thermalPaused, lastSeenAt);
        }
        if (identityEvaluated) processIdentity(match);
    }

    private void processIdentity(AureaPassiveFaceRecognizer.Match match) {
        if (!isRecognitionEnabled(activity)) {
            setIdentity(AureaIdentityPublisher.DISABLED, 0f);
            return;
        }
        String candidate = match == null
            ? AureaIdentityPublisher.UNKNOWN : match.name;
        if (candidate.equals(candidateIdentity)) {
            candidateIdentityMatches++;
        } else {
            candidateIdentity = candidate;
            candidateIdentityMatches = 1;
        }
        boolean namedIdentityActive = !AureaIdentityPublisher.NONE.equals(currentIdentity)
            && !AureaIdentityPublisher.UNKNOWN.equals(currentIdentity)
            && !AureaIdentityPublisher.DISABLED.equals(currentIdentity);
        int required = AureaIdentityPublisher.UNKNOWN.equals(candidate)
            ? (namedIdentityActive
                ? REQUIRED_UNKNOWN_AFTER_IDENTITY
                : REQUIRED_UNKNOWN_MATCHES)
            : REQUIRED_IDENTITY_MATCHES;
        if (candidateIdentityMatches < required) return;
        setIdentity(candidate, match == null ? 0f : match.score);
    }

    private void setIdentity(String identity, float confidence) {
        String value = identity == null || identity.trim().isEmpty()
            ? AureaIdentityPublisher.NONE : identity.trim();
        if (value.equals(currentIdentity)
                && Math.abs(currentIdentityConfidence - confidence) < 0.01f) return;
        currentIdentity = value;
        currentIdentityConfidence = confidence;
        if (!AureaIdentityPublisher.NONE.equals(value)
                && !AureaIdentityPublisher.UNKNOWN.equals(value)
                && !AureaIdentityPublisher.DISABLED.equals(value)) {
            lastRecognizedAt = System.currentTimeMillis();
        }
        publishIdentity();
    }

    private void clearIdentity() {
        candidateIdentity = "";
        candidateIdentityMatches = 0;
        currentIdentityConfidence = 0f;
        currentIdentity = isRecognitionEnabled(activity)
            ? AureaIdentityPublisher.NONE : AureaIdentityPublisher.DISABLED;
    }

    private void publishIdentity() {
        identityPublisher.publish(
            currentIdentity,
            currentIdentityConfidence,
            present,
            cameraActive,
            isRecognitionEnabled(activity),
            recognizer.profileCount(),
            lastRecognizedAt
        );
    }

    private Face largestFace(List<Face> faces) {
        Face largest = null;
        int area = 0;
        if (faces == null) return null;
        for (Face face : faces) {
            int candidateArea = Math.max(0, face.getBoundingBox().width())
                * Math.max(0, face.getBoundingBox().height());
            if (candidateArea > area) {
                largest = face;
                area = candidateArea;
            }
        }
        return largest;
    }

    private void applyThermalGuard() {
        float temperature = AureaTabletTelemetry.capture(activity).temperatureC;
        if (!thermalPaused && temperature >= PAUSE_TEMPERATURE_C) {
            thermalPaused = true;
            present = false;
            clearIdentity();
            unbindCamera();
            publisher.publish(false, false, true, lastSeenAt);
            publishIdentity();
            log.warning(
                "AUREA Presence",
                "Fotocamera sospesa dalla protezione termica"
            );
            return;
        }
        if (thermalPaused && (temperature <= 0f || temperature <= RESUME_TEMPERATURE_C)) {
            thermalPaused = false;
            log.info("AUREA Presence", "Temperatura regolare; fotocamera riattivata");
            bindCamera();
        }
    }

    private void setDimmed(boolean shouldDim) {
        if (dimmed == shouldDim || destroyed) return;
        dimmed = shouldDim;
        WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
        attributes.screenBrightness = shouldDim ? DIM_BRIGHTNESS : normalBrightness;
        activity.getWindow().setAttributes(attributes);
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error == null ? "errore sconosciuto" : error.getClass().getSimpleName();
        }
        message = message.trim();
        return message.length() > 160 ? message.substring(0, 160) : message;
    }
}
