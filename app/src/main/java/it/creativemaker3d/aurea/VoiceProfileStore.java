package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class VoiceProfileStore {
    private static final String PREFS = "aurea_voice_profiles";
    private static final float MAX_EFFECTIVE_THRESHOLD = 0.85f;

    private final Context context;

    VoiceProfileStore(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean hasProfile(String name) {
        return prefs().contains(key(name) + "_vector");
    }

    void saveProfile(String name, float[] signature, float threshold) {
        ByteBuffer bytes = ByteBuffer
            .allocate(signature.length * 4)
            .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : signature) {
            bytes.putFloat(value);
        }
        String encoded = Base64.encodeToString(bytes.array(), Base64.NO_WRAP);
        prefs().edit()
            .putString(key(name) + "_vector", encoded)
            .putFloat(key(name) + "_threshold", threshold)
            .apply();
    }

    VoiceProfile loadProfile(String name) {
        String encoded = prefs().getString(key(name) + "_vector", null);
        if (encoded == null || encoded.trim().isEmpty()) {
            return null;
        }
        try {
            byte[] raw = Base64.decode(encoded, Base64.DEFAULT);
            if (raw.length == 0 || raw.length % 4 != 0) {
                return null;
            }
            ByteBuffer bytes = ByteBuffer
                .wrap(raw)
                .order(ByteOrder.LITTLE_ENDIAN);
            float[] signature = new float[raw.length / 4];
            for (int i = 0; i < signature.length; i++) {
                signature[i] = bytes.getFloat();
            }
            float storedThreshold = prefs().getFloat(
                key(name) + "_threshold",
                0.85f
            );
            float threshold = Math.min(storedThreshold, MAX_EFFECTIVE_THRESHOLD);
            return new VoiceProfile(signature, threshold);
        } catch (Exception ignored) {
            return null;
        }
    }

    void deleteProfile(String name) {
        prefs().edit()
            .remove(key(name) + "_vector")
            .remove(key(name) + "_threshold")
            .apply();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String key(String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return Base64.encodeToString(
            normalized.getBytes(StandardCharsets.UTF_8),
            Base64.NO_WRAP | Base64.URL_SAFE
        );
    }

    static final class VoiceProfile {
        final float[] signature;
        final float threshold;

        VoiceProfile(float[] signature, float threshold) {
            this.signature = signature;
            this.threshold = threshold;
        }
    }
}
