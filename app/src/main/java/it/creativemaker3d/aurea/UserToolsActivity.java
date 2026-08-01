package it.creativemaker3d.aurea;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Area delle funzioni comuni di AUREA.
 *
 * È accessibile a qualunque persona registrata e riconosciuta. Le operazioni
 * amministrative sui profili non sono presenti qui e restano protette dal
 * pulsante persona+ riservato a Giuseppe.
 */
public final class UserToolsActivity extends Activity {
    private String currentPerson;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        currentPerson = RegisteredUserAccess.currentPerson(this);
        if (currentPerson.isEmpty()) {
            denyAccess();
            return;
        }

        buildInterface();
        hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        currentPerson = RegisteredUserAccess.currentPerson(this);
        if (currentPerson.isEmpty()) {
            denyAccess();
        }
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(32), dp(24), dp(32), dp(24));
        root.setBackgroundColor(Color.rgb(2, 7, 13));

        TextView title = text("Strumenti AUREA", 30, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidth());

        TextView identity = text(
            "Identità confermata: " + currentPerson,
            16,
            Color.rgb(124, 220, 255)
        );
        identity.setGravity(Gravity.CENTER);
        identity.setPadding(0, dp(6), 0, dp(20));
        root.addView(identity, fullWidth());

        LinearLayout backupCard = card();
        TextView backupTitle = text("Backup e ripristino", 23, Color.WHITE);
        backupCard.addView(backupTitle, fullWidth());

        TextView backupDescription = text(
            "Salva profili, firme vocali e preferenze in un file protetto da password. "
                + "Il token Home Assistant resta escluso.",
            15,
            Color.rgb(190, 210, 225)
        );
        backupDescription.setPadding(0, dp(6), 0, dp(14));
        backupCard.addView(backupDescription, fullWidth());

        Button backup = button("Apri backup e ripristino");
        backup.setOnClickListener(view -> startActivity(
            new Intent(this, BackupRestoreActivity.class)
        ));
        backupCard.addView(backup, fullWidth());
        root.addView(backupCard, fullWidthWithBottom(dp(16)));

        TextView policy = text(
            "Questi strumenti sono disponibili a tutte le persone registrate. "
                + "Aggiunta, modifica ed eliminazione dei profili restano riservate a Giuseppe.",
            14,
            Color.rgb(150, 172, 190)
        );
        policy.setGravity(Gravity.CENTER);
        policy.setPadding(dp(12), dp(6), dp(12), dp(18));
        root.addView(policy, fullWidth());

        Button close = button("Torna a Casa Tablet");
        close.setOnClickListener(view -> finish());
        root.addView(close, fullWidth());

        setContentView(root);
    }

    private void denyAccess() {
        Toast.makeText(
            this,
            "Funzione disponibile dopo il riconoscimento di una persona registrata",
            Toast.LENGTH_LONG
        ).show();
        finish();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(18), dp(22), dp(18));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(12, 25, 37));
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), Color.rgb(45, 78, 101));
        card.setBackground(background);
        return card;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        return button;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams fullWidthWithBottom(int bottom) {
        LinearLayout.LayoutParams params = fullWidth();
        params.bottomMargin = bottom;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.post(() -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                }
            } else {
                decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
            }
        });
    }
}
