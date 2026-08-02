package it.creativemaker3d.aurea;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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

import java.util.List;

/**
 * Gestione trasparente delle preferenze apprese per la persona riconosciuta.
 *
 * Ogni utente registrato vede e modifica soltanto i propri ricordi. La gestione
 * biometrica resta separata e riservata a Giuseppe tramite persona+.
 */
public final class AureaLearningActivity extends Activity {
    private AureaLearningStore learningStore;
    private AureaBrainStore brainStore;
    private String currentPerson;
    private LinearLayout memoriesContainer;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        currentPerson = RegisteredUserAccess.currentPerson(this);
        if (currentPerson.isEmpty()) {
            denyAccess();
            return;
        }

        learningStore = new AureaLearningStore(this);
        brainStore = new AureaBrainStore(this);
        buildInterface();
        refreshMemories();
        hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        currentPerson = RegisteredUserAccess.currentPerson(this);
        if (currentPerson.isEmpty()) {
            denyAccess();
            return;
        }
        refreshMemories();
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(2, 7, 13));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(32), dp(20), dp(32), dp(20));
        scroll.addView(root, fullWidth());

        TextView title = text("AUREA Learning 1.0", 29, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidth());

        TextView identity = text(
            "Preferenze personali di " + currentPerson,
            16,
            Color.rgb(124, 220, 255)
        );
        identity.setGravity(Gravity.CENTER);
        identity.setPadding(0, dp(5), 0, dp(12));
        root.addView(identity, fullWidth());

        TextView explanation = text(
            "AUREA salva una preferenza solo dopo una tua conferma. "
                + "Puoi controllare, modificare o cancellare ogni ricordo da questa schermata.",
            15,
            Color.rgb(190, 210, 225)
        );
        explanation.setGravity(Gravity.CENTER);
        explanation.setPadding(dp(8), 0, dp(8), dp(14));
        root.addView(explanation, fullWidth());

        LinearLayout controls = card();
        controls.addView(text("Controllo apprendimento", 23, Color.WHITE), fullWidth());

        status = text("", 15, Color.rgb(190, 210, 225));
        status.setPadding(0, dp(6), 0, dp(10));
        controls.addView(status, fullWidth());

        Button add = button("Aggiungi una preferenza manualmente");
        add.setOnClickListener(view -> showAddDialog());
        controls.addView(add, fullWidthWithTop(dp(6)));

        Button clear = button("Cancella tutte le mie preferenze apprese");
        clear.setOnClickListener(view -> confirmClearAll());
        controls.addView(clear, fullWidthWithTop(dp(6)));
        root.addView(controls, fullWidthWithBottom(dp(14)));

        memoriesContainer = new LinearLayout(this);
        memoriesContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(memoriesContainer, fullWidthWithBottom(dp(14)));

        TextView examples = text(
            "Puoi anche dire: “Aurea, ricorda che la sera preferisco la luce al 40%”, "
                + "“Aurea, cosa ricordi di me?” oppure “Aurea, dimentica la preferenza della luce”.",
            14,
            Color.rgb(150, 172, 190)
        );
        examples.setGravity(Gravity.CENTER);
        examples.setPadding(dp(12), 0, dp(12), dp(14));
        root.addView(examples, fullWidth());

        Button close = button("Torna ad AUREA Brain");
        close.setOnClickListener(view -> finish());
        root.addView(close, fullWidth());

        setContentView(scroll);
    }

    private void refreshMemories() {
        if (learningStore == null || memoriesContainer == null) {
            return;
        }

        List<AureaLearningStore.Memory> memories = learningStore.list(currentPerson);
        status.setText(
            "Ricordi confermati: " + memories.size()
                + "\nQuesti dati restano sul tablet e vengono usati solo nel profilo di "
                + currentPerson + "."
        );
        memoriesContainer.removeAllViews();

        if (memories.isEmpty()) {
            LinearLayout empty = card();
            TextView emptyText = text(
                "Nessuna preferenza appresa. AUREA non memorizzerà nulla senza conferma.",
                16,
                Color.rgb(190, 210, 225)
            );
            emptyText.setGravity(Gravity.CENTER);
            empty.addView(emptyText, fullWidth());
            memoriesContainer.addView(empty, fullWidth());
            return;
        }

        for (int index = 0; index < memories.size(); index++) {
            AureaLearningStore.Memory memory = memories.get(index);
            LinearLayout item = card();

            TextView number = text(
                "Preferenza " + (index + 1),
                14,
                Color.rgb(124, 220, 255)
            );
            item.addView(number, fullWidth());

            TextView value = text(memory.text, 18, Color.WHITE);
            value.setPadding(0, dp(5), 0, dp(10));
            item.addView(value, fullWidth());

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);

            Button edit = button("Modifica");
            edit.setOnClickListener(view -> showEditDialog(memory));
            actions.addView(edit, weighted());

            Button delete = button("Elimina");
            delete.setOnClickListener(view -> confirmDelete(memory));
            LinearLayout.LayoutParams deleteParams = weighted();
            deleteParams.leftMargin = dp(8);
            actions.addView(delete, deleteParams);

            item.addView(actions, fullWidth());
            memoriesContainer.addView(item, fullWidthWithBottom(dp(10)));
        }
    }

    private void showAddDialog() {
        EditText input = memoryField("");
        new AlertDialog.Builder(this)
            .setTitle("Nuova preferenza")
            .setMessage("Scrivi un'informazione che AUREA deve ricordare per il tuo profilo.")
            .setView(input)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Memorizza", (dialog, which) -> {
                boolean saved = learningStore.add(
                    currentPerson,
                    input.getText().toString()
                );
                if (saved) {
                    brainStore.clearConversation(currentPerson);
                    Toast.makeText(
                        this,
                        "Preferenza memorizzata",
                        Toast.LENGTH_SHORT
                    ).show();
                    refreshMemories();
                } else {
                    Toast.makeText(
                        this,
                        "Preferenza vuota o già presente",
                        Toast.LENGTH_LONG
                    ).show();
                }
            })
            .show();
    }

    private void showEditDialog(AureaLearningStore.Memory memory) {
        EditText input = memoryField(memory.text);
        new AlertDialog.Builder(this)
            .setTitle("Modifica preferenza")
            .setView(input)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva", (dialog, which) -> {
                boolean saved = learningStore.update(
                    currentPerson,
                    memory.id,
                    input.getText().toString()
                );
                if (saved) {
                    brainStore.clearConversation(currentPerson);
                    Toast.makeText(
                        this,
                        "Preferenza aggiornata",
                        Toast.LENGTH_SHORT
                    ).show();
                    refreshMemories();
                } else {
                    Toast.makeText(
                        this,
                        "Testo non valido o già presente",
                        Toast.LENGTH_LONG
                    ).show();
                }
            })
            .show();
    }

    private void confirmDelete(AureaLearningStore.Memory memory) {
        new AlertDialog.Builder(this)
            .setTitle("Eliminare questa preferenza?")
            .setMessage(memory.text)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Elimina", (dialog, which) -> {
                if (learningStore.delete(currentPerson, memory.id)) {
                    brainStore.clearConversation(currentPerson);
                    refreshMemories();
                }
            })
            .show();
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
            .setTitle("Cancellare tutte le preferenze?")
            .setMessage(
                "Saranno eliminati soltanto i ricordi appresi di " + currentPerson
                    + ". Volto, voce, saluti e configurazione Home Assistant resteranno invariati."
            )
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Cancella tutto", (dialog, which) -> {
                learningStore.clear(currentPerson);
                brainStore.clearConversation(currentPerson);
                refreshMemories();
            })
            .show();
    }

    private EditText memoryField(String value) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setHint("Esempio: la sera preferisco la luce del salone al 40%");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setMaxLines(4);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        return input;
    }

    private void denyAccess() {
        Toast.makeText(
            this,
            "AUREA Learning è disponibile dopo il riconoscimento personale",
            Toast.LENGTH_LONG
        ).show();
        finish();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(16), dp(22), dp(16));

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

    private LinearLayout.LayoutParams fullWidthWithTop(int top) {
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = top;
        return params;
    }

    private LinearLayout.LayoutParams fullWidthWithBottom(int bottom) {
        LinearLayout.LayoutParams params = fullWidth();
        params.bottomMargin = bottom;
        return params;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        );
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
