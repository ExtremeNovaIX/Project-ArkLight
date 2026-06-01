package p1.component.agent.gamer.adapter.core;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 已进入桥接队列的游戏操作。
 *
 * @param request          即将调用底层 MCP 的工具请求
 * @param plannedStateJson 入队时 agent 看到的原始状态 JSON
 * @param metadata         适配器附加的修复元数据
 */
public record QueuedGameOperation(
        ToolExecutionRequest request,
        String plannedStateJson,
        Map<String, Object> metadata
) {

    /**
     * 从 agent 原始操作创建排队操作。
     *
     * @param operation    agent 提交的操作
     * @param plannedState agent 做计划时看到的状态
     * @return 可进入队列的操作对象
     */
    public static QueuedGameOperation from(GameOperation operation, GameStateSnapshot plannedState) {
        // MCP 请求需要唯一 id，便于 LangChain4j 和底层工具链追踪本次调用。
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id(UUID.randomUUID().toString())
                .name(operation.toolName())
                .arguments(operation.args() == null ? "{}" : operation.args().toString())
                .build();
        return new QueuedGameOperation(
                request,
                plannedState.rawJson(),
                new HashMap<>()
        );
    }

    /**
     * 替换底层 MCP 请求，同时保留原始元数据。
     *
     * @param request 修复后的工具请求
     * @return 替换 request 后的新排队操作
     */
    public QueuedGameOperation withRequest(ToolExecutionRequest request) {
        return new QueuedGameOperation(request, plannedStateJson, metadata);
    }
}
