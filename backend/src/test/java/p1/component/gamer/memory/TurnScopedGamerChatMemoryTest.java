package p1.component.gamer.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.*;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.memory.TurnScopedGamerChatMemory;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnScopedGamerChatMemoryTest {

    @Test
    void keepsCurrentTurnMessagesButDropsPreviousTurnMessages() {
        CapturingChatModel chatModel = new CapturingChatModel();
        ProbeAgent agent = AiServices.builder(ProbeAgent.class)
                .chatModel(chatModel)
                .chatMemoryProvider(TurnScopedGamerChatMemory::new)
                .build();

        agent.ask("session-a", "第一轮状态", "测试游戏");
        agent.ask("session-a", "第二轮状态", "测试游戏");

        String requestText = chatModel.lastRequest.get().messages().toString();
        assertTrue(requestText.contains("测试游戏"));
        assertTrue(requestText.contains("第二轮状态"));
        assertFalse(requestText.contains("第一轮状态"));
    }

    private interface ProbeAgent {
        @SystemMessage("你正在玩 {{gameName}}")
        String ask(@MemoryId String memoryId,
                   @UserMessage String userMessage,
                   @V("gameName") String gameName);
    }

    private static class CapturingChatModel implements ChatModel {
        private final AtomicReference<ChatRequest> lastRequest = new AtomicReference<>();

        @Override
        public ChatResponse doChat(ChatRequest request) {
            lastRequest.set(request);
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("ok"))
                    .build();
        }
    }
}
