package it.creativemaker3d.aurea;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int AUDIO_PERMISSION = 41;

    private WebView dashboard;
    private PermissionRequest pendingWebPermission;
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

        dashboard = new WebView(this);
        dashboard.setBackgroundColor(Color.rgb(2, 7, 13));

        WebSettings settings = dashboard.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(dashboard, true);

        dashboard.setWebViewClient(new WebViewClient());
        dashboard.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleWebPermission(request));
            }
        });

        setContentView(dashboard);
        dashboard.loadUrl(dashboardUrl);
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
        if (requestCode != AUDIO_PERMISSION || pendingWebPermission == null) return;

        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            pendingWebPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            pendingWebPermission.deny();
            Toast.makeText(this, "Consenti il microfono per parlare con AUREA", Toast.LENGTH_LONG).show();
        }
        pendingWebPermission = null;
    }

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
        if (dashboard != null) hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        if (pendingWebPermission != null) {
            pendingWebPermission.deny();
            pendingWebPermission = null;
        }
        if (dashboard != null) {
            dashboard.stopLoading();
            dashboard.destroy();
            dashboard = null;
        }
        super.onDestroy();
    }
}
