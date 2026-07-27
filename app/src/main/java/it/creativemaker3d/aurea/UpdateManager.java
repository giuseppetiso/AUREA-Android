package it.creativemaker3d.aurea;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class UpdateManager {
    private static final String VERSION_URL =
        "https://raw.githubusercontent.com/giuseppetiso/AUREA-Android/main/version.json";

    private final Activity activity;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private long downloadId = -1;
    private boolean waitingInstallPermission;

    UpdateManager(Activity activity) {
        this.activity = activity;
    }

    void check(boolean showIfCurrent) {
        io.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(VERSION_URL).openConnection();
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);
                connection.setUseCaches(false);
                int code = connection.getResponseCode();
                if (code != 200) throw new IllegalStateException("HTTP " + code);

                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }

                JSONObject json = new JSONObject(body.toString());
                int availableCode = json.getInt("versionCode");
                String availableName = json.getString("versionName");
                String apkUrl = json.getString("apkUrl");
                int installedCode = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionCode;

                activity.runOnUiThread(() -> {
                    if (availableCode > installedCode) {
                        showUpdate(availableName, apkUrl);
                    } else if (showIfCurrent) {
                        Toast.makeText(activity, "AUREA è già aggiornata", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception ignored) {
                if (showIfCurrent) {
                    activity.runOnUiThread(() -> Toast.makeText(
                        activity, "Controllo aggiornamenti non disponibile", Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    private void showUpdate(String versionName, String apkUrl) {
        if (activity.isFinishing()) return;
        new AlertDialog.Builder(activity)
            .setTitle("Aggiornamento AUREA disponibile")
            .setMessage("È disponibile AUREA " + versionName + ". Vuoi scaricarla e installarla ora?")
            .setNegativeButton("Più tardi", null)
            .setPositiveButton("Scarica e installa", (dialog, which) -> download(apkUrl))
            .show();
    }

    private void download(String apkUrl) {
        try {
            File destination = new File(activity.getExternalFilesDir(
                Environment.DIRECTORY_DOWNLOADS), "AUREA-update.apk");
            if (destination.exists()) destination.delete();

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Aggiornamento AUREA")
                .setDescription("Download in corso")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(destination));

            DownloadManager manager =
                (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            downloadId = manager.enqueue(request);

            IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= 33) {
                activity.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                activity.registerReceiver(downloadReceiver, filter);
            }
            Toast.makeText(activity, "Download aggiornamento avviato", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(activity, "Impossibile avviare il download", Toast.LENGTH_LONG).show();
        }
    }

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            long completed = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (completed != downloadId) return;
            try { activity.unregisterReceiver(this); } catch (Exception ignored) {}
            openInstaller();
        }
    };

    private void openInstaller() {
        if (Build.VERSION.SDK_INT >= 26 &&
            !activity.getPackageManager().canRequestPackageInstalls()) {
            Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
            Toast.makeText(activity,
                "Consenti installazioni da AUREA, poi ripeti l’aggiornamento",
                Toast.LENGTH_LONG).show();
            waitingInstallPermission = true;
            activity.startActivity(permission);
            return;
        }

        File apk = new File(activity.getExternalFilesDir(
            Environment.DIRECTORY_DOWNLOADS), "AUREA-update.apk");
        Uri uri = FileProvider.getUriForFile(activity,
            activity.getPackageName() + ".fileprovider", apk);
        Intent install = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(install);
    }

    void resumePendingInstall() {
        if (waitingInstallPermission && (Build.VERSION.SDK_INT < 26 ||
            activity.getPackageManager().canRequestPackageInstalls())) {
            waitingInstallPermission = false;
            openInstaller();
        }
    }

    void close() {
        io.shutdownNow();
        try { activity.unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
    }
}
