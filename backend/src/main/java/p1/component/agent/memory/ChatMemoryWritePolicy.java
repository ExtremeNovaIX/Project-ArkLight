package p1.component.agent.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static p1.utils.SessionUtil.normalizeSessionId;

/**
 * 记忆写入策略。
 * <p>
 * 上游可以把某一次运行时触发消息声明为 transient，避免它被当成真实用户发言写入记忆。
 */
@Service
public class ChatMemoryWritePolicy {

    private final Map<String, Deque<SuppressionToken>> suppressionsBySession = new ConcurrentHashMap<>();

    public <T> T suppressUserMessage(String sessionId, String expectedUserName, Supplier<T> action) {
        String key = normalizeSessionId(sessionId);
        SuppressionToken token = new SuppressionToken(expectedUserName);
        Deque<SuppressionToken> queue = suppressionsBySession.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(token);
        }
        try {
            return action.get();
        } finally {
            synchronized (queue) {
                queue.remove(token);
                if (queue.isEmpty()) {
                    suppressionsBySession.remove(key, queue);
                }
            }
        }
    }

    public boolean shouldSuppress(String sessionId, ChatMessage message) {
        if (!(message instanceof UserMessage userMessage)) {
            return false;
        }
        Deque<SuppressionToken> queue = suppressionsBySession.get(normalizeSessionId(sessionId));
        if (queue == null) {
            return false;
        }
        synchronized (queue) {
            SuppressionToken token = queue.peekFirst();
            if (token == null || !token.matches(userMessage.name())) {
                return false;
            }
            queue.removeFirst();
            if (queue.isEmpty()) {
                suppressionsBySession.remove(normalizeSessionId(sessionId), queue);
            }
            return true;
        }
    }

    private record SuppressionToken(String expectedUserName) {
        private boolean matches(String actualUserName) {
            if (expectedUserName == null || expectedUserName.isBlank()) {
                return true;
            }
            return expectedUserName.equals(actualUserName);
        }
    }
}
