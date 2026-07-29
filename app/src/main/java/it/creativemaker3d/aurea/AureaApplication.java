package it.creativemaker3d.aurea;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;

/**
 * Inserisce la verifica vocale tra il riconoscimento facciale e la dashboard,
 * senza modificare il flusso stabile di MainActivity e Vosk.
 */
public final class AureaApplication extends Application
        implements Application.ActivityLifecycleCallbacks {

    private boolean voiceGateActive;
    private boolean redirecting;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle state) {
        if (activity instanceof VoiceGateActivity) {
            voiceGateActive = true;
            redirecting = false;
            return;
        }
        if (!(activity instanceof MainActivity)) {
            return;
        }

        Intent source = activity.getIntent();
        String person = source == null
            ? null
            : source.getStringExtra("aurea_recognized_person");
        if (person == null || person.trim().isEmpty()) {
            redirecting = false;
            return;
        }

        if (voiceGateActive) {
            voiceGateActive = false;
            redirecting = false;
            return;
        }
        if (redirecting) {
            return;
        }

        redirecting = true;
        Intent voice = new Intent(activity, VoiceGateActivity.class);
        voice.putExtra("aurea_recognized_person", person.trim());
        activity.startActivity(voice);
        activity.finish();
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (activity instanceof VoiceGateActivity && !activity.isChangingConfigurations()) {
            voiceGateActive = false;
        }
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityResumed(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle state) {
    }
}
