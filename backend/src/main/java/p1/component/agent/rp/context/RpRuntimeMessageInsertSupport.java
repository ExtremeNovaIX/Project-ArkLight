package p1.component.agent.rp.context;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

/**
 * RP 运行时上下文消息插入辅助。
 * <p>
 * 时间、游戏等动态背景都应位于本轮用户消息之前，避免元信息覆盖用户刚发出的强信号。
 */
public final class RpRuntimeMessageInsertSupport {

    private RpRuntimeMessageInsertSupport() {
    }

    /**
     * 查找运行时背景应插入的位置。
     *
     * @param messages 当前请求消息
     * @return 最后一条用户消息下标；没有用户消息时返回消息末尾
     */
    public static int beforeCurrentUserMessage(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage) {
                return index;
            }
        }
        return messages.size();
    }
}
