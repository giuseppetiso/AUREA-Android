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
    private static final String HA_PREFS = "aurea";
    private static final String KEY_HA_URL = "ha_url";
    private static final String KEY_HA_TOKEN = "ha_token";

    private final ToolsBridge toolsBridge = new ToolsBridge();
    private final Handler integrationHandler = new Handler(Looper.getMainLooper());
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

    private AureaBrainClient brainClient;
    private boolean forwardingIdentityResult;

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
        integrationHandler.removeCallbacks(integrationTask);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
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

        invokeMainState("think");
        String person = RegisteredUserAccess.currentPerson(this);
        brainIo.execute(() -> {
            AureaBrainClient.Result result = brainClient.process(command, person);
            runOnUiThread(() -> {
                if (!invokeMainSpeak(result.answer)) {
                    Toast.makeText(
                        this,
                        result.answer,
                        Toast.LENGTH_LONG
                    ).show();
                }
                if (result.continueConversation) {
                    Toast.makeText(
                        this,
                        "AUREA attende una risposta: pronuncia di nuovo “Aurea”",
                        Toast.LENGTH_LONG
                    ).show();
                }
            });
        });
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
