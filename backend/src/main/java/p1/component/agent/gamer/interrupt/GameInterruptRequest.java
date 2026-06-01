package p1.component.agent.gamer.interrupt;

import java.time.Instant;

/**
 * 外部来源提交给游戏桥接层的打断请求。
 * <p>
 * 该对象只表达“下一次游戏规划必须看到的新意图”，真正是否能执行仍由 RP 游戏控制链路
 * 基于最新游戏状态重新判断，避免 RP 直接耦合具体游戏工具。
 *
 * @param gameName    游戏名
 * @param sessionId   用户侧 RP 会话 id
 * @param memoryId    游戏桥接层使用的会话 id
 * @param source      打断来源，例如 rp
 * @param instruction 新的游戏行动意图
 * @param requestedAt 请求创建时间
 */
public record GameInterruptRequest(
        String gameName,
        String sessionId,
        String memoryId,
        String source,
        String instruction,
        Instant requestedAt
) {
}
