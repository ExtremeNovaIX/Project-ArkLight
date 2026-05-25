package p1.component.agent.gamer.memory;

import p1.component.agent.gamer.bridge.GameBridgeActionStatus;

import java.time.Instant;
import java.util.List;

/**
 * gamer 一次决策的轻量工作记忆记录。
 * <p>
 * 该记录只保存 agent 自己提交的决策摘要、操作意图和桥接层结果，不保存完整游戏状态。
 *
 * @param timestamp       记录时间
 * @param gameName        游戏名
 * @param memoryId        游戏会话 key
 * @param status          agent 声明的队列状态或桥接层中断状态
 * @param summary         agent 提交的本批决策说明
 * @param reasoning       模型返回的 reasoning_content；没有时为空
 * @param operations      agent 提交的操作摘要
 * @param result          桥接层执行结果摘要
 * @param interruptReason 中断原因；没有中断时为空
 */
public record GamerDecisionRecord(
        Instant timestamp,
        String gameName,
        String memoryId,
        GameBridgeActionStatus status,
        String summary,
        String reasoning,
        List<String> operations,
        String result,
        String interruptReason
) {
}
