package p1.component.rp.game;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.interrupt.GameInterruptService;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.loop.ActiveGameSession;
import p1.component.agent.rp.game.tool.RpGameToolProvider;

import static org.junit.jupiter.api.Assertions.*;

class RpGameToolProviderTest {

    @Test
    void shouldHideGameToolOutsideGameMode() {
        RpGameToolProvider provider = new RpGameToolProvider(new ActiveGameRegistry(), new GameInterruptService());

        ToolProviderResult result = provider.provideTools(new ToolProviderRequest("rp-session", UserMessage.from("hello")));

        assertNull(result.toolSpecificationByName(RpGameToolProvider.GAME_CONTROL_TOOL));
    }

    @Test
    void shouldExposeGameToolAndRegisterInterruptInGameMode() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        GameInterruptService interruptService = new GameInterruptService();
        registry.register("STS2MCP", "rp-session");
        RpGameToolProvider provider = new RpGameToolProvider(registry, interruptService);

        ToolProviderResult result = provider.provideTools(new ToolProviderRequest("rp-session", UserMessage.from("改打防御")));
        assertNotNull(result.toolSpecificationByName(RpGameToolProvider.GAME_CONTROL_TOOL));

        String toolResult = result.toolExecutorByName(RpGameToolProvider.GAME_CONTROL_TOOL).execute(
                ToolExecutionRequest.builder()
                        .name(RpGameToolProvider.GAME_CONTROL_TOOL)
                        .arguments("{\"action\":\"APPLY_INSTRUCTION\",\"instruction\":\"优先防御，别继续全力输出。\"}")
                        .build(),
                "rp-session");

        assertTrue(toolResult.contains("重新决策"));
        assertTrue(interruptService.peek("STS2MCP-rp-session").orElseThrow().instruction().contains("优先防御"));
    }

    @Test
    void shouldPauseAndResumeGameSessionThroughGameTool() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        GameInterruptService interruptService = new GameInterruptService();
        ActiveGameSession session = registry.register("STS2MCP", "rp-session");
        RpGameToolProvider provider = new RpGameToolProvider(registry, interruptService);

        ToolProviderResult result = provider.provideTools(new ToolProviderRequest("rp-session", UserMessage.from("先停")));
        result.toolExecutorByName(RpGameToolProvider.GAME_CONTROL_TOOL).execute(
                ToolExecutionRequest.builder()
                        .name(RpGameToolProvider.GAME_CONTROL_TOOL)
                        .arguments("{\"action\":\"PAUSE\",\"instruction\":\"暂停游戏，等待用户下一步。\"}")
                        .build(),
                "rp-session");

        assertEquals(ActiveGameSession.State.PAUSED, session.getState());

        result.toolExecutorByName(RpGameToolProvider.GAME_CONTROL_TOOL).execute(
                ToolExecutionRequest.builder()
                        .name(RpGameToolProvider.GAME_CONTROL_TOOL)
                        .arguments("{\"action\":\"RESUME\",\"instruction\":\"继续推进。\"}")
                        .build(),
                "rp-session");

        assertEquals(ActiveGameSession.State.RUNNING, session.getState());
        assertTrue(interruptService.peek("STS2MCP-rp-session").orElseThrow().instruction().contains("继续推进"));
    }

    @Test
    void shouldExposeGameToolForExplicitRpSessionBinding() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        GameInterruptService interruptService = new GameInterruptService();
        registry.register("STS2MCP", "game-session", "rp-session");
        RpGameToolProvider provider = new RpGameToolProvider(registry, interruptService);

        ToolProviderResult result = provider.provideTools(new ToolProviderRequest("rp-session", UserMessage.from("改计划")));
        assertNotNull(result.toolSpecificationByName(RpGameToolProvider.GAME_CONTROL_TOOL));

        result.toolExecutorByName(RpGameToolProvider.GAME_CONTROL_TOOL).execute(
                ToolExecutionRequest.builder()
                        .name(RpGameToolProvider.GAME_CONTROL_TOOL)
                        .arguments("{\"action\":\"APPLY_INSTRUCTION\",\"instruction\":\"换成保守打法。\"}")
                        .build(),
                "rp-session");

        assertTrue(interruptService.peek("STS2MCP-game-session").orElseThrow().instruction().contains("保守"));
    }

    @Test
    void shouldResumePausedSessionWhenApplyingExplicitInstruction() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        GameInterruptService interruptService = new GameInterruptService();
        ActiveGameSession session = registry.register("STS2MCP", "rp-session");
        session.setState(ActiveGameSession.State.PAUSED);
        RpGameToolProvider provider = new RpGameToolProvider(registry, interruptService);
        ToolProviderResult result = provider.provideTools(new ToolProviderRequest("rp-session", UserMessage.from("结束回合")));

        String toolResult = result.toolExecutorByName(RpGameToolProvider.GAME_CONTROL_TOOL).execute(
                ToolExecutionRequest.builder()
                        .name(RpGameToolProvider.GAME_CONTROL_TOOL)
                        .arguments("{\"action\":\"APPLY_INSTRUCTION\",\"instruction\":\"本回合直接结束回合。\"}")
                        .build(),
                "rp-session");

        assertEquals(ActiveGameSession.State.RUNNING, session.getState());
        assertTrue(toolResult.contains("已恢复"));
        assertTrue(interruptService.peek("STS2MCP-rp-session").orElseThrow().instruction().contains("直接结束回合"));
    }
}
