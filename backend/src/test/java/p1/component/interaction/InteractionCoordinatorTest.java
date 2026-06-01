package p1.component.interaction;

import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.GameSessionKey;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.interaction.InteractionCoordinator;
import p1.config.prop.AssistantProperties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionCoordinatorTest {

    @Test
    void shouldPauseBoundGamerWhileUserTurnIsActive() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        InteractionCoordinator coordinator = new InteractionCoordinator(registry, new AssistantProperties());

        try (InteractionCoordinator.InteractionLease ignored = coordinator.beginUserTurn("rp-session")) {
            assertFalse(coordinator.canGameActForGamer(
                    "STS2MCP", GameSessionKey.of("STS2MCP", "game-session")).allowed());
            assertFalse(coordinator.canStartBackgroundSpeech("rp-session").allowed());
        }

        assertTrue(coordinator.canGameActForGamer(
                "STS2MCP", GameSessionKey.of("STS2MCP", "game-session")).allowed());
    }

    @Test
    void shouldReserveFutureUserActivityAndRpSpeechHolds() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        InteractionCoordinator coordinator = new InteractionCoordinator(registry, new AssistantProperties());

        try (InteractionCoordinator.InteractionLease ignored = coordinator.beginUserActivity("rp-session")) {
            assertFalse(coordinator.canGameActForRpSession("rp-session").allowed());
        }
        try (InteractionCoordinator.InteractionLease ignored = coordinator.beginRpSpeech("rp-session")) {
            assertFalse(coordinator.canGameActForRpSession("rp-session").allowed());
        }

        assertTrue(coordinator.canGameActForRpSession("rp-session").allowed());
    }

    @Test
    void shouldIgnoreOnlyRpSpeechForRpControlledActions() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        InteractionCoordinator coordinator = new InteractionCoordinator(registry, new AssistantProperties());

        try (InteractionCoordinator.InteractionLease ignored = coordinator.beginRpSpeech("rp-session")) {
            assertTrue(coordinator.canGameActForGamerIgnoringRpSpeech(
                    "STS2MCP", GameSessionKey.of("STS2MCP", "game-session")).allowed());
            assertFalse(coordinator.canGameActForGamer(
                    "STS2MCP", GameSessionKey.of("STS2MCP", "game-session")).allowed());

            try (InteractionCoordinator.InteractionLease userTurn = coordinator.beginUserTurn("rp-session")) {
                assertFalse(coordinator.canGameActForGamerIgnoringRpSpeech(
                        "STS2MCP", GameSessionKey.of("STS2MCP", "game-session")).allowed());
            }
        }
    }

    @Test
    void shouldBlockGameAfterTypingActivityHeartbeat() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        InteractionCoordinator coordinator = new InteractionCoordinator(registry, new AssistantProperties());

        coordinator.observeUserActivity("rp-session");
        coordinator.observeUserActivity("rp-session");

        assertFalse(coordinator.canGameActForGamer(
                "STS2MCP", GameSessionKey.of("STS2MCP", "game-session")).allowed());
    }

    @Test
    void shouldBlockGameUntilGameWaitIsReleased() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        InteractionCoordinator coordinator = new InteractionCoordinator(registry, new AssistantProperties());

        coordinator.beginGameWait("rp-session", java.time.Duration.ofMinutes(5));

        assertFalse(coordinator.canGameActForGamer(
                "STS2MCP", GameSessionKey.of("STS2MCP", "game-session")).allowed());

        coordinator.endGameWait("rp-session");

        assertTrue(coordinator.canGameActForGamer(
                "STS2MCP", GameSessionKey.of("STS2MCP", "game-session")).allowed());
    }
}
