package p1.component.agent.gamer.bridge.queue;

import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.trace.GameQueueExecutionTrace;

/**
 * 队列执行结果。
 *
 * @param latestState    最近一次获取到的游戏状态
 * @param attempted      尝试执行的 MCP 操作数
 * @param successful     成功执行的 MCP 操作数
 * @param boundaryReason 操作后状态已推进导致队列正常截断的原因
 * @param trace          队列执行复盘信息
 */
public record GameQueueDrainResult(
        GameStateSnapshot latestState,
        int attempted,
        int successful,
        String boundaryReason,
        GameQueueExecutionTrace trace
) {
    public GameQueueDrainResult(GameStateSnapshot latestState,
                                int attempted,
                                int successful,
                                GameQueueExecutionTrace trace) {
        this(latestState, attempted, successful, "", trace);
    }

    public boolean stoppedByStateBoundary() {
        return boundaryReason != null && !boundaryReason.isBlank();
    }
}
