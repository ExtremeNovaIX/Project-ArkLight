package p1.component.agent.gamer.bridge.queue;

import p1.component.agent.gamer.adapter.core.GameStateSnapshot;

import java.util.List;

/**
 * 队列执行结果。
 *
 * @param latestState  最近一次获取到的游戏状态
 * @param attempted    尝试执行的 MCP 操作数
 * @param successful   成功执行的 MCP 操作数
 * @param softFailures 可跳过的软错误列表
 */
public record GameQueueDrainResult(
        GameStateSnapshot latestState,
        int attempted,
        int successful,
        List<GameSoftOperationFailure> softFailures
) {
}
