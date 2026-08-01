package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;

/**
 * Preferenze locali associate a una persona riconosciuta.
 *
 * Non contiene dati biometrici. Salva soltanto il nome da pronunciare e il
 * comportamento del saluto personale.
 */
final class PersonPreferencesStore {
    private static final String PREFS = "aurea_person_preferences";
    private static final String SUFFIX_SPOKEN_NAME = "_spoken_name";
    private static final String SUFFIX_GREETING_ENABLED = "_greeting_enabled";
    private static final String SUFFIX_TIME_GREETING = "_time_greeting";
    private static final String SUFFIX_CUSTOM_GREETING = "_custom_greeting";

    private final Context context;

    PersonPreferencesStore(Context context) {
        this.context = context.getApplicationContext();
    }

    Profile load(String personName) {
        String cleanName = clean(personName);
        String prefix = key(cleanName);
        SharedPreferences prefs = prefs();
        String spokenName = clean(
            prefs.getString(prefix + SUFFIX_SPOKEN_NAME, cleanName)
        );
        if (spokenName.isEmpty()) {
            spokenName = cleanName;
        }
        return new Profile(
            spokenName,
            prefs.getBoolean(prefix + SUFFIX_GREETING_ENABLED, true),
            prefs.getBoolean(prefix + SUFFIX_TIME_GREETING, true),
            clean(prefs.getString(prefix + SUFFIX_CUSTOM_GREETING, ""))
        );
    }

    void save(String personName, Profile profile) {
        if (profile == null) {
            return;
        }
        String cleanName = clean(personName);
        if (cleanName.isEmpty()) {
            return;
        }
        String spokenName = clean(profile.spokenName);
        if (spokenName.isEmpty()) {
            spokenName = cleanName;
        }
        String prefix = key(cleanName);
        prefs().edit()
            .putString(prefix + SUFFIX_SPOKEN_NAME, spokenName)
            .putBoolean(prefix + SUFFIX_GREETING_ENABLED, profile.greetingEnabled)
            .putBoolean(prefix + SUFFIX_TIME_GREETING, profile.timeGreeting)
            .putString(prefix + SUFFIX_CUSTOM_GREETING, clean(profile.customGreeting))
            .apply();
    }

    void delete(String personName) {
        String prefix = key(clean(personName));
        prefs().edit()
            .remove(prefix + SUFFIX_SPOKEN_NAME)
            .remove(prefix + SUFFIX_GREETING_ENABLED)
            .remove(prefix + SUFFIX_TIME_GREETING)
            .remove(prefix + SUFFIX_CUSTOM_GREETING)
            .apply();
    }

    String buildGreeting(String personName) {
        Profile profile = load(personName);
        if (!profile.greetingEnabled) {
            return "";
        }

        String spokenName = clean(profile.spokenName);
        if (spokenName.isEmpty()) {
            spokenName = clean(personName);
        }

        String custom = clean(profile.customGreeting);
        if (!custom.isEmpty()) {
            String result = custom.replace("{nome}", spokenName).trim();
            if (!result.isEmpty()) {
                return ensureFinalPunctuation(result);
            }
        }

        String opening = profile.timeGreeting
            ? openingForCurrentHour()
            : "Ciao";
        return opening + " " + spokenName + ", che piacere vederti.";
    }

    private String openingForCurrentHour() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour < 12) {
            return "Buongiorno";
        }
        if (hour >= 12 && hour < 18) {
            return "Buon pomeriggio";
        }
        if (hour >= 18) {
            return "Buonasera";
        }
        return "Ciao";
    }

    private String ensureFinalPunctuation(String value) {
        char last = value.charAt(value.length() - 1);
        if (last == '.' || last == '!' || last == '?') {
            return value;
        }
        return value + ".";
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String key(String name) {
        String normalized = clean(name).toLowerCase(Locale.ROOT);
        return Base64.encodeToString(
            normalized.getBytes(StandardCharsets.UTF_8),
            Base64.NO_WRAP | Base64.URL_SAFE
        );
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    static final class Profile {
        final String spokenName;
        final boolean greetingEnabled;
        final boolean timeGreeting;
        final String customGreeting;

        Profile(
                String spokenName,
                boolean greetingEnabled,
                boolean timeGreeting,
                String customGreeting) {
            this.spokenName = spokenName;
            this.greetingEnabled = greetingEnabled;
            this.timeGreeting = timeGreeting;
            this.customGreeting = customGreeting;
        }
    }
}
