package it.creativemaker3d.aurea;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements RecognitionListener {
    private static final int AUDIO_PERMISSION = 41;

    private static final String PREFS = "aurea";
    private static final String PREF_HA_URL = "ha_url";
    private static final String PREF_HA_TOKEN = "ha_token";
    private static final String PREF_DASHBOARD_URL = "dashboard_url";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private WebView dashboard;
    private PermissionRequest pendingWebPermission;
    private SpeechRecognizer recognizer;
    private Intent commandIntent;
    private TextToSpeech tts;
    private UpdateManager updateManager;
    private VoskWakeWord wakeWord;

    private boolean commandListening;
    private boolean speaking;
    private boolean destroyed;
    private boolean activityVisible;
    private boolean startCommandAfterPermission;
    private boolean recoveringWebView;
    private boolean modelPreparingNoticeShown;
    private boolean wakeReadyNoticeShown;

    private String haUrl;
    private String haToken;
    private String dashboardUrl;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        haUrl = prefs.getString(PREF_HA_URL, "http://192.168.178.72:8123");
        haToken = prefs.getString(PREF_HA_TOKEN, "");
        dashboardUrl = prefs.getString(
            PREF_DASHBOARD_URL,
            haUrl + "/lovelace/home");

        if (haToken.isEmpty()) {
            showSetup();
        } else {
            showDashboard();
        }

        updateManager = new UpdateManager(this);
        updateManager.check(false);
    }

    private void showSetup() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(80, 50, 80, 50);
        panel.setBackgroundColor(Color.rgb(2, 7, 13));

        TextView title = new TextView(this);
        title.setText("AUREA · Prima configurazione");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        panel.addView(title);

        EditText url = field("Indirizzo Home Assistant", haUrl);
        EditText dash = field("Dashboard", dashboardUrl);
        EditText token = field("Token dedicato AUREA", "");
        panel.addView(url);
        panel.addView(dash);
        panel.addView(token);

        Button save = new Button(this);
        save.setText("Salva e avvia AUREA");
        save.setOnClickListener(v -> {
            haUrl = trimSlash(url.getText().toString().trim());
            dashboardUrl = dash.getText().toString().trim();
            haToken = token.getText().toString().trim();

            if (haUrl.isEmpty() || haToken.isEmpty()) {
                Toast.makeText(
                    this,
                    "Inserisci indirizzo e token",
                    Toast.LENGTH_LONG).show();
                return;
            }

            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_HA_URL, haUrl)
                .putString(PREF_DASHBOARD_URL, dashboardUrl)
                .putString(PREF_HA_TOKEN, haToken)
                .apply();

            showDashboard();
        });
        panel.addView(save);
        setContentView(panel);
    }

    private EditText field(String hint, String value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        input.setSingleLine(true);
        input.setPadding(16, 18, 16, 18);
        input.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return input;
    }

    private void showDashboard() {
        initTextToSpeech();

        dashboard = new WebView(this);
        dashboard.setBackgroundColor(Color.rgb(2, 7, 13));
        dashboard.addJavascriptInterface(new AureaBridge(), "AureaNative");

        WebSettings settings = dashboard.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(dashboard, true);

        dashboard.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request) {
                return handleAureaUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleAureaUrl(Uri.parse(url));
            }

            @Override
            public boolean onRenderProcessGone(
                    WebView view,
                    android.webkit.RenderProcessGoneDetail detail) {
                recoverDashboard();
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                installNativeButtons(view);
                prepareWakeWordIfPossible();
            }
        });

        dashboard.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleWebPermission(request));
            }
        });

        setContentView(dashboard);
        dashboard.post(this::hideSystemUi);
        dashboard.loadUrl(dashboardUrl);
        requestAudioPermissionOnStartup();
    }

    private final class AureaBridge {
        @JavascriptInterface
        public void startListening() {
            main.post(MainActivity.this::startOneShotListening);
        }

        @JavascriptInterface
        public void closeApp() {
            main.post(MainActivity.this::closeAurea);
        }

        @JavascriptInterface
        public void checkUpdates() {
            main.post(() -> {
                if (updateManager != null) {
                    updateManager.check(true);
                }
            });
        }
    }

    private void installNativeButtons(WebView view) {
        String script = "(function(){"
            + "if(window.__aureaNativeButtonInstalled)return;"
            + "window.__aureaNativeButtonInstalled=true;"
            + "document.addEventListener('click',function(e){"
            + "var p=e.composedPath?e.composedPath():[];"
            + "var text=function(n){return ((n&&((n.innerText||n.textContent)))||'')"
            + ".trim().replace(/\\s+/g,' ');};"
            + "var hit=p.some(function(n){var t=text(n);"
            + "return t==='Parla con AUREA'||t==='Attiva Assist';});"
            + "if(hit){e.preventDefault();e.stopImmediatePropagation();"
            + "window.AureaNative.startListening();return;}"
            + "var closeHit=p.some(function(n){return text(n)==='Chiudi AUREA';});"
            + "if(closeHit){e.preventDefault();e.stopImmediatePropagation();"
            + "window.AureaNative.closeApp();return;}"
            + "var updateHit=p.some(function(n){return text(n)==='Controlla aggiornamenti';});"
            + "if(updateHit){e.preventDefault();e.stopImmediatePropagation();"
            + "window.AureaNative.checkUpdates();}"
            + "},true);"
            + "})();";
        view.evaluateJavascript(script, null);
    }

    private boolean handleAureaUrl(Uri uri) {
        if (uri == null || !"aurea".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }

        String host = uri.getHost();
        if ("listen".equalsIgnoreCase(host)) {
            startOneShotListening();
        } else if ("close".equalsIgnoreCase(host)) {
            closeAurea();
        } else if ("update".equalsIgnoreCase(host)) {
            if (updateManager != null) {
                updateManager.check(true);
            }
        }
        return true;
    }

    private void closeAurea() {
        stopWakeWord();
        stopCommandRecognition();
        finishAndRemoveTask();
    }

    private void initTextToSpeech() {
        if (tts != null || destroyed) {
            return;
        }

        tts = new TextToSpeech(getApplicationContext(), status -> main.post(() -> {
            if (destroyed || status != TextToSpeech.SUCCESS || tts == null) {
                return;
            }

            tts.setLanguage(Locale.ITALIAN);
            tts.setSpeechRate(0.94f);
            tts.setOnUtteranceProgressListener(
                new android.speech.tts.UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                        main.post(() -> {
                            stopWakeWord();
                            speaking = true;
                            setDashboardState("speak");
                        });
                    }

                    @Override
                    public void onDone(String id) {
                        main.post(() -> {
                            speaking = false;
                            setDashboardState("idle");
                            main.postDelayed(
                                MainActivity.this::startWakeWord,
                                450L);
                        });
                    }

                    @Override
                    public void onError(String id) {
                        main.post(() -> {
                            speaking = false;
                            setDashboardState("idle");
                            main.postDelayed(
                                MainActivity.this::startWakeWord,
                                650L);
                        });
                    }
                });
        }));
    }

    private void requestAudioPermissionOnStartup() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            prepareWakeWordIfPossible();
            return;
        }

        startCommandAfterPermission = false;
        main.postDelayed(() -> {
            if (!destroyed
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    AUDIO_PERMISSION);
            }
        }, 700L);
    }

    private void recoverDashboard() {
        if (recoveringWebView || destroyed) {
            return;
        }

        recoveringWebView = true;
        stopWakeWord();
        stopCommandRecognition();

        main.post(() -> {
            WebView failed = dashboard;
            dashboard = null;
            if (failed != null) {
                failed.stopLoading();
                failed.clearCache(true);
                failed.destroy();
            }
            recoveringWebView = false;
            showDashboard();
        });
    }

    private boolean ensureRecognizer() {
        if (recognizer != null) {
            return true;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                this,
                "Riconoscimento vocale non disponibile",
                Toast.LENGTH_LONG).show();
            return false;
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);

        commandIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        commandIntent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        commandIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT");
        commandIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        commandIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        commandIntent.putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            1100L);
        commandIntent.putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            700L);
        return true;
    }

    private void startOneShotListening() {
        stopWakeWord();

        if (destroyed || speaking || commandListening) {
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            startCommandAfterPermission = true;
            requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO},
                AUDIO_PERMISSION);
            return;
        }

        if (!ensureRecognizer()) {
            startWakeWord();
            return;
        }

        commandListening = true;
        setDashboardState("listen");

        try {
            recognizer.startListening(commandIntent);
        } catch (RuntimeException error) {
            commandListening = false;
            setDashboardState("idle");
            Toast.makeText(
                this,
                "Microfono momentaneamente occupato",
                Toast.LENGTH_SHORT).show();
            main.postDelayed(this::startWakeWord, 900L);
        }
    }

    private void stopCommandRecognition() {
        if (recognizer != null && commandListening) {
            try {
                recognizer.cancel();
            } catch (RuntimeException ignored) {
            }
        }
        commandListening = false;
    }

    private void sendCommand(String command) {
        stopWakeWord();
        setDashboardState("think");

        io.execute(() -> {
            try {
                URL endpoint = new URL(haUrl + "/api/conversation/process");
                HttpURLConnection connection =
                    (HttpURLConnection) endpoint.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer " + haToken);
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json");
                connection.setDoOutput(true);

                JSONObject request = new JSONObject();
                request.put("text", command);
                request.put("language", "it");

                try (OutputStream output = connection.getOutputStream()) {
                    output.write(
                        request.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
                String body = readAll(stream);

                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("HTTP " + code);
                }

                JSONObject root = new JSONObject(body);
                String answer = root.getJSONObject("response")
                    .getJSONObject("speech")
                    .getJSONObject("plain")
                    .optString("speech", "Fatto.");
                speak(answer);
            } catch (Exception error) {
                speak("Non riesco a comunicare con Home Assistant.");
            }
        });
    }

    private String readAll(InputStream input) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private void speak(String text) {
        main.post(() -> {
            stopWakeWord();
            stopCommandRecognition();

            if (tts == null) {
                speaking = false;
                setDashboardState("idle");
                startWakeWord();
                return;
            }

            speaking = true;
            int result = tts.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                new Bundle(),
                "aurea-answer");

            if (result == TextToSpeech.ERROR) {
                speaking = false;
                setDashboardState("idle");
                startWakeWord();
            }
        });
    }

    private void prepareWakeWordIfPossible() {
        if (destroyed
                || dashboard == null
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        ensureWakeWordController();
        wakeWord.initialize();
    }

    private void ensureWakeWordController() {
        if (wakeWord != null) {
            return;
        }

        wakeWord = new VoskWakeWord(
            getApplicationContext(),
            main,
            new VoskWakeWord.Listener() {
                @Override
                public void onPreparing() {
                    if (modelPreparingNoticeShown) {
                        return;
                    }
                    modelPreparingNoticeShown = true;
                    Toast.makeText(
                        MainActivity.this,
                        "Preparazione ascolto locale: scarico una sola volta "
                            + "il modello italiano (circa 48 MB).",
                        Toast.LENGTH_LONG).show();
                }

                @Override
                public void onReady() {
                    if (!wakeReadyNoticeShown) {
                        wakeReadyNoticeShown = true;
                        Toast.makeText(
                            MainActivity.this,
                            "Parola “Aurea” pronta: ascolto continuo locale attivo.",
                            Toast.LENGTH_LONG).show();
                    }
                    startWakeWord();
                }

                @Override
                public void onDetected() {
                    handleWakeWordDetected();
                }

                @Override
                public void onError(String message) {
                    Toast.makeText(
                        MainActivity.this,
                        message,
                        Toast.LENGTH_LONG).show();
                }
            });
    }

    private void startWakeWord() {
        if (destroyed
                || !activityVisible
                || speaking
                || commandListening
                || dashboard == null
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        ensureWakeWordController();
        if (!wakeWord.isReady()) {
            wakeWord.initialize();
            return;
        }
        wakeWord.start();
    }

    private void stopWakeWord() {
        if (wakeWord != null) {
            wakeWord.stop();
        }
    }

    private void handleWakeWordDetected() {
        if (destroyed || speaking || commandListening) {
            return;
        }

        stopWakeWord();
        setDashboardState("listen");
        main.postDelayed(this::startOneShotListening, 260L);
    }

    private void setDashboardState(String state) {
        if (dashboard == null) {
            return;
        }

        main.post(() -> {
            if (dashboard == null || destroyed) {
                return;
            }
            dashboard.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('aurea-state',"
                    + "{detail:'" + state + "'}));",
                null);
        });
    }

    private void handleWebPermission(PermissionRequest request) {
        boolean wantsAudio = false;
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                wantsAudio = true;
                break;
            }
        }

        if (!wantsAudio) {
            request.deny();
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            request.grant(
                new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            pendingWebPermission = request;
            requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO},
                AUDIO_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);

        if (requestCode != AUDIO_PERMISSION) {
            return;
        }

        boolean granted = results.length > 0
            && results[0] == PackageManager.PERMISSION_GRANTED;

        if (pendingWebPermission != null) {
            if (granted) {
                pendingWebPermission.grant(
                    new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingWebPermission.deny();
            }
            pendingWebPermission = null;
        }

        if (!granted) {
            Toast.makeText(
                this,
                "Consenti il microfono per parlare con AUREA",
                Toast.LENGTH_LONG).show();
            return;
        }

        if (startCommandAfterPermission) {
            startCommandAfterPermission = false;
            startOneShotListening();
        } else {
            prepareWakeWordIfPossible();
        }
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
    }

    @Override
    public void onBeginningOfSpeech() {
    }

    @Override
    public void onRmsChanged(float rms) {
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
    }

    @Override
    public void onError(int error) {
        commandListening = false;
        setDashboardState("idle");

        if (!destroyed
                && error != SpeechRecognizer.ERROR_NO_MATCH
                && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                && error != SpeechRecognizer.ERROR_CLIENT) {
            Toast.makeText(
                this,
                "Non ho capito, riprova",
                Toast.LENGTH_SHORT).show();
        }

        main.postDelayed(this::startWakeWord, 700L);
    }

    @Override
    public void onResults(Bundle results) {
        commandListening = false;
        ArrayList<String> phrases =
            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

        if (phrases == null
                || phrases.isEmpty()
                || phrases.get(0).trim().isEmpty()) {
            setDashboardState("idle");
            main.postDelayed(this::startWakeWord, 600L);
            return;
        }

        sendCommand(phrases.get(0).trim());
    }

    @Override
    public void onPartialResults(Bundle results) {
    }

    @Override
    public void onEvent(int type, Bundle params) {
    }

    private String trimSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private void hideSystemUi() {
        View decorView = getWindow().getDecorView();
        decorView.post(() -> {
            if (destroyed) {
                return;
            }

            if (android.os.Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller =
                    decorView.getWindowInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(
                        WindowInsetsController
                            .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (dashboard != null && dashboard.canGoBack()) {
            dashboard.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityVisible = true;

        if (dashboard != null) {
            hideSystemUi();
            dashboard.onResume();
            main.postDelayed(this::startWakeWord, 700L);
        }

        if (updateManager != null) {
            updateManager.resumePendingInstall();
        }
    }

    @Override
    protected void onPause() {
        activityVisible = false;
        stopWakeWord();
        stopCommandRecognition();

        if (dashboard != null) {
            dashboard.onPause();
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        activityVisible = false;
        stopWakeWord();

        if (pendingWebPermission != null) {
            pendingWebPermission.deny();
            pendingWebPermission = null;
        }

        if (wakeWord != null) {
            wakeWord.delete();
            wakeWord = null;
        }

        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }

        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }

        io.shutdownNow();

        if (updateManager != null) {
            updateManager.close();
            updateManager = null;
        }

        if (dashboard != null) {
            dashboard.stopLoading();
            dashboard.destroy();
            dashboard = null;
        }

        super.onDestroy();
    }
}
