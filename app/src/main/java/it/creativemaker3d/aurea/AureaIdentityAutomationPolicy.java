package it.creativemaker3d.aurea;

import java.util.Locale;

/** Regole pure e testabili per gli eventi dell'automazione identità. */
final class AureaIdentityAutomationPolicy {
    static final String ABSENT = "assente";
    static final String PENDING = "in_verifica";
    static final String RECOGNIZED = "riconosciuto";
    static final String UNKNOWN = "sconosciuto";
    static final String DISABLED = "disattivato";
    static final String NONE_IDENTITY = "nessuno";
    static final long GREETING_COOLDOWN_MS = 2L * 60L * 60L * 1000L;

    private AureaIdentityAutomationPolicy() {
    }

    static Decision evaluate(
            String previousIdentity,
            String requestedIdentity,
            boolean present,
            boolean recognitionEnabled,
            boolean greetingsEnabled,
            long now,
            long lastGreetingAt) {
        String previous = normalize(previousIdentity);
        String identity;
        String state;
        String activeProfile = "";

        if (!recognitionEnabled) {
            identity = DISABLED;
            state = DISABLED;
        } else if (!present) {
            identity = NONE_IDENTITY;
            state = ABSENT;
        } else {
            identity = normalize(requestedIdentity);
            if (identity.isEmpty() || NONE_IDENTITY.equals(identity)) {
                identity = NONE_IDENTITY;
                state = PENDING;
            } else if (UNKNOWN.equals(identity)) {
                state = UNKNOWN;
            } else if (DISABLED.equals(identity)) {
                state = DISABLED;
            } else {
                state = RECOGNIZED;
                activeProfile = requestedIdentity == null
                    ? identity : requestedIdentity.trim();
            }
        }

        boolean changed = !identity.equals(previous);
        boolean newUnknownEpisode = UNKNOWN.equals(identity)
            && !UNKNOWN.equals(previous);
        boolean cooldownPassed = lastGreetingAt <= 0L
            || now - lastGreetingAt >= GREETING_COOLDOWN_MS;
        boolean shouldGreet = RECOGNIZED.equals(state)
            && changed
            && greetingsEnabled
            && cooldownPassed;
        long remaining = lastGreetingAt <= 0L
            ? 0L
            : Math.max(0L, GREETING_COOLDOWN_MS - (now - lastGreetingAt));

        return new Decision(
            identity,
            state,
            activeProfile,
            changed,
            shouldGreet,
            newUnknownEpisode,
            remaining
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    static final class Decision {
        final String identity;
        final String state;
        final String activeProfile;
        final boolean changed;
        final boolean shouldGreet;
        final boolean newUnknownEpisode;
        final long greetingCooldownRemainingMs;

        Decision(
                String identity,
                String state,
                String activeProfile,
                boolean changed,
                boolean shouldGreet,
                boolean newUnknownEpisode,
                long greetingCooldownRemainingMs) {
            this.identity = identity;
            this.state = state;
            this.activeProfile = activeProfile;
            this.changed = changed;
            this.shouldGreet = shouldGreet;
            this.newUnknownEpisode = newUnknownEpisode;
            this.greetingCooldownRemainingMs = greetingCooldownRemainingMs;
        }
    }
}
