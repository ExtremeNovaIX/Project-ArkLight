package p1.component.agent.gamer.adapter.core;

import lombok.Getter;

/**
 * 游戏桥接层异常。
 * <p>
 * 该异常用于表达适配器修复失败、状态解析失败、工具参数非法、
 * 队列中断等可反馈给 RP/parser 的问题。
 */
@Getter
public class GameBridgeException extends RuntimeException {

    public enum Kind {
        FAILURE,
        STATE_ADVANCED,
        INTERACTION_DEFERRED
    }

    private final int executedCount;
    private final Kind kind;
    private final GameStateSnapshot latestState;

    /**
     * 创建只包含错误说明的桥接异常。
     *
     * @param message 错误说明（同时用作 agent 通知）
     */
    public GameBridgeException(String message) {
        this(message, 0, Kind.FAILURE, null, null);
    }

    /**
     * 创建只包含错误说明的桥接异常。
     *
     * @param message       错误说明（同时用作 agent 通知）
     * @param executedCount 中断前已成功执行的 MCP 操作数
     */
    public GameBridgeException(String message, int executedCount) {
        this(message, executedCount, Kind.FAILURE, null, null);
    }

    public GameBridgeException(String message, Kind kind) {
        this(message, 0, kind, null, null);
    }

    public GameBridgeException(String message, int executedCount, Kind kind) {
        this(message, executedCount, kind, null, null);
    }

    public GameBridgeException(String message, int executedCount, Kind kind, GameStateSnapshot latestState) {
        this(message, executedCount, kind, latestState, null);
    }

    /**
     * 创建包含底层异常的桥接异常。
     *
     * @param message 错误说明
     * @param cause   底层异常
     */
    public GameBridgeException(String message, Throwable cause) {
        this(message, 0, Kind.FAILURE, null, cause);
    }

    public GameBridgeException(String message,
                               int executedCount,
                               Kind kind,
                               GameStateSnapshot latestState,
                               Throwable cause) {
        super(message, cause);
        this.executedCount = executedCount;
        this.kind = kind == null ? Kind.FAILURE : kind;
        this.latestState = latestState;
    }

}
