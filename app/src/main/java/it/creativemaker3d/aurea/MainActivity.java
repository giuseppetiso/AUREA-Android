package it.creativemaker3d.aurea;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements RecognitionListener {
    private static final int AUDIO_PERMISSION = 41;
    private static final String WAKE_WORD = "aurea";
    private static final long RESTART_DELAY_MS = 500;
    private static final long AFTER_SPEECH_DELAY_MS = 1500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SpeechRecognizer recognizer;
    private Intent recognizeIntent;
    private TextToSpeech tts;
    private WebView avatar;
    private boolean commandMode;
    private boolean speaking;
    private boolean destroyed;
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
            showAurea();
        }
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
            showAurea();
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

    private void showAurea() {
        hideSystemUi();
        initTextToSpeech();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.rgb(2, 7, 13));

        avatar = webView();
        avatar.loadUrl("file:///android_asset/aurea/index.html?mode=avatar&lowPower=1");
        root.addView(avatar, new LinearLayout.LayoutParams(0, -1, 1f));

        WebView dashboard = webView();
        dashboard.loadUrl(dashboardUrl);
        root.addView(dashboard, new LinearLayout.LayoutParams(0, -1, 1f));
        setContentView(root);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
        } else {
            startRecognizer();
        }
    }

    private void initTextToSpeech() {
        if (tts != null || destroyed) return;
        try {
            tts = new TextToSpeech(getApplicationContext(), status -> main.post(() -> {
                TextToSpeech engine = tts;
                if (destroyed || status != TextToSpeech.SUCCESS || engine == null) return;
                try {
                    engine.setLanguage(Locale.ITALIAN);
                    engine.setSpeechRate(0.94f);
                    engine.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                        @Override public void onStart(String id) {
                            speaking = true;
                            setAvatarState("speak");
                        }
                        @Override public void onDone(String id) {
                            speaking = false;
                            setAvatarState("idle");
                            scheduleListening(AFTER_SPEECH_DELAY_MS);
                        }
                        @Override public void onError(String id) {
                            speaking = false;
                            setAvatarState("idle");
                            scheduleListening(AFTER_SPEECH_DELAY_MS);
                        }
                    });
                } catch (RuntimeException ignored) {
                    // Keep AUREA usable even if the tablet voice engine is unavailable.
                }
            }));
        } catch (RuntimeException ignored) {
            tts = null;
        }
    }

    private WebView webView() {
        WebView web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient());
        return web;
    }

    private void startRecognizer() {
        if (destroyed || speaking || !SpeechRecognizer.isRecognitionAvailable(this)) return;
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
            recognizeIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recognizeIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recognizeIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT");
            recognizeIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recognizeIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        }
        try {
            recognizer.startListening(recognizeIntent);
        } catch (Exception ignored) {
            scheduleListening(1000);
        }
    }

    private void scheduleListening(long delay) {
        main.postDelayed(this::startRecognizer, delay);
    }

    private void handlePhrases(List<String> phrases, boolean finalResult) {
        if (phrases == null || phrases.isEmpty() || speaking) return;
        if (!commandMode) {
            for (String phrase : phrases) {
                String normalized = normalize(phrase);
                if (normalized.contains(WAKE_WORD)) {
                    commandMode = true;
                    setAvatarState("listen");
                    if (recognizer != null) recognizer.cancel();
                    scheduleListening(250);
                    return;
                }
            }
            if (finalResult) scheduleListening(RESTART_DELAY_MS);
            return;
        }

        if (!finalResult) return;
        String command = phrases.get(0).trim();
        commandMode = false;
        if (command.isEmpty() || normalize(command).equals(WAKE_WORD)) {
            setAvatarState("idle");
            scheduleListening(RESTART_DELAY_MS);
            return;
        }
        setAvatarStatus("ELABORAZIONE");
        sendCommand(command);
    }

    private void sendCommand(String command) {
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
            stopListeningForSpeech();
            if (tts == null) {
                speaking = false;
                scheduleListening(AFTER_SPEECH_DELAY_MS);
                return;
            }
            Bundle params = new Bundle();
            int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "aurea-answer");
            if (result == TextToSpeech.ERROR) {
                speaking = false;
                setAvatarState("idle");
                scheduleListening(AFTER_SPEECH_DELAY_MS);
            }
        });
    }

    private void stopListeningForSpeech() {
        speaking = true;
        commandMode = false;
        main.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (RuntimeException ignored) {
                // The recognizer may already be stopping.
            }
        }
        setAvatarState("speak");
        setAvatarStatus("RISPOSTA");
    }

    private void setAvatarState(String state) {
        if (avatar == null) return;
        main.post(() -> avatar.evaluateJavascript(
            "window.AUREA&&window.AUREA.setState('" + state + "')", null));
    }

    private void setAvatarStatus(String status) {
        if (avatar == null) return;
        main.post(() -> avatar.evaluateJavascript(
            "(function(){var e=document.getElementById('status-label');if(e)e.textContent='" + status + "'})()", null));
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ITALIAN)
            .replace('à', 'a').replace('è', 'e').replace('é', 'e')
            .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u');
    }

    private String trimSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private void hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == AUDIO_PERMISSION && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startRecognizer();
        } else {
            Toast.makeText(this, "AUREA necessita del microfono", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onReadyForSpeech(Bundle params) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rms) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}
    @Override public void onError(int error) {
        if (!destroyed && !speaking) scheduleListening(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 900 : RESTART_DELAY_MS);
    }
    @Override public void onResults(Bundle results) {
        handlePhrases(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION), true);
    }
    @Override public void onPartialResults(Bundle results) {
        handlePhrases(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION), false);
    }
    @Override public void onEvent(int type, Bundle params) {}

    @Override protected void onResume() {
        super.onResume();
        if (haToken != null && !haToken.isEmpty()) {
            hideSystemUi();
            if (!speaking) scheduleListening(300);
        }
    }

    @Override protected void onPause() {
        if (recognizer != null) recognizer.cancel();
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (recognizer != null) recognizer.destroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        io.shutdownNow();
        super.onDestroy();
    }
}
