package p1.component.agent.rp.expression;

import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.bridge.GameBridgeActionStatus;
import p1.component.agent.gamer.bridge.GameExpressionPayload;

import java.time.Instant;
import java.util.List;

/**
 * 一次游戏 ACTION 产生的 RP 表达欲候选。
 * <p>
 * 该对象把 gamer 自评表达、执行结果和当前会话绑定在一起，供评分器判断是否进入 RP 待表达队列。
 *
 * @param gameName        游戏名
 * @param gamerMemoryId   gamer 会话 key
 * @param rpSessionId     RP 会话 id
 * @param status          本次 ACTION 状态
 * @param payload         gamer 输出的表达欲字段
 * @param operations      本次 ACTION 中提交的操作
 * @param sourceAction    面向人的行动摘要
 * @param result          桥接层执行结果
 * @param interruptReason 中断或失败原因；正常执行时为空
 * @param createdAt       候选产生时间
 */
public record GameExpressionCandidate(
        String gameName,
        String gamerMemoryId,
        String rpSessionId,
        GameBridgeActionStatus status,
        GameExpressionPayload payload,
        List<GameOperation> operations,
        String sourceAction,
        String result,
        String interruptReason,
        Instant createdAt
) {
}
