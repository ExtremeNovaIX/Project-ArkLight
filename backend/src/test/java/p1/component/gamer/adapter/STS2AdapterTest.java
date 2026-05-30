package p1.component.gamer.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.adapter.core.GameBridgeException;
import p1.component.agent.gamer.adapter.core.GameAdapterContext;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.adapter.core.QueuedGameOperation;
import p1.component.agent.gamer.adapter.STS2Adapter;
import p1.config.mcp.MCPProperties;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class STS2AdapterTest {

    private final STS2Adapter adapter = new STS2Adapter();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRepairPlayCardIndexByCardName() throws Exception {
        GameStateSnapshot planned = state("""
                {
                  "state_type":"monster",
                  "player":{"hand":[
                    {"name":"打击"},
                    {"name":"防御"},
                    {"name":"中和"}
                  ]}
                }
                """);
        GameStateSnapshot current = state("""
                {
                  "state_type":"monster",
                  "player":{"hand":[
                    {"name":"打击"},
                    {"name":"中和"}
                  ]}
                }
                """);

        GameOperation operation = new GameOperation(
                "combat_play_card",
                objectMapper.readTree("{\"card_index\":2,\"target\":\"NIBBIT_0\"}"),
                "打出中和"
        );
        QueuedGameOperation queued = adapter.prepareOperation(operation, planned);

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(queued, current);

        assertEquals("{\"card_index\":1,\"target\":\"NIBBIT_0\"}", repaired.arguments());
    }

    @Test
    void shouldTranslateCardNameToCurrentCardIndex() throws Exception {
        GameStateSnapshot planned = state("""
                {
                  "state_type":"monster",
                  "player":{"hand":[
                    {"name":"打击"},
                    {"name":"防御"}
                  ]}
                }
                """);
        GameStateSnapshot current = state("""
                {
                  "state_type":"monster",
                  "player":{"hand":[
                    {"name":"防御"},
                    {"name":"打击"}
                  ]}
                }
                """);

        GameOperation operation = new GameOperation(
                "combat_play_card",
                objectMapper.readTree("{\"card\":\"打击\",\"target\":\"NIBBIT_0\"}"),
                "按牌名打出打击"
        );
        QueuedGameOperation queued = adapter.prepareOperation(operation, planned);

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(queued, current);

        assertEquals(
                objectMapper.readTree("{\"target\":\"NIBBIT_0\",\"card_index\":1}"),
                objectMapper.readTree(repaired.arguments())
        );
    }

    @Test
    void shouldDropTargetWhenPlayingSelfTargetCard() throws Exception {
        GameStateSnapshot planned = state("""
                {
                  "state_type":"monster",
                  "player":{"hand":[
                    {"name":"防御","target_type":"Self"}
                  ]}
                }
                """);
        GameStateSnapshot current = state("""
                {
                  "state_type":"monster",
                  "player":{"hand":[
                    {"name":"防御","target_type":"Self"}
                  ]}
                }
                """);

        GameOperation operation = new GameOperation(
                "combat_play_card",
                objectMapper.readTree("{\"card\":\"防御\",\"target\":\"PLAYER_0\"}"),
                "打出防御"
        );
        QueuedGameOperation queued = adapter.prepareOperation(operation, planned);

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(queued, current);

        assertEquals(
                objectMapper.readTree("{\"card_index\":0}"),
                objectMapper.readTree(repaired.arguments())
        );
    }

    @Test
    void shouldRenderRawSourceStateForAgent() throws Exception {
        GameStateSnapshot state = state("""
                {
                  "state_type":"card_select",
                  "battle":{"turn":"player","is_play_phase":true,"enemies":[]},
                  "player":{"hp":60,"max_hp":70,"energy":3,"max_energy":3,"hand":[
                    {"index":0,"name":"打击","cost":"1","description":"造成6点伤害。"},
                    {"index":1,"name":"打击","cost":"1","description":"造成6点伤害。"},
                    {"index":2,"name":"防御","cost":"1","description":"获得5点格挡。"},
                    {"index":3,"name":"贪婪","cost":"0","description":"不能被打出。永恒。","keywords":[
                      {"name":"永恒","description":"无法从你的牌组中移除或变化。"}
                    ]}
                  ]},
                  "card_select":{
                    "type":"discard",
                    "cards":[
                      {"name":"打击","cost":"1","description":"造成6点伤害。"},
                      {"name":"防御","cost":"1","description":"获得5点格挡。"}
                    ]
                  }
                }
                """);

        String rendered = adapter.renderStateForAgent(state);

        assertEquals(state.json(), objectMapper.readTree(rendered));
        assertEquals(0, objectMapper.readTree(rendered).path("player").path("hand").path(0).path("index").asInt());
        assertEquals("永恒", objectMapper.readTree(rendered)
                .path("player").path("hand").path(3).path("keywords").path(0).path("name").asText());
        assertTrue(rendered.contains("\"card_select\""));
        assertFalse(rendered.contains("state.type=card_select"));
        assertFalse(rendered.contains("card_select.cards:"));
    }

    @Test
    void shouldKeepOverBudgetPlayForMcpJudgement() throws Exception {
        GameStateSnapshot planned = state("""
                {
                  "state_type":"monster",
                  "player":{"energy":3,"hand":[
                    {"name":"痛击","cost":"2","description":"造成8点伤害。给予2层易伤。"},
                    {"name":"打击","cost":"1","description":"造成6点伤害。"},
                    {"name":"防御","cost":"1","description":"获得5点格挡。"}
                  ]}
                }
                """);
        List<GameOperation> operations = List.of(
                new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"痛击\"}"), "2费上易伤"),
                new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"打击\"}"), "1费输出"),
                new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"防御\"}"), "超费防御"),
                new GameOperation("combat_end_turn", objectMapper.readTree("{}"), "结束回合")
        );

        ArrayDeque<QueuedGameOperation> queue = adapter.prepareBatch(operations, planned);
        List<QueuedGameOperation> prepared = new ArrayList<>(queue);

        assertEquals(4, prepared.size());
        assertEquals("痛击", objectMapper.readTree(prepared.get(0).request().arguments()).path("card").asText());
        assertEquals("打击", objectMapper.readTree(prepared.get(1).request().arguments()).path("card").asText());
        assertEquals("防御", objectMapper.readTree(prepared.get(2).request().arguments()).path("card").asText());
        assertEquals("combat_end_turn", prepared.get(3).request().name());
    }

    @Test
    void shouldKeepAllPlayOperationsWithoutEnergyPreValidation() throws Exception {
        GameStateSnapshot planned = state("""
                {
                  "state_type":"monster",
                  "player":{"energy":3,"hand":[
                    {"name":"放血","cost":"0","description":"失去3点生命。获得2点能量。"},
                    {"name":"痛击","cost":"2","description":"造成8点伤害。给予2层易伤。"},
                    {"name":"打击","cost":"1","description":"造成6点伤害。"},
                    {"name":"头槌","cost":"1","description":"造成9点伤害。将弃牌堆中的1张牌放到抽牌堆顶。"}
                  ]}
                }
                """);
        List<GameOperation> operations = List.of(
                new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"放血\"}"), "0费回能"),
                new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"痛击\"}"), "2费攻击"),
                new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"打击\"}"), "1费攻击"),
                new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"头槌\"}"), "1费攻击")
        );

        ArrayDeque<QueuedGameOperation> queue = adapter.prepareBatch(operations, planned);

        assertEquals(4, queue.size());
    }

    @Test
    void shouldNarrowAvailableOperationsByStateType() throws Exception {
        ToolProviderResult tools = tools(
                "get_game_state",
                "combat_play_card",
                "combat_end_turn",
                "combat_select_card",
                "combat_confirm_selection",
                "rewards_pick_card",
                "deck_select_card",
                "proceed_to_map"
        );
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setStateToolName("get_game_state");

        String combatTools = adapter.renderAvailableOperations(
                tools, config, state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}"));
        String rewardTools = adapter.renderAvailableOperations(
                tools, config, state("{\"state_type\":\"card_reward\"}"));
        String selectTools = adapter.renderAvailableOperations(
                tools, config, state("{\"state_type\":\"card_select\",\"card_select\":{\"cards\":[]}}"));

        assertTrue(combatTools.contains("combat_play_card"));
        assertTrue(combatTools.contains("combat_end_turn"));
        assertFalse(combatTools.contains("combat_select_card"));
        assertFalse(combatTools.contains("combat_confirm_selection"));
        assertFalse(combatTools.contains("rewards_pick_card"));
        assertFalse(combatTools.contains("deck_select_card"));

        assertTrue(rewardTools.contains("rewards_pick_card"));
        assertFalse(rewardTools.contains("combat_play_card"));

        assertTrue(selectTools.contains("deck_select_card"));
        assertFalse(selectTools.contains("combat_play_card"));
        assertFalse(selectTools.contains("combat_select_card"));
        assertFalse(selectTools.contains("combat_confirm_selection"));
        assertFalse(selectTools.contains("rewards_pick_card"));
    }

    @Test
    void shouldExposeDeckSelectionForMultiplayerCardSelectWithBattle() throws Exception {
        String cardSelectJson = """
                {
                  "game_mode":"multiplayer",
                  "state_type":"card_select",
                  "battle":{"turn":"player","is_play_phase":true},
                  "card_select":{
                    "screen_type":"simple_select",
                    "prompt":"选择一张牌放到你的抽牌堆顶。",
                    "cards":[{"name":"痛击","index":0}]
                  }
                }
                """;
        ToolProviderResult tools = toolsWithResponses(Map.of(
                "get_game_state", cardSelectJson,
                "mp_get_game_state", cardSelectJson,
                "deck_select_card", "{}",
                "mp_combat_select_card", "{}",
                "mp_combat_confirm_selection", "{}",
                "mp_combat_play_card", "{}",
                "mp_combat_end_turn", "{}"
        ));
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setStateToolName("get_game_state");

        GameStateSnapshot cardSelect = adapter.fetchState(new GameAdapterContext("STS2MCP", "session", tools, config));
        String availableOperations = adapter.renderAvailableOperations(tools, config, cardSelect);

        assertTrue(availableOperations.contains("- deck_select_card:"));
        assertFalse(availableOperations.contains("- mp_combat_select_card:"));
        assertFalse(availableOperations.contains("- mp_combat_confirm_selection:"));
        assertFalse(availableOperations.contains("- mp_combat_play_card:"));
        assertFalse(availableOperations.contains("- mp_combat_end_turn:"));
    }

    @Test
    void shouldTreatEliteBattleAsCombatPlayWindow() throws Exception {
        String eliteBattleJson = """
                {
                  "game_mode":"multiplayer",
                  "state_type":"elite",
                  "battle":{"round":1,"turn":"player","is_play_phase":true},
                  "player":{"energy":0,"hand":[]}
                }
                """;
        ToolProviderResult tools = toolsWithResponses(Map.of(
                "get_game_state", eliteBattleJson,
                "mp_get_game_state", eliteBattleJson,
                "mp_combat_play_card", "{}",
                "mp_combat_end_turn", "{}",
                "mp_event_choose_option", "{}",
                "mp_map_vote", "{}"
        ));
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setStateToolName("get_game_state");
        GameStateSnapshot eliteBattle = state(eliteBattleJson);
        QueuedGameOperation endTurn = adapter.prepareOperation(
                new GameOperation("mp_combat_end_turn", objectMapper.readTree("{}"), "提交结束回合投票"),
                eliteBattle
        );

        assertDoesNotThrow(() -> adapter.repairBeforeExecute(endTurn, eliteBattle));

        adapter.fetchState(new GameAdapterContext("STS2MCP", "session", tools, config));
        String availableOperations = adapter.renderAvailableOperations(tools, config, eliteBattle);
        assertTrue(availableOperations.contains("- mp_combat_end_turn:"));
        assertTrue(availableOperations.contains("- mp_combat_play_card:"));
        assertFalse(availableOperations.contains("- mp_event_choose_option:"));
        assertFalse(availableOperations.contains("- mp_map_vote:"));
    }

    @Test
    void shouldDetectMultiplayerLobbyFromSingleplayerState() throws Exception {
        ToolProviderResult tools = toolsWithResponses(Map.of(
                "get_game_state", """
                        {
                          "state_type":"menu",
                          "menu_screen":"character_select",
                          "lobby":{
                            "type":"client",
                            "game_mode":"standard",
                            "player_count":2,
                            "all_ready":false,
                            "players":[{"is_local":true,"character_id":"IRONCLAD"}]
                          },
                          "options":[
                            {"name":"IRONCLAD","enabled":true},
                            {"name":"embark","enabled":true},
                            {"name":"unready","enabled":false}
                          ]
                        }
                        """,
                "mp_get_game_state", "{\"status\":\"error\",\"message\":\"Not in a multiplayer run.\"}",
                "menu_select", "{}",
                "event_choose_option", "{}",
                "mp_event_choose_option", "{}"
        ));
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setStateToolName("get_game_state");

        GameStateSnapshot fetched = adapter.fetchState(new GameAdapterContext("STS2MCP", "session", tools, config));
        String renderedEventTools = adapter.renderAvailableOperations(tools, config, state("{\"state_type\":\"event\"}"));

        assertEquals("menu", fetched.stateType());
        assertTrue(renderedEventTools.contains("- mp_event_choose_option:"));
        assertFalse(renderedEventTools.contains("- event_choose_option:"));
        assertFalse(renderedEventTools.contains("- menu_select:"));
    }

    @Test
    void shouldRejectStaleMenuSelectWhenStateAlreadyAdvanced() throws Exception {
        QueuedGameOperation operation = QueuedGameOperation.from(
                new GameOperation("menu_select", objectMapper.readTree("{\"option\":\"embark\"}"), "开始游戏"),
                state("{\"state_type\":\"menu\"}")
        );

        GameBridgeException ex = assertThrows(GameBridgeException.class,
                () -> adapter.repairBeforeExecute(operation, state("{\"state_type\":\"event\"}")));

        assertTrue(ex.getMessage().contains("菜单操作已过期"));
    }

    @Test
    void shouldSoftContinueOnlyWhenStillCombatPlayWindow() throws Exception {
        QueuedGameOperation operation = QueuedGameOperation.from(
                new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"防御\"}"), "尝试出牌"),
                state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true},\"player\":{\"hand\":[]}}")
        );

        assertTrue(adapter.shouldContinueAfterOperationFailure(
                operation,
                state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}"),
                state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}"),
                "MCP 工具执行失败: EnergyCostTooHigh"
        ));
        assertFalse(adapter.shouldContinueAfterOperationFailure(
                operation,
                state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}"),
                state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"enemy\",\"is_play_phase\":false}}"),
                "MCP 工具执行失败: EnergyCostTooHigh"
        ));
        assertFalse(adapter.shouldContinueAfterOperationFailure(
                operation,
                state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}"),
                state("{\"state_type\":\"rewards\"}"),
                "MCP 工具执行失败: EnergyCostTooHigh"
        ));
    }

    @Test
    void shouldRejectEndTurnFromPreviousCombatRound() throws Exception {
        GameStateSnapshot planned = state("""
                {"state_type":"monster","battle":{"round":3,"turn":"player","is_play_phase":true}}
                """);
        GameStateSnapshot current = state("""
                {"state_type":"monster","battle":{"round":4,"turn":"player","is_play_phase":true}}
                """);
        QueuedGameOperation queued = adapter.prepareOperation(
                new GameOperation("mp_combat_end_turn", objectMapper.readTree("{}"), "旧回合结束指令"),
                planned
        );

        GameBridgeException ex = assertThrows(
                GameBridgeException.class,
                () -> adapter.repairBeforeExecute(queued, current)
        );

        assertTrue(ex.getMessage().contains("结束回合操作已过期"));
        assertFalse(adapter.shouldContinueAfterOperationFailure(
                queued,
                planned,
                current,
                "adapter执行指令修复失败: " + ex.getMessage()
        ));
    }

    @Test
    void shouldThrowWhenPlannedCardIsGone() throws Exception {
        GameStateSnapshot planned = state("""
                {"state_type":"monster","player":{"hand":[{"name":"打击"},{"name":"防御"}]}}
                """);
        GameStateSnapshot current = state("""
                {"state_type":"monster","player":{"hand":[{"name":"打击"}]}}
                """);
        GameOperation operation = new GameOperation(
                "combat_play_card",
                objectMapper.readTree("{\"card_index\":1}"),
                "打出防御"
        );
        QueuedGameOperation queued = adapter.prepareOperation(operation, planned);

        assertThrows(GameBridgeException.class, () -> adapter.repairBeforeExecute(queued, current));
    }

    @Test
    void shouldInterruptWhenStateTypeChanges() throws Exception {
        QueuedGameOperation operation = QueuedGameOperation.from(
                new GameOperation("combat_end_turn", objectMapper.readTree("{}"), ""),
                state("{\"state_type\":\"monster\",\"player\":{\"hand\":[]}}")
        );

        GameBridgeException ex = assertThrows(GameBridgeException.class, () ->
                adapter.monitorAfterExecute(
                        operation,
                        state("{\"state_type\":\"monster\",\"player\":{\"hand\":[]}}"),
                        state("{\"state_type\":\"rewards\",\"player\":{\"hand\":[]}}"),
                        "{}",
                        true
                )
        );

        assertTrue(ex.getMessage().contains("state_type"));
    }

    @Test
    void shouldInterruptWhenPlayCardHandDeltaIsUnexpected() throws Exception {
        GameStateSnapshot planned = state("""
                {"state_type":"monster","player":{"hand":[{"name":"空翻"},{"name":"打击"}]}}
                """);
        GameOperation operation = new GameOperation(
                "combat_play_card",
                objectMapper.readTree("{\"card_index\":0}"),
                "打出空翻"
        );
        QueuedGameOperation queued = adapter.prepareOperation(operation, planned);

        GameBridgeException ex = assertThrows(GameBridgeException.class, () ->
                adapter.monitorAfterExecute(
                        queued,
                        state("{\"state_type\":\"monster\",\"player\":{\"hand\":[{\"name\":\"空翻\"},{\"name\":\"打击\"}]}}"),
                        state("{\"state_type\":\"monster\",\"player\":{\"hand\":[{\"name\":\"打击\"},{\"name\":\"防御\"},{\"name\":\"打击\"}]}}"),
                        "{}",
                        true
                )
        );

        assertTrue(ex.getMessage().contains("手牌数量"));
    }

    @Test
    void shouldIgnoreUnexpectedHandDeltaWhenNoRemainingOperations() throws Exception {
        GameStateSnapshot planned = state("""
                {"state_type":"monster","player":{"hand":[{"name":"打击"},{"name":"打击"},{"name":"防御"},{"name":"打击"}]}}
                """);
        GameOperation operation = new GameOperation(
                "combat_play_card",
                objectMapper.readTree("{\"card_index\":3,\"target\":\"FUZZY_WURM_CRAWLER_0\"}"),
                "最后一张计划内攻击"
        );
        QueuedGameOperation queued = adapter.prepareOperation(operation, planned);

        assertDoesNotThrow(() ->
                adapter.monitorAfterExecute(
                        queued,
                        state("{\"state_type\":\"monster\",\"player\":{\"hand\":[{\"name\":\"打击\"},{\"name\":\"打击\"},{\"name\":\"防御\"},{\"name\":\"打击\"}]}}"),
                        state("{\"state_type\":\"monster\",\"player\":{\"hand\":[]}}"),
                        "{}",
                        false
                )
        );
    }

    private GameStateSnapshot state(String raw) throws Exception {
        return new GameStateSnapshot(raw, objectMapper.readTree(raw), objectMapper.readTree(raw).path("state_type").asText(""));
    }

    private ToolProviderResult tools(String... names) {
        ToolProviderResult.Builder builder = ToolProviderResult.builder();
        for (String name : names) {
            ToolSpecification spec = ToolSpecification.builder()
                    .name(name)
                    .description(name + " description")
                    .parameters(JsonObjectSchema.builder().build())
                    .build();
            builder.add(spec, (request, memoryId) -> "{}");
        }
        return builder.build();
    }

    private ToolProviderResult toolsWithResponses(Map<String, String> responses) {
        ToolProviderResult.Builder builder = ToolProviderResult.builder();
        responses.forEach((name, response) -> {
            ToolSpecification spec = ToolSpecification.builder()
                    .name(name)
                    .description(name + " description")
                    .parameters(JsonObjectSchema.builder().build())
                    .build();
            builder.add(spec, (request, memoryId) -> response);
        });
        return builder.build();
    }
}
