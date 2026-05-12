package p1.component.agent.gamer.adapter.core;

import lombok.Getter;

/**
 * 游戏桥接层异常。
 * <p>
 * 该异常用于表达适配器修复失败、状态解析失败、工具参数非法、
 * 队列中断等可反馈给 agent 的问题。
 */
@Getter
public class GameBridgeException extends RuntimeException {

    private final int executedCount;

    /**
     * 创建只包含错误说明的桥接异常。
     *
     * @param message 错误说明（同时用作 agent 通知）
     */
    public GameBridgeException(String message) {
        super(message);
        this.executedCount = 0;
    }

    /**
     * 创建只包含错误说明的桥接异常。
     *
     * @param message       错误说明（同时用作 agent 通知）
     * @param executedCount 中断前已成功执行的 MCP 操作数
     */
    public GameBridgeException(String message, int executedCount) {
        super(message);
        this.executedCount = executedCount;
    }

    /**
     * 创建包含底层异常的桥接异常。
     *
     * @param message 错误说明
     * @param cause   底层异常
     */
    public GameBridgeException(String message, Throwable cause) {
        super(message, cause);
        this.executedCount = 0;
    }

}
