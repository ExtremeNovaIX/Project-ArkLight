package p1.component.rp.game;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.interrupt.GameInterruptService;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.loop.ActiveGameSession;
import p1.component.agent.gamer.loop.GameLoopObservationBackoffService;
import p1.component.agent.interaction.GameCoordinationService;
import p1.component.agent.interaction.InteractionCoordinator;
import p1.component.agent.rp.game.tool.RpGameToolProvider;
import p1.component.agent.rp.proactive.RpLiveMessageHub;
import p1.component.agent.rp.proactive.RpProactiveSessionRegistry;
import p1.config.prop.AssistantProperties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RpGameToolProviderTest {

    @Test
    void shouldHideGameToolOutsideGameMode() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        RpGameToolProvider provider = provider(registry, new GameInterruptService(), new InteractionCoordinator(registry, new AssistantProperties()));

        ToolProviderResult result = provider.provideTools(new ToolProviderRequest("rp-session", UserMessage.from("hello")));

        assertNull(result.toolSpecificationByName(RpGameToolProvider.GAME_COORDINATION_TOOL));
    }

    @Test
    void shouldExposeGameToolAndRegisterInterruptInGameMode() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        GameInterruptService interruptService = new GameInterruptService();
        registry.register("STS2MCP", "rp-session");
        RpGameToolProvider provider = provider(registry, interruptService, new InteractionCoordinator(registry, new AssistantProperties()));

        ToolProviderResult result = provider.provideTools(new ToolProviderRequest("rp-session", UserMessage.from("改打防御")));
        assertNotNull(result.toolSpecificationByName(RpGameToolProvider.GAME_COORDINATION_TOOL));

        String toolResult = result.toolExecutorByName(RpGameToolProvider.GAME_COORDINATION_TOOL).execute(
                ToolExecutionRequest.builder()
                        .name(RpGameToolProvider.GAME_COORDINATION_TOOL)
                        .arguments("{\"action\":\"APPLY_INSTRUCTION\",\"instruction\":\"优先防御，别继续全力输出。\"}")
                        .build(),
                "rp-session");

        assertTrue(toolResult.contains("重新决策"));
        assertTrue(interruptService.peek("STS2MCP-rp-session").orElseThrow().instruction().contains("优先防御"));
    }

    @Test
    void shouldWaitAndResumeGameSessionThroughGameTool() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        GameInterruptService interruptService = new GameInterruptService();
        InteractionCoordinator coordinator = new InteractionCoordinator(registry, new AssistantProperties());
        ActiveGameSession session = registry.register("STS2MCP", "rp-session");
        RpGameToolProvider provider = provider(registry, interruptService, coordinator);

        ToolProviderResult result = provider.provideTools(new ToolProviderRequest("rp-session", UserMessage.from("先停")));
        result.toolExecutorByName(RpGameToolProvider.GAME_COORDINATION_TOOL).execute(
                ToolExecutionRequest.builder()
                        .name(RpGameToolProvider.GAME_COORDINATION_TOOL)
                        .arguments("{\"action\":\"WAIT\",\"duration_seconds\":10,\"instruction\":\"用户要先看一下局面。\"}")
                        .build(),
                "rp-session");

        assertEquals(ActiveGameSession.State.RUNNING, session.getState());
        assertFalse(coordinator.canGameActForRpSession("rp-session").allowed());

        result.toolExecutorByName(RpGameToolProvider.GAME_COORDINATION_TOOL).execute(
                ToolExecutionRequest.builder()
                        .name(RpGameToolProvider.GAME_COORDINATION_TOOL)
                        .arguments("{\"action\":\"READY\"}")
                        .build(),
                "rp-session");

        assertEquals(ActiveGameSession.State.RUNNING, session.getState());
        assertTrue(coordinator.canGameActForRpSession("rp-session").allowed());
    }

    @Test
    void shouldExposeGameToolForExplicitRpSessionBinding() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        GameInterruptService interruptService = new GameInterruptService();
        registry.register("STS2MCP", "game-session", "rp-session");
        RpGameToolProvider provider = provider(registry, interruptService, new InteractionCoordinator(registry, new AssistantProperties()));

        ToolProviderResult result = provider.provideTools(new ToolProviderRequest("rp-session", UserMessage.from("改计划")));
        assertNotNull(result.toolSpecificationByName(RpGameToolProvider.GAME_COORDINATION_TOOL));

        result.toolExecutorByName(RpGameToolProvider.GAME_COORDINATION_TOOL).execute(
                ToolExecutionRequest.builder()
                        .name(RpGameToolProvider.GAME_COORDINATION_TOOL)
                        .arguments("{\"action\":\"APPLY_INSTRUCTION\",\"instruction\":\"换成保守打法。\"}")
                        .build(),
                "rp-session");

        assertTrue(interruptService.peek("STS2MCP-game-session").orElseThrow().instruction().contains("保守"));
    }

    @Test
    void shouldReleaseWaitWhenApplyingExplicitInstruction() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        GameInterruptService interruptService = new GameInterruptService();
        InteractionCoordinator coordinator = new InteractionCoordinator(registry, new AssistantProperties());
        ActiveGameSession session = registry.register("STS2MCP", "rp-session");
        RpGameToolProvider provider = provider(registry, interruptService, coordinator);
        ToolProviderResult result = provider.provideTools(new ToolProviderRequest("rp-session", UserMessage.from("结束回合")));

        result.toolExecutorByName(RpGameToolProvider.GAME_COORDINATION_TOOL).execute(
                ToolExecutionRequest.builder()
                        .name(RpGameToolProvider.GAME_COORDINATION_TOOL)
                        .arguments("{\"action\":\"WAIT\",\"instruction\":\"先等用户确认。\"}")
                        .build(),
                "rp-session");
        assertFalse(coordinator.canGameActForRpSession("rp-session").allowed());

        String toolResult = result.toolExecutorByName(RpGameToolProvider.GAME_COORDINATION_TOOL).execute(
                ToolExecutionRequest.builder()
                        .name(RpGameToolProvider.GAME_COORDINATION_TOOL)
                        .arguments("{\"action\":\"APPLY_INSTRUCTION\",\"instruction\":\"本回合直接结束回合。\"}")
                        .build(),
                "rp-session");

        assertEquals(ActiveGameSession.State.RUNNING, session.getState());
        assertTrue(coordinator.canGameActForRpSession("rp-session").allowed());
        assertTrue(interruptService.peek("STS2MCP-rp-session").orElseThrow().instruction().contains("直接结束回合"));
        assertTrue(toolResult.contains("重新决策"));
    }

    private RpGameToolProvider provider(ActiveGameRegistry registry,
                                        GameInterruptService interruptService,
                                        InteractionCoordinator interactionCoordinator) {
        AssistantProperties properties = new AssistantProperties();
        GameCoordinationService coordinationService = new GameCoordinationService(
                registry,
                interruptService,
                interactionCoordinator,
                new GameLoopObservationBackoffService(),
                mock(RpLiveMessageHub.class),
                mock(RpProactiveSessionRegistry.class),
                mock(ChatMemoryProvider.class),
                properties,
                Runnable::run);
        return new RpGameToolProvider(registry, coordinationService);
    }
}
