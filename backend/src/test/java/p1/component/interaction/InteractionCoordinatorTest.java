package p1.component.interaction;

import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.GameSessionKey;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.interaction.InteractionCoordinator;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionCoordinatorTest {

    @Test
    void shouldAllowGameWhenNoExplicitGameHoldExists() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        InteractionCoordinator coordinator = new InteractionCoordinator(registry);

        assertTrue(coordinator.canGameActForRpSession("rp-session").allowed());
        assertTrue(coordinator.canGameActForGamer(
                "STS2MCP", GameSessionKey.of("STS2MCP", "game-session")).allowed());
        assertTrue(coordinator.canStartBackgroundSpeech("rp-session").allowed());
    }

    @Test
    void shouldPauseNextGameLoopDuringVoiceInputButAllowCurrentRequestToContinue() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        InteractionCoordinator coordinator = new InteractionCoordinator(registry);

        coordinator.beginGameVoiceInput("rp-session", Duration.ofSeconds(12));

        assertFalse(coordinator.canGameActForRpSession("rp-session").allowed());
        assertTrue(coordinator.canContinueCurrentGameRequestForGamer(
                "STS2MCP", GameSessionKey.of("STS2MCP", "game-session")).allowed());

        coordinator.endGameVoiceInput("rp-session");

        assertTrue(coordinator.canGameActForRpSession("rp-session").allowed());
    }

    @Test
    void shouldBlockGameUntilGameWaitIsReleased() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        InteractionCoordinator coordinator = new InteractionCoordinator(registry);

        coordinator.beginGameWait("rp-session", Duration.ofMinutes(5));

        assertFalse(coordinator.canGameActForGamer(
                "STS2MCP", GameSessionKey.of("STS2MCP", "game-session")).allowed());
        assertFalse(coordinator.canContinueCurrentGameRequestForGamer(
                "STS2MCP", GameSessionKey.of("STS2MCP", "game-session")).allowed());

        coordinator.endGameWait("rp-session");

        assertTrue(coordinator.canGameActForGamer(
                "STS2MCP", GameSessionKey.of("STS2MCP", "game-session")).allowed());
    }
}
