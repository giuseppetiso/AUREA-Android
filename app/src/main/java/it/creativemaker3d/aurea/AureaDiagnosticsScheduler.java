package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Mantiene un solo controllo diagnostico periodico, anche dopo riavvii e aggiornamenti. */
final class AureaDiagnosticsScheduler {
    static final long INTERVAL_MINUTES = 30L;

    private static final String PERIODIC_WORK = "aurea_diagnostics_periodic";
    private static final String UPGRADE_WORK = "aurea_diagnostics_after_upgrade";
    private static final String PREFS = "aurea_diagnostics_monitor";
    private static final String KEY_SCHEDULED_VERSION = "scheduled_version";

    private AureaDiagnosticsScheduler() {
    }

    static void schedule(Context source) {
        Context context = source.getApplicationContext();
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();

        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
            AureaDiagnosticsMonitorWorker.class,
            INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.MINUTES)
            .build();

        WorkManager workManager = WorkManager.getInstance(context);
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        );

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int scheduledVersion = prefs.getInt(KEY_SCHEDULED_VERSION, 0);
        if (scheduledVersion == BuildConfig.VERSION_CODE) return;

        OneTimeWorkRequest afterUpgrade = new OneTimeWorkRequest.Builder(
            AureaDiagnosticsMonitorWorker.class
        )
            .setConstraints(constraints)
            .setInitialDelay(2L, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.MINUTES)
            .build();
        workManager.enqueueUniqueWork(
            UPGRADE_WORK,
            ExistingWorkPolicy.REPLACE,
            afterUpgrade
        );
        prefs.edit().putInt(KEY_SCHEDULED_VERSION, BuildConfig.VERSION_CODE).apply();
    }
}
