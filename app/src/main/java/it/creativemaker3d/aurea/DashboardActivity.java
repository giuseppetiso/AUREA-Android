package it.creativemaker3d.aurea;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dashboard compatibile con i risultati biometrici ricevuti mentre la Home è
 * già aperta.
 *
 * MainActivity viene normalmente riutilizzata da Android con onNewIntent().
 * Il coordinatore AUREA elabora però i risultati volto/voce alla creazione
 * dell'attività. Quando arriva un'identità riconosciuta, questa classe riapre
 * quindi una nuova dashboard con lo stesso Intent, permettendo al flusso di
 * proseguire correttamente verso la verifica vocale o Gestione persone.
 *
 * AUREA Brain intercetta inoltre il testo già riconosciuto e lo invia al
 * conversation agent configurato, mantenendo una memoria separata per persona.
 */
public final class DashboardActivity extends MainActivity {
    private static final long TOOLS_RETRY_MS = 1500L;
    private static final long FOLLOW_UP_INITIAL_DELAY_MS = 300L;
    private static final long FOLLOW_UP_POLL_MS = 120L;
    private static final long FOLLOW_UP_START_DELAY_MS = 220L;
    private static final long FOLLOW_UP_SPEECH_START_WAIT_MS = 2500L;

    private static final String HA_PREFS = "aurea";
    private static final String KEY_HA_URL = "ha_url";
    private static final String KEY_HA_TOKEN = "ha_token";

    private final ToolsBridge toolsBridge = new ToolsBridge();
    private final Handler integrationHandler = new Handler(Looper.getMainLooper());
    private final Handler conversationHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService brainIo = Executors.newSingleThreadExecutor();

    private final Runnable integrationTask = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            installToolsIntegration();
            integrationHandler.postDelayed(this, TOOLS_RETRY_MS);
        }
    };

    private final Runnable followUpTask = new Runnable() {
        @Override
        public void run() {
            if (!followUpRequested || isFinishing() || isDestroyed()) {
                return;
            }

            boolean speaking = readMainBoolean("speaking");
            if (speaking) {
                followUpSawSpeech = true;
                conversationHandler.postDelayed(this, FOLLOW_UP_POLL_MS);
                return;
            }

            if (!followUpSawSpeech
                    && System.currentTimeMillis() < followUpSpeechStartDeadline) {
                conversationHandler.postDelayed(this, FOLLOW_UP_POLL_MS);
                return;
            }

            followUpRequested = false;
            conversationHandler.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (invokeMainListening()) {
                    Toast.makeText(
                        DashboardActivity.this,
                        "Puoi continuare senza ripetere “Aurea”",
                        Toast.LENGTH_SHORT
                    ).show();
                }
            }, FOLLOW_UP_START_DELAY_MS);
        }
    };

    private AureaBrainClient brainClient;
    private boolean forwardingIdentityResult;
    private boolean followUpRequested;
    private boolean followUpSawSpeech;
    private long followUpSpeechStartDeadline;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        refreshBrainConnection();
        startToolsIntegration();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBrainConnection();
        startToolsIntegration();
    }

    @Override
    protected void onPause() {
        cancelFollowUp();
        integrationHandler.removeCallbacks(integrationTask);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelFollowUp();
        conversationHandler.removeCallbacksAndMessages(null);
        integrationHandler.removeCallbacksAndMessages(null);
        brainIo.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (intent == null || forwardingIdentityResult
                || !intent.hasExtra("aurea_recognized_person")) {
            return;
        }

        forwardingIdentityResult = true;
        Intent freshDashboard = new Intent(intent);
        freshDashboard.setComponent(new ComponentName(this, MainActivity.class));
        freshDashboard.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);

        finish();
        startActivity(freshDashboard);
        overridePendingTransition(0, 0);
    }

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> phrases = results.getStringArrayList(
            SpeechRecognizer.RESULTS_RECOGNITION
        );
        if (phrases == null
                || phrases.isEmpty()
                || phrases.get(0).trim().isEmpty()
                || brainClient == null) {
            super.onResults(results);
            return;
        }

        String command = phrases.get(0).trim();
        if (!setMainBoolean("commandListening", false)) {
            super.onResults(results);
            return;
        }

        cancelFollowUp();
        invokeMainState("think");
        String person = RegisteredUserAccess.currentPerson(this);
        brainIo.execute(() -> {
            AureaBrainClient.Result result = brainClient.process(command, person);
            runOnUiThread(() -> {
                boolean offerFollowUp = shouldOfferFollowUp(result);
                if (!invokeMainSpeak(result.answer)) {
                    Toast.makeText(
                        this,
                        result.answer,
                        Toast.LENGTH_LONG
                    ).show();
                }
                if (offerFollowUp) {
                    requestFollowUpAfterSpeech();
                }
            });
        });
    }

    private boolean shouldOfferFollowUp(AureaBrainClient.Result result) {
        AureaBrainStore store = new AureaBrainStore(this);
        return result.continueConversation
            || (store.isEnabled() && !store.agentId().isEmpty());
    }

    private void requestFollowUpAfterSpeech() {
        conversationHandler.removeCallbacks(followUpTask);
        followUpRequested = true;
        followUpSawSpeech = false;
        followUpSpeechStartDeadline = System.currentTimeMillis()
            + FOLLOW_UP_SPEECH_START_WAIT_MS;
        conversationHandler.postDelayed(
            followUpTask,
            FOLLOW_UP_INITIAL_DELAY_MS
        );
    }

    private void cancelFollowUp() {
        followUpRequested = false;
        followUpSawSpeech = false;
        conversationHandler.removeCallbacks(followUpTask);
    }

    private void refreshBrainConnection() {
        SharedPreferences prefs = getSharedPreferences(HA_PREFS, MODE_PRIVATE);
        String haUrl = prefs.getString(
            KEY_HA_URL,
            "http://192.168.178.72:8123"
        );
        String haToken = prefs.getString(KEY_HA_TOKEN, "");

        if (brainClient == null) {
            brainClient = new AureaBrainClient(this, haUrl, haToken);
        } else {
            brainClient.updateConnection(haUrl, haToken);
        }
    }

    private boolean setMainBoolean(String fieldName, boolean value) {
        try {
            Field field = MainActivity.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setBoolean(this, value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean readMainBoolean(String fieldName) {
        try {
            Field field = MainActivity.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getBoolean(this);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean invokeMainSpeak(String text) {
        try {
            Method method = MainActivity.class.getDeclaredMethod(
                "speak",
                String.class
            );
            method.setAccessible(true);
            method.invoke(this, text);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean invokeMainListening() {
        try {
            Method method = MainActivity.class.getDeclaredMethod(
                "startOneShotListening"
            );
            method.setAccessible(true);
            method.invoke(this);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void invokeMainState(String state) {
        try {
            Method method = MainActivity.class.getDeclaredMethod(
                "setDashboardState",
                String.class
            );
            method.setAccessible(true);
            method.invoke(this, state);
        } catch (Exception ignored) {
        }
    }

    private void startToolsIntegration() {
        integrationHandler.removeCallbacks(integrationTask);
        integrationHandler.postDelayed(integrationTask, 250L);
    }

    private void installToolsIntegration() {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        WebView webView = findWebView(getWindow().getDecorView());
        if (webView == null) {
            return;
        }

        webView.addJavascriptInterface(toolsBridge, "AureaToolsNative");
        webView.evaluateJavascript(
            "(function(){"
                + "if(window.__aureaToolsBarInstalled)return;"
                + "window.__aureaToolsBarInstalled=true;"
                + "var normalize=function(v){return String(v||'').trim()"
                + ".replace(/\\s+/g,' ').toLowerCase();};"
                + "var isToolsNode=function(n){"
                + "if(!n)return false;"
                + "var values=[];"
                + "values.push(n.innerText||n.textContent||'');"
                + "values.push(n.icon||'');"
                + "if(n.getAttribute){"
                + "values.push(n.getAttribute('aria-label')||'');"
                + "values.push(n.getAttribute('title')||'');"
                + "values.push(n.getAttribute('href')||'');"
                + "values.push(n.getAttribute('icon')||'');"
                + "values.push(n.getAttribute('data-icon')||'');"
                + "}"
                + "var v=normalize(values.join(' '));"
                + "return v.indexOf('strumenti aurea')>=0"
                + "||v.indexOf('aurea://tools')>=0"
                + "||v.indexOf('mdi:cog')>=0;"
                + "};"
                + "document.addEventListener('click',function(e){"
                + "var p=e.composedPath?e.composedPath():[];"
                + "var hit=p.some(isToolsNode);"
                + "if(!hit)return;"
                + "e.preventDefault();"
                + "e.stopPropagation();"
                + "e.stopImmediatePropagation();"
                + "if(window.AureaToolsNative){"
                + "window.AureaToolsNative.openTools();"
                + "}"
                + "},true);"
                + "})();",
            null
        );
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

    private final class ToolsBridge {
        @JavascriptInterface
        public void openTools() {
            runOnUiThread(() -> {
                if (!RegisteredUserAccess.isAllowed(DashboardActivity.this)) {
                    Toast.makeText(
                        DashboardActivity.this,
                        "Strumenti disponibili dopo il riconoscimento di una persona registrata",
                        Toast.LENGTH_LONG
                    ).show();
                    return;
                }

                startActivity(new Intent(
                    DashboardActivity.this,
                    UserToolsActivity.class
                ));
            });
        }
    }
}
