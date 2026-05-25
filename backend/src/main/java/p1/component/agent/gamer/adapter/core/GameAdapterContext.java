package p1.component.agent.gamer.adapter.core;

import dev.langchain4j.service.tool.ToolProviderResult;
import p1.config.mcp.MCPProperties;

/**
 * 游戏适配器运行上下文。
 *
 * @param gameName  游戏名
 * @param sessionId LangChain4j 的会话记忆 id
 * @param tools     底层 MCP 工具集合
 * @param config    游戏 MCP 配置
 */
public record GameAdapterContext(
        String gameName,
        String sessionId,
        ToolProviderResult tools,
        MCPProperties.GameMCPConfig config
) {
}
