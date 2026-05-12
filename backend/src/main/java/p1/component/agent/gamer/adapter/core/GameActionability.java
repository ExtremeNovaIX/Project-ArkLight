package p1.component.agent.gamer.adapter.core;

/**
 * 游戏行动窗口探测结果。
 *
 * @param status 判断状态
 * @param reason 可读原因，用于日志和诊断
 */
public record GameActionability(
        GameActionabilityStatus status,
        String reason
) {

    /**
     * 构造可行动结果。
     *
     * @param reason 可读原因
     * @return 可行动结果
     */
    public static GameActionability actionable(String reason) {
        return new GameActionability(GameActionabilityStatus.ACTIONABLE, reason);
    }

    /**
     * 构造等待结果。
     *
     * @param reason 可读原因
     * @return 等待结果
     */
    public static GameActionability waiting(String reason) {
        return new GameActionability(GameActionabilityStatus.WAITING, reason);
    }

    /**
     * 构造游戏结束结果。
     *
     * @param reason 可读原因
     * @return 游戏结束结果
     */
    public static GameActionability gameOver(String reason) {
        return new GameActionability(GameActionabilityStatus.GAME_OVER, reason);
    }

    /**
     * 构造未知结果。
     *
     * @param reason 可读原因
     * @return 未知结果
     */
    public static GameActionability unknown(String reason) {
        return new GameActionability(GameActionabilityStatus.UNKNOWN, reason);
    }

    /**
     * 判断当前结果是否允许调用 agent。
     *
     * @return 只有 ACTIONABLE 返回 true
     */
    public boolean actionable() {
        return status == GameActionabilityStatus.ACTIONABLE;
    }
}
