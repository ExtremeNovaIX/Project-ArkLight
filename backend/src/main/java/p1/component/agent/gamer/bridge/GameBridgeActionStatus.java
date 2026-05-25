package p1.component.agent.gamer.bridge;

/**
 * 桥接层记录的队列执行状态。
 * <p>
 * 这个状态来自虚拟工具 enqueue_operations 的 status 参数，用于让外层游戏循环获得结构化结果。
 */
public enum GameBridgeActionStatus {
    CONTINUE,
    WAIT,
    GAME_OVER,
    INTERRUPTED,
    UNKNOWN;

    /**
     * 将模型传入的字符串状态归一化为枚举。
     *
     * @param value enqueue_operations.status 的原始值
     * @return 可被循环层和桥接层安全使用的状态枚举
     */
    public static GameBridgeActionStatus from(String value) {
        if (value == null || value.isBlank()) {
            return CONTINUE;
        }

        // 只接受明确支持的状态；未知值按 CONTINUE 处理，避免模型拼写失误导致循环停摆。
        return switch (value.trim().toUpperCase()) {
            case "WAIT" -> WAIT;
            case "GAME_OVER" -> GAME_OVER;
            default -> CONTINUE;
        };
    }
}
