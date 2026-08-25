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
    private static final String VERSION_API_URL =
        "https://api.github.com/repos/giuseppetiso/AUREA-Android/"
            + "contents/version.json?ref=main";
    private static final String VERSION_FALLBACK_URL =
        "https://raw.githubusercontent.com/giuseppetiso/AUREA-Android/"
            + "aurea-latest/version.json";

    private static final class Channel {
        final int code;
        final String name;
        final String apkUrl;

        Channel(int code, String name, String apkUrl) {
            this.code = code;
            this.name = name;
            this.apkUrl = apkUrl;
        }
    }

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
                Channel channel = readChannel();
                int installedCode = BuildConfig.VERSION_CODE;
                String installedName = BuildConfig.VERSION_NAME;

                activity.runOnUiThread(() -> {
                    if (channel.code > installedCode) {
                        showUpdate(channel.name, channel.apkUrl);
                    } else if (showIfCurrent) {
                        Toast.makeText(
                            activity,
                            "Installata " + installedName + " (" + installedCode + ")"
                                + " · canale " + channel.name + " (" + channel.code + ")",
                            Toast.LENGTH_LONG
                        ).show();
                    }
                });
            } catch (Exception error) {
                new AureaDiagnosticsLog(activity).error(
                    "Aggiornamenti",
                    "Controllo del canale firmato non riuscito",
                    error
                );
                if (showIfCurrent) {
                    activity.runOnUiThread(() -> Toast.makeText(
                        activity, "Controllo aggiornamenti non disponibile", Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    private Channel readChannel() throws Exception {
        Exception primaryError;
        try {
            return readChannelUrl(
                VERSION_API_URL + "&check=" + System.currentTimeMillis(),
                "application/vnd.github.raw+json"
            );
        } catch (Exception error) {
            primaryError = error;
        }

        try {
            return readChannelUrl(
                VERSION_FALLBACK_URL + "?check=" + System.currentTimeMillis(),
                "application/json"
            );
        } catch (Exception fallbackError) {
            fallbackError.addSuppressed(primaryError);
            throw fallbackError;
        }
    }

    private Channel readChannelUrl(String address, String accept) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setUseCaches(false);
            connection.setDefaultUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty(
                "User-Agent",
                "AUREA-Android/" + BuildConfig.VERSION_NAME
            );
            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                throw new IllegalStateException("HTTP " + responseCode);
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }

            JSONObject json = new JSONObject(body.toString());
            return new Channel(
                json.getInt("versionCode"),
                json.getString("versionName"),
                json.getString("apkUrl")
            );
        } finally {
            connection.disconnect();
        }
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
            if (manager == null) {
                throw new IllegalStateException("DownloadManager non disponibile");
            }
            downloadId = manager.enqueue(request);

            IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= 33) {
                activity.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                activity.registerReceiver(downloadReceiver, filter);
            }
            Toast.makeText(activity, "Download aggiornamento avviato", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            new AureaDiagnosticsLog(activity).error(
                "Aggiornamenti",
                "Avvio download APK non riuscito",
                error
            );
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
        try {
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
            if (!apk.isFile()) {
                throw new IllegalStateException("APK scaricato non trovato");
            }
            Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".fileprovider", apk);
            Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
        } catch (Exception error) {
            new AureaDiagnosticsLog(activity).error(
                "Aggiornamenti",
                "Apertura installazione APK non riuscita",
                error
            );
            Toast.makeText(
                activity,
                "Impossibile aprire l'installazione dell'aggiornamento",
                Toast.LENGTH_LONG
            ).show();
        }
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
