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
import android.webkit.WebResourceRequest;
import android.webkit.WebChromeClient;
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
    private static final int MODE_IDLE = 0;
    private static final int MODE_WAKE_WORD = 1;
    private static final int MODE_COMMAND = 2;
    private static final long WAKE_RESTART_DELAY_MS = 700L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable wakeRestart = this::startWakeWordListening;

    private WebView dashboard;
    private PermissionRequest pendingWebPermission;
    private SpeechRecognizer recognizer;
    private Intent commandIntent;
    private Intent wakeWordIntent;
    private TextToSpeech tts;
    private UpdateManager updateManager;
    private boolean listening;
    private boolean speaking;
    private boolean destroyed;
    private boolean activityVisible;
    private boolean startListeningAfterPermission;
    private boolean recoveringWebView;
    private boolean ignoreRecognitionCallbacks;
    private int recognitionMode = MODE_IDLE;
    private String haUrl;
    private String haToken;
    private String dashboardUrl;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SharedPreferences prefs = getSharedPreferences("aurea", MODE_PRIVATE);
        haUrl = prefs.getString("ha_url", "http://192.168.178.72:8123");
        haToken = prefs.getString("ha_token", "");
        dashboardUrl = prefs.getString("dashboard_url", haUrl + "/lovelace/home");

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
                Toast.makeText(this, "Inserisci indirizzo e token", Toast.LENGTH_LONG).show();
                return;
            }
            getSharedPreferences("aurea", MODE_PRIVATE).edit()
                .putString("ha_url", haUrl)
                .putString("dashboard_url", dashboardUrl)
                .putString("ha_token", haToken)
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
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleAureaUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleAureaUrl(Uri.parse(url));
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                recoverDashboard();
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                installNativeVoiceButton(view);
                scheduleWakeWordListening(900L);
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
            main.post(() -> startOneShotListening());
        }

        @JavascriptInterface
        public void closeApp() {
            main.post(MainActivity.this::closeAurea);
        }

        @JavascriptInterface
        public void checkUpdates() {
            main.post(() -> {
                if (updateManager != null) updateManager.check(true);
            });
        }
    }

    private void installNativeVoiceButton(WebView view) {
        String script = "(function(){"
            + "if(window.__aureaNativeButtonInstalled)return;"
            + "window.__aureaNativeButtonInstalled=true;"
            + "document.addEventListener('click',function(e){"
            + "var p=e.composedPath?e.composedPath():[];"
            + "var hit=p.some(function(n){"
            + "var t=((n&&((n.innerText||n.textContent)))||'').trim().replace(/\\s+/g,' ');"
            + "return t==='Parla con AUREA'||t==='Attiva Assist';"
            + "});"
            + "if(hit){e.preventDefault();e.stopImmediatePropagation();"
            + "window.AureaNative.startListening();}"
            + "var closeHit=p.some(function(n){"
            + "var t=((n&&((n.innerText||n.textContent)))||'').trim().replace(/\\s+/g,' ');"
            + "return t==='Chiudi AUREA';"
            + "});"
            + "if(closeHit){e.preventDefault();e.stopImmediatePropagation();"
            + "window.AureaNative.closeApp();}"
            + "var updateHit=p.some(function(n){"
            + "var t=((n&&((n.innerText||n.textContent)))||'').trim().replace(/\\s+/g,' ');"
            + "return t==='Controlla aggiornamenti';"
            + "});"
            + "if(updateHit){e.preventDefault();e.stopImmediatePropagation();"
            + "window.AureaNative.checkUpdates();}"
            + "},true);"
            + "})();";
        view.evaluateJavascript(script, null);
    }

    private boolean handleAureaUrl(Uri uri) {
        if (uri == null || !"aurea".equalsIgnoreCase(uri.getScheme())) return false;
        if ("listen".equalsIgnoreCase(uri.getHost())) {
            startOneShotListening();
        } else if ("close".equalsIgnoreCase(uri.getHost())) {
            closeAurea();
        } else if ("update".equalsIgnoreCase(uri.getHost())) {
            if (updateManager != null) updateManager.check(true);
        }
        return true;
    }

    private void closeAurea() {
        cancelWakeWordRestart();
        stopCurrentRecognition();
        finishAndRemoveTask();
    }

    private void initTextToSpeech() {
        if (tts != null || destroyed) return;
        tts = new TextToSpeech(getApplicationContext(), status -> main.post(() -> {
            if (destroyed || status != TextToSpeech.SUCCESS || tts == null) return;
            tts.setLanguage(Locale.ITALIAN);
            tts.setSpeechRate(0.94f);
            tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String id) {
                    main.post(() -> {
                        cancelWakeWordRestart();
                        speaking = true;
                        setDashboardState("speak");
                    });
                }

                @Override public void onDone(String id) {
                    main.post(() -> {
                        speaking = false;
                        setDashboardState("idle");
                        scheduleWakeWordListening(500L);
                    });
                }

                @Override public void onError(String id) {
                    main.post(() -> {
                        speaking = false;
                        setDashboardState("idle");
                        scheduleWakeWordListening(700L);
                    });
                }
            });
        }));
    }

    private void requestAudioPermissionOnStartup() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            scheduleWakeWordListening(900L);
            return;
        }
        startListeningAfterPermission = false;
        main.postDelayed(() -> {
            if (!destroyed && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
            }
        }, 700);
    }

    private void recoverDashboard() {
        if (recoveringWebView || destroyed) return;
        recoveringWebView = true;
        cancelWakeWordRestart();
        stopCurrentRecognition();
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
        if (recognizer != null) return true;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Riconoscimento vocale non disponibile", Toast.LENGTH_LONG).show();
            return false;
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);

        commandIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        commandIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        commandIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT");
        commandIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        commandIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        commandIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L);
        commandIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L);

        wakeWordIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        wakeWordIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        wakeWordIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT");
        wakeWordIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        wakeWordIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        wakeWordIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L);
        wakeWordIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 450L);
        return true;
    }

    private void startOneShotListening() {
        cancelWakeWordRestart();
        if (destroyed || speaking) return;

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startListeningAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
            return;
        }

        if (listening) {
            stopCurrentRecognition();
            main.postDelayed(this::startOneShotListening, 650L);
            return;
        }

        if (!ensureRecognizer()) return;
        beginRecognition(MODE_COMMAND, commandIntent);
    }

    private void startWakeWordListening() {
        if (destroyed || !activityVisible || speaking || dashboard == null || listening) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        if (!ensureRecognizer()) return;
        beginRecognition(MODE_WAKE_WORD, wakeWordIntent);
    }

    private void beginRecognition(int mode, Intent intent) {
        ignoreRecognitionCallbacks = false;
        recognitionMode = mode;
        listening = true;
        if (mode == MODE_COMMAND) setDashboardState("listen");
        try {
            recognizer.startListening(intent);
        } catch (RuntimeException error) {
            listening = false;
            recognitionMode = MODE_IDLE;
            if (mode == MODE_COMMAND) {
                setDashboardState("idle");
                Toast.makeText(this, "Microfono momentaneamente occupato", Toast.LENGTH_SHORT).show();
            }
            scheduleWakeWordListening(1200L);
        }
    }

    private void stopCurrentRecognition() {
        cancelWakeWordRestart();
        recognitionMode = MODE_IDLE;
        if (recognizer != null && listening) {
            ignoreRecognitionCallbacks = true;
            try {
                recognizer.cancel();
            } catch (RuntimeException ignored) {
            }
            main.postDelayed(() -> ignoreRecognitionCallbacks = false, 350L);
        }
        listening = false;
    }

    private void scheduleWakeWordListening(long delayMs) {
        cancelWakeWordRestart();
        if (destroyed || !activityVisible || speaking || dashboard == null) return;
        main.postDelayed(wakeRestart, Math.max(delayMs, WAKE_RESTART_DELAY_MS));
    }

    private void cancelWakeWordRestart() {
        main.removeCallbacks(wakeRestart);
    }

    private void sendCommand(String command) {
        cancelWakeWordRestart();
        setDashboardState("think");
        io.execute(() -> {
            try {
                URL endpoint = new URL(haUrl + "/api/conversation/process");
                HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty("Authorization", "Bearer " + haToken);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);

                JSONObject request = new JSONObject();
                request.put("text", command);
                request.put("language", "it");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(request.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
                String body = readAll(stream);
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);

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
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private void speak(String text) {
        main.post(() -> {
            cancelWakeWordRestart();
            stopCurrentRecognition();
            if (tts == null) {
                speaking = false;
                setDashboardState("idle");
                scheduleWakeWordListening(700L);
                return;
            }
            speaking = true;
            int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), "aurea-answer");
            if (result == TextToSpeech.ERROR) {
                speaking = false;
                setDashboardState("idle");
                scheduleWakeWordListening(700L);
            }
        });
    }

    private String commandAfterWakeWord(ArrayList<String> phrases) {
        if (phrases == null) return null;
        for (String phrase : phrases) {
            if (phrase == null) continue;
            String normalized = phrase.toLowerCase(Locale.ITALIAN)
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
            if (normalized.isEmpty()) continue;

            String[] words = normalized.split(" ");
            for (int i = 0; i < words.length; i++) {
                if (!"aurea".equals(words[i])) continue;
                StringBuilder command = new StringBuilder();
                for (int j = i + 1; j < words.length; j++) {
                    if (command.length() > 0) command.append(' ');
                    command.append(words[j]);
                }
                return command.toString();
            }
        }
        return null;
    }

    private void setDashboardState(String state) {
        if (dashboard == null) return;
        main.post(() -> {
            if (dashboard == null || destroyed) return;
            dashboard.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('aurea-state',{detail:'" + state + "'}));",
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
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            pendingWebPermission = request;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != AUDIO_PERMISSION) return;

        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (pendingWebPermission != null) {
            if (granted) {
                pendingWebPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                scheduleWakeWordListening(900L);
            } else {
                pendingWebPermission.deny();
            }
            pendingWebPermission = null;
            return;
        }
        if (granted && startListeningAfterPermission) {
            startListeningAfterPermission = false;
            startOneShotListening();
        } else if (granted) {
            scheduleWakeWordListening(900L);
        } else {
            Toast.makeText(this, "Consenti il microfono per parlare con AUREA", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onReadyForSpeech(Bundle params) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rms) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}

    @Override
    public void onError(int error) {
        if (ignoreRecognitionCallbacks) return;

        int finishedMode = recognitionMode;
        listening = false;
        recognitionMode = MODE_IDLE;

        if (finishedMode == MODE_WAKE_WORD) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                Toast.makeText(this, "Consenti il microfono per usare la parola Aurea", Toast.LENGTH_LONG).show();
                return;
            }
            scheduleWakeWordListening(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1500L : 800L);
            return;
        }

        setDashboardState("idle");
        if (!destroyed && error != SpeechRecognizer.ERROR_NO_MATCH &&
            error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            Toast.makeText(this, "Non ho capito, riprova", Toast.LENGTH_SHORT).show();
        }
        scheduleWakeWordListening(900L);
    }

    @Override
    public void onResults(Bundle results) {
        if (ignoreRecognitionCallbacks) return;

        int finishedMode = recognitionMode;
        listening = false;
        recognitionMode = MODE_IDLE;
        ArrayList<String> phrases = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

        if (finishedMode == MODE_WAKE_WORD) {
            String command = commandAfterWakeWord(phrases);
            if (command == null) {
                scheduleWakeWordListening(700L);
                return;
            }
            if (command.isEmpty()) {
                setDashboardState("listen");
                main.postDelayed(this::startOneShotListening, 350L);
                return;
            }
            sendCommand(command);
            return;
        }

        if (phrases == null || phrases.isEmpty() || phrases.get(0).trim().isEmpty()) {
            setDashboardState("idle");
            scheduleWakeWordListening(800L);
            return;
        }
        sendCommand(phrases.get(0).trim());
    }

    @Override public void onPartialResults(Bundle results) {}
    @Override public void onEvent(int type, Bundle params) {}

    private String trimSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private void hideSystemUi() {
        View decorView = getWindow().getDecorView();
        decorView.post(() -> {
            if (destroyed) return;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = decorView.getWindowInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
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
            scheduleWakeWordListening(900L);
        }
        if (updateManager != null) updateManager.resumePendingInstall();
    }

    @Override
    protected void onPause() {
        activityVisible = false;
        cancelWakeWordRestart();
        stopCurrentRecognition();
        if (dashboard != null) dashboard.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        activityVisible = false;
        cancelWakeWordRestart();
        if (pendingWebPermission != null) {
            pendingWebPermission.deny();
            pendingWebPermission = null;
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
