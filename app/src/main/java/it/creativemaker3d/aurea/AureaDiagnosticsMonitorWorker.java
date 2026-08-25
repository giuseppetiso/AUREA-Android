package it.creativemaker3d.aurea;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Esegue diagnosi e consegna del rapporto quando AUREA non è in primo piano. */
public final class AureaDiagnosticsMonitorWorker extends Worker {
    public AureaDiagnosticsMonitorWorker(
            @NonNull Context context,
            @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        AureaDiagnosticsProbe.Snapshot snapshot = new AureaDiagnosticsProbe(context).run();
        AureaDiagnosticsPublisher.PublishResult delivery =
            new AureaDiagnosticsPublisher(context).publish(snapshot);
        if (delivery.success) return Result.success();
        return delivery.retryable ? Result.retry() : Result.failure();
    }
}
