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

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private WebView dashboard;
    private PermissionRequest pendingWebPermission;
    private SpeechRecognizer recognizer;
    private Intent recognizeIntent;
    private TextToSpeech tts;
    private UpdateManager updateManager;
    private boolean listening;
    private boolean speaking;
    private boolean destroyed;
    private boolean startListeningAfterPermission;
    private boolean recoveringWebView;
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
        hideSystemUi();
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
            }
        });
        dashboard.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleWebPermission(request));
            }
        });

        setContentView(dashboard);
        dashboard.loadUrl(dashboardUrl);
        requestAudioPermissionOnStartup();
    }

    private final class AureaBridge {
        @JavascriptInterface
        public void startListening() {
            main.post(() -> startOneShotListening());
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
        }
        return true;
    }

    private void closeAurea() {
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
                    speaking = true;
                    setDashboardState("speak");
                }
                @Override public void onDone(String id) {
                    speaking = false;
                    setDashboardState("idle");
                }
                @Override public void onError(String id) {
                    speaking = false;
                    setDashboardState("idle");
                }
            });
        }));
    }

    private void requestAudioPermissionOnStartup() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return;
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

    private void startOneShotListening() {
        if (destroyed || speaking || listening) return;

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startListeningAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Riconoscimento vocale non disponibile", Toast.LENGTH_LONG).show();
            return;
        }

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
            recognizeIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recognizeIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recognizeIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT");
            recognizeIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            recognizeIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        }

        listening = true;
        setDashboardState("listen");
        try {
            recognizer.startListening(recognizeIntent);
        } catch (RuntimeException error) {
            listening = false;
            setDashboardState("idle");
            Toast.makeText(this, "Microfono momentaneamente occupato", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendCommand(String command) {
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
            listening = false;
            if (recognizer != null) {
                try { recognizer.cancel(); } catch (RuntimeException ignored) {}
            }
            if (tts == null) {
                speaking = false;
                setDashboardState("idle");
                return;
            }
            speaking = true;
            int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), "aurea-answer");
            if (result == TextToSpeech.ERROR) {
                speaking = false;
                setDashboardState("idle");
            }
        });
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
            } else {
                pendingWebPermission.deny();
            }
            pendingWebPermission = null;
            return;
        }
        if (granted && startListeningAfterPermission) {
            startListeningAfterPermission = false;
            startOneShotListening();
        } else if (!granted) {
            Toast.makeText(this, "Consenti il microfono per parlare con AUREA", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onReadyForSpeech(Bundle params) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rms) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}
    @Override public void onError(int error) {
        listening = false;
        setDashboardState("idle");
        if (!destroyed && error != SpeechRecognizer.ERROR_NO_MATCH &&
            error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            Toast.makeText(this, "Non ho capito, riprova", Toast.LENGTH_SHORT).show();
        }
    }
    @Override public void onResults(Bundle results) {
        listening = false;
        ArrayList<String> phrases = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (phrases == null || phrases.isEmpty() || phrases.get(0).trim().isEmpty()) {
            setDashboardState("idle");
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
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
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
        if (dashboard != null) {
            hideSystemUi();
            dashboard.onResume();
        }
        if (updateManager != null) updateManager.resumePendingInstall();
    }

    @Override
    protected void onPause() {
        if (dashboard != null) dashboard.onPause();
        if (recognizer != null && listening) {
            recognizer.cancel();
            listening = false;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
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
