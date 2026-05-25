package p1.component.agent.gamer.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderResult;
import p1.component.agent.gamer.adapter.core.*;
import p1.config.mcp.MCPProperties;

import java.util.ArrayDeque;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 游戏适配器接口。
 * <p>
 * 通用桥接层负责排队、逐条执行和转发 MCP 工具；具体游戏适配器负责解释游戏语义，
 * 例如如何获取状态、如何修复过期操作、以及什么时候应该中断剩余队列。
 */
public interface GameAdapter {

    /**
     * 返回适配器 id。
     *
     * @return 配置中 mcp.games.*.adapter 使用的适配器标识
     */
    String id();

    /**
     * 判断该适配器是否支持当前游戏配置。
     *
     * @param gameName 游戏名
     * @param config   游戏 MCP 配置
     * @return true 表示当前适配器可用于该游戏
     */
    default boolean supports(String gameName, MCPProperties.GameMCPConfig config) {
        return id().equalsIgnoreCase(config.getAdapter());
    }

    /**
     * 构建状态工具参数。子类可覆写以传入特定参数（如 format=json）。
     *
     * @param context 适配器运行上下文
     * @return 状态工具参数 JSON
     */
    default String stateToolArguments(GameAdapterContext context) {
        return "{}";
    }

    /**
     * 解析 MCP 返回的状态 JSON。
     *
     * @param raw MCP 状态工具返回的原始文本
     * @return 标准化状态快照
     */
    default GameStateSnapshot parseState(String raw) {
        try {
            JsonNode json = new ObjectMapper().readTree(raw);
            String stateType = json.path("state_type").asText("");
            return new GameStateSnapshot(raw, json, stateType);
        } catch (Exception e) {
            String preview = raw == null ? "null" : raw.substring(0, Math.min(raw.length(), 500));
            throw new GameBridgeException("解析游戏状态失败，MCP 返回: " + preview, e);
        }
    }

    /**
     * 解析状态工具在 MCP 工具列表中的实际名称。
     * 子类可覆写以处理前缀（如 STS2 多人模式 mp_ 前缀）。
     *
     * @param config 游戏 MCP 配置
     * @return MCP 工具列表中的实际状态工具名
     */
    default String resolveStateToolName(MCPProperties.GameMCPConfig config) {
        return config.getStateToolName();
    }

    /**
     * 从底层 MCP 获取最新游戏状态。
     *
     * @param context 适配器运行上下文
     * @return 标准化后的游戏状态快照
     */
    default GameStateSnapshot fetchState(GameAdapterContext context) {
        String name = resolveStateToolName(context.config());
        ToolExecutor executor = context.tools().toolExecutorByName(name);
        if (executor == null) {
            String availableTools = context.tools().tools().keySet().stream()
                    .map(ToolSpecification::name)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.joining(", "));
            throw new GameBridgeException("状态工具不存在: " + name
                    + (availableTools.isBlank() ? "" : "；可用工具: " + availableTools));
        }
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name(name)
                .arguments(stateToolArguments(context))
                .build();
        String raw = executor.execute(request, context.sessionId());
        return parseState(raw);
    }

    /**
     * 根据已经获取到的游戏状态判断是否需要 agent 行动。
     * <p>
     * 默认返回可行动，以保持通用适配器的旧行为；具体游戏可覆写为更精确的判断。
     *
     * @param state 最新游戏状态快照
     * @return 当前行动窗口判断结果
     */
    default GameActionability evaluateActionability(GameStateSnapshot state) {
        return GameActionability.actionable("通用适配器默认认为当前状态可行动");
    }

    /**
     * 将游戏状态渲染为注入给 agent 的文本。
     *
     * @param state 游戏状态快照
     * @return 适合放入 prompt 的状态文本
     */
    default String renderStateForAgent(GameStateSnapshot state) {
        return state.rawJson();
    }

    /**
     * 将两份状态快照渲染成面向 agent 的操作结果差异。
     * <p>
     * 通用默认实现只比较 state_type；具体游戏适配器可以覆写为更有语义的白名单 diff。
     *
     * @param before 操作队列执行前状态
     * @param after  操作队列执行后状态
     * @return 适合放入 prompt 和复盘日志的状态差异文本
     */
    default String renderStateDiffForAgent(GameStateSnapshot before, GameStateSnapshot after) {
        String beforeType = before == null ? "" : before.stateType();
        String afterType = after == null ? "" : after.stateType();
        if (beforeType == null || beforeType.isBlank()) {
            beforeType = "(未知)";
        }
        if (afterType == null || afterType.isBlank()) {
            afterType = "(未知)";
        }
        if (beforeType.equals(afterType)) {
            return "- state_type: 无变化 (" + afterType + ")";
        }
        return "- state_type: " + beforeType + " -> " + afterType;
    }

    // ── 操作队列钩子（含合理默认实现） ──

    /**
     * 在操作入队前记录游戏语义信息。
     *
     * @param operation    agent 提交的原始操作
     * @param plannedState agent 做计划时看到的状态
     * @return 带有元数据的排队操作
     */
    default QueuedGameOperation prepareOperation(GameOperation operation, GameStateSnapshot plannedState) {
        return QueuedGameOperation.from(operation, plannedState);
    }

    /**
     * 将一批操作全部入队。子类可覆写以跨操作模拟状态变化（如手牌减少），
     * 确保每条操作的元数据（plannedCardName 等）基于前序操作执行后的预期状态。
     *
     * @param operations   agent 提交的原始操作列表
     * @param plannedState agent 做计划时看到的状态
     * @return 带有元数据的排队操作队列
     */
    default ArrayDeque<QueuedGameOperation> prepareBatch(List<GameOperation> operations, GameStateSnapshot plannedState) {
        ArrayDeque<QueuedGameOperation> queue = new ArrayDeque<>();
        for (GameOperation op : operations) {
            queue.offerLast(prepareOperation(op, plannedState));
        }
        return queue;
    }

    /**
     * 判断操作队列真正开始执行前是否需要重新读取一次游戏状态。
     * <p>
     * 默认复用 agent 决策时的状态以减少通用桥接层开销；对状态可能在模型思考期间自行推进的游戏，
     * 适配器应返回 true，让第一条 MCP 操作也基于执行前的最新状态做修复和校验。
     *
     * @return true 表示队列执行前需要从 MCP 重读状态
     */
    default boolean shouldRefreshStateBeforeDrain() {
        return false;
    }

    /**
     * 在操作真正执行前进行修复。
     *
     * @param operation    即将执行的排队操作
     * @param currentState 执行前最新状态
     * @return 修复后的 MCP 工具请求
     */
    default ToolExecutionRequest repairBeforeExecute(QueuedGameOperation operation, GameStateSnapshot currentState) {
        return operation.request();
    }

    /**
     * 在操作执行后判断剩余队列是否仍然可靠。
     * 默认不做任何操作（继续执行剩余队列）。
     *
     * @param operation   已执行的操作
     * @param beforeState 执行前状态
     * @param afterState  执行后状态
     * @param toolResult  MCP 工具返回文本
     * @param hasRemainingOperations true 表示当前队列后面还有未执行操作
     * @throws GameBridgeException 剩余队列不可靠，应当丢弃
     */
    default void monitorAfterExecute(QueuedGameOperation operation,
                                     GameStateSnapshot beforeState,
                                     GameStateSnapshot afterState,
                                     String toolResult,
                                     boolean hasRemainingOperations) {
    }

    /**
     * 从 MCP 工具返回值中提取业务错误。
     * <p>
     * 默认只识别通用 JSON 结构：{"status":"error","error":"..."}。
     * 连接失败、HTTP 异常等基础设施错误通常不会进入这里，而是在工具调用阶段直接抛异常。
     *
     * @param toolResult MCP 工具返回文本
     * @return 错误说明；不是业务错误时返回 null
     */
    default String extractToolError(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return null;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(toolResult);
            if ("error".equals(root.path("status").asText(""))) {
                return root.path("error").asText("未知错误");
            }
        } catch (Exception ignored) {
            // 非 JSON 返回值不是可恢复业务错误，由调用方按硬错误处理。
        }
        return null;
    }

    /**
     * 判断单条操作失败后是否可以丢弃该操作并继续执行队列。
     * <p>
     * 默认保守返回 false；具体游戏可基于最新状态判断当前行动窗口是否仍可靠。
     *
     * @param operation   失败的操作
     * @param beforeState 失败前状态
     * @param afterState  失败后重新获取到的最新状态
     * @param reason      失败原因
     * @return true 表示记录软错误并继续后续操作
     */
    default boolean shouldContinueAfterOperationFailure(QueuedGameOperation operation,
                                                       GameStateSnapshot beforeState,
                                                       GameStateSnapshot afterState,
                                                       String reason) {
        return false;
    }

    /**
     * 判断某个 MCP 工具是否是状态查询工具。
     *
     * @param toolName MCP 工具名
     * @param config   游戏 MCP 配置
     * @return true 表示该工具应由桥接层内部使用，不暴露给 agent 作为操作工具
     */
    default boolean isStateTool(String toolName, MCPProperties.GameMCPConfig config) {
        return toolName != null && toolName.equals(resolveStateToolName(config));
    }

    /**
     * 渲染可供 agent 提交到 operations 的 MCP 操作工具列表。
     *
     * @param tools  底层 MCP 工具集合
     * @param config 游戏 MCP 配置
     * @return 可用操作工具的说明文本
     */
    default String renderAvailableOperations(ToolProviderResult tools, MCPProperties.GameMCPConfig config) {
        return renderAvailableOperations(tools, config, null);
    }

    /**
     * 根据当前状态渲染可供 agent 提交到 operations 的 MCP 操作工具列表。
     * <p>
     * 默认实现只过滤状态工具；具体游戏可以根据 state_type 收窄工具列表，减少模型误选和 prompt 体积。
     *
     * @param tools  底层 MCP 工具集合
     * @param config 游戏 MCP 配置
     * @param state  最新游戏状态；为空时按通用规则渲染
     * @return 可用操作工具的说明文本
     */
    default String renderAvailableOperations(ToolProviderResult tools,
                                             MCPProperties.GameMCPConfig config,
                                             GameStateSnapshot state) {
        StringBuilder sb = new StringBuilder();
        tools.tools().keySet().stream()
                .filter(spec -> !isStateTool(spec.name(), config))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .forEach(spec -> sb.append("- ")
                        .append(spec.name())
                        .append(": ")
                        .append(spec.description() == null ? "" : spec.description())
                        .append("\n"));
        return sb.isEmpty() ? "(没有可用操作工具)" : sb.toString();
    }
}
