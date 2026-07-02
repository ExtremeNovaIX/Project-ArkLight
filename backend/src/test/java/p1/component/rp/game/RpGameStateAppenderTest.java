package p1.component.rp.game;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.trace.GamerDecisionTraceService;
import p1.component.agent.interaction.GameCoordinationService;
import p1.component.agent.rp.game.context.RpCurrentGameContextService;
import p1.component.agent.rp.game.context.RpGameRuntimeInstructionContext;
import p1.component.agent.rp.game.context.RpGameStateAppender;
import p1.component.agent.rp.game.control.RpGameTurnService;
import p1.component.agent.rp.game.interrupt.RpGameInterruptionService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RpGameStateAppenderTest {

    @Test
    void shouldKeepNonGameRequestFreeOfGameContext() {
        RpGameStateAppender appender = new RpGameStateAppender(
                new ActiveGameRegistry(),
                null,
                null,
                new RpGameRuntimeInstructionContext(),
                mock(RpGameInterruptionService.class),
                mock(GamerDecisionTraceService.class));
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("普通闲聊")))
                .build();

        ChatRequest updated = appender.augment(request, "rp-session");

        assertSame(request, updated);
        assertEquals(1, updated.messages().size());
    }

    @Test
    void shouldInsertGameContextBeforeCurrentUserMessageWithoutActionSnapshot() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        RpCurrentGameContextService contextService = mock(RpCurrentGameContextService.class);
        GameCoordinationService coordinationService = mock(GameCoordinationService.class);
        when(contextService.build("STS2MCP", "game-session")).thenReturn("<current_game_state />");
        when(coordinationService.renderWaitContext("rp-session"))
                .thenReturn("<game_wait>waiting=true</game_wait>");
        RpGameInterruptionService interruptionService = mock(RpGameInterruptionService.class);
        when(interruptionService.consumeRuntimeEvent("rp-session"))
                .thenReturn("<game_runtime_event>上一轮已中断</game_runtime_event>");
        RpGameRuntimeInstructionContext runtimeInstructionContext = new RpGameRuntimeInstructionContext();
        GamerDecisionTraceService traceService = mock(GamerDecisionTraceService.class);
        RpGameStateAppender appender = new RpGameStateAppender(
                registry,
                contextService,
                coordinationService,
                runtimeInstructionContext,
                interruptionService,
                traceService);
        UserMessage currentUserMessage = UserMessage.from("你可以直接结束回合吗？");
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(AiMessage.from("前一轮回复"), currentUserMessage))
                .build();

        ChatRequest updated = appender.augment(request, "rp-session");

        assertEquals(3, updated.messages().size());
        UserMessage gameContext = assertInstanceOf(UserMessage.class, updated.messages().get(1));
        assertEquals("current game state", gameContext.name());
        assertTrue(gameContext.singleText().contains("<game_mode>"));
        assertFalse(gameContext.singleText().contains("<context>"));
        assertFalse(gameContext.singleText().contains("loop_state="));
        assertFalse(gameContext.singleText().contains("total_steps="));
        assertFalse(gameContext.singleText().contains("<streaming_control_protocol>"));
        assertFalse(gameContext.singleText().contains("\"mode\":\"action|ask|chat|wait\""));
        assertFalse(gameContext.singleText().contains("do 必须是具体操作"));
        assertTrue(gameContext.singleText().contains("<game_wait>waiting=true</game_wait>"));
        assertFalse(gameContext.singleText().contains("<game_loop_instruction>"));
        assertTrue(gameContext.singleText().contains("<game_runtime_event>"));
        assertFalse(gameContext.singleText().contains("<recent_game_actions>"));
        verify(traceService).recordRpVisibleContext("STS2MCP", "game-session", gameContext.singleText());
        assertSame(currentUserMessage, updated.messages().get(2));
    }

    @Test
    void shouldReplaceGameLoopTriggerWithGameContextRequest() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        RpCurrentGameContextService contextService = mock(RpCurrentGameContextService.class);
        GameCoordinationService coordinationService = mock(GameCoordinationService.class);
        when(contextService.build("STS2MCP", "game-session")).thenReturn("<current_game_state />");
        when(coordinationService.renderWaitContext("rp-session")).thenReturn("");
        RpGameInterruptionService interruptionService = mock(RpGameInterruptionService.class);
        RpGameStateAppender appender = new RpGameStateAppender(
                registry,
                contextService,
                coordinationService,
                new RpGameRuntimeInstructionContext(),
                interruptionService,
                mock(GamerDecisionTraceService.class));
        UserMessage loopTrigger = UserMessage.from(RpGameTurnService.GAME_LOOP_MESSAGE_NAME, ".");
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(AiMessage.from("前一轮回复"), loopTrigger))
                .build();

        ChatRequest updated = appender.augment(request, "rp-session");

        assertEquals(2, updated.messages().size());
        UserMessage gameContext = assertInstanceOf(UserMessage.class, updated.messages().get(1));
        assertEquals("current game state", gameContext.name());
        assertTrue(gameContext.singleText().contains("<current_game_state />"));
    }
}
