package p1.component.agent.gamer.bridge;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import p1.component.agent.gamer.GameSessionKey;
import p1.component.agent.gamer.GamerMCPClientFactory;
import p1.component.agent.gamer.adapter.GameAdapter;
import p1.component.agent.gamer.adapter.core.*;
import p1.component.agent.gamer.bridge.queue.GameOperationQueueProcessor;
import p1.component.agent.gamer.interrupt.GameInterruptRequest;
import p1.component.agent.gamer.interrupt.GameInterruptService;
import p1.component.agent.gamer.memory.GamerWorkingMemoryService;
import p1.config.mcp.MCPProperties;

/**
 * gameAgent和MCP Server的桥接服务。
 * <p>
 * 该服务负责在每次 agent 决策前注入最新游戏状态，并把流式 ACTION JSON 转发到底层 MCP 工具。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GameBridgeService {

    private final GamerMCPClientFactory mcpClientFactory;
    private final MCPProperties mcpProperties;
    private final GameAdapterRegistry adapterRegistry;
    private final GameOperationQueueProcessor queueProcessor;
    private final GamerWorkingMemoryService workingMemoryService;
    private final GameInterruptService interruptService;

    /**
     * 构建注入给 agent 的上下文。
     *
     * @param gameName    游戏名
     * @param sessionId   用户侧会话 id
     * @param userMessage 外层调用传入的用户指令
     * @return 包含桥接规则、最新状态、可用操作和用户指令的文本
     */
    public String buildAgentContext(String gameName, String sessionId, String userMessage) {
        String memoryId = GameSessionKey.of(gameName, sessionId);
        MCPProperties.GameMCPConfig config = requireConfig(gameName);
        GameAdapter adapter = adapterRegistry.getAdapter(gameName, config);
        ToolProvider rawProvider = mcpClientFactory.getToolProvider(gameName);

        ToolProviderResult tools = rawProvider.provideTools(new ToolProviderRequest(memoryId, UserMessage.from(userMessage)));

        // 每次构建新上下文前清掉上一轮状态，避免本轮 agent 没有调用队列工具时读到旧结果。
        queueProcessor.clearLastStatus(memoryId);

        // 状态只由桥接层主动获取，并直接注入给 agent
        GameStateSnapshot state = adapter.fetchState(new GameAdapterContext(gameName, memoryId, tools, config));
        queueProcessor.rememberPlanningState(memoryId, state);
        workingMemoryService.observeState(gameName, memoryId, state);

        // 上一轮队列中断原因会随最新状态一并注入，要求 agent 放弃旧计划重新决策。
        String notice = queueProcessor.consumeNotice(memoryId);
        String rpInstruction = interruptService.consume(memoryId)
                .map(GameInterruptRequest::instruction)
                .orElse("");
        String lastActionResult = queueProcessor.peekLastActionResult(memoryId);
        String workingMemory = workingMemoryService.renderMemory(gameName, memoryId);
        return renderAgentContext(adapter, config, tools, state, notice, rpInstruction, lastActionResult, workingMemory, userMessage);
    }

    /**
     * 渲染注入给 gamer agent 的完整动态上下文。
     *
     * @param adapter          当前游戏适配器
     * @param config           当前游戏 MCP 配置
     * @param tools            当前 MCP 工具集合
     * @param state            最新游戏状态
     * @param notice           桥接层提示
     * @param rpInstruction    RP 提交的新游戏意图
     * @param lastActionResult 上一批操作结果
     * @param workingMemory    gamer 工作记忆
     * @param userMessage      本轮用户指令
     * @return 面向 gamer agent 的上下文文本
     */
    private String renderAgentContext(GameAdapter adapter,
                                      MCPProperties.GameMCPConfig config,
                                      ToolProviderResult tools,
                                      GameStateSnapshot state,
                                      String notice,
                                      String rpInstruction,
                                      String lastActionResult,
                                      String workingMemory,
                                      String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("<bridge_rules>\n")
                .append("- 系统已经注入最新游戏状态，不要调用任何状态查询工具。\n")
                .append("- latest_game_state 是游戏 MCP 返回的源 JSON；优先直接读取其中的原生字段。\n")
                .append("- 游戏操作必须输出 ACTION JSON，一次提交一个候选操作队列；关键战术分叉可输出 ASK JSON 向用户确认。\n")
                .append("- operations 中的每一项使用下方列出的 MCP 工具名和参数；桥接层会逐条执行。\n")
                .append("- MCP 业务失败后，如果最新状态仍可行动，桥接层会记录错误、跳过失败操作并继续剩余队列。\n")
                .append("- state_type 变化、进入新界面、抽牌/弃牌导致手牌不可预测变化时，桥接层会中断队列并丢弃剩余操作。\n")
                .append("- 如果桥接层报告队列中断，立即基于 latest_game_state 重新决策，不要沿用旧队列。\n")
                .append("- 抽牌、弃牌、随机、领取奖励、打开选择界面等会改变行动窗口的操作应放在队列末尾。\n")
                .append("- gamer_memory 只是历史决策摘要，不能覆盖 latest_game_state 中的当前事实。\n")
                .append("</bridge_rules>\n\n");
        sb.append("<streaming_output_rules>\n")
                .append("- JSON 对象只能是 ACTION 或 ASK；同一稳定行动窗口内尽量一次提交完整确定队列。\n")
                .append("- ACTION 必须包含 operations；ASK 不包含 operations，用于关键战术分叉时直接向用户提出短问题。\n")
                .append("- 只有用户偏好、关键资源消耗、隐藏信息或高风险路线选择会明显改变结果时才 ASK；普通可判断局面必须直接 ACTION。\n")
                .append("- ASK JSON 格式：{\"type\":\"ask\",\"question\":\"这回合稳还是赌？\",\"choices\":[\"稳一点\",\"赌一波\",\"你判断\"],\"reason\":\"赌成功能击杀，失败会亏防御资源。\",\"default_choice\":\"稳一点\"}\n")
                .append("- ACTION 必须包含 expression；这是给 RP 人格消化的内心活动候选，不是给用户照读的台词。ASK 不需要 expression。\n")
                .append("- expression 要诚实表达这一步有没有想开口的冲动，不要因为它未必会被说出口就主动压低分数；真正发言频率由 RP 层控制。\n")
                .append("- expression.score 为 0-100；普通操作 0-30，策略转向 45-65，风险/改计划/失误/关键选择 70-90。\n")
                .append("- expression.inner_thought 必须是自然内心活动，避免写工具名、JSON、桥接层、MCP、日志等技术细节。\n")
                .append("- 抽牌、弃牌、随机生成、打开选择界面、确认选择、领取奖励等会改变状态的操作必须放在当前 JSON 的最后。\n")
                .append("- JSON 输出后可以继续输出下一个 JSON；不要使用 Markdown 代码块。\n")
                .append("- 示例只展示格式，实际 tool/args 必须来自 available_operations/latest_game_state：\n")
                .append("{\"type\":\"action\",\"status\":\"CONTINUE\",\"summary\":\"先执行确定收益操作\",\"operations\":[{\"tool\":\"combat_play_card\",\"args\":{\"card\":\"痛击\",\"target\":\"ENEMY_0\"},\"note\":\"先上易伤\"}],\"expression\":{\"score\":35,\"type\":\"self_talk\",\"urgency\":\"defer\",\"inner_thought\":\"先把易伤挂上，后面的伤害才更稳。\",\"reason\":\"这是本次行动的主要收益点\"}}\n")
                .append("</streaming_output_rules>\n\n");
        if (notice != null && !notice.isBlank()) {
            sb.append("<bridge_notice>\n").append(notice).append("\n</bridge_notice>\n\n");
        }
        if (!rpInstruction.isBlank()) {
            sb.append("<external_game_instruction>\n")
                    .append("外部交互通道提交了新的游戏意图或系统提示：")
                    .append(rpInstruction)
                    .append("\n请优先遵守该提示，并基于 latest_game_state 重新规划；不要沿用被打断的旧队列。")
                    .append("\n</external_game_instruction>\n\n");
        }
        if (lastActionResult != null && !lastActionResult.isBlank()) {
            sb.append("<last_action_result>\n")
                    .append(lastActionResult)
                    .append("\n</last_action_result>\n\n");
        }
        sb.append("<gamer_memory>\n")
                .append(workingMemory)
                .append("\n</gamer_memory>\n\n")
                .append("<latest_game_state>\n")
                .append(adapter.renderStateForAgent(state))
                .append("\n</latest_game_state>\n\n")
                .append("<available_operations>\n")
                .append(adapter.renderAvailableOperations(tools, config, state))
                .append("</available_operations>\n\n")
                .append("<user_instruction>\n")
                .append(userMessage == null ? "" : userMessage)
                .append("\n</user_instruction>");
        return sb.toString();
    }

    /**
     * 执行流式解析得到的操作队列。
     * <p>
     * 流式路径不经过 LangChain4j tool calling，但仍复用同一个队列处理器，
     * 因此修复、软错误、状态监视和中断逻辑保持一致。
     *
     * @param gameName     游戏名
     * @param sessionId    用户侧会话 id
     * @param rawArguments enqueue_operations 兼容 JSON 参数
     * @return 队列处理器返回的执行结果文本
     */
    public String executeOperationQueue(String gameName, String sessionId, String rawArguments) {
        String memoryId = GameSessionKey.of(gameName, sessionId);
        MCPProperties.GameMCPConfig config = requireConfig(gameName);
        GameAdapter adapter = adapterRegistry.getAdapter(gameName, config);
        ToolProvider rawProvider = mcpClientFactory.getToolProvider(gameName);
        ToolProviderResult tools = rawProvider.provideTools(new ToolProviderRequest(memoryId, UserMessage.from("streaming operation dispatch")));
        return queueProcessor.enqueueAndDrain(gameName, memoryId, adapter, config, tools, rawArguments);
    }

    /**
     * 探测当前游戏会话是否需要 agent 行动。
     * <p>
     * 桥接层统一负责获取最新 MCP 状态；适配器只负责解释这份状态是否可行动。
     *
     * @param gameName  游戏名
     * @param sessionId 用户侧会话 id
     * @return 当前行动窗口判断结果
     */
    public GameActionability probeActionability(String gameName, String sessionId) {
        String memoryId = GameSessionKey.of(gameName, sessionId);
        MCPProperties.GameMCPConfig config = requireConfig(gameName);
        GameAdapter adapter = adapterRegistry.getAdapter(gameName, config);
        ToolProvider rawProvider = mcpClientFactory.getToolProvider(gameName);
        ToolProviderResult tools = rawProvider.provideTools(new ToolProviderRequest(memoryId, UserMessage.from("probe game actionability")));
        GameStateSnapshot state = adapter.fetchState(new GameAdapterContext(gameName, memoryId, tools, config));
        return adapter.evaluateActionability(state);
    }

    /**
     * 查询本轮 agent 是否通过桥接工具提交了结构化状态。
     *
     * @param gameName  游戏名
     * @param sessionId 用户侧会话 id
     * @return 最近一次 enqueue_operations.status；本轮未提交时返回 UNKNOWN
     */
    public GameBridgeActionStatus lastActionStatus(String gameName, String sessionId) {
        return queueProcessor.lastStatus(GameSessionKey.of(gameName, sessionId));
    }

    /**
     * 获取并校验游戏 MCP 配置。
     *
     * @param gameName 游戏名
     * @return 已启用的游戏 MCP 配置
     */
    private MCPProperties.GameMCPConfig requireConfig(String gameName) {
        MCPProperties.GameMCPConfig config = mcpProperties.getGames().get(gameName);
        if (config == null || !config.isEnabled()) {
            throw new IllegalArgumentException("游戏未配置 MCP: " + gameName);
        }
        return config;
    }
}
