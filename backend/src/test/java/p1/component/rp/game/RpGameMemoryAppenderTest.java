package p1.component.rp.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.bridge.GameBridgeActionStatus;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.rp.game.memory.RpGameMemoryAppender;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RpGameMemoryAppenderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldKeepBridgeInterruptDetailsOutOfRpMemory() {
        ChatMemoryProvider memoryProvider = mock(ChatMemoryProvider.class);
        ChatMemory memory = mock(ChatMemory.class);
        when(memoryProvider.get("rp-session")).thenReturn(memory);

        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        RpGameMemoryAppender appender = new RpGameMemoryAppender(memoryProvider, registry);

        appender.appendGameAction(
                "STS2MCP",
                "STS2MCP-game-session",
                GameBridgeActionStatus.INTERRUPTED,
                "选择光子切割增强过牌",
                List.of(new GameOperation("rewards_pick_card", objectMapper.createObjectNode(), "选择光子切割增强过牌")),
                "操作队列已中断，局面发生变化。",
                "STS2 出牌后手牌数量变化不符合预期，状态监视触发中断。");

        ArgumentCaptor<AiMessage> messageCaptor = ArgumentCaptor.forClass(AiMessage.class);
        verify(memory).add(messageCaptor.capture());
        String text = messageCaptor.getValue().text();

        assertTrue(text.contains("选择光子切割增强过牌"));
        assertFalse(text.contains("结果："));
        assertFalse(text.contains("补充："));
        assertFalse(text.contains("中断"));
        assertFalse(text.contains("手牌数量"));
    }
}
