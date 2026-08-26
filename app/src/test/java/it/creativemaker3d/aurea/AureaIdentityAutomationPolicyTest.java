package it.creativemaker3d.aurea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AureaIdentityAutomationPolicyTest {
    private static final long NOW = 1_000_000_000L;

    @Test
    public void firstRecognizedArrivalGreetsAndActivatesProfile() {
        AureaIdentityAutomationPolicy.Decision result = evaluate(
            "nessuno",
            "Giuseppe",
            true,
            true,
            true,
            0L
        );

        assertEquals(AureaIdentityAutomationPolicy.RECOGNIZED, result.state);
        assertEquals("Giuseppe", result.activeProfile);
        assertTrue(result.changed);
        assertTrue(result.shouldGreet);
        assertFalse(result.newUnknownEpisode);
    }

    @Test
    public void samePersonDoesNotGreetAgainWhileStillPresent() {
        AureaIdentityAutomationPolicy.Decision result = evaluate(
            "giuseppe",
            "Giuseppe",
            true,
            true,
            true,
            NOW - AureaIdentityAutomationPolicy.GREETING_COOLDOWN_MS - 1L
        );

        assertEquals(AureaIdentityAutomationPolicy.RECOGNIZED, result.state);
        assertFalse(result.changed);
        assertFalse(result.shouldGreet);
    }

    @Test
    public void returnInsideCooldownDoesNotGreet() {
        AureaIdentityAutomationPolicy.Decision result = evaluate(
            "nessuno",
            "Giuseppe",
            true,
            true,
            true,
            NOW - 20L * 60L * 1000L
        );

        assertTrue(result.changed);
        assertFalse(result.shouldGreet);
        assertTrue(result.greetingCooldownRemainingMs > 0L);
    }

    @Test
    public void returnAfterCooldownGreets() {
        AureaIdentityAutomationPolicy.Decision result = evaluate(
            "nessuno",
            "Giuseppe",
            true,
            true,
            true,
            NOW - AureaIdentityAutomationPolicy.GREETING_COOLDOWN_MS - 1L
        );

        assertTrue(result.shouldGreet);
        assertEquals(0L, result.greetingCooldownRemainingMs);
    }

    @Test
    public void unknownStartsOnePrudentEpisodeAndNeverGreets() {
        AureaIdentityAutomationPolicy.Decision first = evaluate(
            "nessuno",
            "sconosciuto",
            true,
            true,
            true,
            0L
        );
        AureaIdentityAutomationPolicy.Decision repeated = evaluate(
            "sconosciuto",
            "sconosciuto",
            true,
            true,
            true,
            0L
        );

        assertEquals(AureaIdentityAutomationPolicy.UNKNOWN, first.state);
        assertTrue(first.newUnknownEpisode);
        assertFalse(first.shouldGreet);
        assertFalse(repeated.newUnknownEpisode);
    }

    @Test
    public void faceWithoutDecisionIsPendingAndAbsenceClearsProfile() {
        AureaIdentityAutomationPolicy.Decision pending = evaluate(
            "nessuno",
            "nessuno",
            true,
            true,
            true,
            0L
        );
        AureaIdentityAutomationPolicy.Decision absent = evaluate(
            "giuseppe",
            "Giuseppe",
            false,
            true,
            true,
            0L
        );

        assertEquals(AureaIdentityAutomationPolicy.PENDING, pending.state);
        assertEquals(AureaIdentityAutomationPolicy.ABSENT, absent.state);
        assertTrue(absent.activeProfile.isEmpty());
        assertFalse(absent.shouldGreet);
    }

    @Test
    public void disabledRecognitionNeverGreets() {
        AureaIdentityAutomationPolicy.Decision result = evaluate(
            "nessuno",
            "Giuseppe",
            true,
            false,
            true,
            0L
        );

        assertEquals(AureaIdentityAutomationPolicy.DISABLED, result.state);
        assertFalse(result.shouldGreet);
        assertTrue(result.activeProfile.isEmpty());
    }

    @Test
    public void disabledPassiveGreetingsStillActivatesProfileWithoutSpeaking() {
        AureaIdentityAutomationPolicy.Decision result = evaluate(
            "nessuno",
            "Giuseppe",
            true,
            true,
            false,
            0L
        );

        assertEquals(AureaIdentityAutomationPolicy.RECOGNIZED, result.state);
        assertEquals("Giuseppe", result.activeProfile);
        assertTrue(result.changed);
        assertFalse(result.shouldGreet);
    }

    private AureaIdentityAutomationPolicy.Decision evaluate(
            String previous,
            String next,
            boolean present,
            boolean recognitionEnabled,
            boolean greetingsEnabled,
            long lastGreetingAt) {
        return AureaIdentityAutomationPolicy.evaluate(
            previous,
            next,
            present,
            recognitionEnabled,
            greetingsEnabled,
            NOW,
            lastGreetingAt
        );
    }
}
