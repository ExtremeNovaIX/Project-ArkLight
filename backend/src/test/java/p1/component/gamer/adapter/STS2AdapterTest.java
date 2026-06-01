package p1.component.gamer.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.adapter.core.GameBridgeException;
import p1.component.agent.gamer.adapter.core.GameAdapterContext;
import p1.component.agent.gamer.adapter.core.GameActionWindowSignature;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.adapter.core.GameOperationPrecondition;
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

        GameOperation operation = new GameOperation("combat_play_card", objectMapper.readTree("{\"card_index\":2,\"target\":\"NIBBIT_0\"}"));
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

        GameOperation operation = new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"打击\",\"target\":\"NIBBIT_0\"}"));
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

        GameOperation operation = new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"防御\",\"target\":\"PLAYER_0\"}"));
        QueuedGameOperation queued = adapter.prepareOperation(operation, planned);

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(queued, current);

        assertEquals(
                objectMapper.readTree("{\"card_index\":0}"),
                objectMapper.readTree(repaired.arguments())
        );
    }

    @Test
    void shouldRenderFlattenedStateForRp() throws Exception {
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

        assertTrue(rendered.contains("state.type=card_select"));
        assertTrue(rendered.contains("player.hp=60/70"));
        assertTrue(rendered.contains("hand:"));
        assertTrue(rendered.contains("card_select="));
        assertFalse(rendered.trim().startsWith("{"));
        assertFalse(rendered.contains("\"player\""));
        assertFalse(rendered.contains("index=0"));
    }

    @Test
    void shouldRenderEventOptionTitleAsNameForRp() throws Exception {
        GameStateSnapshot state = state("""
                {
                  "state_type":"event",
                  "event":{
                    "options":[
                      {"index":0,"title":"获得涅奥护符","description":"升级你的1张打击和1张防御。"}
                    ]
                  }
                }
                """);

        String rendered = adapter.renderStateForAgent(state);

        assertTrue(rendered.contains("event.options:"));
        assertTrue(rendered.contains("name=获得涅奥护符"));
        assertTrue(rendered.contains("description=升级你的1张打击和1张防御。"));
        assertFalse(rendered.contains("index=0"));
    }

    @Test
    void shouldRenderRewardDetailsForRp() throws Exception {
        GameStateSnapshot state = state("""
                {
                  "state_type":"card_reward",
                  "rewards":{
                    "items":[
                      {"index":0,"type":"gold","description":"Obtain 25 gold.","gold_amount":25},
                      {"index":1,"type":"card","description":"Add a card to your deck."}
                    ],
                    "can_proceed":true
                  },
                  "card_reward":{
                    "cards":[
                      {"index":0,"name":"Burning Pact","type":"Skill","cost":"1","rarity":"Uncommon","description":"Exhaust 1 card. Draw 2 cards."},
                      {"index":1,"name":"Bloodletting","type":"Skill","cost":"0","rarity":"Uncommon","description":"Lose 3 HP. Gain 2 Energy."},
                      {"index":2,"name":"Limit Break","type":"Skill","cost":"1","rarity":"Rare","description":"Double your Strength."}
                    ],
                    "can_skip":true
                  }
                }
                """);

        String rendered = adapter.renderStateForAgent(state);

        assertTrue(rendered.contains("rewards.items:"));
        assertTrue(rendered.contains("index=1 type=card description=Add a card to your deck."));
        assertFalse(rendered.contains("can_proceed"));
        assertTrue(rendered.contains("card_reward.cards:"));
        assertTrue(rendered.contains("index=2 name=Limit Break type=Skill cost=1 rarity=Rare description=Double your Strength."));
        assertTrue(rendered.contains("card_reward.can_skip=true"));
    }

    @Test
    void shouldHideCanProceedFromRpStateSummary() throws Exception {
        GameStateSnapshot state = state("""
                {
                  "state_type":"shop",
                  "shop":{
                    "items":[{"index":0,"name":"黑洞","price":36}],
                    "can_proceed":false
                  }
                }
                """);

        String rendered = adapter.renderStateForAgent(state);

        assertFalse(rendered.contains("can_proceed"));
        assertTrue(rendered.contains("state.type=shop"));
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
                new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"痛击\"}")),
                new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"打击\"}")),
                new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"防御\"}")),
                new GameOperation("combat_end_turn", objectMapper.readTree("{}"))
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
                new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"放血\"}")),
                new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"痛击\"}")),
                new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"打击\"}")),
                new GameOperation("combat_play_card", objectMapper.readTree("{\"card\":\"头槌\"}"))
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
                "rewards_claim",
                "rewards_pick_card",
                "rewards_skip_card",
                "deck_select_card",
                "proceed_to_map"
        );
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setStateToolName("get_game_state");

        String combatTools = adapter.renderAvailableOperations(
                tools, config, state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}"));
        String combatToolSummary = adapter.renderAvailableOperationSummary(
                tools, config, state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}"));
        String rewardTools = adapter.renderAvailableOperations(
                tools, config, state("{\"state_type\":\"card_reward\"}"));
        String postCombatRewardTools = adapter.renderAvailableOperations(
                tools, config, state("{\"state_type\":\"rewards\",\"rewards\":{\"items\":[],\"can_proceed\":true}}"));
        String selectTools = adapter.renderAvailableOperations(
                tools, config, state("{\"state_type\":\"card_select\",\"card_select\":{\"cards\":[]}}"));

        assertTrue(combatTools.contains("combat_play_card"));
        assertTrue(combatTools.contains("combat_end_turn"));
        assertFalse(combatTools.contains("combat_select_card"));
        assertFalse(combatTools.contains("combat_confirm_selection"));
        assertFalse(combatTools.contains("rewards_pick_card"));
        assertFalse(combatTools.contains("deck_select_card"));
        assertTrue(combatToolSummary.contains("- 打出卡牌"));
        assertTrue(combatToolSummary.contains("- 结束回合"));
        assertFalse(combatToolSummary.contains("combat_play_card"));
        assertFalse(combatToolSummary.contains("combat_end_turn"));
        assertFalse(combatToolSummary.contains("args 使用"));
        assertFalse(combatToolSummary.contains("description"));

        assertTrue(rewardTools.contains("rewards_pick_card"));
        assertTrue(rewardTools.contains("rewards_skip_card"));
        assertFalse(rewardTools.contains("rewards_claim"));
        assertFalse(rewardTools.contains("combat_play_card"));

        assertTrue(postCombatRewardTools.contains("rewards_claim"));
        assertFalse(postCombatRewardTools.contains("rewards_pick_card"));
        assertFalse(postCombatRewardTools.contains("rewards_skip_card"));

        assertTrue(selectTools.contains("deck_select_card"));
        assertFalse(selectTools.contains("combat_play_card"));
        assertFalse(selectTools.contains("combat_select_card"));
        assertFalse(selectTools.contains("combat_confirm_selection"));
        assertFalse(selectTools.contains("rewards_pick_card"));
    }

    @Test
    void shouldExposeCombatSelectionToolsForHandSelectEvenWithBattle() throws Exception {
        ToolProviderResult tools = tools(
                "combat_play_card",
                "combat_end_turn",
                "combat_select_card",
                "combat_confirm_selection",
                "deck_select_card"
        );
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setStateToolName("get_game_state");
        GameStateSnapshot handSelect = state("""
                {
                  "state_type":"hand_select",
                  "battle":{"turn":"player","is_play_phase":true},
                  "hand_select":{"cards":[{"name":"防御","index":0}],"can_confirm":false}
                }
                """);

        String rendered = adapter.renderAvailableOperations(tools, config, handSelect);

        assertTrue(rendered.contains("combat_select_card"));
        assertTrue(rendered.contains("combat_confirm_selection"));
        assertFalse(rendered.contains("combat_play_card"));
        assertFalse(rendered.contains("combat_end_turn"));
        assertFalse(rendered.contains("deck_select_card"));
    }

    @Test
    void shouldRepairCombatSelectionByCardName() throws Exception {
        GameStateSnapshot current = state("""
                {
                  "state_type":"hand_select",
                  "hand_select":{"cards":[
                    {"name":"打击","index":0},
                    {"name":"防御","index":1}
                  ]}
                }
                """);
        GameOperation operation = new GameOperation("combat_select_card", objectMapper.readTree("{\"card\":\"防御\"}"));
        QueuedGameOperation queued = QueuedGameOperation.from(operation, current);

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(queued, current);

        assertEquals(
                objectMapper.readTree("{\"card_index\":1}"),
                objectMapper.readTree(repaired.arguments())
        );
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
                new GameOperation("mp_combat_end_turn", objectMapper.readTree("{}")),
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
    void shouldRepairEventOptionNameToOptionIndex() throws Exception {
        String rawEventState = """
                {
                  "game_mode":"multiplayer",
                  "state_type":"event",
                  "options":[
                    {"index":0,"name":"小型扭蛋"},
                    {"index":1,"name":"巨大扭蛋"}
                  ]
                }
                """;
        ToolProviderResult tools = toolsWithResponses(Map.of(
                "get_game_state", rawEventState,
                "mp_get_game_state", rawEventState,
                "event_choose_option", "{}",
                "mp_event_choose_option", "{}"
        ));
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setStateToolName("get_game_state");
        GameStateSnapshot eventState = adapter.fetchState(new GameAdapterContext("STS2MCP", "session", tools, config));
        QueuedGameOperation operation = QueuedGameOperation.from(
                new GameOperation("event_choose_option", objectMapper.readTree("{\"option\":\"选择第 0 个选项：小型扭蛋\"}")),
                eventState
        );

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(operation, eventState);

        assertEquals("mp_event_choose_option", repaired.name());
        assertEquals(
                objectMapper.readTree("{\"option_index\":0}"),
                objectMapper.readTree(repaired.arguments())
        );
    }

    @Test
    void shouldRepairEventOptionTitleOrRelicNameToOptionIndex() throws Exception {
        String rawEventState = """
                {
                  "game_mode":"multiplayer",
                  "state_type":"event",
                  "event":{
                    "options":[
                      {"index":0,"title":"奥术卷轴","description":"获得一张随机稀有牌。","relic_name":"奥术卷轴"},
                      {"index":1,"title":"铅制镇纸","description":"从2张无色牌中选择1张加入你的牌组。","relic_name":"铅制镇纸"},
                      {"index":2,"title":"卷轴箱","description":"失去所有金币，并从2个卡牌包中选择1包加入你的牌组。","relic_name":"卷轴箱"}
                    ]
                  }
                }
                """;
        GameStateSnapshot eventState = state(rawEventState);
        QueuedGameOperation operation = QueuedGameOperation.from(
                new GameOperation("event_choose_option", objectMapper.readTree("{\"option\":\"铅制镇纸\"}")),
                eventState
        );

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(operation, eventState);

        assertEquals(
                objectMapper.readTree("{\"option_index\":1}"),
                objectMapper.readTree(repaired.arguments())
        );
    }

    @Test
    void shouldRepairNaturalOrdinalOptionTextToOptionIndex() throws Exception {
        String rawEventState = """
                {
                  "game_mode":"multiplayer",
                  "state_type":"event",
                  "options":[
                    {"index":0,"title":"奥术卷轴"},
                    {"index":1,"title":"铅制镇纸"},
                    {"index":2,"title":"卷轴箱"}
                  ]
                }
                """;
        GameStateSnapshot eventState = state(rawEventState);
        QueuedGameOperation operation = QueuedGameOperation.from(
                new GameOperation("event_choose_option", objectMapper.readTree("{\"option\":\"选择第2个选项\"}")),
                eventState
        );

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(operation, eventState);

        assertEquals(
                objectMapper.readTree("{\"option_index\":1}"),
                objectMapper.readTree(repaired.arguments())
        );
    }

    @Test
    void shouldPreferSemanticOptionNameWhenOrdinalTextIsConflicting() throws Exception {
        String rawEventState = """
                {
                  "game_mode":"multiplayer",
                  "state_type":"event",
                  "options":[
                    {"index":0,"title":"奥术卷轴"},
                    {"index":1,"title":"铅制镇纸"},
                    {"index":2,"title":"卷轴箱"}
                  ]
                }
                """;
        GameStateSnapshot eventState = state(rawEventState);
        QueuedGameOperation operation = QueuedGameOperation.from(
                new GameOperation("event_choose_option", objectMapper.readTree("{\"option\":\"选择第1项：铅制镇纸\"}")),
                eventState
        );

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(operation, eventState);

        assertEquals(
                objectMapper.readTree("{\"option_index\":1}"),
                objectMapper.readTree(repaired.arguments())
        );
    }

    @Test
    void shouldRejectStaleMenuSelectWhenStateAlreadyAdvanced() throws Exception {
        QueuedGameOperation operation = QueuedGameOperation.from(
                new GameOperation("menu_select", objectMapper.readTree("{\"option\":\"embark\"}")),
                state("{\"state_type\":\"menu\"}")
        );

        GameBridgeException ex = assertThrows(GameBridgeException.class,
                () -> adapter.repairBeforeExecute(operation, state("{\"state_type\":\"event\"}")));

        assertTrue(ex.getMessage().contains("菜单操作已过期"));
    }

    @Test
    void shouldThrowWhenPlannedCardIsGone() throws Exception {
        GameStateSnapshot planned = state("""
                {"state_type":"monster","player":{"hand":[{"name":"打击"},{"name":"防御"}]}}
                """);
        GameStateSnapshot current = state("""
                {"state_type":"monster","player":{"hand":[{"name":"打击"}]}}
                """);
        GameOperation operation = new GameOperation("combat_play_card", objectMapper.readTree("{\"card_index\":1}"));
        QueuedGameOperation queued = adapter.prepareOperation(operation, planned);

        assertThrows(GameBridgeException.class, () -> adapter.repairBeforeExecute(queued, current));
    }

    @Test
    void shouldInterruptWhenStateTypeChanges() throws Exception {
        QueuedGameOperation operation = QueuedGameOperation.from(
                new GameOperation("combat_end_turn", objectMapper.readTree("{}")),
                state("{\"state_type\":\"monster\",\"player\":{\"hand\":[]}}")
        );

        GameBridgeException ex = assertThrows(GameBridgeException.class, () ->
                adapter.monitorAfterExecute(
                        operation,
                        state("{\"state_type\":\"monster\",\"player\":{\"hand\":[]}}"),
                        state("{\"state_type\":\"rewards\",\"player\":{\"hand\":[]}}"),
                        "{}"
                )
        );

        assertTrue(ex.getMessage().contains("state_type"));
    }

    @Test
    void shouldInterruptWhenPlayCardHandDeltaIsUnexpected() throws Exception {
        GameStateSnapshot planned = state("""
                {"state_type":"monster","player":{"hand":[{"name":"空翻"},{"name":"打击"}]}}
                """);
        GameOperation operation = new GameOperation("combat_play_card", objectMapper.readTree("{\"card_index\":0}"));
        QueuedGameOperation queued = adapter.prepareOperation(operation, planned);

        GameBridgeException ex = assertThrows(GameBridgeException.class, () ->
                adapter.monitorAfterExecute(
                        queued,
                        state("{\"state_type\":\"monster\",\"player\":{\"hand\":[{\"name\":\"空翻\"},{\"name\":\"打击\"}]}}"),
                        state("{\"state_type\":\"monster\",\"player\":{\"hand\":[{\"name\":\"打击\"},{\"name\":\"防御\"},{\"name\":\"打击\"}]}}"),
                        "{}"
                )
        );

        assertTrue(ex.getMessage().contains("手牌数量"));
    }

    @Test
    void shouldInterruptWhenRewardPickDoesNotChangeCardRewardState() throws Exception {
        QueuedGameOperation operation = QueuedGameOperation.from(
                new GameOperation("rewards_pick_card", objectMapper.readTree("{\"card_index\":2}")),
                state("{\"state_type\":\"card_reward\"}")
        );
        String unchanged = """
                {
                  "state_type":"card_reward",
                  "card_reward":{"cards":[
                    {"index":0,"name":"Burning Pact"},
                    {"index":1,"name":"Bloodletting"},
                    {"index":2,"name":"Limit Break"}
                  ]}
                }
                """;

        GameBridgeException ex = assertThrows(GameBridgeException.class, () ->
                adapter.monitorAfterExecute(
                        operation,
                        state(unchanged),
                        state(unchanged),
                        "{}"
                )
        );

        assertTrue(ex.getMessage().contains("奖励选牌"));
    }

    @Test
    void shouldInterruptUnexpectedHandDeltaForSingleOperation() throws Exception {
        GameStateSnapshot planned = state("""
                {"state_type":"monster","player":{"hand":[{"name":"打击"},{"name":"打击"},{"name":"防御"},{"name":"打击"}]}}
                """);
        GameOperation operation = new GameOperation("combat_play_card", objectMapper.readTree("{\"card_index\":3,\"target\":\"FUZZY_WURM_CRAWLER_0\"}"));
        QueuedGameOperation queued = adapter.prepareOperation(operation, planned);

        assertThrows(GameBridgeException.class, () ->
                adapter.monitorAfterExecute(
                        queued,
                        state("{\"state_type\":\"monster\",\"player\":{\"hand\":[{\"name\":\"打击\"},{\"name\":\"打击\"},{\"name\":\"防御\"},{\"name\":\"打击\"}]}}"),
                        state("{\"state_type\":\"monster\",\"player\":{\"hand\":[]}}"),
                        "{}"
                )
        );
    }

    @Test
    void shouldKeepSameCombatActionWindowWhenTeammateOnlyChangesEnemyHp() throws Exception {
        GameStateSnapshot before = state("""
                {
                  "game_mode":"multiplayer",
                  "state_type":"monster",
                  "battle":{
                    "round":1,
                    "turn":"player",
                    "is_play_phase":true,
                    "enemies":[{"entity_id":"NIBBIT_0","name":"小啃兽","hp":30}]
                  },
                  "player":{"hand":[{"name":"防御"}]}
                }
                """);
        GameStateSnapshot after = state("""
                {
                  "game_mode":"multiplayer",
                  "state_type":"monster",
                  "battle":{
                    "round":1,
                    "turn":"player",
                    "is_play_phase":true,
                    "enemies":[{"entity_id":"NIBBIT_0","name":"小啃兽","hp":12}]
                  },
                  "player":{"hand":[{"name":"防御"}]}
                }
                """);

        GameActionWindowSignature beforeWindow = adapter.actionWindowSignature(before);
        GameActionWindowSignature afterWindow = adapter.actionWindowSignature(after);

        assertTrue(beforeWindow.sameWindow(afterWindow));
        assertEquals(beforeWindow.compact(), afterWindow.compact());
    }

    @Test
    void shouldFailPreconditionWhenPlannedTargetDisappears() throws Exception {
        GameStateSnapshot planned = state("""
                {
                  "state_type":"monster",
                  "battle":{"enemies":[{"entity_id":"NIBBIT_0","name":"小啃兽"}]},
                  "player":{"hand":[{"name":"打击","target_type":"Enemy"}]}
                }
                """);
        GameStateSnapshot current = state("""
                {
                  "state_type":"monster",
                  "battle":{"enemies":[{"entity_id":"CULTIST_0","name":"邪教徒"}]},
                  "player":{"hand":[{"name":"打击","target_type":"Enemy"}]}
                }
                """);
        GameOperation operation = new GameOperation("combat_play_card", objectMapper.readTree("{\"card_index\":0,\"target\":\"NIBBIT_0\"}"));
        QueuedGameOperation queued = adapter.prepareOperation(operation, planned);

        GameOperationPrecondition precondition = adapter.checkOperationPrecondition(queued, current);

        assertFalse(precondition.satisfied());
        assertTrue(precondition.reason().contains("目标"));
    }

    @Test
    void shouldFailPreconditionWhenPlannedHandCardIsGone() throws Exception {
        GameStateSnapshot planned = state("""
                {"state_type":"monster","player":{"hand":[{"name":"打击"},{"name":"防御"}]}}
                """);
        GameStateSnapshot current = state("""
                {"state_type":"monster","player":{"hand":[{"name":"打击"}]}}
                """);
        GameOperation operation = new GameOperation("combat_play_card", objectMapper.readTree("{\"card_index\":1}"));
        QueuedGameOperation queued = adapter.prepareOperation(operation, planned);

        GameOperationPrecondition precondition = adapter.checkOperationPrecondition(queued, current);

        assertFalse(precondition.satisfied());
        assertTrue(precondition.reason().contains("手牌"));
    }

    @Test
    void shouldRepairIndexOnlyEventOptionByPlannedLabelAfterReorder() throws Exception {
        GameStateSnapshot planned = state("""
                {
                  "state_type":"event",
                  "options":[
                    {"index":0,"name":"小型扭蛋"},
                    {"index":1,"name":"巨大扭蛋"}
                  ]
                }
                """);
        GameStateSnapshot current = state("""
                {
                  "state_type":"event",
                  "options":[
                    {"index":0,"name":"巨大扭蛋"},
                    {"index":1,"name":"小型扭蛋"}
                  ]
                }
                """);
        GameOperation operation = new GameOperation("event_choose_option", objectMapper.readTree("{\"option_index\":0}"));
        QueuedGameOperation queued = adapter.prepareOperation(operation, planned);

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(queued, current);

        assertEquals(
                objectMapper.readTree("{\"option_index\":1}"),
                objectMapper.readTree(repaired.arguments())
        );
    }

    @Test
    void shouldFailPreconditionWhenPlannedEventOptionIsGone() throws Exception {
        GameStateSnapshot planned = state("""
                {
                  "state_type":"event",
                  "options":[
                    {"index":0,"name":"小型扭蛋"},
                    {"index":1,"name":"巨大扭蛋"}
                  ]
                }
                """);
        GameStateSnapshot current = state("""
                {
                  "state_type":"event",
                  "options":[
                    {"index":0,"name":"离开"}
                  ]
                }
                """);
        GameOperation operation = new GameOperation("event_choose_option", objectMapper.readTree("{\"option_index\":0}"));
        QueuedGameOperation queued = adapter.prepareOperation(operation, planned);

        GameOperationPrecondition precondition = adapter.checkOperationPrecondition(queued, current);

        assertFalse(precondition.satisfied());
        assertTrue(precondition.reason().contains("选项"));
    }

    @Test
    void shouldRepairRewardPickIndexByPlannedCardNameAfterReorder() throws Exception {
        GameStateSnapshot planned = state("""
                {
                  "state_type":"card_reward",
                  "card_reward":{"cards":[
                    {"index":0,"name":"Burning Pact"},
                    {"index":1,"name":"Bloodletting"},
                    {"index":2,"name":"Limit Break"}
                  ]}
                }
                """);
        GameStateSnapshot current = state("""
                {
                  "state_type":"card_reward",
                  "card_reward":{"cards":[
                    {"index":0,"name":"Limit Break"},
                    {"index":1,"name":"Burning Pact"},
                    {"index":2,"name":"Bloodletting"}
                  ]}
                }
                """);
        GameOperation operation = new GameOperation("rewards_pick_card", objectMapper.readTree("{\"card_index\":2}"));
        QueuedGameOperation queued = adapter.prepareOperation(operation, planned);

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(queued, current);

        assertEquals(
                objectMapper.readTree("{\"card_index\":0}"),
                objectMapper.readTree(repaired.arguments())
        );
    }

    @Test
    void shouldFailPreconditionWhenPlannedRewardCardIsGone() throws Exception {
        GameStateSnapshot planned = state("""
                {
                  "state_type":"card_reward",
                  "card_reward":{"cards":[
                    {"index":0,"name":"Burning Pact"},
                    {"index":1,"name":"Bloodletting"},
                    {"index":2,"name":"Limit Break"}
                  ]}
                }
                """);
        GameStateSnapshot current = state("""
                {
                  "state_type":"card_reward",
                  "card_reward":{"cards":[
                    {"index":0,"name":"Burning Pact"},
                    {"index":1,"name":"Bloodletting"}
                  ]}
                }
                """);
        GameOperation operation = new GameOperation("rewards_pick_card", objectMapper.readTree("{\"card_index\":2}"));
        QueuedGameOperation queued = adapter.prepareOperation(operation, planned);

        GameOperationPrecondition precondition = adapter.checkOperationPrecondition(queued, current);

        assertFalse(precondition.satisfied());
        assertTrue(precondition.reason().contains("卡牌"));
    }

    @Test
    void shouldRepairMapNodeIndexByPlannedCoordinateAfterReorder() throws Exception {
        GameStateSnapshot planned = state("""
                {
                  "state_type":"map",
                  "map":{"next_options":[
                    {"index":0,"col":1,"row":2,"type":"monster"},
                    {"index":1,"col":2,"row":2,"type":"event"}
                  ]}
                }
                """);
        GameStateSnapshot current = state("""
                {
                  "state_type":"map",
                  "map":{"next_options":[
                    {"index":0,"col":2,"row":2,"type":"event"},
                    {"index":1,"col":1,"row":2,"type":"monster"}
                  ]}
                }
                """);
        GameOperation operation = new GameOperation("map_choose_node", objectMapper.readTree("{\"node_index\":0}"));
        QueuedGameOperation queued = adapter.prepareOperation(operation, planned);

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(queued, current);

        assertEquals(
                objectMapper.readTree("{\"node_index\":1}"),
                objectMapper.readTree(repaired.arguments())
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
