package p1.component.agent.gamer.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;

/**
 * gamer 专用的单轮聊天记忆。
 * <p>
 * LangChain4j 需要 ChatMemory 才能把 memoryId 传给工具层，并在工具调用循环中保留当前轮消息；
 * 但 gamer 不应该把完整游戏状态带到下一轮，所以这里在新一轮 user/system 消息进入时清空旧内容。
 */
public class TurnScopedGamerChatMemory implements ChatMemory {

    private final Object id;
    private final List<ChatMessage> messages = new ArrayList<>();

    /**
     * 创建单轮聊天记忆。
     *
     * @param id LangChain4j 传入的会话 id
     */
    public TurnScopedGamerChatMemory(Object id) {
        this.id = id;
    }

    /**
     * 返回记忆 id，供 LangChain4j 关联工具调用上下文。
     *
     * @return 会话 id
     */
    @Override
    public Object id() {
        return id;
    }

    /**
     * 写入当前轮消息。
     *
     * @param message 本轮聊天消息
     */
    @Override
    public synchronized void add(ChatMessage message) {
        if (message == null) {
            return;
        }

        ChatMessageType type = message.type();
        if (type == ChatMessageType.SYSTEM) {
            // 新 system message 代表新一次 agent 调用，清掉上一轮状态和回复。
            messages.clear();
            messages.add(message);
            return;
        }

        if (type == ChatMessageType.USER) {
            // 没有 system message 的调用也要从 user message 开始清理旧轮次。
            if (messages.isEmpty() || messages.getLast().type() != ChatMessageType.SYSTEM) {
                messages.clear();
            }
            messages.add(message);
            return;
        }

        // AI/tool 消息只属于当前工具调用循环，保留到本轮结束即可。
        messages.add(message);
    }

    /**
     * 返回当前轮消息快照。
     *
     * @return 当前轮 system/user/tool 消息
     */
    @Override
    public synchronized List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    /**
     * 清空当前轮消息。
     */
    @Override
    public synchronized void clear() {
        messages.clear();
    }
}
