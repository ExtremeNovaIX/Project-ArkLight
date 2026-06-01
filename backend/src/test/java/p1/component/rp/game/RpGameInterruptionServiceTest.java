package p1.component.rp.game;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import p1.component.agent.rp.game.interrupt.RpGameInterruptionService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RpGameInterruptionServiceTest {

    @Test
    void shouldAppendRawRpOutputAndExposeOneShotRuntimeEvent() {
        ChatMemoryProvider memoryProvider = mock(ChatMemoryProvider.class);
        ChatMemory memory = mock(ChatMemory.class);
        when(memoryProvider.get("rp-session")).thenReturn(memory);

        RpGameInterruptionService service = new RpGameInterruptionService(memoryProvider);
        String rawOutput = """
                <turn>
                <step 1>
                {"mode":"action","say":"先挡一下。","do":"打出防御"}
                </step 1>
                </turn>
                """;

        service.recordActionFailure("rp-session", rawOutput, "MCP 工具不存在。", 1);

        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(memory, org.mockito.Mockito.times(2)).add(messageCaptor.capture());
        List<ChatMessage> messages = messageCaptor.getAllValues();

        AiMessage partial = assertInstanceOf(AiMessage.class, messages.get(0));
        assertEquals(rawOutput.trim(), partial.text());

        UserMessage event = assertInstanceOf(UserMessage.class, messages.get(1));
        assertEquals(RpGameInterruptionService.EVENT_MESSAGE_NAME, event.name());
        assertTrue(event.singleText().contains("已经闭合并执行成功的完整 action 控制块数量：1"));
        assertTrue(event.singleText().contains("它们已经被打出"));

        String runtimeEvent = service.consumeRuntimeEvent("rp-session");
        assertTrue(runtimeEvent.contains("<game_runtime_event>"));
        assertTrue(runtimeEvent.contains("MCP 工具不存在。"));
        assertTrue(service.consumeRuntimeEvent("rp-session").isBlank());
    }
}
