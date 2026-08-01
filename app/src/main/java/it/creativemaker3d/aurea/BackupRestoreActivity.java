package it.creativemaker3d.aurea;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Esporta e ripristina i dati locali di AUREA tramite Storage Access Framework.
 *
 * L'attività non richiede permessi di archiviazione e non conosce il token Home
 * Assistant, che viene deliberatamente escluso dal file di backup.
 */
public final class BackupRestoreActivity extends Activity {
    private static final int REQUEST_CREATE_BACKUP = 301;
    private static final int REQUEST_OPEN_BACKUP = 302;
    private static final int MAX_BACKUP_BYTES = 8 * 1024 * 1024;
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private EditText passwordInput;
    private EditText confirmationInput;
    private TextView statusView;
    private Button createButton;
    private Button restoreButton;
    private Button closeButton;
    private char[] pendingPassword;
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String person = RegisteredUserAccess.currentPerson(this);
        if (person.isEmpty()) {
            Toast.makeText(
                this,
                "Backup disponibile dopo il riconoscimento di una persona registrata",
                Toast.LENGTH_LONG
            ).show();
            finish();
            return;
        }

        buildInterface(person);
        hideSystemUi();
    }

    private void buildInterface(String person) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(34), dp(22), dp(34), dp(22));
        root.setBackgroundColor(Color.rgb(2, 7, 13));
        scroll.addView(root, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("Backup e ripristino AUREA", 29, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidth());

        TextView identity = text(
            "Identità confermata: " + person,
            15,
            Color.rgb(124, 220, 255)
        );
        identity.setGravity(Gravity.CENTER);
        identity.setPadding(0, dp(5), 0, dp(16));
        root.addView(identity, fullWidth());

        TextView explanation = text(
            "Il backup contiene profili del volto, firme vocali numeriche, nomi, "
                + "saluti e impostazioni della dashboard. Il token Home Assistant "
                + "non viene inserito e resta invariato durante il ripristino.",
            15,
            Color.rgb(195, 214, 228)
        );
        explanation.setGravity(Gravity.CENTER);
        explanation.setPadding(dp(8), 0, dp(8), dp(18));
        root.addView(explanation, fullWidth());

        passwordInput = passwordField("Password del backup");
        root.addView(passwordInput, fullWidthWithBottom(dp(8)));

        confirmationInput = passwordField("Ripeti password per creare il backup");
        root.addView(confirmationInput, fullWidthWithBottom(dp(12)));

        TextView warning = text(
            "Usa almeno 8 caratteri e conserva la password: non può essere recuperata. "
                + "Per ripristinare è sufficiente compilare il primo campo.",
            13,
            Color.rgb(155, 178, 196)
        );
        warning.setGravity(Gravity.CENTER);
        warning.setPadding(dp(8), 0, dp(8), dp(14));
        root.addView(warning, fullWidth());

        createButton = button("Crea backup cifrato");
        createButton.setOnClickListener(view -> requestCreateBackup());
        root.addView(createButton, fullWidthWithBottom(dp(8)));

        restoreButton = button("Ripristina da un backup");
        restoreButton.setOnClickListener(view -> requestRestoreBackup());
        root.addView(restoreButton, fullWidthWithBottom(dp(14)));

        statusView = text("Nessuna operazione in corso.", 15, Color.rgb(210, 225, 238));
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(10), dp(8), dp(10), dp(18));
        root.addView(statusView, fullWidth());

        closeButton = button("Torna agli strumenti");
        closeButton.setOnClickListener(view -> finish());
        root.addView(closeButton, fullWidth());

        setContentView(scroll);
    }

    private void requestCreateBackup() {
        if (busy) {
            return;
        }
        String password = passwordInput.getText().toString();
        String confirmation = confirmationInput.getText().toString();
        if (!validatePassword(password)) {
            return;
        }
        if (!password.equals(confirmation)) {
            statusView.setText("Le due password non coincidono.");
            confirmationInput.requestFocus();
            return;
        }

        replacePendingPassword(password.toCharArray());
        String stamp = new SimpleDateFormat(
            "yyyyMMdd_HHmm",
            Locale.ITALY
        ).format(new Date());

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "AUREA_backup_" + stamp + ".aurea");
        startActivityForResult(intent, REQUEST_CREATE_BACKUP);
    }

    private void requestRestoreBackup() {
        if (busy) {
            return;
        }
        String password = passwordInput.getText().toString();
        if (!validatePassword(password)) {
            return;
        }

        replacePendingPassword(password.toCharArray());
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(
            Intent.EXTRA_MIME_TYPES,
            new String[]{"application/octet-stream", "application/json", "text/plain"}
        );
        startActivityForResult(intent, REQUEST_OPEN_BACKUP);
    }

    private boolean validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            statusView.setText("La password deve contenere almeno 8 caratteri.");
            passwordInput.requestFocus();
            return false;
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            wipePendingPassword();
            statusView.setText("Operazione annullata.");
            return;
        }
        if (pendingPassword == null || pendingPassword.length == 0) {
            statusView.setText("Password non più disponibile. Ripeti l’operazione.");
            return;
        }

        Uri uri = data.getData();
        if (requestCode == REQUEST_CREATE_BACKUP) {
            writeBackup(uri);
        } else if (requestCode == REQUEST_OPEN_BACKUP) {
            confirmRestore(uri);
        }
    }

    private void writeBackup(Uri uri) {
        setBusy(true, "Creazione e cifratura del backup…");
        char[] password = takePendingPasswordCopy();
        worker.execute(() -> {
            try {
                byte[] encrypted = AureaBackupCodec.create(this, password);
                try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                    if (output == null) {
                        throw new IllegalStateException("destinazione non disponibile");
                    }
                    output.write(encrypted);
                    output.flush();
                }
                runOnUiThread(() -> {
                    setBusy(false, "Backup creato e protetto correttamente.");
                    clearPasswordFields();
                    Toast.makeText(
                        this,
                        "Backup AUREA salvato",
                        Toast.LENGTH_LONG
                    ).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> setBusy(
                    false,
                    "Backup non riuscito: " + safeMessage(error)
                ));
            } finally {
                Arrays.fill(password, '\0');
                wipePendingPassword();
            }
        });
    }

    private void confirmRestore(Uri uri) {
        new AlertDialog.Builder(this)
            .setTitle("Ripristinare questo backup?")
            .setMessage(
                "I profili e le preferenze locali attuali verranno sostituiti. "
                    + "Il token Home Assistant non verrà modificato. Dopo il ripristino "
                    + "AUREA si riavvierà e richiederà il riconoscimento."
            )
            .setNegativeButton("Annulla", (dialog, which) -> {
                wipePendingPassword();
                statusView.setText("Ripristino annullato.");
            })
            .setPositiveButton("Ripristina", (dialog, which) -> restoreBackup(uri))
            .show();
    }

    private void restoreBackup(Uri uri) {
        setBusy(true, "Lettura e verifica del backup…");
        char[] password = takePendingPasswordCopy();
        worker.execute(() -> {
            try {
                byte[] encrypted = readLimited(uri);
                AureaBackupCodec.RestoreSummary summary = AureaBackupCodec.restore(
                    this,
                    encrypted,
                    password
                );
                runOnUiThread(() -> showRestoreSuccess(summary));
            } catch (Exception error) {
                runOnUiThread(() -> setBusy(
                    false,
                    "Ripristino non riuscito: password errata o file danneggiato."
                ));
            } finally {
                Arrays.fill(password, '\0');
                wipePendingPassword();
            }
        });
    }

    private byte[] readLimited(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IllegalStateException("file non disponibile");
            }
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > MAX_BACKUP_BYTES) {
                    throw new IllegalArgumentException("file troppo grande");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void showRestoreSuccess(AureaBackupCodec.RestoreSummary summary) {
        setBusy(false, "Ripristino completato.");
        String source = summary.appVersion.isEmpty()
            ? "versione non indicata"
            : "AUREA " + summary.appVersion;
        new AlertDialog.Builder(this)
            .setTitle("Backup ripristinato")
            .setMessage(
                "Origine: " + source + "\n"
                    + "Profili volto: " + summary.faceProfiles + "\n"
                    + "Profili voce: " + summary.voiceProfiles + "\n\n"
                    + "Il token Home Assistant è rimasto invariato. "
                    + "AUREA verrà ora riavviata."
            )
            .setCancelable(false)
            .setPositiveButton("Riavvia AUREA", (dialog, which) -> restartAurea())
            .show();
    }

    private void restartAurea() {
        ComponentName dashboard = new ComponentName(this, MainActivity.class);
        Intent restart = Intent.makeRestartActivityTask(dashboard);
        restart.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(restart);
        finishAffinity();
        overridePendingTransition(0, 0);
    }

    private void setBusy(boolean value, String status) {
        busy = value;
        createButton.setEnabled(!value);
        restoreButton.setEnabled(!value);
        closeButton.setEnabled(!value);
        passwordInput.setEnabled(!value);
        confirmationInput.setEnabled(!value);
        statusView.setText(status);
    }

    private void replacePendingPassword(char[] value) {
        wipePendingPassword();
        pendingPassword = value;
    }

    private char[] takePendingPasswordCopy() {
        return pendingPassword == null
            ? new char[0]
            : Arrays.copyOf(pendingPassword, pendingPassword.length);
    }

    private void wipePendingPassword() {
        if (pendingPassword != null) {
            Arrays.fill(pendingPassword, '\0');
            pendingPassword = null;
        }
    }

    private void clearPasswordFields() {
        passwordInput.setText("");
        confirmationInput.setText("");
    }

    private EditText passwordField(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.rgb(125, 150, 170));
        input.setSingleLine(true);
        input.setInputType(
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        return input;
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

    private String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
            ? "errore sconosciuto"
            : message.trim();
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

    @Override
    protected void onDestroy() {
        wipePendingPassword();
        worker.shutdownNow();
        super.onDestroy();
    }
}
