package p1.component.agent.rp.game.control;

import lombok.CustomLog;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static p1.utils.SessionUtil.normalizeSessionId;

/**
 * RP 游戏控制回合锁。
 * <p>
 * 同一个 RP 会话内，流式输出、控制块解析、parser 翻译和动作提交必须保持串行，
 * 否则后续回合可能基于旧游戏状态生成动作，再交给已经推进到新状态的 parser 执行。
 */
@Service
@CustomLog
public class RpGameControlTurnLockService {

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T withLock(String rpSessionId, String source, Supplier<T> action) {
        String key = normalizeSessionId(rpSessionId);
        ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock(true));
        if (!lock.tryLock()) {
            log.debug("[RP游戏控制回合] 等待上一轮结束: rpSession={}, source={}", key, source);
            lock.lock();
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
