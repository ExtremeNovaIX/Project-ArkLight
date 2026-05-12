package p1.component.agent.gamer.interrupt;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.GameSessionKey;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏桥接层的外部打断事件总线。
 * <p>
 * RP、用户界面或其他上层模块向这里登记“新意图”
 */
@Component
@Slf4j
public class GameInterruptService {

    private final Map<String, GameInterruptRequest> pendingRequests = new ConcurrentHashMap<>();

    /**
     * 提交或覆盖一个待处理打断。
     * <p>
     * 同一游戏会话只保留最新打断，避免用户连续改口时旧意图再次污染后续规划。
     *
     * @param gameName    游戏名
     * @param sessionId   用户侧 RP 会话 id
     * @param source      打断来源
     * @param instruction 新的游戏行动意图
     * @return 已登记的打断请求
     */
    public GameInterruptRequest requestInterrupt(String gameName, String sessionId, String source, String instruction) {
        String memoryId = GameSessionKey.of(gameName, sessionId);
        GameInterruptRequest request = new GameInterruptRequest(
                gameName,
                sessionId,
                memoryId,
                source == null || source.isBlank() ? "unknown" : source.trim(),
                instruction == null ? "" : instruction.trim(),
                Instant.now());
        pendingRequests.put(memoryId, request);
        log.info("[游戏打断] 已登记外部打断: game={}, session={}, source={}, instruction={}",
                gameName, sessionId, request.source(), request.instruction());
        return request;
    }

    /**
     * 查看待处理打断，但不消费。
     *
     * @param memoryId gamer 使用的会话记忆 id
     * @return 当前待处理打断；没有时为空
     */
    public Optional<GameInterruptRequest> peek(@NonNull String memoryId) {
        return Optional.ofNullable(pendingRequests.get(memoryId));
    }

    /**
     * 取出并删除待处理打断。
     *
     * @param memoryId gamer 使用的会话记忆 id
     * @return 当前待处理打断；没有时为空
     */
    public Optional<GameInterruptRequest> consume(@NonNull String memoryId) {
        return Optional.ofNullable(pendingRequests.remove(memoryId));
    }
}
