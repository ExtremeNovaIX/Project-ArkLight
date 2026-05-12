package p1.component.agent.rp.expression;

/**
 * 表达欲评分后的处理决策。
 *
 * @param type       处理方式
 * @param finalScore 底层校正后的表达欲分数
 * @param reason     决策原因，供日志排查
 */
public record GameExpressionDecision(
        DecisionType type,
        int finalScore,
        String reason
) {

    /**
     * 表达欲处理方式。
     */
    public enum DecisionType {
        /**
         * 直接丢弃，不进入 RP 上下文。
         */
        DROP,

        /**
         * 只保留为游戏行动记忆，不主动触发 RP 开口。
         */
        STORE_ONLY,

        /**
         * 放入 RP 待表达队列，由下一次 RP 请求自然消化。
         */
        PENDING_RP_EXPRESSION
    }
}
