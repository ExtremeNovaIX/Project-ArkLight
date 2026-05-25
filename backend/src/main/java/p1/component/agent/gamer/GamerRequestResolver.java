package p1.component.agent.gamer;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import p1.config.mcp.GamerProperties;
import p1.config.mcp.MCPProperties;

import java.util.List;
import java.util.Map;

/**
 * 将可选请求参数解析为明确的游戏和会话目标。
 */
@Component
@RequiredArgsConstructor
public class GamerRequestResolver {

    private final GamerProperties gamerProperties;
    private final MCPProperties mcpProperties;

    public String resolveGameName(String requestedGameName) {
        if (StringUtils.hasText(requestedGameName)) {
            return requestedGameName.trim();
        }
        if (StringUtils.hasText(gamerProperties.getDefaultGameName())) {
            return gamerProperties.getDefaultGameName().trim();
        }

        List<String> enabledGames = mcpProperties.getGames().entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().isEnabled())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        if (enabledGames.size() == 1) {
            return enabledGames.getFirst();
        }
        if (enabledGames.isEmpty()) {
            throw new IllegalArgumentException("未配置任何已启用的 MCP 游戏服务器，请先注册或放入 mcp-servers 目录。");
        }
        throw new IllegalArgumentException("请求缺少 gameName，且当前有多个已启用 MCP 游戏服务器: "
                + String.join(", ", enabledGames));
    }

    public String resolveSessionId(String requestedSessionId) {
        if (StringUtils.hasText(requestedSessionId)) {
            return requestedSessionId.trim();
        }
        if (StringUtils.hasText(gamerProperties.getDefaultSessionId())) {
            return gamerProperties.getDefaultSessionId().trim();
        }
        return "default";
    }
}
