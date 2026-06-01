package p1.component.agent.gamer.loop;

import org.springframework.stereotype.Service;
import p1.component.agent.gamer.GameSessionKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 游戏会话级执行锁。
 * <p>
 * 同一个游戏会话内，观察、RP 回合、MCP 动作提交必须串行，避免旧状态和新动作交叉。
 */
@Service
public class GameSessionExecutionLockService {

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public boolean tryWithLock(String gameName, String sessionId, Runnable action) {
        ReentrantLock lock = lock(gameName, sessionId);
        if (!lock.tryLock()) {
            return false;
        }
        try {
            action.run();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public <T> T withLock(String gameName, String sessionId, Supplier<T> action) {
        ReentrantLock lock = lock(gameName, sessionId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lock(String gameName, String sessionId) {
        return locks.computeIfAbsent(GameSessionKey.of(gameName, sessionId), ignored -> new ReentrantLock(true));
    }
}
