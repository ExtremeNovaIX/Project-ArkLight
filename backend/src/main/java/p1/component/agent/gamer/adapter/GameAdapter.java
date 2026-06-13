package p1.component.agent.gamer.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import p1.component.agent.gamer.adapter.core.GameActionWindowSignature;
import p1.component.agent.gamer.adapter.core.GameActionability;
import p1.component.agent.gamer.adapter.core.GameAdapterContext;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.adapter.core.GameOperationPrecondition;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.adapter.core.QueuedGameOperation;
import p1.config.mcp.MCPProperties;

import java.util.ArrayDeque;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 游戏适配器抽象父类。
 * <p>
 * 通用桥接层负责排队、逐条执行和转发 MCP 工具；具体游戏适配器负责解释游戏语义，
 * 例如如何获取状态、如何修复过期操作、以及什么时候应该中断剩余队列。
 */
public abstract class GameAdapter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 返回适配器 id。
     *
     * @return 配置中 mcp.games.*.adapter 使用的适配器标识
     */
    public abstract String id();

    /**
     * 判断该适配器是否支持当前游戏配置。
     *
     * @param gameName 游戏名
     * @param config   游戏 MCP 配置
     * @return true 表示当前适配器可用于该游戏
     */
    public boolean supports(String gameName, MCPProperties.GameMCPConfig config) {
        return id().equalsIgnoreCase(config.getAdapter());
    }

    /**
     * 构建状态工具参数。子类可覆写以传入特定参数（如 format=json）。
     *
     * @param context 适配器运行上下文
     * @return 状态工具参数 JSON
     */
    public String stateToolArguments(GameAdapterContext context) {
        return "{}";
    }

    /**
     * 解析状态工具在 MCP 工具列表中的实际名称。
     *
     * @param config 游戏 MCP 配置
     * @return MCP 工具列表中的实际状态工具名
     */
    public String resolveStateToolName(MCPProperties.GameMCPConfig config) {
        return config.getStateToolName();
    }

    /**
     * 从底层 MCP 获取最新游戏状态。具体游戏决定使用哪个状态工具、协议格式和检测策略。
     *
     * @param context 适配器运行上下文
     * @return 标准化后的游戏状态快照
     */
    public abstract GameStateSnapshot fetchState(GameAdapterContext context);

    /**
     * 根据已经获取到的游戏状态判断是否需要 RP 行动。
     *
     * @param state 最新游戏状态快照
     * @return 当前行动窗口判断结果
     */
    public GameActionability evaluateActionability(GameStateSnapshot state) {
        return GameActionability.actionable("通用适配器默认认为当前状态可行动");
    }

    /**
     * 将游戏状态渲染为注入给 RP 的文本。
     *
     * @param state 游戏状态快照
     * @return 适合放入 prompt 的状态文本
     */
    public String renderStateForAgent(GameStateSnapshot state) {
        return state.rawJson();
    }

    /**
     * 在操作入队前记录游戏语义信息。
     *
     * @param operation    agent 提交的原始操作
     * @param plannedState agent 做计划时看到的状态
     * @return 带有元数据的排队操作
     */
    public QueuedGameOperation prepareOperation(GameOperation operation, GameStateSnapshot plannedState) {
        return QueuedGameOperation.from(operation, plannedState);
    }

    /**
     * 将一批操作全部入队。
     *
     * @param context      适配器运行上下文
     * @param operations   agent 提交的原始操作列表
     * @param plannedState agent 做计划时看到的状态
     * @return 带有元数据的排队操作队列
     */
    public ArrayDeque<QueuedGameOperation> prepareBatch(GameAdapterContext context,
                                                        List<GameOperation> operations,
                                                        GameStateSnapshot plannedState) {
        ArrayDeque<QueuedGameOperation> queue = new ArrayDeque<>();
        for (GameOperation op : operations) {
            queue.offerLast(prepareOperation(op, plannedState));
        }
        return queue;
    }

    /**
     * 判断操作队列真正开始执行前是否需要重新读取一次游戏状态。
     *
     * @return true 表示队列执行前需要从 MCP 重读状态
     */
    public boolean shouldRefreshStateBeforeDrain() {
        return false;
    }

    /**
     * 返回当前状态对应的本地行动窗口签名。
     *
     * @param state 当前游戏状态
     * @return 行动窗口签名
     */
    public GameActionWindowSignature actionWindowSignature(GameStateSnapshot state) {
        return GameActionWindowSignature.unchecked();
    }

    /**
     * 检查单条操作在当前状态下是否仍满足执行前置条件。
     *
     * @param operation    即将执行的排队操作
     * @param currentState 执行前最新状态
     * @return 前置条件检查结果
     */
    public GameOperationPrecondition checkOperationPrecondition(QueuedGameOperation operation,
                                                                GameStateSnapshot currentState) {
        return GameOperationPrecondition.passed();
    }

    /**
     * 在操作真正执行前进行修复。
     *
     * @param context      适配器运行上下文
     * @param operation    即将执行的排队操作
     * @param currentState 执行前最新状态
     * @return 修复后的 MCP 工具请求
     */
    public ToolExecutionRequest repairBeforeExecute(GameAdapterContext context,
                                                    QueuedGameOperation operation,
                                                    GameStateSnapshot currentState) {
        return operation.request();
    }

    /**
     * 在操作执行后判断状态是否仍然可靠。
     *
     * @param context     适配器运行上下文
     * @param operation   已执行的操作
     * @param beforeState 执行前状态
     * @param afterState  执行后状态
     * @param toolResult  MCP 工具返回文本
     */
    public void monitorAfterExecute(GameAdapterContext context,
                                    QueuedGameOperation operation,
                                    GameStateSnapshot beforeState,
                                    GameStateSnapshot afterState,
                                    String toolResult) {
    }

    /**
     * 从 MCP 工具返回值中提取业务错误。
     *
     * @param context    适配器运行上下文
     * @param toolResult MCP 工具返回文本
     * @return 错误说明；不是业务错误时返回 null
     */
    public String extractToolError(GameAdapterContext context, String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(toolResult);
            if ("error".equals(root.path("status").asText(""))) {
                return root.path("error").asText("未知错误");
            }
        } catch (Exception ignored) {
            // 非 JSON 返回值不是可恢复业务错误，由调用方按硬错误处理。
        }
        return null;
    }

    /**
     * 判断某个 MCP 工具是否是状态查询工具。
     *
     * @param toolName MCP 工具名
     * @param context  适配器运行上下文
     * @return true 表示该工具应由桥接层内部使用，不暴露给 agent 作为操作工具
     */
    public boolean isStateTool(String toolName, GameAdapterContext context) {
        return toolName != null && toolName.equals(resolveStateToolName(context.config()));
    }

    /**
     * 根据当前状态渲染可供 agent 提交到 operations 的 MCP 操作工具列表。
     *
     * @param context 适配器运行上下文
     * @param state   最新游戏状态；为空时按通用规则渲染
     * @return 可用操作工具的说明文本
     */
    public String renderAvailableOperations(GameAdapterContext context, GameStateSnapshot state) {
        return renderGenericAvailableOperations(context, state);
    }

    /**
     * 通用操作工具渲染，只过滤状态工具。
     *
     * @param context 适配器运行上下文
     * @param state   最新游戏状态
     * @return 可用操作工具的说明文本
     */
    protected String renderGenericAvailableOperations(GameAdapterContext context, GameStateSnapshot state) {
        StringBuilder sb = new StringBuilder();
        context.tools().tools().keySet().stream()
                .filter(spec -> !isStateTool(spec.name(), context))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .forEach(spec -> sb.append("- ")
                        .append(spec.name())
                        .append(": ")
                        .append(spec.description() == null ? "" : spec.description())
                        .append("\n"));
        return sb.isEmpty() ? "(没有可用操作工具)" : sb.toString();
    }

    /**
     * 渲染给 RP 看的操作工具摘要。
     *
     * @param context 适配器运行上下文
     * @param state   最新游戏状态
     * @return 扁平工具名列表
     */
    public String renderAvailableOperationSummary(GameAdapterContext context, GameStateSnapshot state) {
        String rendered = renderAvailableOperations(context, state);
        String summary = rendered.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("- "))
                .map(line -> line.substring(2))
                .map(line -> {
                    int colon = line.indexOf(':');
                    return colon >= 0 ? line.substring(0, colon).trim() : line.trim();
                })
                .filter(name -> !name.isBlank())
                .map(name -> "- " + name)
                .collect(Collectors.joining("\n"));
        return summary.isBlank() ? "(没有可用操作工具)" : summary;
    }
}