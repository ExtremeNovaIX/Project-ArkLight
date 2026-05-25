package p1.component.agent.context;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import p1.component.agent.rp.context.RpRequestTimeAppender;
import p1.model.ChatLogEntity;
import p1.service.ChatLogRepository;
import p1.utils.ChatMessageUtil;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RpRequestTimeAppenderTest {

    @Test
    void shouldRenderLessThan15MinutesForRecentPreviousUserMessage() {
        ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);
        RpRequestTimeAppender appender = new RpRequestTimeAppender(chatLogRepository);

        ChatLogEntity latestUserMessage = new ChatLogEntity();
        latestUserMessage.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        ChatLogEntity previousUserMessage = new ChatLogEntity();
        previousUserMessage.setCreatedAt(LocalDateTime.now().minusMinutes(5));

        when(chatLogRepository.findTop2BySessionIdAndRoleOrderByCreatedAtDesc("session-1", "USER"))
                .thenReturn(List.of(latestUserMessage, previousUserMessage));

        UserMessage currentUserMessage = UserMessage.from("hello");
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(currentUserMessage))
                .build();

        ChatRequest updated = appender.augment(request, "session-1");
        String dynamicMessage = ChatMessageUtil.extractText(updated.messages().get(0));

        assertTrue(dynamicMessage.contains("上次用户发消息时间：小于15分钟"));
        assertSame(currentUserMessage, updated.messages().get(updated.messages().size() - 1));
    }
}
