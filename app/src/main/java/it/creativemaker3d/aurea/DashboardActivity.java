package it.creativemaker3d.aurea;

import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

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
 * La dashboard aggiunge inoltre un accesso discreto agli strumenti comuni. Il
 * pulsante è disponibile a qualunque persona registrata e riconosciuta, mentre
 * persona+ continua a usare il flusso amministratore riservato a Giuseppe.
 */
public final class DashboardActivity extends MainActivity {
    private static final String TOOLS_BUTTON_TAG = "aurea_registered_tools";

    private boolean forwardingIdentityResult;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        scheduleToolsButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        scheduleToolsButton();
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

    private void scheduleToolsButton() {
        View decor = getWindow().getDecorView();
        decor.postDelayed(this::installToolsButton, 900L);
        decor.postDelayed(this::installToolsButton, 2200L);
    }

    private void installToolsButton() {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        View decor = getWindow().getDecorView();
        View existing = decor.findViewWithTag(TOOLS_BUTTON_TAG);
        boolean allowed = RegisteredUserAccess.isAllowed(this);

        if (!allowed) {
            if (existing != null) {
                existing.setVisibility(View.GONE);
            }
            return;
        }

        if (existing != null) {
            existing.setVisibility(View.VISIBLE);
            return;
        }

        Button button = new Button(this);
        button.setTag(TOOLS_BUTTON_TAG);
        button.setText("⚙");
        button.setTextSize(22f);
        button.setTextColor(Color.WHITE);
        button.setContentDescription("Strumenti AUREA");
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        button.setAlpha(0.90f);
        button.setElevation(dp(5));
        button.setBackgroundTintList(
            ColorStateList.valueOf(Color.rgb(18, 43, 60))
        );
        button.setOnClickListener(view -> startActivity(
            new Intent(this, UserToolsActivity.class)
        ));

        int size = dp(52);
        int margin = dp(12);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            size,
            size,
            Gravity.END | Gravity.TOP
        );
        params.setMargins(margin, margin, margin, margin);
        addContentView(button, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
