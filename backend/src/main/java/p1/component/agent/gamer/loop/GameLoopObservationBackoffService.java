package p1.component.agent.gamer.loop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import p1.config.mcp.GameLoopProperties;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏循环慢观察退避服务。
 * <p>
 * 该组件只处理“当前会话连续没有有效动作时，下次什么时候再探测”的节流状态，
 * 不参与游戏生命周期、MCP 调用或 RP 决策。
 */
@Component
@Slf4j
public class GameLoopObservationBackoffService {

    private final Map<String, ObservationState> states = new ConcurrentHashMap<>();

    /**
     * 判断当前会话是否还没到下一次观察时间。
     *
     * @param session 当前游戏会话
     * @param props   游戏循环配置
     * @return true 表示本次 tick 可直接跳过该会话
     */
    public boolean shouldSkip(ActiveGameSession session, GameLoopProperties props) {
        if (!enabled(props)) {
            return false;
        }
        ObservationState state = states.get(key(session));
        return state != null && state.nextObserveAt.isAfter(Instant.now());
    }

    /**
     * 记录一次没有产生有效动作的观察。
     *
     * @param session 当前游戏会话
     * @param props   游戏循环配置
     * @param reason  本次无动作原因
     */
    public void recordNoEffectiveAction(ActiveGameSession session, GameLoopProperties props, String reason) {
        if (!enabled(props)) {
            reset(session);
            return;
        }
        GameLoopProperties.SlowObserveConfig config = props.getSlowObserve();
        ObservationState state = states.computeIfAbsent(key(session), ignored -> new ObservationState());
        synchronized (state) {
            state.noEffectiveCount++;
            if (state.noEffectiveCount < Math.max(1, config.getThreshold())) {
                state.nextObserveAt = Instant.EPOCH;
                return;
            }

            long initialMs = Math.max(1L, config.getInitialMs());
            long stepMs = Math.max(0L, config.getStepMs());
            long maxMs = Math.max(initialMs, config.getMaxMs());
            state.currentDelayMs = state.currentDelayMs <= 0
                    ? initialMs
                    : Math.min(maxMs, state.currentDelayMs + stepMs);
            state.nextObserveAt = Instant.now().plusMillis(state.currentDelayMs);
            state.lastReason = reason == null ? "" : reason;
            log.debug("[RP游戏循环] 进入慢观察: game={}, session={}, delayMs={}, noEffective={}, reason={}",
                    session.getGameName(), session.getSessionId(), state.currentDelayMs,
                    state.noEffectiveCount, state.lastReason);
        }
    }

    /**
     * 记录一次有效动作或强唤醒，清除退避状态。
     *
     * @param session 当前游戏会话
     */
    public void reset(ActiveGameSession session) {
        if (session != null) {
            states.remove(key(session));
        }
    }

    /**
     * 记录一次有效动作或强唤醒，清除退避状态。
     *
     * @param gameName  游戏名
     * @param sessionId 游戏会话 id
     */
    public void reset(String gameName, String sessionId) {
        states.remove(gameName + ":" + sessionId);
    }

    /**
     * 判断慢观察开关。
     *
     * @param props 游戏循环配置
     * @return true 表示启用
     */
    private boolean enabled(GameLoopProperties props) {
        return props != null
                && props.getSlowObserve() != null
                && props.getSlowObserve().isEnabled();
    }

    /**
     * 构建会话 key。
     *
     * @param session 当前游戏会话
     * @return 稳定 key
     */
    private String key(ActiveGameSession session) {
        return session.getGameName() + ":" + session.getSessionId();
    }

    /**
     * 单会话慢观察状态。
     */
    private static final class ObservationState {
        private int noEffectiveCount;
        private long currentDelayMs;
        private Instant nextObserveAt = Instant.EPOCH;
        private String lastReason = "";
    }
}
