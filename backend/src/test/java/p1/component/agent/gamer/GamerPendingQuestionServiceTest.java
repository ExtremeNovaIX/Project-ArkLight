package p1.component.agent.gamer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.interrupt.GameInterruptService;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.loop.GameLoopObservationBackoffService;
import p1.component.agent.interaction.InteractionCoordinator;
import p1.component.agent.rp.proactive.RpProactiveSessionRegistry;
import p1.component.agent.tts.TtsSpeechService;
import p1.component.agent.tts.TtsSpeechSession;
import p1.config.prop.AssistantProperties;
import p1.utils.ChatMessageUtil;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GamerPendingQuestionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSpeakQuestionAndRouteUserAnswerBackToGamer() throws Exception {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        GameInterruptService interruptService = new GameInterruptService();
        InteractionCoordinator coordinator = new InteractionCoordinator(registry, new AssistantProperties());
        RecordingMemory memory = new RecordingMemory("rp-session");
        TtsSpeechService ttsSpeechService = mock(TtsSpeechService.class);
        TtsSpeechSession ttsSession = mock(TtsSpeechSession.class);
        when(ttsSpeechService.open(eq("rp-session"), eq("gamer-ask"))).thenReturn(ttsSession);
        GamerPendingQuestionService service = service(registry, interruptService, coordinator, memory, ttsSpeechService);

        GamerPendingQuestionService.PendingQuestion question = service.registerQuestion(
                "STS2MCP",
                "game-session",
                askJson()).orElseThrow();

        assertEquals("rp-session", question.rpSessionId());
        assertFalse(coordinator.canGameActForRpSession("rp-session").allowed());
        verify(ttsSession).accept("这回合稳还是赌？ 选项：稳一点 / 赌一波 / 你判断");
        verify(ttsSession).finish();
        assertTrue(memory.text().contains("游戏队友询问"));

        String acknowledgement = service.answerPendingQuestion("rp-session", "赌").orElseThrow();

        assertTrue(acknowledgement.contains("赌一波"));
        assertTrue(coordinator.canGameActForRpSession("rp-session").allowed());
        assertTrue(interruptService.peek("STS2MCP-game-session").orElseThrow().instruction().contains("用户回答"));
        assertTrue(interruptService.peek("STS2MCP-game-session").orElseThrow().instruction().contains("赌一波"));
        assertTrue(memory.text().contains("用户回答游戏队友询问"));
    }

    @Test
    void shouldReturnSystemInstructionWhenQuestionTimesOut() throws Exception {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        GameInterruptService interruptService = new GameInterruptService();
        InteractionCoordinator coordinator = new InteractionCoordinator(registry, new AssistantProperties());
        RecordingMemory memory = new RecordingMemory("rp-session");
        TtsSpeechService ttsSpeechService = mock(TtsSpeechService.class);
        when(ttsSpeechService.open(eq("rp-session"), eq("gamer-ask"))).thenReturn(mock(TtsSpeechSession.class));
        GamerPendingQuestionService service = service(registry, interruptService, coordinator, memory, ttsSpeechService);

        GamerPendingQuestionService.PendingQuestion question = service.registerQuestion(
                "STS2MCP",
                "game-session",
                askJson()).orElseThrow();

        Method timeout = GamerPendingQuestionService.class.getDeclaredMethod(
                "timeoutIfStillPending",
                GamerPendingQuestionService.PendingQuestion.class);
        timeout.setAccessible(true);
        timeout.invoke(service, question);

        assertFalse(service.hasPendingQuestion("rp-session"));
        assertTrue(coordinator.canGameActForRpSession("rp-session").allowed());
        assertTrue(interruptService.peek("STS2MCP-game-session").orElseThrow().source().contains("system"));
        assertTrue(interruptService.peek("STS2MCP-game-session").orElseThrow().instruction().contains("15 秒内没有收到任何回复"));
        assertTrue(memory.text().contains("游戏队友询问超时"));
    }

    private GamerPendingQuestionService service(ActiveGameRegistry registry,
                                                GameInterruptService interruptService,
                                                InteractionCoordinator coordinator,
                                                RecordingMemory memory,
                                                TtsSpeechService ttsSpeechService) {
        return new GamerPendingQuestionService(
                registry,
                interruptService,
                coordinator,
                new GameLoopObservationBackoffService(),
                ignored -> memory,
                ttsSpeechService,
                new RpProactiveSessionRegistry(),
                Runnable::run);
    }

    private JsonNode askJson() throws Exception {
        return objectMapper.readTree("""
                {
                  "type": "ask",
                  "question": "这回合稳还是赌？",
                  "choices": ["稳一点", "赌一波", "你判断"],
                  "reason": "赌成功能击杀，失败会亏防御资源。",
                  "default_choice": "稳一点"
                }
                """);
    }

    private static final class RecordingMemory implements ChatMemory {
        private final String id;
        private final List<ChatMessage> messages = new ArrayList<>();

        private RecordingMemory(String id) {
            this.id = id;
        }

        @Override
        public Object id() {
            return id;
        }

        @Override
        public void add(ChatMessage message) {
            messages.add(message);
        }

        @Override
        public List<ChatMessage> messages() {
            return messages;
        }

        @Override
        public void clear() {
            messages.clear();
        }

        private String text() {
            return messages.stream()
                    .map(ChatMessageUtil::extractText)
                    .reduce("", (left, right) -> left + "\n" + right);
        }
    }
}
