package p1.component.agent.gamer.trace;

/**
 * 单条 MCP 操作执行复盘。
 *
 * @param index           队列内序号，从 1 开始
 * @param toolName        实际调用的 MCP 工具名
 * @param arguments       实际调用的 MCP 参数
 * @param beforeStateType 执行前 state_type
 * @param beforeStateHash 执行前状态短 hash
 * @param afterStateType  执行后 state_type
 * @param afterStateHash  执行后状态短 hash
 * @param plannedActionWindow 计划时的行动窗口签名
 * @param currentActionWindow 执行前的行动窗口签名
 * @param preconditionSignature 操作前置条件签名
 * @param outcomeKind     执行结果分类
 * @param elapsedMs       单条操作耗时
 * @param result          MCP 返回摘要
 * @param error           错误摘要；成功时为空
 */
public record GameOperationExecutionTrace(
        int index,
        String toolName,
        String arguments,
        String beforeStateType,
        String beforeStateHash,
        String afterStateType,
        String afterStateHash,
        String plannedActionWindow,
        String currentActionWindow,
        String preconditionSignature,
        String outcomeKind,
        long elapsedMs,
        String result,
        String error
) {
    public GameOperationExecutionTrace(int index,
                                       String toolName,
                                       String arguments,
                                       String beforeStateType,
                                       String beforeStateHash,
                                       String afterStateType,
                                       String afterStateHash,
                                       long elapsedMs,
                                       String result,
                                       String error) {
        this(index, toolName, arguments, beforeStateType, beforeStateHash, afterStateType, afterStateHash,
                "", "", "", "", elapsedMs, result, error);
    }
}
