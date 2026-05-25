package p1.component.agent.rp.context;

import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import p1.component.agent.rp.game.context.RpGameStateAppender;

/**
 * RP 聊天请求动态上下文编排器。
 * <p>
 * 这里集中串联时间、游戏模式等运行时状态，避免把动态信息写进 RP 静态提示词。
 */
@Component
@RequiredArgsConstructor
public class RpChatRequestAugmenter {

    private final RpRequestTimeAppender timeAppender;
    private final RpGameStateAppender gameStateAppender;

    /**
     * 依次追加 RP 运行时上下文。
     *
     * @param request  原始聊天请求
     * @param memoryId RP 会话 id
     * @return 已追加动态上下文的请求
     */
    public ChatRequest augment(ChatRequest request, Object memoryId) {
        ChatRequest withTime = timeAppender.augment(request, memoryId);
        return gameStateAppender.augment(withTime, memoryId);
    }
}
