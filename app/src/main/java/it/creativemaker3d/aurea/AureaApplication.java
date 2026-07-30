package it.creativemaker3d.aurea;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;

/**
 * Coordina dashboard e riconoscimento senza bloccare l'avvio normale.
 *
 * La dashboard è sempre l'attività principale. Volto e voce vengono mostrati
 * come pannelli trasparenti sopra il riquadro sinistro dell'avatar soltanto
 * quando il tablet non è ancora fidato.
 */
public final class AureaApplication extends Application
        implements Application.ActivityLifecycleCallbacks {

    private static final long FACE_DELAY_MS = 1100L;
    private static final long MIN_VOICE_FLOW_MS = 5200L;

    private static final String APP_PREFS = "aurea";
    private static final String PREF_HA_URL = "ha_url";
    private static final String PREF_DASHBOARD_URL = "dashboard_url";
    private static final String DEFAULT_HA_URL = "http://192.168.178.72:8123";
    private static final String TABLET_DASHBOARD_PATH = "/lovelace/casa-tablet";

    private final Handler main = new Handler(Looper.getMainLooper());

    private IdentitySessionStore identityStore;
    private boolean faceGateActive;
    private boolean voiceGateActive;
    private boolean redirecting;
    private boolean skipRecognitionUntilNextProcess;
    private long voiceGateOpenedAt;

    @Override
    public void onCreate() {
        super.onCreate();
        migrateTabletDashboardUrl();
        identityStore = new IdentitySessionStore(this);
        registerActivityLifecycleCallbacks(this);
    }

    /**
     * Mantiene il token e l'indirizzo Home Assistant già configurati, ma forza
     * l'avvio sulla vista Lovelace dedicata al tablet. La scrittura avviene
     * prima che MainActivity legga le preferenze.
     */
    private void migrateTabletDashboardUrl() {
        SharedPreferences prefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        String haUrl = prefs.getString(PREF_HA_URL, DEFAULT_HA_URL);
        if (haUrl == null || haUrl.trim().isEmpty()) {
            haUrl = DEFAULT_HA_URL;
        } else {
            haUrl = haUrl.trim();
        }
        while (haUrl.endsWith("/")) {
            haUrl = haUrl.substring(0, haUrl.length() - 1);
        }

        String desiredUrl = haUrl + TABLET_DASHBOARD_PATH;
        String storedUrl = prefs.getString(PREF_DASHBOARD_URL, "");
        if (!desiredUrl.equals(storedUrl)) {
            prefs.edit()
                .putString(PREF_DASHBOARD_URL, desiredUrl)
                .apply();
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle state) {
        if (activity instanceof FaceGateActivity) {
            faceGateActive = true;
            redirecting = false;
            configureIdentityWindow(activity);
            return;
        }

        if (activity instanceof VoiceGateActivity) {
            voiceGateActive = true;
            voiceGateOpenedAt = SystemClock.elapsedRealtime();
            redirecting = false;
            configureIdentityWindow(activity);
            return;
        }

        if (!(activity instanceof MainActivity)) {
            return;
        }

        Intent source = activity.getIntent();
        String person = source == null
            ? null
            : source.getStringExtra("aurea_recognized_person");
        person = person == null ? "" : person.trim();

        if (!person.isEmpty() && voiceGateActive) {
            long elapsed = SystemClock.elapsedRealtime() - voiceGateOpenedAt;
            boolean profileExists = new VoiceProfileStore(this).hasProfile(person);
            if (profileExists && elapsed >= MIN_VOICE_FLOW_MS) {
                identityStore.trust(person);
            } else {
                skipRecognitionUntilNextProcess = true;
            }
            voiceGateActive = false;
            faceGateActive = false;
            redirecting = false;
            removeOtherAureaTasks(activity);
            return;
        }

        if (!person.isEmpty() && faceGateActive) {
            faceGateActive = false;
            launchVoiceGate(activity, person);
            return;
        }

        if (person.isEmpty() && faceGateActive) {
            faceGateActive = false;
            redirecting = false;
            skipRecognitionUntilNextProcess = true;
            removeOtherAureaTasks(activity);
            return;
        }

        boolean migrated = identityStore.migrateExistingProfilesIfNeeded();
        if (migrated || identityStore.isTrusted()) {
            removeOtherAureaTasks(activity);
            return;
        }

        if (skipRecognitionUntilNextProcess || redirecting) {
            return;
        }

        redirecting = true;
        main.postDelayed(() -> launchFaceGate(activity), FACE_DELAY_MS);
    }

    private void launchFaceGate(Activity dashboard) {
        if (dashboard.isFinishing() || dashboard.isDestroyed()
                || identityStore.isTrusted()
                || skipRecognitionUntilNextProcess) {
            redirecting = false;
            return;
        }

        Intent face = new Intent(dashboard, FaceGateActivity.class);
        face.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        face.putExtra("aurea_identity_overlay", true);
        startActivity(face);
    }

    private void launchVoiceGate(Activity transientDashboard, String person) {
        if (redirecting || person == null || person.trim().isEmpty()) {
            return;
        }

        redirecting = true;
        Intent voice = new Intent(transientDashboard, VoiceGateActivity.class);
        voice.putExtra("aurea_recognized_person", person.trim());
        voice.putExtra("aurea_identity_overlay", true);
        transientDashboard.startActivity(voice);
        transientDashboard.finish();
    }

    private void configureIdentityWindow(Activity activity) {
        Window window = activity.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int screenWidth = Math.max(metrics.widthPixels, metrics.heightPixels);
        int screenHeight = Math.min(metrics.widthPixels, metrics.heightPixels);
        int panelWidth = Math.max(1, screenWidth / 2);
        int margin = Math.round(10f * metrics.density);
        int panelHeight = Math.min(panelWidth, Math.max(1, screenHeight - margin));

        WindowManager.LayoutParams params = window.getAttributes();
        params.gravity = Gravity.START | Gravity.TOP;
        params.width = panelWidth;
        params.height = panelHeight;
        params.x = 0;
        params.y = 0;
        params.dimAmount = 0f;
        window.setAttributes(params);
        window.setLayout(panelWidth, panelHeight);
        activity.overridePendingTransition(0, 0);
    }

    private void removeOtherAureaTasks(Activity current) {
        try {
            int currentTask = current.getTaskId();
            ActivityManager manager =
                (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (manager == null) {
                return;
            }
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info != null && info.taskId != currentTask) {
                    task.finishAndRemoveTask();
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (activity instanceof FaceGateActivity && !activity.isChangingConfigurations()) {
            faceGateActive = false;
            redirecting = false;
        }
        if (activity instanceof VoiceGateActivity && !activity.isChangingConfigurations()) {
            voiceGateActive = false;
            redirecting = false;
        }
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityResumed(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle state) {
    }
}
