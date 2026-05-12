package p1.component.agent.rp.expression;

import java.time.Instant;

/**
 * 等待注入 RP 上下文的游戏表达冲动。
 *
 * @param rpSessionId   RP 会话 id
 * @param gameName      游戏名
 * @param score         最终表达欲分数
 * @param type          表达类型
 * @param urgency       表达紧急度
 * @param innerThought  可交给 RP 消化的内心活动
 * @param reason        表达原因
 * @param sourceAction  触发表达的游戏行动
 * @param createdAt     产生时间
 */
public record PendingRpExpression(
        String rpSessionId,
        String gameName,
        int score,
        String type,
        String urgency,
        String innerThought,
        String reason,
        String sourceAction,
        Instant createdAt
) {
}
