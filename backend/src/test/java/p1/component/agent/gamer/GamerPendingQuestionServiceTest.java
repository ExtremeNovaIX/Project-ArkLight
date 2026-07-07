package p1.component.agent.gamer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.loop.GameLoopObservationBackoffService;
import p1.component.agent.interaction.InteractionCoordinator;
import p1.component.agent.rp.proactive.RpProactiveSessionRegistry;
import p1.component.agent.tts.TtsSpeechService;
import p1.component.agent.tts.TtsSpeechSession;
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
    void shouldSpeakQuestionAndReturnUserAnswerContextToRp() throws Exception {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        InteractionCoordinator coordinator = new InteractionCoordinator(registry);
        RecordingMemory memory = new RecordingMemory("rp-session");
        TtsSpeechService ttsSpeechService = mock(TtsSpeechService.class);
        TtsSpeechSession ttsSession = mock(TtsSpeechSession.class);
        when(ttsSpeechService.open(eq("rp-session"), eq("game-ask"))).thenReturn(ttsSession);
        GamerPendingQuestionService service = service(registry, coordinator, memory, ttsSpeechService);

        GamerPendingQuestionService.PendingQuestion question = service.registerQuestion(
                "STS2MCP",
                "game-session",
                askJson()).orElseThrow();

        assertEquals("rp-session", question.rpSessionId());
        assertFalse(coordinator.canGameActForRpSession("rp-session").allowed());
        verify(ttsSession).accept("这回合稳还是赌？ 选项：稳一点 / 赌一波 / 你判断");
        verify(ttsSession).finish();
        assertTrue(memory.text().contains("游戏队友询问"));

        String answerContext = service.consumeAnswerForRp("rp-session", "赌").orElseThrow();

        assertTrue(answerContext.contains("<game_ask_answer>"));
        assertTrue(answerContext.contains("赌一波"));
        assertTrue(coordinator.canGameActForRpSession("rp-session").allowed());
        assertTrue(memory.text().contains("用户回答游戏队友询问"));
        assertTrue(memory.userMessageNames().contains("game_ask_user_answer"));
    }

    @Test
    void shouldRecordTimeoutAndResumeRpControlWhenQuestionTimesOut() throws Exception {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        InteractionCoordinator coordinator = new InteractionCoordinator(registry);
        RecordingMemory memory = new RecordingMemory("rp-session");
        TtsSpeechService ttsSpeechService = mock(TtsSpeechService.class);
        when(ttsSpeechService.open(eq("rp-session"), eq("game-ask"))).thenReturn(mock(TtsSpeechSession.class));
        GamerPendingQuestionService service = service(registry, coordinator, memory, ttsSpeechService);

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
        assertTrue(memory.text().contains("游戏队友询问超时"));
        assertTrue(memory.userMessageNames().contains("system_game_ask_timeout"));
    }

    private GamerPendingQuestionService service(ActiveGameRegistry registry,
                                                InteractionCoordinator coordinator,
                                                RecordingMemory memory,
                                                TtsSpeechService ttsSpeechService) {
        return new GamerPendingQuestionService(
                registry,
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

        private List<String> userMessageNames() {
            return messages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .map(UserMessage::name)
                    .toList();
        }
    }
}
