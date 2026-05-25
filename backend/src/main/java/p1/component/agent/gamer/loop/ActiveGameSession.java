package p1.component.agent.gamer.loop;

import lombok.Data;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 活跃游戏会话。
 * <p>
 * 该对象只保存循环层需要的生命周期、最近活动时间、决策次数和连续失败次数。
 * 单次队列里执行了多少 MCP 指令由桥接层管理，不再记录在这里。
 */
@Data
public class ActiveGameSession {

    /**
     * 游戏循环会话状态。
     */
    public enum State {RUNNING, PAUSED, STOPPED}

    private final String gameName;
    private final String sessionId;
    /**
     * 关联的 RP 会话 id。
     * <p>
     * 默认与游戏 sessionId 相同；需要把游戏会话和 RP 对话会话分开时由启动请求显式指定。
     */
    private final String rpSessionId;
    private volatile State state;
    private final Instant startedAt;
    private volatile Instant lastActivityAt;
    /**
     * 循环层唤醒 agent 的总次数。
     */
    private final AtomicInteger totalStepCount = new AtomicInteger(0);
    /**
     * 连续失败次数，用于截断控制
     */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /**
     * 创建一个新的运行中会话。
     *
     * @param gameName  游戏名
     * @param sessionId 用户侧会话 id
     */
    public ActiveGameSession(String gameName, String sessionId) {
        this(gameName, sessionId, sessionId);
    }

    /**
     * 创建一个新的运行中会话，并绑定 RP 会话。
     *
     * @param gameName    游戏名
     * @param sessionId   游戏侧会话 id
     * @param rpSessionId RP 会话 id
     */
    public ActiveGameSession(String gameName, String sessionId, String rpSessionId) {
        this.gameName = gameName;
        this.sessionId = sessionId;
        this.rpSessionId = rpSessionId == null || rpSessionId.isBlank() ? sessionId : rpSessionId.trim();
        this.state = State.RUNNING;
        this.startedAt = Instant.now();
        this.lastActivityAt = Instant.now();
    }

    /**
     * 增加并返回总决策次数。
     *
     * @return 增加后的总决策次数
     */
    public int incrementAndGetTotalSteps() {
        return totalStepCount.incrementAndGet();
    }

    /**
     * 刷新最近活动时间。
     */
    public void touch() {
        lastActivityAt = Instant.now();
    }

    /**
     * 获取连续失败次数。
     *
     * @return 连续失败次数
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * 增加并返回连续失败次数。
     *
     * @return 增加后的连续失败次数
     */
    public int incrementFailures() {
        return consecutiveFailures.incrementAndGet();
    }

    /**
     * 清零连续失败次数。
     */
    public void resetFailures() {
        consecutiveFailures.set(0);
    }
}
