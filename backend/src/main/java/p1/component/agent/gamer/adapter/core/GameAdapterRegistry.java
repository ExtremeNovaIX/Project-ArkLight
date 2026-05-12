package p1.component.agent.gamer.adapter.core;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.adapter.GameAdapter;
import p1.config.mcp.MCPProperties;

import java.util.List;

/**
 * 游戏适配器注册表。
 * <p>
 * Spring 会注入所有 GameAdapter 实现，本类负责按游戏配置选择具体适配器。
 */
@Component
@RequiredArgsConstructor
public class GameAdapterRegistry {

    private final List<GameAdapter> adapters;

    /**
     * 根据游戏配置选择适配器。
     *
     * @param gameName 游戏名
     * @param config   游戏 MCP 配置
     * @return 匹配的游戏适配器
     * @throws IllegalStateException 未找到匹配的适配器
     */
    public GameAdapter getAdapter(String gameName, MCPProperties.GameMCPConfig config) {
        return adapters.stream()
                .filter(adapter -> adapter.supports(gameName, config))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(String.format("未找到%s GameAdapter", gameName)));
    }
}
