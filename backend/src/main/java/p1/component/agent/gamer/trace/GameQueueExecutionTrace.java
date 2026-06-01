package p1.component.agent.gamer.trace;

import java.util.List;

/**
 * 单个 RP do 对应的 MCP 队列执行复盘。
 *
 * @param queueId        队列 id
 * @param startStateType 队列开始时的 state_type
 * @param startStateHash 队列开始时的状态短 hash
 * @param startActionWindow 队列开始时的行动窗口签名
 * @param elapsedMs      队列执行总耗时
 * @param operations     队列内每条 MCP 操作的执行复盘
 * @param boundaryReason 队列因状态推进而正常停止的原因
 */
public record GameQueueExecutionTrace(
        String queueId,
        String startStateType,
        String startStateHash,
        String startActionWindow,
        long elapsedMs,
        List<GameOperationExecutionTrace> operations,
        String boundaryReason
) {
    public GameQueueExecutionTrace(String queueId,
                                   String startStateType,
                                   String startStateHash,
                                   long elapsedMs,
                                   List<GameOperationExecutionTrace> operations,
                                   String boundaryReason) {
        this(queueId, startStateType, startStateHash, "", elapsedMs, operations, boundaryReason);
    }
}
