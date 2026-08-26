package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Archivio locale versionato delle firme vocali. Non conserva audio. */
final class VoiceProfileStore {
    private static final String PREFS = "aurea_voice_profiles";
    private static final int SCHEMA_V2 = 2;
    private static final float MAX_LEGACY_THRESHOLD = 0.85f;

    private final Context context;

    VoiceProfileStore(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean hasProfile(String name) {
        return prefs().contains(key(name) + "_vector")
            || prefs().contains(key(name) + "_templates");
    }

    boolean needsCalibration(String name) {
        VoiceProfile profile = loadProfile(name);
        return profile == null || !profile.calibratedV2;
    }

    void saveProfile(String name, float[] signature, float threshold) {
        prefs().edit()
            .putString(key(name) + "_vector", encode(signature))
            .putFloat(key(name) + "_threshold", threshold)
            .apply();
    }

    void saveProfileV2(String name, List<float[]> signatures, float threshold) {
        if (signatures == null || signatures.size() < 3) {
            throw new IllegalArgumentException("Profilo vocale incompleto");
        }
        JSONArray templates = new JSONArray();
        for (float[] signature : signatures) {
            if (signature != null) templates.put(encode(signature));
        }
        float[] centroid = VoiceSignature.mean(signatures);
        prefs().edit()
            .putInt(key(name) + "_schema", SCHEMA_V2)
            .putString(key(name) + "_templates", templates.toString())
            .putString(key(name) + "_vector", encode(centroid))
            .putFloat(key(name) + "_threshold", threshold)
            .putLong(key(name) + "_calibrated_at", System.currentTimeMillis())
            .apply();
    }

    VoiceProfile loadProfile(String name) {
        String prefix = key(name);
        SharedPreferences prefs = prefs();
        int schema = prefs.getInt(prefix + "_schema", 1);
        ArrayList<float[]> templates = new ArrayList<>();
        if (schema >= SCHEMA_V2) {
            try {
                JSONArray stored = new JSONArray(
                    prefs.getString(prefix + "_templates", "[]")
                );
                for (int index = 0; index < stored.length(); index++) {
                    float[] value = decode(stored.optString(index, ""));
                    if (value != null) templates.add(value);
                }
            } catch (Exception ignored) {
            }
        }
        float[] centroid = decode(prefs.getString(prefix + "_vector", null));
        if (centroid == null && templates.isEmpty()) return null;
        if (centroid == null) centroid = VoiceSignature.mean(templates);
        if (templates.isEmpty() && centroid != null) templates.add(centroid);

        float storedThreshold = prefs.getFloat(prefix + "_threshold", 0.80f);
        boolean calibrated = schema >= SCHEMA_V2 && templates.size() >= 3;
        float threshold = calibrated
            ? clamp(storedThreshold, 0.68f, 0.90f)
            : Math.min(storedThreshold, MAX_LEGACY_THRESHOLD);
        return new VoiceProfile(templates, centroid, threshold, calibrated);
    }

    float matchScore(VoiceProfile profile, float[] signature) {
        if (profile == null || signature == null) return -1f;
        return VoiceSignature.profileScore(
            profile.templates,
            profile.signature,
            signature
        );
    }

    void deleteProfile(String name) {
        String prefix = key(name);
        prefs().edit()
            .remove(prefix + "_schema")
            .remove(prefix + "_templates")
            .remove(prefix + "_vector")
            .remove(prefix + "_threshold")
            .remove(prefix + "_calibrated_at")
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

    private static String encode(float[] signature) {
        if (signature == null || signature.length == 0) return "";
        ByteBuffer bytes = ByteBuffer.allocate(signature.length * 4)
            .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : signature) bytes.putFloat(value);
        return Base64.encodeToString(bytes.array(), Base64.NO_WRAP);
    }

    private static float[] decode(String encoded) {
        if (encoded == null || encoded.trim().isEmpty()) return null;
        try {
            byte[] raw = Base64.decode(encoded, Base64.DEFAULT);
            if (raw.length == 0 || raw.length % 4 != 0) return null;
            ByteBuffer bytes = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            float[] signature = new float[raw.length / 4];
            for (int index = 0; index < signature.length; index++) {
                signature[index] = bytes.getFloat();
            }
            return signature;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static final class VoiceProfile {
        final List<float[]> templates;
        final float[] signature;
        final float threshold;
        final boolean calibratedV2;

        VoiceProfile(
                List<float[]> templates,
                float[] signature,
                float threshold,
                boolean calibratedV2) {
            this.templates = templates;
            this.signature = signature;
            this.threshold = threshold;
            this.calibratedV2 = calibratedV2;
        }
    }
}
