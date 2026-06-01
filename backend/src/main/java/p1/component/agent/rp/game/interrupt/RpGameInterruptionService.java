package p1.component.agent.rp.game.interrupt;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static p1.utils.SessionUtil.normalizeSessionId;

/**
 * 统一记录 RP 游戏控制流的中断事件。
 * <p>
 * 该服务同时写入长期记忆和下一轮一次性运行时上下文，避免异常处理散落在 stream、parser 和 bridge 各层。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RpGameInterruptionService {

    public static final String EVENT_MESSAGE_NAME = "game_runtime_event";

    private final ChatMemoryProvider chatMemoryProvider;
    private final Map<String, Deque<String>> pendingEventsBySession = new ConcurrentHashMap<>();

    public void recordActionFailure(String rpSessionId,
                                    String rawOutput,
                                    String reason,
                                    int completedActionBlocks) {
        record(rpSessionId, rawOutput, "游戏动作执行失败", reason, completedActionBlocks);
    }

    public void recordStreamFailure(String rpSessionId,
                                    String rawOutput,
                                    String reason,
                                    int completedActionBlocks) {
        record(rpSessionId, rawOutput, "RP 响应流中断", reason, completedActionBlocks);
    }

    public String consumeRuntimeEvent(String rpSessionId) {
        Deque<String> queue = pendingEventsBySession.get(normalizeSessionId(rpSessionId));
        if (queue == null) {
            return "";
        }
        String event;
        synchronized (queue) {
            event = queue.pollFirst();
            if (queue.isEmpty()) {
                pendingEventsBySession.remove(normalizeSessionId(rpSessionId), queue);
            }
        }
        if (event == null || event.isBlank()) {
            return "";
        }
        return """
                <game_runtime_event>
                %s
                </game_runtime_event>
                """.formatted(event.trim()).trim();
    }

    private void record(String rpSessionId,
                        String rawOutput,
                        String kind,
                        String reason,
                        int completedActionBlocks) {
        if (rpSessionId == null || rpSessionId.isBlank()) {
            return;
        }
        String sessionId = normalizeSessionId(rpSessionId);
        String event = renderEvent(kind, reason, completedActionBlocks);
        rememberPendingEvent(sessionId, event);
        appendToMemory(sessionId, rawOutput, event);
    }

    private void rememberPendingEvent(String sessionId, String event) {
        Deque<String> queue = pendingEventsBySession.computeIfAbsent(sessionId, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(event);
        }
    }

    private void appendToMemory(String sessionId, String rawOutput, String event) {
        try {
            ChatMemory memory = chatMemoryProvider.get(sessionId);
            String raw = normalize(rawOutput);
            if (!raw.isBlank()) {
                memory.add(AiMessage.from(raw));
            }
            memory.add(UserMessage.from(EVENT_MESSAGE_NAME, event));
            log.debug("[RP游戏中断] 已写入 RP 记忆: rpSession={}", sessionId);
        } catch (Exception e) {
            log.warn("[RP游戏中断] 写入 RP 记忆失败: rpSession={}, reason={}", sessionId, e.getMessage());
        }
    }

    private String renderEvent(String kind, String reason, int completedActionBlocks) {
        int completed = Math.max(0, completedActionBlocks);
        return """
                上一轮 RP 游戏控制流被中断。
                类型：%s
                原因：%s
                已经闭合并执行成功的完整 action 控制块数量：%d。它们已经被打出，请不要重复这些已经完成的操作。
                请基于最新游戏状态继续；用户没有新的发言，不要重复同一句话，除非当前游戏状态发生了新的可决策变化。
                """.formatted(
                normalize(kind).isBlank() ? "未知中断" : normalize(kind),
                normalize(reason).isBlank() ? "未提供原因" : normalize(reason),
                completed).trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
