package p1.component.gamer.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.adapter.STS2Adapter;
import p1.component.agent.gamer.adapter.core.*;
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
    void shouldExposeRpTipsForCurrentGameContext() {
        String tips = adapter.tips();

        assertTrue(tips.startsWith("- 卡牌与敌方意图的当前显示值均视为已结算面板值"));
        assertTrue(tips.contains("禁止重复应用已体现的自身/来源侧修正"));
        assertTrue(tips.contains("- 杀戮尖塔使用向下取整算数"));
        assertTrue(tips.contains("- 抽牌、弃牌、随机、领取奖励、打开选择界面等会改变行动窗口的操作必须放在本批队列末尾"));
        assertFalse(tips.contains("<tips>"));
    }

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

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(context(), queued, current);

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

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(context(), queued, current);

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

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(context(), queued, current);

        assertEquals(
                objectMapper.readTree("{\"card_index\":0}"),
                objectMapper.readTree(repaired.arguments())
        );
    }

    @Test
    void shouldRenderCombatMarkdownForRpWithoutExecutionIds() throws Exception {
        GameStateSnapshot state = state("""
                {
                  "game_mode":"multiplayer",
                  "state_type":"monster",
                  "run":{"act":1,"floor":3,"ascension":0},
                  "battle":{
                    "round":1,
                    "turn":"player",
                    "is_play_phase":true,
                    "all_players_ready":false,
                    "enemies":[{
                      "entity_id":"JAW_WORM_0",
                      "combat_id":42,
                      "name":"Jaw Worm",
                      "hp":44,
                      "max_hp":44,
                      "block":0,
                      "intents":[{"type":"Attack","label":"11","description":"Deals 11 damage."}]
                    }]
                  },
                  "players":[{
                    "character":"The Ironclad","hp":72,"max_hp":80,"gold":99,"is_alive":true,"is_local":true,"is_ready_to_end_turn":false
                  },{
                    "character":"The Necrobinder","hp":60,"max_hp":66,"block":0,"gold":80,"is_alive":true,"is_local":false,"is_ready_to_end_turn":false,
                    "pets":[{"id":"OSTY","name":"Otsy","alive":true,"hp":12,"max_hp":12,"block":0}]
                  }],
                  "player":{
                    "character":"The Ironclad",
                    "hp":72,
                    "max_hp":80,
                    "block":0,
                    "energy":3,
                    "max_energy":3,
                    "gold":99,
                    "draw_pile_count":15,
                    "discard_pile_count":3,
                    "exhaust_pile_count":1,
                    "relics":[{"id":"BURNING_BLOOD","name":"Burning Blood","description":"At the end of combat, heal 6 HP.","counter":null}],
                    "potions":[{"id":"SWIFT_POTION","name":"Swift Potion","description":"Draw 3 cards.","slot":0,"can_use_in_combat":true,"target_type":"None"}],
                    "hand":[
                      {"index":0,"name":"Strike","type":"Attack","cost":"1","target_type":"AnyEnemy","can_play":true,"description":"Deal 6 damage."},
                      {"index":1,"name":"Defend","type":"Skill","cost":"1","target_type":"Self","can_play":true,"description":"Gain 5 Block."},
                      {"index":2,"name":"Bash","type":"Attack","cost":"2","target_type":"AnyEnemy","can_play":false,"unplayable_reason":"NotEnoughEnergy","description":"Deal 8 damage. Apply Vulnerable."}
                    ]
                  }
                }
                """);

        String rendered = adapter.renderStateForAgent(state);

        assertTrue(rendered.contains("## 当前局面"));
        assertTrue(rendered.contains("- game_mode：multiplayer"));
        assertTrue(rendered.contains("- state_type：monster"));
        assertTrue(rendered.contains("- The Ironclad：HP 72/80，格挡 0，能量 3，金币 99"));
        assertTrue(rendered.contains("- The Necrobinder：HP 60/66，格挡 0，金币 80，alive=true，ready=false"));
        assertTrue(rendered.contains("- Otsy：HP 12/12，格挡 0，alive=true"));
        assertTrue(rendered.contains("- Jaw Worm：HP 44/44，格挡 0"));
        assertTrue(rendered.contains("intent：Attack 11；Deals 11 damage."));
        assertTrue(rendered.contains("- Bash：cost 2，Attack，target AnyEnemy，can_play false，unplayable_reason NotEnoughEnergy。Deal 8 damage. Apply Vulnerable."));
        assertTrue(rendered.contains("- 敌方即将造成伤害：11"));
        assertTrue(rendered.contains("- 当前格挡缺口：11"));
        assertTrue(rendered.contains("- 当前可用能量：3"));
        assertTrue(rendered.contains("- 不可打的牌：Bash(NotEnoughEnergy)"));
        assertTrue(rendered.contains("- 牌堆：draw 15，discard 3，exhaust 1"));
        assertFalse(rendered.trim().startsWith("{"));
        assertFalse(rendered.contains("\"player\""));
        assertFalse(rendered.contains("index"));
        assertFalse(rendered.contains("entity_id"));
        assertFalse(rendered.contains("combat_id"));
        assertFalse(rendered.contains("max_energy"));
        assertFalse(rendered.contains("slot"));
    }

    @Test
    void shouldUseNativeMcpMarkdownForRpAndSanitizeExecutionFields() throws Exception {
        String rawJson = """
                {
                  "state_type":"monster",
                  "battle":{
                    "turn":"player",
                    "is_play_phase":true,
                    "enemies":[{
                      "entity_id":"CORPSE_SLUG_0",
                      "name":"噬尸蛞蝓",
                      "hp":26,
                      "max_hp":26,
                      "block":0,
                      "status":[{"name":"饥饿","amount":4,"description":"当有敌人死亡时，噬尸蛞蝓会立即吃下尸体，在本回合被击晕然后获得4点力量。","keywords":[{"name":"击晕","description":"这个敌人在其下一回合无法行动。"}]}],
                      "intents":[{"type":"Attack","label":"7×2","description":"这个敌人将要攻击造成7点伤害2次。"}]
                    }]
                  },
                  "player":{
                    "character":"铁甲战士",
                    "hp":63,
                    "max_hp":80,
                    "block":0,
                    "energy":3,
                    "max_energy":3,
                    "draw_pile_count":3,
                    "discard_pile_count":0,
                    "exhaust_pile_count":0,
                    "hand":[{"index":0,"name":"防御","type":"Skill","cost":"1","can_play":true,"description":"获得3点格挡。"}],
                    "draw_pile":[{"name":"打击","description":"造成9点伤害。"}]
                  }
                }
                """;
        String nativeMarkdown = """
                # Game State: monster

                **Round 3** | Turn: player | Play Phase: True

                ## Player (You)
                **铁甲战士** - HP: 63/80 | Block: 0 | Energy: 3/3 | Gold: 99

                ### Hand
                - [0] **防御** (1 energy) [Skill] ✓ - 获得3点格挡。 (target: Self)

                ### Deck Information

                #### Draw Pile (3 cards, sorted by rarity)
                - 打击 (1): 造成9点伤害。

                #### Discard Pile (0 cards)
                - *(empty)*

                #### Exhaust Pile (0 cards)
                - *(empty)*

                ## Enemies
                ### 噬尸蛞蝓 (`CORPSE_SLUG_0`)
                HP: 26/26 | Block: 0
                **Intent:** 攻势 (Attack) 7×2 - 这个敌人将要攻击造成7点伤害2次。
                ### Status
                  - **饥饿** (4): 当有敌人死亡时，噬尸蛞蝓会立即吃下尸体，在本回合被击晕然后获得4点力量。

                ## Keyword Glossary
                - **击晕**: 这个敌人在其下一回合无法行动。
                """;
        JsonNode root = objectMapper.readTree(rawJson);
        GameStateSnapshot state = new GameStateSnapshot(rawJson, root, root.path("state_type").asText(""), nativeMarkdown);

        String rendered = adapter.renderStateForAgent(state);

        assertTrue(rendered.contains("当有敌人死亡时，噬尸蛞蝓会立即吃下尸体"));
        assertTrue(rendered.contains("## Keyword Glossary"));
        assertTrue(rendered.contains("这个敌人在其下一回合无法行动。"));
        assertTrue(rendered.contains("Energy: 3"));
        assertTrue(rendered.contains("draw 3"));
        assertTrue(rendered.contains("discard 0"));
        assertTrue(rendered.contains("exhaust 0"));
        assertFalse(rendered.contains("### 数值说明"));
        assertFalse(rendered.contains("当前展示的伤害、格挡、费用、意图等数值已经是游戏在当前 buff/debuff 下计算后的结果"));
        assertTrue(rendered.contains("## 本步决策重点"));
        assertTrue(rendered.contains("当前可用能量"));
        assertFalse(rendered.contains("[0]"));
        assertFalse(rendered.contains("`CORPSE_SLUG_0`"));
        assertFalse(rendered.contains("Energy: 3/3"));
        assertFalse(rendered.contains("Draw Pile"));
        assertFalse(rendered.contains("造成9点伤害。"));
        assertFalse(rendered.contains("max_energy"));
    }
    @Test
    void shouldRenderEventMarkdownForRpWithoutOptionIndex() throws Exception {
        GameStateSnapshot state = state("""
                {
                  "state_type":"event",
                  "run":{"act":1,"floor":2,"ascension":0},
                  "event":{
                    "event_id":"NEOW",
                    "event_name":"Neow",
                    "is_ancient":true,
                    "in_dialogue":false,
                    "body":"Choose a blessing.",
                    "options":[
                      {"index":0,"title":"Obtain a rare card","description":"Choose 1 of 3 rare cards.","is_locked":false,"is_proceed":false,"relic_name":"Tiny House","relic_description":"Obtain rewards."}
                    ]
                  }
                }
                """);

        String rendered = adapter.renderStateForAgent(state);

        assertTrue(rendered.contains("## 事件"));
        assertTrue(rendered.contains("- event_id：NEOW"));
        assertTrue(rendered.contains("- event_name：Neow"));
        assertTrue(rendered.contains("- body：Choose a blessing."));
        assertTrue(rendered.contains("- Obtain a rare card：Choose 1 of 3 rare cards.，locked=false，proceed=false，relic=Tiny House：Obtain rewards."));
        assertFalse(rendered.contains("index"));
    }

    @Test
    void shouldRenderRewardMarkdownForRpWithoutRewardIndexes() throws Exception {
        GameStateSnapshot state = state("""
                {
                  "state_type":"card_reward",
                  "player":{"character":"The Ironclad","hp":65,"max_hp":80,"block":0,"gold":124},
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

        assertTrue(rendered.contains("## 可选奖励牌"));
        assertTrue(rendered.contains("- Burning Pact：cost 1，Skill，Uncommon。Exhaust 1 card. Draw 2 cards."));
        assertTrue(rendered.contains("- Limit Break：cost 1，Skill，Rare。Double your Strength."));
        assertTrue(rendered.contains("- 可以跳过奖励：true"));
        assertTrue(rendered.contains("- 当前金币：124"));
        assertTrue(rendered.contains("- 当前生命：65/80"));
        assertFalse(rendered.contains("index"));
        assertFalse(rendered.contains("card_index"));
        assertFalse(rendered.contains("can_proceed"));
    }

    @Test
    void shouldRenderMapVotesAndHideNodeIndexesForRp() throws Exception {
        GameStateSnapshot state = state("""
                {
                  "game_mode":"multiplayer",
                  "state_type":"map",
                  "run":{"act":1,"floor":6,"ascension":0},
                  "player":{"character":"The Ironclad","hp":41,"max_hp":80,"block":0,"gold":143,
                    "relics":[{"name":"Burning Blood","description":"At the end of combat, heal 6 HP."},{"name":"Anchor","description":"Start each combat with 10 Block."}]},
                  "players":[
                    {"character":"The Ironclad","hp":41,"max_hp":80,"gold":143,"is_alive":true,"is_local":true},
                    {"character":"The Necrobinder","hp":60,"max_hp":66,"gold":80,"is_alive":true,"is_local":false}
                  ],
                  "map":{
                    "current_position":{"col":3,"row":6,"type":"Monster"},
                    "next_options":[
                      {"index":0,"col":2,"row":7,"type":"RestSite","leads_to":[{"col":1,"row":8,"type":"Elite"},{"col":3,"row":8,"type":"Shop"}]},
                      {"index":1,"col":3,"row":7,"type":"Monster","leads_to":[{"col":3,"row":8,"type":"Event"}]}
                    ],
                    "votes":[
                      {"player":"The Ironclad","is_local":true,"voted":true,"vote_col":2,"vote_row":7},
                      {"player":"The Necrobinder","is_local":false,"voted":false}
                    ],
                    "all_voted":false,
                    "nodes":[{"col":0,"row":0,"type":"Start"}]
                  }
                }
                """);

        String rendered = adapter.renderStateForAgent(state);

        assertTrue(rendered.contains("## 地图"));
        assertTrue(rendered.contains("- 当前位置：(3,6)，Monster"));
        assertTrue(rendered.contains("- RestSite，坐标 (2,7)，后续 Elite / Shop"));
        assertTrue(rendered.contains("- The Ironclad：voted=true，vote=(2,7)"));
        assertTrue(rendered.contains("- The Necrobinder：voted=false"));
        assertTrue(rendered.contains("- all_voted：false"));
        assertTrue(rendered.contains("- 可选节点类型：RestSite，Monster"));
        assertTrue(rendered.contains("- 已投票人数：1/2"));
        assertFalse(rendered.contains("index"));
        assertFalse(rendered.contains("nodes"));
        assertFalse(rendered.contains("local_player_slot"));
    }

    @Test
    void shouldRenderShopRestTreasureAndSelectionFieldsWithoutHiddenFields() throws Exception {
        GameStateSnapshot state = state("""
                {
                  "state_type":"shop",
                  "player":{"character":"The Ironclad","hp":50,"max_hp":80,"block":0,"gold":99},
                  "shop":{
                    "items":[
                      {"index":0,"category":"card","price":75,"is_stocked":true,"can_afford":true,"card_name":"Offering","card_type":"Skill","card_cost":"1","card_rarity":"Rare","card_description":"Lose 6 HP. Gain 2 Energy. Draw 3 cards."},
                      {"index":1,"category":"card_removal","price":100,"is_stocked":true,"can_afford":false}
                    ],
                    "can_proceed":false
                  },
                  "rest_site":{"options":[{"index":0,"name":"Rest","description":"Heal 30% of max HP.","is_enabled":true}]},
                  "treasure":{"relics":[{"index":0,"name":"Lantern","description":"Start each combat with 1 Energy.","rarity":"Common"}],"is_bidding_phase":true,"bids":[{"player":"The Ironclad","voted":true,"vote_relic_index":0}],"all_bid":false},
                  "card_select":{"screen_type":"transform","prompt":"Choose a card.","cards":[{"index":0,"name":"Strike","type":"Attack","cost":"1","rarity":"Basic","description":"Deal 6 damage."}],"can_confirm":true,"can_cancel":true}
                }
                """);

        String rendered = adapter.renderStateForAgent(state);

        assertTrue(rendered.contains("## 商店"));
        assertTrue(rendered.contains("- card Offering：price 75，stocked=true，afford=true，cost 1，Skill，Rare。Lose 6 HP. Gain 2 Energy. Draw 3 cards."));
        assertTrue(rendered.contains("- card_removal：price 100，stocked=true，afford=false"));
        assertTrue(rendered.contains("## 休息点"));
        assertTrue(rendered.contains("- Rest：Heal 30% of max HP.，enabled=true"));
        assertTrue(rendered.contains("## 宝箱"));
        assertTrue(rendered.contains("- Lantern：Common。Start each combat with 1 Energy."));
        assertTrue(rendered.contains("- is_bidding_phase：true"));
        assertTrue(rendered.contains("- The Ironclad：voted=true"));
        assertTrue(rendered.contains("## 选牌"));
        assertTrue(rendered.contains("- screen_type：transform"));
        assertTrue(rendered.contains("- Strike：cost 1，Attack，Basic。Deal 6 damage."));
        assertFalse(rendered.contains("can_proceed"));
        assertFalse(rendered.contains("index"));
        assertFalse(rendered.contains("vote_relic_index"));
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

        ArrayDeque<QueuedGameOperation> queue = adapter.prepareBatch(context(), operations, planned);
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

        ArrayDeque<QueuedGameOperation> queue = adapter.prepareBatch(context(), operations, planned);

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

        String combatTools = adapter.renderAvailableOperations(context(tools, config), state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}"));
        String combatToolSummary = adapter.renderAvailableOperationSummary(context(tools, config), state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}"));
        String rewardTools = adapter.renderAvailableOperations(context(tools, config), state("{\"state_type\":\"card_reward\"}"));
        String postCombatRewardTools = adapter.renderAvailableOperations(context(tools, config), state("{\"state_type\":\"rewards\",\"rewards\":{\"items\":[],\"can_proceed\":true}}"));
        String selectTools = adapter.renderAvailableOperations(context(tools, config), state("{\"state_type\":\"card_select\",\"card_select\":{\"cards\":[]}}"));

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

        String rendered = adapter.renderAvailableOperations(context(tools, config), handSelect);

        assertTrue(rendered.contains("combat_select_card"));
        assertTrue(rendered.contains("combat_confirm_selection"));
        assertFalse(rendered.contains("combat_play_card"));
        assertFalse(rendered.contains("combat_end_turn"));
        assertFalse(rendered.contains("deck_select_card"));
    }

    @Test
    void shouldAcceptCombatSelectionByCardIndex() throws Exception {
        GameStateSnapshot current = state("""
                {
                  "state_type":"hand_select",
                  "hand_select":{"cards":[
                    {"name":"Strike","index":0},
                    {"name":"Defend","index":1}
                  ]}
                }
                """);
        GameOperation operation = new GameOperation("combat_select_card", objectMapper.readTree("{\"card_index\":1}"));
        QueuedGameOperation queued = QueuedGameOperation.from(operation, current);

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(context(), queued, current);

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
        String availableOperations = adapter.renderAvailableOperations(context(tools, config), cardSelect);

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

        assertDoesNotThrow(() -> adapter.repairBeforeExecute(context(), endTurn, eliteBattle));

        adapter.fetchState(new GameAdapterContext("STS2MCP", "session", tools, config));
        String availableOperations = adapter.renderAvailableOperations(context(tools, config), eliteBattle);
        assertTrue(availableOperations.contains("- mp_combat_end_turn:"));
        assertTrue(availableOperations.contains("- mp_combat_play_card:"));
        assertFalse(availableOperations.contains("- mp_event_choose_option:"));
        assertFalse(availableOperations.contains("- mp_map_vote:"));
    }

    @Test
    void shouldPreferMultiplayerStateWhenOnlyMultiplayerResponseHasGameMode() throws Exception {
        String singleplayerShapedMap = """
                {
                  "state_type":"map",
                  "map":{"next_options":[{"index":0,"col":1,"row":1,"type":"Monster"}]}
                }
                """;
        String multiplayerMap = """
                {
                  "game_mode" : "multiplayer",
                  "state_type":"map",
                  "map":{"next_options":[{"index":0,"col":1,"row":1,"type":"Monster"}]}
                }
                """;
        ToolProviderResult tools = toolsWithResponses(Map.of(
                "get_game_state", singleplayerShapedMap,
                "mp_get_game_state", multiplayerMap,
                "map_choose_node", "{}",
                "mp_map_vote", "{}"
        ));
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setStateToolName("get_game_state");

        GameStateSnapshot fetched = adapter.fetchState(new GameAdapterContext("STS2MCP", "session", tools, config));
        String availableOperations = adapter.renderAvailableOperations(context(tools, config), fetched);

        assertTrue(fetched.rawJson().contains("\"game_mode\" : \"multiplayer\""));
        assertTrue(availableOperations.contains("- mp_map_vote:"));
        assertFalse(availableOperations.contains("- map_choose_node:"));
    }

    @Test
    void shouldFetchNativeMarkdownTogetherWithJsonState() {
        List<String> arguments = new ArrayList<>();
        ToolSpecification spec = ToolSpecification.builder()
                .name("get_game_state")
                .description("state")
                .parameters(JsonObjectSchema.builder().build())
                .build();
        ToolProviderResult tools = ToolProviderResult.builder()
                .add(spec, (request, memoryId) -> {
                    arguments.add(request.arguments());
                    if (request.arguments().contains("markdown")) {
                        return "# Game State: map\n\n## Player";
                    }
                    return "{\"state_type\":\"map\"}";
                })
                .build();
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setStateToolName("get_game_state");

        GameStateSnapshot fetched = adapter.fetchState(new GameAdapterContext("STS2MCP", "session", tools, config));

        assertEquals("map", fetched.stateType());
        assertEquals("# Game State: map\n\n## Player", fetched.rawMarkdown());
        assertTrue(arguments.contains("{\"format\":\"json\"}"));
        assertTrue(arguments.contains("{\"format\":\"markdown\"}"));
    }
    @Test
    void shouldNotLeakDetectedModeBetweenSessions() throws Exception {
        String multiplayerMap = """
                {
                  "game_mode":"multiplayer",
                  "state_type":"map",
                  "map":{"next_options":[{"index":0,"col":1,"row":1,"type":"Monster"}]}
                }
                """;
        String singleplayerMap = """
                {
                  "state_type":"map",
                  "map":{"next_options":[{"index":0,"col":2,"row":1,"type":"Monster"}]}
                }
                """;
        ToolProviderResult multiplayerTools = toolsWithResponses(Map.of(
                "get_game_state", singleplayerMap,
                "mp_get_game_state", multiplayerMap,
                "map_choose_node", "{}",
                "mp_map_vote", "{}"
        ));
        ToolProviderResult singleplayerTools = toolsWithResponses(Map.of(
                "get_game_state", singleplayerMap,
                "mp_get_game_state", "{\"status\":\"error\",\"message\":\"Not in a multiplayer run.\"}",
                "map_choose_node", "{}",
                "mp_map_vote", "{}"
        ));
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setStateToolName("get_game_state");

        GameStateSnapshot multiplayerState = adapter.fetchState(
                new GameAdapterContext("STS2MCP", "session-mp", multiplayerTools, config));
        adapter.fetchState(new GameAdapterContext("STS2MCP", "session-sp", singleplayerTools, config));

        String multiplayerOperations = adapter.renderAvailableOperations(context(multiplayerTools, config), multiplayerState);

        assertTrue(multiplayerOperations.contains("- mp_map_vote:"));
        assertFalse(multiplayerOperations.contains("- map_choose_node:"));
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
        String renderedEventTools = adapter.renderAvailableOperations(context(tools, config), state("{\"state_type\":\"event\"}"));

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

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(context(), operation, eventState);

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

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(context(), operation, eventState);

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

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(context(), operation, eventState);

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

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(context(), operation, eventState);

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
                () -> adapter.repairBeforeExecute(context(), operation, state("{\"state_type\":\"event\"}")));

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

        assertThrows(GameBridgeException.class, () -> adapter.repairBeforeExecute(context(), queued, current));
    }

    @Test
    void shouldInterruptWhenStateTypeChanges() throws Exception {
        QueuedGameOperation operation = QueuedGameOperation.from(
                new GameOperation("combat_end_turn", objectMapper.readTree("{}")),
                state("{\"state_type\":\"monster\",\"player\":{\"hand\":[]}}")
        );

        GameBridgeException ex = assertThrows(GameBridgeException.class, () ->
                adapter.monitorAfterExecute(
                        context(),
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
                        context(),
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
                        context(),
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
                        context(),
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

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(context(), queued, current);

        assertEquals(
                objectMapper.readTree("{\"option_index\":1}"),
                objectMapper.readTree(repaired.arguments())
        );
    }

    @Test
    void shouldAcceptRestSiteOptionIndexFromRestSiteOptions() throws Exception {
        GameStateSnapshot restSite = state("""
                {
                  "state_type":"rest_site",
                  "rest_site":{
                    "options":[
                      {"index":0,"name":"HEAL","label":"休息","enabled":true},
                      {"index":1,"name":"SMITH","label":"锻造","enabled":true}
                    ]
                  }
                }
                """);
        GameOperation operation = new GameOperation("rest_choose_option", objectMapper.readTree("{\"option_index\":0}"));
        QueuedGameOperation queued = adapter.prepareOperation(operation, restSite);

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(context(), queued, restSite);

        assertEquals(
                objectMapper.readTree("{\"option_index\":0}"),
                objectMapper.readTree(repaired.arguments())
        );
    }

    @Test
    void shouldNarrowAvailableOperationsForRestSite() throws Exception {
        ToolProviderResult tools = tools(
                "get_game_state",
                "combat_play_card",
                "event_choose_option",
                "rest_choose_option",
                "proceed_to_map"
        );
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setStateToolName("get_game_state");

        String rendered = adapter.renderAvailableOperations(context(tools, config), state("{\"state_type\":\"rest_site\"}"));

        assertTrue(rendered.contains("rest_choose_option"));
        assertTrue(rendered.contains("proceed_to_map"));
        assertFalse(rendered.contains("combat_play_card"));
        assertFalse(rendered.contains("event_choose_option"));
    }

    @Test
    void shouldKeepRewardPickCardIndexOnly() throws Exception {
        GameStateSnapshot current = state("""
                {
                  "state_type":"card_reward",
                  "card_reward":{"cards":[
                    {"index":0,"name":"Burning Pact"},
                    {"index":1,"name":"Bloodletting"},
                    {"index":2,"name":"Limit Break"}
                  ]}
                }
                """);
        GameOperation operation = new GameOperation("rewards_pick_card", objectMapper.readTree("{\"card_index\":2}"));
        QueuedGameOperation queued = adapter.prepareOperation(operation, current);

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(context(), queued, current);

        assertEquals(
                objectMapper.readTree("{\"card_index\":2}"),
                objectMapper.readTree(repaired.arguments())
        );
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

        ToolExecutionRequest repaired = adapter.repairBeforeExecute(context(), queued, current);

        assertEquals(
                objectMapper.readTree("{\"node_index\":1}"),
                objectMapper.readTree(repaired.arguments())
        );
    }

    private GameAdapterContext context() {
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setStateToolName("get_game_state");
        return context(tools(), config);
    }

    private GameAdapterContext context(ToolProviderResult tools, MCPProperties.GameMCPConfig config) {
        return new GameAdapterContext("STS2MCP", "session", tools, config);
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
