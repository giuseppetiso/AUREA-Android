package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Conserva esclusivamente lo stato temporaneo dell'accesso amministratore.
 * Non contiene dati biometrici e non modifica i profili delle persone.
 */
final class AdminAccessStore {
    static final String ADMIN_NAME = "Giuseppe";

    private static final String PREFS = "aurea_admin_access";
    private static final String KEY_REQUESTED_AT = "requested_at";
    private static final String KEY_GRANTED_UNTIL = "granted_until";

    private static final long REQUEST_TIMEOUT_MS = 2L * 60L * 1000L;
    private static final long GRANT_TIMEOUT_MS = 10L * 60L * 1000L;

    private final Context context;

    AdminAccessStore(Context context) {
        this.context = context.getApplicationContext();
    }

    void requestAccess() {
        prefs().edit()
            .putLong(KEY_REQUESTED_AT, System.currentTimeMillis())
            .remove(KEY_GRANTED_UNTIL)
            .apply();
    }

    boolean isAccessRequested() {
        long requestedAt = prefs().getLong(KEY_REQUESTED_AT, 0L);
        boolean valid = requestedAt > 0L
            && System.currentTimeMillis() - requestedAt <= REQUEST_TIMEOUT_MS;
        if (!valid && requestedAt > 0L) {
            clearRequest();
        }
        return valid;
    }

    void clearRequest() {
        prefs().edit().remove(KEY_REQUESTED_AT).apply();
    }

    boolean grant(String person) {
        if (person == null || !ADMIN_NAME.equalsIgnoreCase(person.trim())) {
            revoke();
            return false;
        }
        prefs().edit()
            .remove(KEY_REQUESTED_AT)
            .putLong(
                KEY_GRANTED_UNTIL,
                System.currentTimeMillis() + GRANT_TIMEOUT_MS
            )
            .apply();
        return true;
    }

    boolean hasValidGrant() {
        long grantedUntil = prefs().getLong(KEY_GRANTED_UNTIL, 0L);
        boolean valid = grantedUntil > System.currentTimeMillis();
        if (!valid && grantedUntil > 0L) {
            revoke();
        }
        return valid;
    }

    void touch() {
        if (!hasValidGrant()) {
            return;
        }
        prefs().edit()
            .putLong(
                KEY_GRANTED_UNTIL,
                System.currentTimeMillis() + GRANT_TIMEOUT_MS
            )
            .apply();
    }

    void revoke() {
        prefs().edit()
            .remove(KEY_REQUESTED_AT)
            .remove(KEY_GRANTED_UNTIL)
            .apply();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
