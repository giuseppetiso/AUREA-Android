package it.creativemaker3d.aurea;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

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
 * L'accesso agli strumenti comuni viene intercettato dal pulsante Lovelace
 * collocato nella stessa barra di microfono, persona+ e chiusura. Non viene più
 * mostrato alcun pulsante flottante sopra la dashboard.
 */
public final class DashboardActivity extends MainActivity {
    private final ToolsBridge toolsBridge = new ToolsBridge();
    private boolean forwardingIdentityResult;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        scheduleToolsIntegration();
    }

    @Override
    protected void onResume() {
        super.onResume();
        scheduleToolsIntegration();
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

    private void scheduleToolsIntegration() {
        View decor = getWindow().getDecorView();
        decor.postDelayed(this::installToolsIntegration, 120L);
        decor.postDelayed(this::installToolsIntegration, 700L);
        decor.postDelayed(this::installToolsIntegration, 1700L);
        decor.postDelayed(this::installToolsIntegration, 3200L);
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
                + "document.addEventListener('click',function(e){"
                + "var p=e.composedPath?e.composedPath():[];"
                + "var hit=p.some(function(n){"
                + "if(!n)return false;"
                + "var t=((n.innerText||n.textContent)||'').trim()"
                + ".replace(/\\s+/g,' ');"
                + "var a='';"
                + "if(n.getAttribute){a=(n.getAttribute('aria-label')||'')+' '"
                + "+(n.getAttribute('title')||'')+' '"
                + "+(n.getAttribute('href')||'');}"
                + "var v=(t+' '+a).toLowerCase();"
                + "return v.indexOf('strumenti aurea')>=0"
                + "||v.indexOf('aurea://tools')>=0;"
                + "});"
                + "if(hit){"
                + "e.preventDefault();e.stopImmediatePropagation();"
                + "if(window.AureaToolsNative){"
                + "window.AureaToolsNative.openTools();"
                + "}"
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
