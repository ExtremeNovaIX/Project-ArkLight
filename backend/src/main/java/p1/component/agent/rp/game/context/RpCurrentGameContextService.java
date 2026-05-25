package p1.component.agent.rp.game.context;

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
import p1.config.mcp.MCPProperties;

/**
 * RP 侧当前游戏上下文读取服务。
 * <p>
 * 该服务只读取 MCP 最新状态，不参与队列执行。
 * 桥接层队列反馈可能含校验和中断细节，不进入 RP 人格上下文。
 */
@Service
@RequiredArgsConstructor
@Slf4j
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
            GameStateSnapshot state = adapter.fetchState(new GameAdapterContext(gameName, memoryId, tools, config));
            GameActionability actionability = adapter.evaluateActionability(state);
            return render(adapter, state, actionability);
        } catch (Exception e) {
            log.warn("[RP游戏状态] 获取当前游戏状态失败: game={}, session={}, reason={}",
                    gameName, sessionId, e.getMessage());
            return "<current_game_state>\nstate_error=" + e.getMessage() + "\n</current_game_state>";
        }
    }

    /**
     * 渲染 RP 侧游戏状态文本。
     *
     * @param adapter          当前游戏适配器
     * @param state            最新游戏状态
     * @param actionability    当前行动窗口判断
     * @return RP 状态上下文
     */
    private String render(GameAdapter adapter,
                          GameStateSnapshot state,
                          GameActionability actionability) {
        StringBuilder sb = new StringBuilder();
        sb.append("<current_game_state>\n")
                .append("actionability=").append(actionability.status()).append("\n")
                .append("actionability_reason=")
                .append(actionability.reason() == null ? "" : actionability.reason())
                .append("\n")
                .append("<latest_game_state>\n")
                .append(adapter.renderStateForAgent(state))
                .append("\n</latest_game_state>\n");
        sb.append("</current_game_state>");
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
