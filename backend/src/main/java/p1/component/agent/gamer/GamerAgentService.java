package p1.component.agent.gamer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import p1.component.agent.gamer.bridge.GameBridgeActionStatus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 管理游戏会话中游戏智能体的生命周期。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GamerAgentService {

    private final GamerStreamingAgentService streamingAgentService;

    /**
     * 每个会话一个锁，防止游戏循环和用户指令并发操作同一会话。
     */
    private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    /**
     * 获取会话级互斥锁。
     *
     * @param sessionId LangChain4j 的会话记忆 id
     * @return 该会话独占的锁对象
     */
    private ReentrantLock getSessionLock(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, k -> new ReentrantLock());
    }

    /**
     * 通过 Gamer Agent 处理用户消息。
     * <p>
     * Gamer Agent 的操作会被流式解析为 ACTION JSON，并逐条转发到底层游戏桥接层。
     *
     * @param gameName    游戏名
     * @param sessionId   用户侧会话 id
     * @param userMessage 外层传入的用户指令
     * @return 游戏智能体或桥接层返回的文本
     */
    public String play(String gameName, String sessionId, String userMessage) {
        String memoryId = GameSessionKey.of(gameName, sessionId);
        ReentrantLock lock = getSessionLock(memoryId);
        lock.lock();
        String previousSessionId = MDC.get("sessionId");
        String previousServiceInfo = MDC.get("serviceInfo");
        try {
            // gamer 使用单独 memoryId 写入 MDC，方便日志和 reasoning_content 暂存按游戏会话归档。
            MDC.put("sessionId", memoryId);
            MDC.put("serviceInfo", "gamer");
            return streamingAgentService.play(gameName, sessionId, userMessage);
        } finally {
            // 同一游戏会话必须串行执行，避免用户指令和定时循环同时操作 MCP。
            restoreMdc("sessionId", previousSessionId);
            restoreMdc("serviceInfo", previousServiceInfo);
            lock.unlock();
        }
    }

    /**
     * 恢复进入 gamer 调用前的 MDC 字段。
     *
     * @param key      MDC 字段名
     * @param oldValue 进入 gamer 调用前的字段值
     */
    private void restoreMdc(String key, String oldValue) {
        if (oldValue == null) {
            MDC.remove(key);
            return;
        }
        MDC.put(key, oldValue);
    }

    /**
     * 查询本轮游戏智能体通过桥接层提交的结构化动作状态。
     *
     * @param gameName  游戏名
     * @param sessionId 用户侧会话 id
     * @return 最近一次 ACTION 队列状态；本轮没有提交时返回 UNKNOWN
     */
    public GameBridgeActionStatus lastActionStatus(String gameName, String sessionId) {
        return streamingAgentService.lastActionStatus(gameName, sessionId);
    }
}
