package p1.component.agent.gamer.loop;

import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.adapter.core.GameActionability;
import p1.component.agent.gamer.bridge.GameBridgeService;
import p1.component.agent.gamer.bridge.GameStateProbe;
import p1.component.agent.interaction.InteractionCoordinator;
import p1.component.agent.rp.game.control.RpGameActionExecutionException;
import p1.component.agent.rp.game.control.RpGameTurnService;
import p1.component.agent.rp.proactive.RpProactiveSessionRegistry;
import p1.config.mcp.GameLoopProperties;
import p1.config.prop.AssistantProperties;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RpGameDriverTest {

    @Test
    void shouldNotHoldExecutionLockWhileRpStreamCallbackExecutesGameAction() throws Exception {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        GameSessionExecutionLockService executionLockService = new GameSessionExecutionLockService();
        ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();
        AtomicBoolean callbackAcquiredBridgeLock = new AtomicBoolean(false);

        GameBridgeService bridgeService = mock(GameBridgeService.class);
        when(bridgeService.probeState("STS2MCP", "game-session"))
                .thenReturn(new GameStateProbe(GameActionability.actionable("player turn"), "state-a"));

        RpGameTurnService turnService = mock(RpGameTurnService.class);
        when(turnService.play(any(ActiveGameSession.class), anyString(), anyBoolean()))
                .thenAnswer(ignored -> {
                    CompletableFuture<Void> callback = CompletableFuture.runAsync(
                            () -> executionLockService.withLock("STS2MCP", "game-session", () -> {
                                callbackAcquiredBridgeLock.set(true);
                                return null;
                            }),
                            callbackExecutor);
                    callback.get(500, TimeUnit.MILLISECONDS);
                    return "";
                });

        RpGameDriver driver = new RpGameDriver(
                turnService,
                bridgeService,
                registry,
                new GameLoopProperties(),
                new InteractionCoordinator(registry, new AssistantProperties()),
                new GameLoopObservationBackoffService(),
                new RpProactiveSessionRegistry());

        try {
            driver.pollTick();
            assertTrue(callbackAcquiredBridgeLock.get(), "RP stream callback should be able to enter bridge execution lock");
        } finally {
            callbackExecutor.shutdownNow();
        }
    }

    @Test
    void shouldWaitTwoTicksBeforeReplanningOnUnchangedStateAfterActionFailure() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");

        GameBridgeService bridgeService = mock(GameBridgeService.class);
        when(bridgeService.probeState("STS2MCP", "game-session"))
                .thenReturn(new GameStateProbe(GameActionability.actionable("player turn"), "state-a"));

        RpGameTurnService turnService = mock(RpGameTurnService.class);
        when(turnService.play(any(ActiveGameSession.class), anyString(), anyBoolean()))
                .thenThrow(new RpGameActionExecutionException("测试动作失败"));

        RpGameDriver driver = new RpGameDriver(
                turnService,
                bridgeService,
                registry,
                new GameLoopProperties(),
                new InteractionCoordinator(registry, new AssistantProperties()),
                new GameLoopObservationBackoffService(),
                new RpProactiveSessionRegistry());

        driver.pollTick();
        driver.pollTick();
        driver.pollTick();
        driver.pollTick();

        verify(turnService, times(2)).play(any(ActiveGameSession.class), anyString(), anyBoolean());
    }

    @Test
    void shouldSkipRpWakeupWhenRpSpokeAndNoUserOrStateChanged() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");

        GameBridgeService bridgeService = mock(GameBridgeService.class);
        when(bridgeService.probeState("STS2MCP", "game-session"))
                .thenReturn(new GameStateProbe(GameActionability.actionable("menu"), "state-a"));

        RpGameTurnService turnService = mock(RpGameTurnService.class);
        when(turnService.play(any(ActiveGameSession.class), anyString(), anyBoolean()))
                .thenReturn("要选哪个角色？");

        RpGameDriver driver = new RpGameDriver(
                turnService,
                bridgeService,
                registry,
                new GameLoopProperties(),
                new InteractionCoordinator(registry, new AssistantProperties()),
                new GameLoopObservationBackoffService(),
                new RpProactiveSessionRegistry());

        driver.pollTick();
        driver.pollTick();

        verify(turnService, times(1)).play(any(ActiveGameSession.class), anyString(), anyBoolean());
    }

    @Test
    void shouldWakeRpAgainWhenUserSpeaksAfterRpSpeech() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        RpProactiveSessionRegistry proactiveSessionRegistry = new RpProactiveSessionRegistry();
        proactiveSessionRegistry.openSubscription("rp-session", "Nova", false);

        GameBridgeService bridgeService = mock(GameBridgeService.class);
        when(bridgeService.probeState("STS2MCP", "game-session"))
                .thenReturn(new GameStateProbe(GameActionability.actionable("menu"), "state-a"));

        RpGameTurnService turnService = mock(RpGameTurnService.class);
        when(turnService.play(any(ActiveGameSession.class), anyString(), anyBoolean()))
                .thenReturn("要选哪个角色？");

        RpGameDriver driver = new RpGameDriver(
                turnService,
                bridgeService,
                registry,
                new GameLoopProperties(),
                new InteractionCoordinator(registry, new AssistantProperties()),
                new GameLoopObservationBackoffService(),
                proactiveSessionRegistry);

        driver.pollTick();
        proactiveSessionRegistry.observeUserSpeech("rp-session", "Nova", false);
        driver.pollTick();

        verify(turnService, times(2)).play(any(ActiveGameSession.class), anyString(), anyBoolean());
    }
}
