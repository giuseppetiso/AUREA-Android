package it.creativemaker3d.aurea;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.File;

/** Raccoglie soltanto telemetria tecnica locale, senza immagini o conversazioni. */
final class AureaTabletTelemetry {
    static final class Snapshot {
        final long time;
        final int batteryPercent;
        final boolean charging;
        final String powerSource;
        final float temperatureC;
        final boolean screenInteractive;
        final int screenBrightness;
        final int mediaVolumePercent;
        final boolean networkConnected;
        final String networkType;
        final boolean appForeground;
        final boolean microphoneAllowed;
        final boolean cameraAllowed;
        final long freeStorageMb;
        final long uptimeMinutes;
        final String activeProfile;

        Snapshot(
                long time,
                int batteryPercent,
                boolean charging,
                String powerSource,
                float temperatureC,
                boolean screenInteractive,
                int screenBrightness,
                int mediaVolumePercent,
                boolean networkConnected,
                String networkType,
                boolean appForeground,
                boolean microphoneAllowed,
                boolean cameraAllowed,
                long freeStorageMb,
                long uptimeMinutes,
                String activeProfile) {
            this.time = time;
            this.batteryPercent = batteryPercent;
            this.charging = charging;
            this.powerSource = clean(powerSource);
            this.temperatureC = temperatureC;
            this.screenInteractive = screenInteractive;
            this.screenBrightness = screenBrightness;
            this.mediaVolumePercent = mediaVolumePercent;
            this.networkConnected = networkConnected;
            this.networkType = clean(networkType);
            this.appForeground = appForeground;
            this.microphoneAllowed = microphoneAllowed;
            this.cameraAllowed = cameraAllowed;
            this.freeStorageMb = freeStorageMb;
            this.uptimeMinutes = uptimeMinutes;
            this.activeProfile = clean(activeProfile);
        }
    }

    private AureaTabletTelemetry() {
    }

    static Snapshot capture(Context source) {
        Context context = source.getApplicationContext();
        Intent battery = context.registerReceiver(
            null,
            new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        );

        int level = extra(battery, BatteryManager.EXTRA_LEVEL, -1);
        int scale = extra(battery, BatteryManager.EXTRA_SCALE, 100);
        int batteryPercent = level < 0 || scale <= 0
            ? -1
            : Math.max(0, Math.min(100, Math.round(level * 100f / scale)));
        int batteryStatus = extra(
            battery,
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        );
        boolean charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING
            || batteryStatus == BatteryManager.BATTERY_STATUS_FULL;
        int plugged = extra(battery, BatteryManager.EXTRA_PLUGGED, 0);
        float temperatureC = extra(battery, BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;

        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean interactive = power != null && power.isInteractive();

        int brightness = -1;
        try {
            brightness = Settings.System.getInt(
                context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS
            );
        } catch (Exception ignored) {
        }

        int volumePercent = -1;
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) {
            int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (maximum > 0) {
                volumePercent = Math.round(
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / maximum
                );
            }
        }

        NetworkState network = networkState(context);
        ActivityManager.RunningAppProcessInfo process =
            new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(process);
        boolean foreground = process.importance
            <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;

        File files = context.getFilesDir();
        long freeMb = files == null ? 0L : files.getUsableSpace() / (1024L * 1024L);
        String profile = AureaIdentityAutomation.activeProfile(context);

        return new Snapshot(
            System.currentTimeMillis(),
            batteryPercent,
            charging,
            powerSource(plugged),
            temperatureC,
            interactive,
            brightness,
            volumePercent,
            network.connected,
            network.type,
            foreground,
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED,
            freeMb,
            SystemClock.elapsedRealtime() / 60000L,
            profile
        );
    }

    private static NetworkState networkState(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(
            Context.CONNECTIVITY_SERVICE
        );
        if (manager == null) return new NetworkState(false, "nessuna");
        try {
            Network active = manager.getActiveNetwork();
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(active);
            if (capabilities == null) return new NetworkState(false, "nessuna");
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return new NetworkState(true, "wifi");
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return new NetworkState(true, "cellulare");
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return new NetworkState(true, "ethernet");
            }
            return new NetworkState(true, "altra");
        } catch (Exception ignored) {
            return new NetworkState(false, "sconosciuta");
        }
    }

    private static String powerSource(int plugged) {
        if ((plugged & BatteryManager.BATTERY_PLUGGED_AC) != 0) return "rete";
        if ((plugged & BatteryManager.BATTERY_PLUGGED_USB) != 0) return "usb";
        if ((plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) return "wireless";
        return "batteria";
    }

    private static int extra(Intent intent, String key, int fallback) {
        return intent == null ? fallback : intent.getIntExtra(key, fallback);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class NetworkState {
        final boolean connected;
        final String type;

        NetworkState(boolean connected, String type) {
            this.connected = connected;
            this.type = type;
        }
    }
}
