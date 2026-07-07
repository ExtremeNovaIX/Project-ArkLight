package p1.component.agent.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.model.enums.MessageRole;
import p1.service.markdown.RawMdService;
import p1.utils.ChatMessageUtil;

@Service
@RequiredArgsConstructor
@CustomLog
public class ChatMemoryAppender {
    private final RawMdService rawMdService;

    /**
     * 追加聊天消息到 raw目录下原始对话记录markdown文件。
     */
    public void appendToRaw(String sessionId, ChatMessage message) {
        try {
            // 只有用户消息和 AI 最终答复会进入持久化 backlog；工具中间态统一忽略。
            switch (message) {
                case UserMessage msg ->
                        handleAppend(sessionId, MessageRole.USER, ChatMessageUtil.extractText(msg));

                case AiMessage msg when ChatMessageUtil.isAiFinalResponseMessage(msg) ->
                        handleAppend(sessionId, MessageRole.ASSISTANT, ChatMessageUtil.extractText(msg));

                default -> log.trace("[忽略消息] 类型: {}", message.getClass().getSimpleName());
            }
        } catch (Exception e) {
            log.error(LogDomain.MEMORY, "memory.append_failed", LogOutcome.FAILED, e);
        }
    }

    private void handleAppend(String sessionId, MessageRole role, String text) {
        String cleanText = text == null ? "" : text.trim();
        if (cleanText.isBlank()) return;

        rawMdService.appendRawMessage(sessionId, role, cleanText);
    }
}
