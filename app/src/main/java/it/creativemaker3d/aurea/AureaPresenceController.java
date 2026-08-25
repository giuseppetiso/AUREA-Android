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
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Rilevamento passivo locale, a bassa frequenza e senza conservare fotogrammi. */
final class AureaPresenceController {
    private static final String PREFS = "aurea_presence";
    private static final String KEY_ENABLED = "enabled";
    private static final long ANALYSIS_INTERVAL_MS = 1200L;
    private static final long PRESENCE_HOLD_MS = 18_000L;
    private static final long DIM_AFTER_MS = 50_000L;
    private static final long WATCHDOG_MS = 1000L;
    private static final long THERMAL_CHECK_MS = 60_000L;
    private static final float PAUSE_TEMPERATURE_C = 45f;
    private static final float RESUME_TEMPERATURE_C = 42f;
    private static final float DIM_BRIGHTNESS = 0.08f;

    private final Activity activity;
    private final LifecycleOwner lifecycleOwner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final AureaPresencePublisher publisher;
    private final AureaDiagnosticsLog log;
    private final FaceDetector detector;

    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis analysis;
    private boolean running;
    private boolean cameraActive;
    private boolean thermalPaused;
    private boolean present;
    private boolean dimmed;
    private boolean destroyed;
    private int detectionStreak;
    private long lastAnalyzedAt;
    private long lastSeenAt;
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
                publisher.publish(false, cameraActive, thermalPaused, lastSeenAt);
            }
            if (now >= nextThermalCheckAt) {
                nextThermalCheckAt = now + THERMAL_CHECK_MS;
                applyThermalGuard();
            }
            setDimmed(!present && now - lastInteractionAt >= DIM_AFTER_MS);
            main.postDelayed(this, WATCHDOG_MS);
        }
    };

    AureaPresenceController(Activity activity, LifecycleOwner lifecycleOwner) {
        this.activity = activity;
        this.lifecycleOwner = lifecycleOwner;
        this.publisher = new AureaPresencePublisher(activity);
        this.log = new AureaDiagnosticsLog(activity);
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
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

    void start() {
        if (destroyed || running) return;
        boolean enabled = isEnabled(activity);
        boolean cameraAllowed = activity.checkSelfPermission(Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
        if (!enabled || !cameraAllowed) {
            publisher.publish(false, false, false, lastSeenAt);
            return;
        }

        running = true;
        lastInteractionAt = System.currentTimeMillis();
        nextThermalCheckAt = 0L;
        normalBrightness = activity.getWindow().getAttributes().screenBrightness;
        main.removeCallbacks(watchdog);
        main.post(watchdog);

        bindCamera();
    }

    void stop() {
        if (!running) return;
        running = false;
        main.removeCallbacks(watchdog);
        present = false;
        detectionStreak = 0;
        unbindCamera();
        setDimmed(false);
        publisher.publish(false, false, thermalPaused, lastSeenAt);
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
        cameraExecutor.shutdownNow();
        publisher.close();
    }

    private void bindCamera() {
        if (!running || destroyed || thermalPaused || cameraActive
                || !isEnabled(activity)) return;
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(activity);
        future.addListener(() -> {
            if (!running || destroyed || thermalPaused) return;
            try {
                cameraProvider = future.get();
                analysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(320, 240))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis
                );
                cameraActive = true;
                publisher.publish(present, true, false, lastSeenAt);
            } catch (Exception error) {
                cameraActive = false;
                publisher.publish(false, false, thermalPaused, lastSeenAt);
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
            .addOnSuccessListener(cameraExecutor, faces ->
                main.post(() -> handleDetection(!faces.isEmpty())))
            .addOnFailureListener(cameraExecutor, error -> {
            })
            .addOnCompleteListener(cameraExecutor, task -> {
                processing.set(false);
                proxy.close();
            });
    }

    private void handleDetection(boolean detected) {
        if (!running || destroyed) return;
        if (!detected) {
            detectionStreak = 0;
            return;
        }
        lastSeenAt = System.currentTimeMillis();
        lastInteractionAt = lastSeenAt;
        setDimmed(false);
        detectionStreak++;
        if (detectionStreak < 2 || present) return;
        present = true;
        publisher.publish(true, cameraActive, thermalPaused, lastSeenAt);
    }

    private void applyThermalGuard() {
        float temperature = AureaTabletTelemetry.capture(activity).temperatureC;
        if (!thermalPaused && temperature >= PAUSE_TEMPERATURE_C) {
            thermalPaused = true;
            present = false;
            unbindCamera();
            publisher.publish(false, false, true, lastSeenAt);
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
