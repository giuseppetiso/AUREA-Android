package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Verifica l'accesso alle funzioni comuni di AUREA.
 *
 * Le funzioni comuni sono disponibili a qualunque persona che abbia già un
 * profilo completo di volto e voce e che sia stata confermata nella sessione
 * corrente. La gestione delle persone resta separata e riservata a Giuseppe.
 */
final class RegisteredUserAccess {
    private static final String FACE_PREFS = "aurea_face_profiles";
    private static final String FACE_KEY = "profiles";

    private RegisteredUserAccess() {
    }

    static boolean isAllowed(Context context) {
        return !currentPerson(context).isEmpty();
    }

    static String currentPerson(Context context) {
        if (context == null) {
            return "";
        }

        IdentitySessionStore session = new IdentitySessionStore(context);
        if (!session.isTrusted()) {
            return "";
        }

        String trusted = clean(session.trustedPerson());
        if (trusted.isEmpty() || !new VoiceProfileStore(context).hasProfile(trusted)) {
            return "";
        }

        try {
            SharedPreferences prefs = context.getSharedPreferences(
                FACE_PREFS,
                Context.MODE_PRIVATE
            );
            String raw = prefs.getString(FACE_KEY, "{}");
            JSONObject root = new JSONObject(raw == null ? "{}" : raw);
            JSONArray names = root.names();
            if (names == null) {
                return "";
            }
            for (int index = 0; index < names.length(); index++) {
                String stored = clean(names.optString(index, ""));
                if (stored.equalsIgnoreCase(trusted)) {
                    return stored;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
