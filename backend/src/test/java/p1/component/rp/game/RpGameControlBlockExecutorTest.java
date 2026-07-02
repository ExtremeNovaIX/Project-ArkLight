package p1.component.rp.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.GamerPendingQuestionService;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.trace.GamerDecisionTraceService;
import p1.component.agent.rp.game.control.RpActionParserService;
import p1.component.agent.rp.game.control.RpControlBlock;
import p1.component.agent.rp.game.control.RpGameControlBlockExecutor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RpGameControlBlockExecutorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldExecuteOnlyCommittedActDo() throws Exception {
        ActiveGameRegistry activeGameRegistry = new ActiveGameRegistry();
        activeGameRegistry.register("STS2", "game-session", "rp-session");
        RpActionParserService actionParserService = mock(RpActionParserService.class);
        when(actionParserService.execute("STS2", "game-session", "打出防御")).thenReturn("ok");
        RpGameControlBlockExecutor executor = new RpGameControlBlockExecutor(
                activeGameRegistry,
                actionParserService,
                mock(GamerPendingQuestionService.class),
                mock(GamerDecisionTraceService.class));

        Optional<String> result = executor.execute("rp-session", block("""
                {"type":"act","do":"打出防御","check":"合法","progress":"防御","next":"","commit":true}
                """));

        assertEquals(Optional.of("ok"), result);
        verify(actionParserService).execute("STS2", "game-session", "打出防御");
    }

    @Test
    void shouldIgnoreUncommittedActDo() throws Exception {
        ActiveGameRegistry activeGameRegistry = new ActiveGameRegistry();
        activeGameRegistry.register("STS2", "game-session", "rp-session");
        RpActionParserService actionParserService = mock(RpActionParserService.class);
        RpGameControlBlockExecutor executor = new RpGameControlBlockExecutor(
                activeGameRegistry,
                actionParserService,
                mock(GamerPendingQuestionService.class),
                mock(GamerDecisionTraceService.class));

        Optional<String> result = executor.execute("rp-session", block("""
                {"type":"act","do":"打出君王之剑攻击敌人","check":"费用不足","progress":"","next":"先加费","commit":false}
                """));

        assertTrue(result.isEmpty());
        verify(actionParserService, never()).execute(any(), any(), any());
    }

    @Test
    void shouldTraceCommittedActException() throws Exception {
        ActiveGameRegistry activeGameRegistry = new ActiveGameRegistry();
        activeGameRegistry.register("STS2", "game-session", "rp-session");
        RpActionParserService actionParserService = mock(RpActionParserService.class);
        when(actionParserService.execute("STS2", "game-session", "选择左边事件"))
                .thenThrow(new RuntimeException("底层执行失败"));
        GamerDecisionTraceService traceService = mock(GamerDecisionTraceService.class);
        RpGameControlBlockExecutor executor = new RpGameControlBlockExecutor(
                activeGameRegistry,
                actionParserService,
                mock(GamerPendingQuestionService.class),
                traceService);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> executor.execute("rp-session", block("""
                {"type":"act","do":"选择左边事件","check":"合法","progress":"","next":"","commit":true}
                """)));

        assertEquals("底层执行失败", thrown.getMessage());
        verify(traceService).appendRpCommandFailureTrace("STS2", "game-session", "选择左边事件", "底层执行失败");
    }

    @Test
    void shouldRegisterVoiceAskWithoutRepeatingSpeech() throws Exception {
        ActiveGameRegistry activeGameRegistry = new ActiveGameRegistry();
        activeGameRegistry.register("STS2", "game-session", "rp-session");
        GamerPendingQuestionService pendingQuestionService = mock(GamerPendingQuestionService.class);
        when(pendingQuestionService.registerQuestion(eq("STS2"), eq("game-session"), any(JsonNode.class), eq(false)))
                .thenReturn(Optional.empty());
        RpGameControlBlockExecutor executor = new RpGameControlBlockExecutor(
                activeGameRegistry,
                mock(RpActionParserService.class),
                pendingQuestionService,
                mock(GamerDecisionTraceService.class));

        executor.execute("rp-session", block("""
                {"type":"voice","kind":"ask","say":"这里要不要先拿药水？"}
                """));

        verify(pendingQuestionService).registerQuestion(eq("STS2"), eq("game-session"), any(JsonNode.class), eq(false));
    }

    private RpControlBlock block(String json) throws Exception {
        return RpControlBlock.from(OBJECT_MAPPER.readTree(json)).orElseThrow();
    }
}
