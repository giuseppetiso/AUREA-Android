package it.creativemaker3d.aurea;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

/**
 * Punto di ingresso dell'icona AUREA.
 *
 * Android può riportare in primo piano il vecchio task WebView lasciandolo
 * sull'ultima pagina visitata. Questo launcher elimina quel task e crea una
 * MainActivity nuova, così viene riletto e caricato l'URL iniziale salvato.
 */
public final class HomeLauncherActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        ComponentName dashboard = new ComponentName(this, MainActivity.class);
        Intent freshStart = Intent.makeRestartActivityTask(dashboard);
        freshStart.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(freshStart);
        finish();
        overridePendingTransition(0, 0);
    }
}
