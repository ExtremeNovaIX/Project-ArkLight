package p1.component.agent.gamer.adapter.core;

/**
 * 游戏当前是否需要 RP 游戏控制链路行动的判断状态。
 */
public enum GameActionabilityStatus {
    /**
     * 当前状态需要或允许 RP 做决策。
     */
    ACTIONABLE,

    /**
     * 当前状态有效，但还不到 RP 行动窗口。
     */
    WAITING,

    /**
     * 游戏已经结束，循环层应停止会话。
     */
    GAME_OVER,

    /**
     * 状态无法可靠判断，循环层应保守跳过本次 tick。
     */
    UNKNOWN
}
