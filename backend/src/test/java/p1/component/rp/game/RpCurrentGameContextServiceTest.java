package p1.component.rp.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.GamerMCPClientFactory;
import p1.component.agent.gamer.adapter.GameAdapter;
import p1.component.agent.gamer.adapter.core.GameAdapterContext;
import p1.component.agent.gamer.adapter.core.GameAdapterRegistry;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.rp.game.context.RpCurrentGameContextService;
import p1.config.mcp.MCPProperties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RpCurrentGameContextServiceTest {

    @Test
    void shouldRenderRpVisibleMarkdownAfterActionsWithoutRawJson() throws Exception {
        MCPProperties properties = new MCPProperties();
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setEnabled(true);
        config.setAdapter("sts2");
        config.setStateToolName("get_game_state");
        config.setTips("- 优先根据当前状态行动\n- 抽牌、弃牌、领奖励等状态变化动作应放在本批末尾");
        properties.putGame("STS2MCP", config);

        GamerMCPClientFactory clientFactory = mock(GamerMCPClientFactory.class);
        ToolProvider toolProvider = mock(ToolProvider.class);
        when(clientFactory.getToolProvider("STS2MCP")).thenReturn(toolProvider);
        when(toolProvider.provideTools(any())).thenReturn(ToolProviderResult.builder().build());

        GameAdapter adapter = mock(GameAdapter.class);
        GameAdapterRegistry registry = mock(GameAdapterRegistry.class);
        when(registry.getAdapter(eq("STS2MCP"), eq(config))).thenReturn(adapter);

        ObjectMapper objectMapper = new ObjectMapper();
        GameStateSnapshot state = new GameStateSnapshot(
                "{\"state_type\":\"monster\",\"player\":{\"hand\":[{\"index\":0,\"name\":\"Strike\"}]}}",
                objectMapper.readTree("{\"state_type\":\"monster\",\"player\":{\"hand\":[{\"index\":0,\"name\":\"Strike\"}]}}"),
                "monster");
        when(adapter.fetchState(any(GameAdapterContext.class))).thenReturn(state);
        when(adapter.renderAvailableOperationSummary(any(GameAdapterContext.class), eq(state)))
                .thenReturn("- 打出卡牌\n- 结束回合");
        when(adapter.renderStateForAgent(state)).thenReturn("## 当前局面\n\n- state_type：monster\n\n## 本步决策重点\n\n- 当前可用能量：3");

        RpCurrentGameContextService service = new RpCurrentGameContextService(clientFactory, properties, registry);

        String rendered = service.build("STS2MCP", "game-session");

        assertTrue(rendered.contains("<current_game_state>"));
        assertTrue(rendered.contains("<available_actions>"));
        assertTrue(rendered.contains("- 打出卡牌"));
        assertTrue(rendered.contains("<tips>"));
        assertTrue(rendered.contains("- 优先根据当前状态行动"));
        assertTrue(rendered.contains("- 抽牌、弃牌、领奖励等状态变化动作应放在本批末尾"));
        assertTrue(rendered.contains("- 当前展示的伤害、格挡、费用、意图等数值已经是游戏在当前 buff/debuff 下计算后的结果。"));
        assertTrue(rendered.contains("<key_game_state_markdown>"));
        assertTrue(rendered.contains("## 本步决策重点"));
        assertTrue(rendered.indexOf("<key_game_state_markdown>") < rendered.indexOf("## 当前局面"));
        assertTrue(rendered.indexOf("## 当前局面") < rendered.indexOf("<tips>"));
        assertTrue(rendered.indexOf("</tips>") < rendered.indexOf("## 本步决策重点"));
        assertFalse(rendered.contains("<current_game_state_json>"));
        assertFalse(rendered.contains("\"hand\""));
        assertFalse(rendered.contains("\"index\""));
    }
}