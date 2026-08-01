package it.creativemaker3d.aurea;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Coordina dashboard e riconoscimento senza bloccare l'avvio normale.
 *
 * La dashboard è sempre l'attività principale. Volto e voce vengono mostrati
 * come pannelli trasparenti sopra il riquadro sinistro dell'avatar soltanto
 * quando il tablet non è ancora fidato o quando Giuseppe richiede l'accesso
 * amministratore alla gestione delle persone.
 */
public final class AureaApplication extends Application
        implements Application.ActivityLifecycleCallbacks {

    private static final long FACE_DELAY_MS = 1100L;
    private static final long MIN_VOICE_FLOW_MS = 5200L;

    private static final String APP_PREFS = "aurea";
    private static final String PREF_HA_URL = "ha_url";
    private static final String PREF_DASHBOARD_URL = "dashboard_url";
    private static final String PREF_HOME_CONFIRMED = "dashboard_home_confirmed";
    private static final String DEFAULT_HA_URL = "http://192.168.178.72:8123";
    private static final String TABLET_DASHBOARD_PATH = "/lovelace/home";
    private static final String HOME_PICKER_TAG = "aurea_home_picker";

    private final Handler main = new Handler(Looper.getMainLooper());

    private IdentitySessionStore identityStore;
    private AdminAccessStore adminStore;
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
        adminStore = new AdminAccessStore(this);
        registerActivityLifecycleCallbacks(this);
    }

    /**
     * Conserva l'indirizzo appreso dall'utente. Corregge soltanto valori vuoti
     * o il vecchio percorso errato introdotto dalla 0.2.18.
     */
    private void migrateTabletDashboardUrl() {
        SharedPreferences prefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        String haUrl = normalizeHaUrl(
            prefs.getString(PREF_HA_URL, DEFAULT_HA_URL)
        );
        String storedUrl = prefs.getString(PREF_DASHBOARD_URL, "");
        storedUrl = storedUrl == null ? "" : storedUrl.trim();

        boolean confirmed = prefs.getBoolean(PREF_HOME_CONFIRMED, false);
        if (confirmed && isSameHomeAssistantOrigin(storedUrl, haUrl)) {
            return;
        }

        if (storedUrl.isEmpty() || storedUrl.endsWith("/lovelace/casa-tablet")) {
            prefs.edit()
                .putString(PREF_DASHBOARD_URL, haUrl + TABLET_DASHBOARD_PATH)
                .apply();
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle state) {
        if (activity instanceof FaceGateActivity) {
            faceGateActive = true;
            redirecting = false;
            configureIdentityWindow(activity);
            if (adminStore.isAccessRequested()) {
                main.post(() -> secureAdminFaceGate(activity));
            }
            return;
        }

        if (activity instanceof VoiceGateActivity) {
            voiceGateActive = true;
            voiceGateOpenedAt = SystemClock.elapsedRealtime();
            redirecting = false;
            configureIdentityWindow(activity);

            if (adminStore.isAccessRequested()) {
                String person = activity.getIntent() == null
                    ? ""
                    : activity.getIntent().getStringExtra("aurea_recognized_person");
                person = person == null ? "" : person.trim();
                boolean validAdmin = AdminAccessStore.ADMIN_NAME.equalsIgnoreCase(person)
                    && new VoiceProfileStore(this).hasProfile(person);
                if (!validAdmin) {
                    voiceGateActive = false;
                    faceGateActive = false;
                    adminStore.clearRequest();
                    Toast.makeText(
                        this,
                        "Accesso riservato a Giuseppe",
                        Toast.LENGTH_LONG
                    ).show();
                    main.post(activity::finish);
                    return;
                }
                main.post(() -> secureAdminVoiceGate(activity));
            }
            return;
        }

        if (!(activity instanceof MainActivity)) {
            return;
        }

        main.postDelayed(() -> installHomePicker(activity), 1400L);

        Intent source = activity.getIntent();
        String person = source == null
            ? null
            : source.getStringExtra("aurea_recognized_person");
        person = person == null ? "" : person.trim();

        if (!person.isEmpty() && voiceGateActive) {
            long elapsed = SystemClock.elapsedRealtime() - voiceGateOpenedAt;
            boolean profileExists = new VoiceProfileStore(this).hasProfile(person);

            if (adminStore.isAccessRequested()) {
                boolean verifiedAdmin = AdminAccessStore.ADMIN_NAME.equalsIgnoreCase(person)
                    && profileExists
                    && elapsed >= MIN_VOICE_FLOW_MS;

                voiceGateActive = false;
                faceGateActive = false;
                redirecting = false;
                removeOtherAureaTasks(activity);

                if (verifiedAdmin && adminStore.grant(person)) {
                    Toast.makeText(
                        this,
                        "Amministratore verificato",
                        Toast.LENGTH_SHORT
                    ).show();
                    main.postDelayed(() -> launchPeopleManager(activity), 250L);
                } else {
                    adminStore.revoke();
                    Toast.makeText(
                        this,
                        "Verifica amministratore non riuscita",
                        Toast.LENGTH_LONG
                    ).show();
                }
                return;
            }

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
            if (adminStore.isAccessRequested()
                    && !AdminAccessStore.ADMIN_NAME.equalsIgnoreCase(person)) {
                faceGateActive = false;
                voiceGateActive = false;
                redirecting = false;
                adminStore.revoke();
                removeOtherAureaTasks(activity);
                Toast.makeText(
                    this,
                    "Accesso riservato a Giuseppe",
                    Toast.LENGTH_LONG
                ).show();
                return;
            }

            faceGateActive = false;
            launchVoiceGate(activity, person);
            return;
        }

        if (person.isEmpty() && faceGateActive) {
            faceGateActive = false;
            redirecting = false;
            skipRecognitionUntilNextProcess = true;
            if (adminStore.isAccessRequested()) {
                adminStore.revoke();
            }
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

    /**
     * Mostra un pulsante temporaneo finché l'utente non conferma la vera
     * pagina Casa Tablet. In questo modo non dipendiamo dal nome interno della
     * plancia Home Assistant.
     */
    private void installHomePicker(Activity activity) {
        if (!(activity instanceof MainActivity)
                || activity.isFinishing()
                || activity.isDestroyed()) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_HOME_CONFIRMED, false)) {
            return;
        }

        View decor = activity.getWindow().getDecorView();
        if (decor.findViewWithTag(HOME_PICKER_TAG) != null) {
            return;
        }

        Button button = new Button(activity);
        button.setTag(HOME_PICKER_TAG);
        button.setText("Imposta Casa Tablet come avvio");
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setAlpha(0.94f);

        int margin = Math.round(14f * activity.getResources()
            .getDisplayMetrics().density);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START | Gravity.BOTTOM
        );
        params.setMargins(margin, margin, margin, margin);

        button.setOnClickListener(view -> {
            WebView webView = findWebView(
                activity.getWindow().getDecorView()
            );
            String currentUrl = webView == null ? null : webView.getUrl();
            String haUrl = normalizeHaUrl(
                prefs.getString(PREF_HA_URL, DEFAULT_HA_URL)
            );

            if (!isSameHomeAssistantOrigin(currentUrl, haUrl)) {
                Toast.makeText(
                    activity,
                    "Apri prima Casa Tablet, poi premi di nuovo.",
                    Toast.LENGTH_LONG
                ).show();
                return;
            }

            String learnedUrl = currentUrl.trim();
            prefs.edit()
                .putString(PREF_DASHBOARD_URL, learnedUrl)
                .putBoolean(PREF_HOME_CONFIRMED, true)
                .apply();

            button.setVisibility(View.GONE);
            Toast.makeText(
                activity,
                "Casa Tablet impostata come pagina iniziale.",
                Toast.LENGTH_LONG
            ).show();
        });

        activity.addContentView(button, params);
    }

    private WebView findWebView(View view) {
        if (view instanceof WebView) {
            return (WebView) view;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }

        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            WebView found = findWebView(group.getChildAt(index));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean isSameHomeAssistantOrigin(String candidate, String haUrl) {
        if (candidate == null || candidate.trim().isEmpty()) {
            return false;
        }
        try {
            Uri page = Uri.parse(candidate.trim());
            Uri base = Uri.parse(normalizeHaUrl(haUrl));
            return safeEquals(page.getScheme(), base.getScheme())
                && safeEquals(page.getHost(), base.getHost())
                && effectivePort(page) == effectivePort(base);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String normalizeHaUrl(String value) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) {
            result = DEFAULT_HA_URL;
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private boolean safeEquals(String first, String second) {
        if (first == null) {
            return second == null;
        }
        return first.equalsIgnoreCase(second);
    }

    private int effectivePort(Uri uri) {
        int port = uri.getPort();
        if (port >= 0) {
            return port;
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
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

    private void launchPeopleManager(Activity dashboard) {
        if (dashboard.isFinishing() || dashboard.isDestroyed()
                || !adminStore.hasValidGrant()) {
            return;
        }
        Intent manager = new Intent(dashboard, PeopleManagerActivity.class);
        dashboard.startActivity(manager);
    }

    private void secureAdminFaceGate(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        applyAdminGate(activity, activity.getWindow().getDecorView(), false);
    }

    private void secureAdminVoiceGate(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        applyAdminGate(activity, activity.getWindow().getDecorView(), true);
    }

    private void applyAdminGate(Activity activity, View view, boolean voiceStage) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            String value = textView.getText() == null
                ? ""
                : textView.getText().toString().trim();

            if (!voiceStage && value.equals("AUREA ti sta riconoscendo")) {
                textView.setText("Accesso amministratore");
            } else if (!voiceStage
                    && value.equals("Guarda la fotocamera per un momento.")) {
                textView.setText(
                    AdminAccessStore.ADMIN_NAME
                        + ", guarda la fotocamera per autorizzare la gestione persone."
                );
            } else if (voiceStage && value.equals("Conferma vocale")) {
                textView.setText("Conferma amministratore");
            }
        }

        if (view instanceof Button) {
            Button button = (Button) view;
            String label = button.getText() == null
                ? ""
                : button.getText().toString().trim();

            boolean registrationButton = label.equals("Registra un'altra persona")
                || label.equals("Registra di nuovo la voce");
            if (registrationButton) {
                button.setVisibility(View.GONE);
            }

            if (label.startsWith("Continua senza")
                    || label.startsWith("Annulla e torna")) {
                button.setText("Annulla");
                button.setVisibility(View.VISIBLE);
                button.setOnClickListener(v -> {
                    adminStore.revoke();
                    activity.finish();
                });
            }
        }

        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            applyAdminGate(activity, group.getChildAt(index), voiceStage);
        }
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
        if (activity instanceof MainActivity) {
            main.post(() -> installHomePicker(activity));
        }
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
