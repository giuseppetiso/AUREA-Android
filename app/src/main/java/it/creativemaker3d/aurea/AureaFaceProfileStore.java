package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Archivio locale versionato delle firme facciali. Non conserva immagini. */
final class AureaFaceProfileStore {
    private static final String PREFS = "aurea_face_profiles";
    private static final String KEY_PROFILES = "profiles";
    private static final int SCHEMA_V2 = 2;

    static final class Template {
        final float[] embedding;
        final float[] texture;

        Template(float[] embedding, float[] texture) {
            this.embedding = embedding;
            this.texture = texture;
        }
    }

    static final class Profile {
        final String name;
        final List<Template> templates;
        final float threshold;
        final float[] legacyVector;

        Profile(
                String name,
                List<Template> templates,
                float threshold,
                float[] legacyVector) {
            this.name = clean(name);
            this.templates = templates;
            this.threshold = threshold;
            this.legacyVector = legacyVector;
        }

        boolean isCalibratedV2() {
            return templates != null && templates.size() >= 6;
        }
    }

    private final Context context;

    AureaFaceProfileStore(Context context) {
        this.context = context.getApplicationContext();
    }

    List<Profile> loadProfiles() {
        ArrayList<Profile> result = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(
                prefs().getString(KEY_PROFILES, "{}")
            );
            JSONArray names = root.names();
            if (names == null) return result;
            for (int index = 0; index < names.length(); index++) {
                String name = clean(names.optString(index, ""));
                JSONObject stored = name.isEmpty() ? null : root.optJSONObject(name);
                if (stored == null) continue;

                ArrayList<Template> templates = new ArrayList<>();
                if (stored.optInt("schema", 1) >= SCHEMA_V2
                        && AureaFaceRecognitionEngine.ENGINE_ID.equals(
                            stored.optString("engine", "")
                        )) {
                    JSONArray values = stored.optJSONArray("templates");
                    if (values != null) {
                        for (int item = 0; item < values.length(); item++) {
                            JSONObject value = values.optJSONObject(item);
                            if (value == null) continue;
                            float[] embedding = decode(value.optString("embedding", ""));
                            float[] texture = decode(value.optString("texture", ""));
                            if (embedding != null && embedding.length >= 128
                                    && texture != null
                                    && texture.length == AureaFaceRecognitionEngine.TEXTURE_SIZE) {
                                templates.add(new Template(embedding, texture));
                            }
                        }
                    }
                }
                float[] legacy = decode(stored.optString("vector", ""));
                float threshold = (float) stored.optDouble(
                    "threshold",
                    templates.isEmpty() ? 0.80 : 0.84
                );
                if (!templates.isEmpty() || legacy != null) {
                    result.add(new Profile(name, templates, threshold, legacy));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    void saveV2(String name, List<AureaFaceRecognitionEngine.Sample> samples,
            float threshold) {
        if (clean(name).isEmpty() || samples == null || samples.size() < 6) {
            throw new IllegalArgumentException("Profilo facciale incompleto");
        }
        try {
            SharedPreferences prefs = prefs();
            JSONObject root = new JSONObject(prefs.getString(KEY_PROFILES, "{}"));
            JSONObject previous = root.optJSONObject(clean(name));
            JSONObject stored = new JSONObject();
            stored.put("schema", SCHEMA_V2);
            stored.put("engine", AureaFaceRecognitionEngine.ENGINE_ID);
            stored.put("threshold", threshold);
            stored.put("calibrated_at", System.currentTimeMillis());
            if (previous != null && previous.has("vector")) {
                stored.put("vector", previous.optString("vector", ""));
            }
            JSONArray templates = new JSONArray();
            for (AureaFaceRecognitionEngine.Sample sample : samples) {
                if (sample == null || sample.embedding == null || sample.texture == null) {
                    continue;
                }
                JSONObject value = new JSONObject();
                value.put("embedding", encode(sample.embedding));
                value.put("texture", encode(sample.texture));
                templates.put(value);
            }
            stored.put("templates", templates);
            root.put(clean(name), stored);
            prefs.edit().putString(KEY_PROFILES, root.toString()).apply();
        } catch (Exception error) {
            throw new IllegalStateException("Impossibile salvare il volto", error);
        }
    }

    int profileCount() {
        return loadProfiles().size();
    }

    int calibratedProfileCount() {
        int count = 0;
        for (Profile profile : loadProfiles()) {
            if (profile.isCalibratedV2()) count++;
        }
        return count;
    }

    boolean needsCalibration(String name) {
        String target = clean(name);
        for (Profile profile : loadProfiles()) {
            if (profile.name.equalsIgnoreCase(target)) {
                return !profile.isCalibratedV2();
            }
        }
        return true;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String encode(float[] vector) {
        ByteBuffer bytes = ByteBuffer.allocate(vector.length * 4)
            .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) bytes.putFloat(value);
        return Base64.encodeToString(bytes.array(), Base64.NO_WRAP);
    }

    private static float[] decode(String encoded) {
        if (encoded == null || encoded.trim().isEmpty()) return null;
        try {
            byte[] raw = Base64.decode(encoded, Base64.DEFAULT);
            if (raw.length == 0 || raw.length % 4 != 0) return null;
            ByteBuffer bytes = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            float[] vector = new float[raw.length / 4];
            for (int index = 0; index < vector.length; index++) {
                vector[index] = bytes.getFloat();
            }
            return vector;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
