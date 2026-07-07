package p1.component.agent.rp.game.context;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.component.agent.gamer.GameSessionKey;
import p1.component.agent.gamer.GamerMCPClientFactory;
import p1.component.agent.gamer.adapter.GameAdapter;
import p1.component.agent.gamer.adapter.core.GameAdapterContext;
import p1.component.agent.gamer.adapter.core.GameAdapterRegistry;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.config.mcp.MCPProperties;

/**
 * RP 侧当前游戏上下文读取服务。
 * <p>
 * 该服务只读取 MCP 最新状态，不参与队列执行。
 * 桥接层队列反馈可能含校验和中断细节，不进入 RP 人格上下文。
 */
@Service
@RequiredArgsConstructor
@CustomLog
public class RpCurrentGameContextService {

    private final GamerMCPClientFactory mcpClientFactory;
    private final MCPProperties mcpProperties;
    private final GameAdapterRegistry adapterRegistry;

    /**
     * 构建 RP 可见的当前游戏状态。
     * <p>
     * 读取失败时返回错误摘要，避免一次 MCP 状态读取失败阻塞 RP 对话。
     *
     * @param gameName  游戏名
     * @param sessionId 游戏侧会话 id
     * @return RP 可见的游戏状态上下文
     */
    public String build(String gameName, String sessionId) {
        String memoryId = GameSessionKey.of(gameName, sessionId);
        try {
            MCPProperties.GameMCPConfig config = requireConfig(gameName);
            GameAdapter adapter = adapterRegistry.getAdapter(gameName, config);
            ToolProvider rawProvider = mcpClientFactory.getToolProvider(gameName);
            ToolProviderResult tools = rawProvider.provideTools(
                    new ToolProviderRequest(memoryId, UserMessage.from("rp game state context")));

            // RP 只读取当前局面。上一批队列结果含有校验、中断和工具细节，不进入人格侧上下文。
            GameAdapterContext context = new GameAdapterContext(gameName, memoryId, tools, config);
            GameStateSnapshot state = adapter.fetchState(context);
            String actionSummary = adapter.renderAvailableOperationSummary(context, state);
            String stateSummary = adapter.renderStateForAgent(state);
            return render(actionSummary, adapter.tips(), stateSummary);
        } catch (Exception e) {
            log.warn(LogDomain.GAME, "game.context_render_failed", LogOutcome.DEGRADED, "gameName", gameName, "sessionId", sessionId, "reason", e.getMessage());
            return "<current_game_state>\nstate_error=" + e.getMessage() + "\n</current_game_state>";
        }
    }

    /**
     * 渲染 RP 侧游戏状态文本。
     *
     * @param actionSummary RP 可见的中文动作摘要
     * @param stateSummary  RP 可见的关键局面 Markdown
     * @return RP 状态上下文
     */
    private String render(String actionSummary, String tips, String stateSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("<current_game_state>\n")
                .append("<available_actions>\n")
                .append(actionSummary == null || actionSummary.isBlank() ? "(没有可用动作)" : actionSummary)
                .append("\n</available_actions>\n")
                .append("<key_game_state_markdown>\n")
                .append(insertTipsBeforeDecisionFocus(stateSummary, tips))
                .append("\n</key_game_state_markdown>\n");
        sb.append("</current_game_state>");
        return sb.toString();
    }

    private String insertTipsBeforeDecisionFocus(String stateSummary, String tips) {
        String renderedState = stateSummary == null || stateSummary.isBlank() ? "(未能获取 STS2 状态)" : stateSummary;
        String tipsBlock = renderTips(tips);
        int decisionFocusStart = renderedState.indexOf("## 本步决策重点");
        if (decisionFocusStart < 0) {
            return renderedState.stripTrailing() + "\n\n" + tipsBlock;
        }
        String beforeDecisionFocus = renderedState.substring(0, decisionFocusStart).stripTrailing();
        String decisionFocus = renderedState.substring(decisionFocusStart).stripLeading();
        return beforeDecisionFocus + "\n\n" + tipsBlock + "\n" + decisionFocus;
    }

    private String renderTips(String tips) {
        StringBuilder sb = new StringBuilder();
        sb.append("<tips>\n");
        if (tips != null && !tips.isBlank()) {
            sb.append(tips.trim()).append("\n");
        }
        sb.append("</tips>\n");
        return sb.toString();
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
