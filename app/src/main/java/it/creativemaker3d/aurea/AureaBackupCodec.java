package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Crea e ripristina backup AUREA protetti da password.
 *
 * Il token Home Assistant non viene mai inserito nel backup. Volti e voci sono
 * già memorizzati come firme numeriche locali; il file le protegge ulteriormente
 * con AES-256-GCM e una chiave derivata dalla password tramite PBKDF2.
 */
final class AureaBackupCodec {
    private static final String BACKUP_FORMAT = "AUREA_BACKUP";
    private static final String ENVELOPE_FORMAT = "AUREA_ENCRYPTED_BACKUP";
    private static final int SCHEMA_VERSION = 1;
    private static final int ITERATIONS = 180_000;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int KEY_BITS = 256;
    private static final byte[] AAD = "AUREA_BACKUP_V1"
        .getBytes(StandardCharsets.UTF_8);

    private static final String APP_PREFS = "aurea";
    private static final String FACE_PREFS = "aurea_face_profiles";
    private static final String VOICE_PREFS = "aurea_voice_profiles";
    private static final String PERSON_PREFS = "aurea_person_preferences";
    private static final String LEARNING_PREFS = AureaLearningStore.PREFS_NAME;

    private static final Set<String> SAFE_APP_KEYS = new HashSet<>(Arrays.asList(
        "ha_url",
        "dashboard_url",
        "dashboard_home_confirmed"
    ));

    private AureaBackupCodec() {
    }

    static byte[] create(Context context, char[] password) throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", BACKUP_FORMAT);
        root.put("schema", SCHEMA_VERSION);
        root.put("createdAt", System.currentTimeMillis());
        root.put("appVersion", BuildConfig.VERSION_NAME);
        root.put("tokenHomeAssistantIncluso", false);

        JSONObject preferences = new JSONObject();
        preferences.put(
            FACE_PREFS,
            snapshot(context, FACE_PREFS, key -> true)
        );
        preferences.put(
            VOICE_PREFS,
            snapshot(context, VOICE_PREFS, key -> true)
        );
        preferences.put(
            PERSON_PREFS,
            snapshot(context, PERSON_PREFS, key -> true)
        );
        preferences.put(
            LEARNING_PREFS,
            snapshot(context, LEARNING_PREFS, key -> true)
        );
        preferences.put(
            APP_PREFS,
            snapshot(context, APP_PREFS, SAFE_APP_KEYS::contains)
        );
        root.put("preferences", preferences);

        return encrypt(root.toString().getBytes(StandardCharsets.UTF_8), password);
    }

    static RestoreSummary restore(
            Context context,
            byte[] encrypted,
            char[] password) throws Exception {
        byte[] plain = decrypt(encrypted, password);
        JSONObject root = new JSONObject(new String(plain, StandardCharsets.UTF_8));

        if (!BACKUP_FORMAT.equals(root.optString("format"))
                || root.optInt("schema", -1) != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Formato backup AUREA non valido");
        }

        JSONObject preferences = root.optJSONObject("preferences");
        if (preferences == null) {
            throw new IllegalArgumentException("Backup privo delle preferenze AUREA");
        }

        restoreReplacing(
            context,
            FACE_PREFS,
            requiredObject(preferences, FACE_PREFS)
        );
        restoreReplacing(
            context,
            VOICE_PREFS,
            requiredObject(preferences, VOICE_PREFS)
        );
        restoreReplacing(
            context,
            PERSON_PREFS,
            requiredObject(preferences, PERSON_PREFS)
        );

        JSONObject learning = preferences.optJSONObject(LEARNING_PREFS);
        if (learning != null) {
            restoreReplacing(context, LEARNING_PREFS, learning);
        }

        restoreSafeAppPreferences(
            context,
            requiredObject(preferences, APP_PREFS)
        );

        new AureaBrainStore(context).clearAllConversations();
        new IdentitySessionStore(context).clearTrust();
        new AdminAccessStore(context).revoke();

        int faces = countFaceProfiles(context);
        int voices = countVoiceProfiles(context);
        return new RestoreSummary(
            faces,
            voices,
            root.optLong("createdAt", 0L),
            root.optString("appVersion", "")
        );
    }

    private static JSONObject requiredObject(
            JSONObject parent,
            String key) throws Exception {
        JSONObject value = parent.optJSONObject(key);
        if (value == null) {
            throw new IllegalArgumentException("Sezione backup mancante: " + key);
        }
        return value;
    }

    private static JSONObject snapshot(
            Context context,
            String name,
            KeyFilter filter) throws Exception {
        JSONObject result = new JSONObject();
        Map<String, ?> values = context
            .getSharedPreferences(name, Context.MODE_PRIVATE)
            .getAll();

        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || !filter.accept(key)) {
                continue;
            }
            JSONObject typed = encodeValue(entry.getValue());
            if (typed != null) {
                result.put(key, typed);
            }
        }
        return result;
    }

    private static JSONObject encodeValue(Object value) throws Exception {
        JSONObject typed = new JSONObject();
        if (value instanceof String) {
            typed.put("type", "string");
            typed.put("value", value);
        } else if (value instanceof Boolean) {
            typed.put("type", "boolean");
            typed.put("value", value);
        } else if (value instanceof Integer) {
            typed.put("type", "int");
            typed.put("value", value);
        } else if (value instanceof Long) {
            typed.put("type", "long");
            typed.put("value", value);
        } else if (value instanceof Float) {
            typed.put("type", "float");
            typed.put("value", ((Float) value).doubleValue());
        } else if (value instanceof Set) {
            typed.put("type", "stringSet");
            JSONArray array = new JSONArray();
            for (Object item : (Set<?>) value) {
                if (item instanceof String) {
                    array.put(item);
                }
            }
            typed.put("value", array);
        } else {
            return null;
        }
        return typed;
    }

    private static void restoreReplacing(
            Context context,
            String name,
            JSONObject values) throws Exception {
        SharedPreferences.Editor editor = context
            .getSharedPreferences(name, Context.MODE_PRIVATE)
            .edit()
            .clear();
        decodeInto(editor, values, key -> true);
        if (!editor.commit()) {
            throw new IllegalStateException("Ripristino non riuscito: " + name);
        }
    }

    private static void restoreSafeAppPreferences(
            Context context,
            JSONObject values) throws Exception {
        SharedPreferences.Editor editor = context
            .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit();
        for (String key : SAFE_APP_KEYS) {
            editor.remove(key);
        }
        decodeInto(editor, values, SAFE_APP_KEYS::contains);
        if (!editor.commit()) {
            throw new IllegalStateException("Ripristino impostazioni AUREA non riuscito");
        }
    }

    private static void decodeInto(
            SharedPreferences.Editor editor,
            JSONObject values,
            KeyFilter filter) throws Exception {
        JSONArray names = values.names();
        if (names == null) {
            return;
        }

        for (int index = 0; index < names.length(); index++) {
            String key = names.optString(index, "");
            if (key.isEmpty() || !filter.accept(key)) {
                continue;
            }
            JSONObject typed = values.optJSONObject(key);
            if (typed == null) {
                continue;
            }
            String type = typed.optString("type", "");
            switch (type) {
                case "string":
                    editor.putString(key, typed.optString("value", ""));
                    break;
                case "boolean":
                    editor.putBoolean(key, typed.optBoolean("value", false));
                    break;
                case "int":
                    editor.putInt(key, typed.optInt("value", 0));
                    break;
                case "long":
                    editor.putLong(key, typed.optLong("value", 0L));
                    break;
                case "float":
                    editor.putFloat(key, (float) typed.optDouble("value", 0.0));
                    break;
                case "stringSet":
                    JSONArray array = typed.optJSONArray("value");
                    HashSet<String> set = new HashSet<>();
                    if (array != null) {
                        for (int item = 0; item < array.length(); item++) {
                            String value = array.optString(item, "");
                            if (!value.isEmpty()) {
                                set.add(value);
                            }
                        }
                    }
                    editor.putStringSet(key, set);
                    break;
                default:
                    break;
            }
        }
    }

    private static byte[] encrypt(byte[] plain, char[] password) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(salt);
        random.nextBytes(iv);

        SecretKey key = deriveKey(password, salt, ITERATIONS);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(AAD);
        byte[] ciphertext = cipher.doFinal(plain);

        JSONObject envelope = new JSONObject();
        envelope.put("format", ENVELOPE_FORMAT);
        envelope.put("version", SCHEMA_VERSION);
        envelope.put("iterations", ITERATIONS);
        envelope.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
        envelope.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
        envelope.put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
        return envelope.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] decrypt(byte[] encrypted, char[] password) throws Exception {
        JSONObject envelope = new JSONObject(
            new String(encrypted, StandardCharsets.UTF_8)
        );
        if (!ENVELOPE_FORMAT.equals(envelope.optString("format"))
                || envelope.optInt("version", -1) != SCHEMA_VERSION) {
            throw new IllegalArgumentException("File non riconosciuto come backup AUREA");
        }

        int iterations = envelope.optInt("iterations", 0);
        if (iterations < 100_000 || iterations > 1_000_000) {
            throw new IllegalArgumentException("Parametri di cifratura non validi");
        }

        byte[] salt = Base64.decode(envelope.getString("salt"), Base64.DEFAULT);
        byte[] iv = Base64.decode(envelope.getString("iv"), Base64.DEFAULT);
        byte[] ciphertext = Base64.decode(
            envelope.getString("data"),
            Base64.DEFAULT
        );
        if (salt.length != SALT_BYTES || iv.length != IV_BYTES
                || ciphertext.length < 16) {
            throw new IllegalArgumentException("Backup AUREA danneggiato");
        }

        SecretKey key = deriveKey(password, salt, iterations);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(AAD);
        return cipher.doFinal(ciphertext);
    }

    private static SecretKey deriveKey(
            char[] password,
            byte[] salt,
            int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(
                "PBKDF2WithHmacSHA256"
            );
            byte[] encoded = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(encoded, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static int countFaceProfiles(Context context) {
        try {
            String raw = context.getSharedPreferences(
                FACE_PREFS,
                Context.MODE_PRIVATE
            ).getString("profiles", "{}");
            JSONObject root = new JSONObject(raw == null ? "{}" : raw);
            JSONArray names = root.names();
            return names == null ? 0 : names.length();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int countVoiceProfiles(Context context) {
        int count = 0;
        Map<String, ?> all = context.getSharedPreferences(
            VOICE_PREFS,
            Context.MODE_PRIVATE
        ).getAll();
        for (String key : all.keySet()) {
            if (key != null && key.endsWith("_vector")) {
                count++;
            }
        }
        return count;
    }

    interface KeyFilter {
        boolean accept(String key);
    }

    static final class RestoreSummary {
        final int faceProfiles;
        final int voiceProfiles;
        final long createdAt;
        final String appVersion;

        RestoreSummary(
                int faceProfiles,
                int voiceProfiles,
                long createdAt,
                String appVersion) {
            this.faceProfiles = faceProfiles;
            this.voiceProfiles = voiceProfiles;
            this.createdAt = createdAt;
            this.appVersion = appVersion == null ? "" : appVersion;
        }
    }
}
