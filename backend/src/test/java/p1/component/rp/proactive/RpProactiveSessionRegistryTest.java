package p1.component.rp.proactive;

import org.junit.jupiter.api.Test;
import p1.component.agent.rp.proactive.RpProactiveSessionRegistry;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpProactiveSessionRegistryTest {

    @Test
    void shouldDetectUserSpeechAfterProactiveSnapshot() {
        RpProactiveSessionRegistry registry = new RpProactiveSessionRegistry();
        registry.openSubscription("rp-session", "test-character", false);
        Instant snapshotTime = registry.findOnline("rp-session").orElseThrow().lastUserSpeechAt();

        registry.observeUserSpeech("rp-session", "test-character", false);

        assertTrue(registry.userSpokeAfter("rp-session", snapshotTime));
    }

    @Test
    void shouldKeepSnapshotValidWhenOnlyRpSpeaks() {
        RpProactiveSessionRegistry registry = new RpProactiveSessionRegistry();
        registry.openSubscription("rp-session", "test-character", false);
        Instant snapshotTime = registry.findOnline("rp-session").orElseThrow().lastUserSpeechAt();

        registry.observeRpSpeech("rp-session");

        assertFalse(registry.userSpokeAfter("rp-session", snapshotTime));
    }

    @Test
    void shouldRememberContextWithoutMarkingSessionOnline() {
        RpProactiveSessionRegistry registry = new RpProactiveSessionRegistry();

        registry.rememberSessionContext("rp-session", "Nova", true);

        RpProactiveSessionRegistry.SessionSnapshot snapshot = registry.findKnown("rp-session").orElseThrow();
        assertEquals("Nova", snapshot.characterName());
        assertTrue(snapshot.shortMode());
        assertFalse(registry.findOnline("rp-session").isPresent());
    }
}
