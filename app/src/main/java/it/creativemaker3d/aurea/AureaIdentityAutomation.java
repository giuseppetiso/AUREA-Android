package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Trasforma la sola identità locale in eventi prudenti e personalizzabili.
 * Non concede autorizzazioni e non conserva immagini, audio o firme biometriche.
 */
final class AureaIdentityAutomation {
    static final String PREFS_NAME = "aurea_identity_automation";
    static final String KEY_PASSIVE_GREETINGS = "passive_greetings_enabled";
    static final String KEY_LAST_HA_PUBLISH = "last_ha_publish";
    static final String KEY_LAST_HA_ERROR = "last_ha_error";

    private static final String KEY_STATE = "state";
    private static final String KEY_IDENTITY = "identity";
    private static final String KEY_ACTIVE_PROFILE = "active_profile";
    private static final String KEY_CONFIDENCE = "confidence";
    private static final String KEY_PRESENT = "present";
    private static final String KEY_LAST_EVENT_AT = "last_event_at";
    private static final String KEY_LAST_RECOGNIZED_AT = "last_recognized_at";
    private static final String KEY_LAST_UNKNOWN_AT = "last_unknown_at";
    private static final String KEY_LAST_GREETING_AT = "last_greeting_at";
    private static final String KEY_LAST_GREETING_PERSON = "last_greeting_person";
    private static final String KEY_LAST_EVENT_GREETED = "last_event_greeted";
    private static final String KEY_RECOGNIZED_EVENTS = "recognized_events";
    private static final String KEY_UNKNOWN_EVENTS = "unknown_events";
    private static final String KEY_GREETING_EVENTS = "greeting_events";
    private static final String PERSON_GREETING_PREFIX = "person_greeting_";

    interface Listener {
        void onGreeting(String person, String greeting);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final PersonPreferencesStore personPreferences;
    private final AureaIdentityAutomationPublisher publisher;
    private final AureaDiagnosticsLog log;
    private final Listener listener;

    AureaIdentityAutomation(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        );
        this.personPreferences = new PersonPreferencesStore(this.context);
        this.publisher = new AureaIdentityAutomationPublisher(this.context);
        this.log = new AureaDiagnosticsLog(this.context);
        this.listener = listener;
    }

    void onState(
            String requestedIdentity,
            float confidence,
            boolean present,
            boolean cameraActive,
            boolean recognitionEnabled) {
        long now = System.currentTimeMillis();
        String previous = clean(prefs.getString(
            KEY_IDENTITY,
            AureaIdentityAutomationPolicy.NONE_IDENTITY
        ));
        String requested = clean(requestedIdentity);
        long lastGreetingAt = lastGreetingAt(requested);
        boolean passiveGreetings = isPassiveGreetingEnabled(context);
        AureaIdentityAutomationPolicy.Decision decision =
            AureaIdentityAutomationPolicy.evaluate(
                previous,
                requested,
                present,
                recognitionEnabled,
                passiveGreetings,
                now,
                lastGreetingAt
            );

        SharedPreferences.Editor editor = prefs.edit()
            .putString(KEY_STATE, decision.state)
            .putString(KEY_IDENTITY, decision.identity)
            .putString(KEY_ACTIVE_PROFILE, decision.activeProfile)
            .putFloat(KEY_CONFIDENCE, confidence)
            .putBoolean(KEY_PRESENT, present);

        boolean greeted = prefs.getBoolean(KEY_LAST_EVENT_GREETED, false);
        if (decision.changed) {
            greeted = false;
            editor.putLong(KEY_LAST_EVENT_AT, now)
                .putBoolean(KEY_LAST_EVENT_GREETED, false);
        }
        if (AureaIdentityAutomationPolicy.RECOGNIZED.equals(decision.state)
                && decision.changed) {
            editor.putLong(KEY_LAST_RECOGNIZED_AT, now)
                .putInt(
                    KEY_RECOGNIZED_EVENTS,
                    prefs.getInt(KEY_RECOGNIZED_EVENTS, 0) + 1
                );
        }
        if (decision.newUnknownEpisode) {
            editor.putLong(KEY_LAST_UNKNOWN_AT, now)
                .putInt(
                    KEY_UNKNOWN_EVENTS,
                    prefs.getInt(KEY_UNKNOWN_EVENTS, 0) + 1
                );
            log.info(
                "AUREA Identity Automation",
                "Presenza sconosciuta confermata e comunicata a Home Assistant"
            );
        }

        String greeting = "";
        if (decision.shouldGreet && !decision.activeProfile.isEmpty()) {
            greeting = personPreferences.buildGreeting(decision.activeProfile);
            if (!greeting.isEmpty()) {
                greeted = true;
                recordGreeting(editor, decision.activeProfile, now);
                editor.putBoolean(KEY_LAST_EVENT_GREETED, true);
            }
        }
        editor.apply();

        publisher.publish(snapshot(
            decision.state,
            decision.identity,
            decision.activeProfile,
            confidence,
            present,
            cameraActive,
            passiveGreetings,
            greeted,
            now
        ));

        if (!greeting.isEmpty() && listener != null) {
            listener.onGreeting(decision.activeProfile, greeting);
        }
    }

    void close() {
        publisher.close();
    }

    static boolean isPassiveGreetingEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PASSIVE_GREETINGS, true);
    }

    static void setPassiveGreetingEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PASSIVE_GREETINGS, enabled).apply();
    }

    static void recordExternalGreeting(Context context, String person) {
        String cleanPerson = clean(person);
        if (cleanPerson.isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        );
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = prefs.edit();
        recordGreeting(prefs, editor, cleanPerson, now);
        editor.apply();
    }

    static String activeProfile(Context context) {
        return clean(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_PROFILE, ""));
    }

    static String state(Context context) {
        return clean(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STATE, AureaIdentityAutomationPolicy.ABSENT));
    }

    static int unknownEvents(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_UNKNOWN_EVENTS, 0);
    }

    static boolean hasPublishError(Context context) {
        return !clean(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_HA_ERROR, "")).isEmpty();
    }

    static String summary(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        );
        String state = clean(prefs.getString(
            KEY_STATE,
            AureaIdentityAutomationPolicy.ABSENT
        ));
        String profile = clean(prefs.getString(KEY_ACTIVE_PROFILE, ""));
        int recognized = prefs.getInt(KEY_RECOGNIZED_EVENTS, 0);
        int unknown = prefs.getInt(KEY_UNKNOWN_EVENTS, 0);
        int greetings = prefs.getInt(KEY_GREETING_EVENTS, 0);
        long lastEvent = prefs.getLong(KEY_LAST_EVENT_AT, 0L);
        String publishError = clean(prefs.getString(KEY_LAST_HA_ERROR, ""));
        return "Stato: " + (state.isEmpty() ? "assente" : state)
            + " · profilo: " + (profile.isEmpty() ? "nessuno" : profile)
            + " · riconoscimenti: " + recognized
            + " · saluti: " + greetings
            + " · sconosciuti confermati: " + unknown
            + (lastEvent > 0L ? " · ultimo evento " + shortTime(lastEvent) : "")
            + (!publishError.isEmpty()
                ? " · ultimo invio Home Assistant non riuscito"
                : "")
            + ".";
    }

    static void resetRuntime(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_STATE)
            .remove(KEY_IDENTITY)
            .remove(KEY_ACTIVE_PROFILE)
            .remove(KEY_CONFIDENCE)
            .remove(KEY_PRESENT)
            .remove(KEY_LAST_EVENT_AT)
            .remove(KEY_LAST_RECOGNIZED_AT)
            .remove(KEY_LAST_UNKNOWN_AT)
            .remove(KEY_LAST_EVENT_GREETED)
            .apply();
    }

    private Snapshot snapshot(
            String state,
            String identity,
            String activeProfile,
            float confidence,
            boolean present,
            boolean cameraActive,
            boolean greetingsEnabled,
            boolean greeted,
            long now) {
        return new Snapshot(
            state,
            identity,
            activeProfile,
            confidence,
            present,
            cameraActive,
            greetingsEnabled,
            greeted,
            prefs.getInt(KEY_RECOGNIZED_EVENTS, 0),
            prefs.getInt(KEY_UNKNOWN_EVENTS, 0),
            prefs.getInt(KEY_GREETING_EVENTS, 0),
            prefs.getLong(KEY_LAST_RECOGNIZED_AT, 0L),
            prefs.getLong(KEY_LAST_UNKNOWN_AT, 0L),
            prefs.getLong(KEY_LAST_GREETING_AT, 0L),
            clean(prefs.getString(KEY_LAST_GREETING_PERSON, "")),
            now
        );
    }

    private long lastGreetingAt(String person) {
        String cleanPerson = clean(person);
        if (cleanPerson.isEmpty()) return 0L;
        return prefs.getLong(personGreetingKey(cleanPerson), 0L);
    }

    private void recordGreeting(
            SharedPreferences.Editor editor,
            String person,
            long now) {
        recordGreeting(prefs, editor, person, now);
    }

    private static void recordGreeting(
            SharedPreferences prefs,
            SharedPreferences.Editor editor,
            String person,
            long now) {
        editor.putLong(personGreetingKey(person), now)
            .putLong(KEY_LAST_GREETING_AT, now)
            .putString(KEY_LAST_GREETING_PERSON, clean(person))
            .putInt(
                KEY_GREETING_EVENTS,
                prefs.getInt(KEY_GREETING_EVENTS, 0) + 1
            );
    }

    private static String personGreetingKey(String person) {
        String normalized = clean(person).toLowerCase(Locale.ROOT);
        String encoded = Base64.encodeToString(
            normalized.getBytes(StandardCharsets.UTF_8),
            Base64.NO_WRAP | Base64.URL_SAFE
        );
        return PERSON_GREETING_PREFIX + encoded;
    }

    private static String shortTime(long value) {
        return DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            Locale.ITALIAN
        ).format(new Date(value));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    static final class Snapshot {
        final String state;
        final String identity;
        final String activeProfile;
        final float confidence;
        final boolean present;
        final boolean cameraActive;
        final boolean greetingsEnabled;
        final boolean greeted;
        final int recognizedEvents;
        final int unknownEvents;
        final int greetingEvents;
        final long lastRecognizedAt;
        final long lastUnknownAt;
        final long lastGreetingAt;
        final String lastGreetingPerson;
        final long now;

        Snapshot(
                String state,
                String identity,
                String activeProfile,
                float confidence,
                boolean present,
                boolean cameraActive,
                boolean greetingsEnabled,
                boolean greeted,
                int recognizedEvents,
                int unknownEvents,
                int greetingEvents,
                long lastRecognizedAt,
                long lastUnknownAt,
                long lastGreetingAt,
                String lastGreetingPerson,
                long now) {
            this.state = clean(state);
            this.identity = clean(identity);
            this.activeProfile = clean(activeProfile);
            this.confidence = confidence;
            this.present = present;
            this.cameraActive = cameraActive;
            this.greetingsEnabled = greetingsEnabled;
            this.greeted = greeted;
            this.recognizedEvents = recognizedEvents;
            this.unknownEvents = unknownEvents;
            this.greetingEvents = greetingEvents;
            this.lastRecognizedAt = lastRecognizedAt;
            this.lastUnknownAt = lastUnknownAt;
            this.lastGreetingAt = lastGreetingAt;
            this.lastGreetingPerson = clean(lastGreetingPerson);
            this.now = now;
        }
    }
}
