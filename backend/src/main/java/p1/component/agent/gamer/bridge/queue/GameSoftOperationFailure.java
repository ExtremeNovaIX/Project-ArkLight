package p1.component.agent.gamer.bridge.queue;

/**
 * 单条操作软错误摘要。
 *
 * @param toolName  MCP 工具名
 * @param arguments MCP 工具参数
 * @param note      agent 对该操作的意图说明
 * @param reason    失败原因
 */
public record GameSoftOperationFailure(
        String toolName,
        String arguments,
        String note,
        String reason
) {
}
