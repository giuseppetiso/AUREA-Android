package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Stato locale del tablet fidato.
 *
 * Il profilo viene considerato fidato soltanto dopo che volto e voce sono già
 * disponibili. Non vengono salvate immagini o registrazioni audio.
 */
final class IdentitySessionStore {
    private static final String PREFS = "aurea_identity_session";
    private static final String KEY_INITIALIZED = "initialized";
    private static final String KEY_TRUSTED = "trusted";
    private static final String KEY_PERSON = "person";

    private final Context context;

    IdentitySessionStore(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isTrusted() {
        return prefs().getBoolean(KEY_TRUSTED, false);
    }

    String trustedPerson() {
        return prefs().getString(KEY_PERSON, "");
    }

    void trust(String person) {
        String normalized = person == null ? "" : person.trim();
        if (normalized.isEmpty()) {
            return;
        }
        prefs().edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putBoolean(KEY_TRUSTED, true)
            .putString(KEY_PERSON, normalized)
            .apply();
    }

    void clearTrust() {
        prefs().edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putBoolean(KEY_TRUSTED, false)
            .remove(KEY_PERSON)
            .apply();
    }

    /**
     * Migra automaticamente chi ha già completato volto e voce nelle versioni
     * precedenti, evitando di chiedere nuovamente la verifica dopo l'update.
     */
    boolean migrateExistingProfilesIfNeeded() {
        SharedPreferences session = prefs();
        if (session.getBoolean(KEY_INITIALIZED, false)) {
            return session.getBoolean(KEY_TRUSTED, false);
        }

        session.edit().putBoolean(KEY_INITIALIZED, true).apply();

        try {
            String raw = context
                .getSharedPreferences("aurea_face_profiles", Context.MODE_PRIVATE)
                .getString("profiles", "{}");
            JSONObject root = new JSONObject(raw == null ? "{}" : raw);
            JSONArray names = root.names();
            if (names == null) {
                return false;
            }

            VoiceProfileStore voices = new VoiceProfileStore(context);
            for (int index = 0; index < names.length(); index++) {
                String person = names.optString(index, "").trim();
                if (!person.isEmpty() && voices.hasProfile(person)) {
                    trust(person);
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
