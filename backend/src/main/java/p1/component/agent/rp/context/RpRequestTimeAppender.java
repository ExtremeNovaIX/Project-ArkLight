package p1.component.agent.rp.context;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import p1.model.ChatLogEntity;
import p1.service.ChatLogRepository;
import p1.utils.PromptTimeBucketUtil;
import p1.utils.SessionUtil;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 用来在向每次RpAgent请求中动态拼装时间上下文消息的组件
 */
@Component
@RequiredArgsConstructor
public class RpRequestTimeAppender {

    private final ChatLogRepository chatLogRepository;

    public ChatRequest augment(ChatRequest request, Object memoryId) {
        if (request == null || memoryId == null || request.messages().isEmpty()) {
            return request;
        }

        List<ChatMessage> messages = request.messages();
        String sessionId = SessionUtil.normalizeSessionId(memoryId.toString());
        List<ChatMessage> updatedMessages = new ArrayList<>(messages);
        // 运行时时间背景放在本轮用户消息前，避免把元信息变成最后一个用户信号。
        updatedMessages.add(RpRuntimeMessageInsertSupport.beforeCurrentUserMessage(updatedMessages),
                buildDynamicMemoryMessage(sessionId));
        return request.toBuilder()
                .messages(updatedMessages)
                .build();
    }

    private UserMessage buildDynamicMemoryMessage(String sessionId) {
        return UserMessage.builder()
                .name("time")
                .addContent(TextContent.from(buildDynamicMemoryContext(sessionId)))
                .build();
    }

    private String buildDynamicMemoryContext(String sessionId) {
        LocalDateTime now = LocalDateTime.now();
        String currentTime = PromptTimeBucketUtil.formatQuarterHour(now);
        String lastUserMessageTime = findPreviousUserMessageTime(sessionId, now);
        return ("<time>\n当前时间：" + currentTime + "\n上次用户发消息时间：" + lastUserMessageTime + "\n</time>").trim();
    }

    private String findPreviousUserMessageTime(String sessionId, LocalDateTime now) {
        List<ChatLogEntity> userMessages =
                chatLogRepository.findTop2BySessionIdAndRoleOrderByCreatedAtDesc(sessionId, "USER");
        if (userMessages.size() < 2) {
            return "（暂无历史用户消息）";
        }
        LocalDateTime time = userMessages.get(1).getCreatedAt();
        return PromptTimeBucketUtil.formatLastUserMessageTime(now, time);
    }
}
