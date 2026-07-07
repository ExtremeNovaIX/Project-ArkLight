package p1.component.rp.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import p1.component.agent.factory.ChatModelFactory;
import p1.component.agent.rp.game.control.RpActionParserAgent;
import p1.config.ExternalConfigBootstrap;
import p1.config.prop.AssistantProperties;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 LLM parser 合约测试。
 * <p>
 * 默认不跑，手动加 -Drun.parser.llm.tests=true 时才会读取外部 config 并调用 parser 映射模型。
 */
@SpringBootTest(classes = RpActionParserAgentRealLlmTest.TestConfig.class)
@EnabledIfSystemProperty(named = "run.parser.llm.tests", matches = "true")
class RpActionParserAgentRealLlmTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        ExternalConfigBootstrap.prepare();
    }

    @Autowired
    private RpActionParserAgent parserAgent;

    @Test
    void shouldUseCardIndexForRewardPick() throws Exception {
        JsonNode action = parse("""
                <allowed_operations>
                - rewards_pick_card: [Rewards] Select a card from the card reward selection screen. Args: card_index, 0-based index.
                - rewards_skip_card: [Rewards] Skip the card reward.
                </allowed_operations>

                <current_game_state_json>
                {
                  "state_type":"card_reward",
                  "card_reward":{
                    "cards":[
                      {"index":0,"name":"燃烧契约","type":"Skill","cost":"1","description":"消耗1张牌。抽2张牌。"},
                      {"index":1,"name":"放血","type":"Skill","cost":"0","description":"失去3点生命。获得2点能量。"},
                      {"index":2,"name":"突破","type":"Skill","cost":"1","description":"使你的力量翻倍。"}
                    ],
                    "can_skip":true
                  }
                }
                </current_game_state_json>

                <rp_do>
                选择突破
                </rp_do>
                """);

        JsonNode operation = onlyOperation(action);
        assertEquals("rewards_pick_card", operation.path("tool").asText());
        JsonNode args = operation.path("args");
        assertEquals(2, args.path("card_index").asInt());
        assertFalse(args.has("card"));
        assertFalse(args.has("name"));
    }

    @Test
    void shouldClaimRewardsFromHighestIndexFirst() throws Exception {
        JsonNode action = parse("""
                <allowed_operations>
                - rewards_claim: [Rewards] Claim a reward from the post-combat rewards screen. Args: reward_index, 0-based index.
                - proceed_to_map: Continue to map.
                </allowed_operations>

                <current_game_state_json>
                {
                  "state_type":"rewards",
                  "rewards":{
                    "items":[
                      {"index":0,"type":"gold","description":"获得25金币。"},
                      {"index":1,"type":"potion","description":"获得一瓶药水。"},
                      {"index":2,"type":"card","description":"将一张牌加入你的牌组。"}
                    ],
                    "can_proceed":true
                  }
                }
                </current_game_state_json>

                <rp_do>
                领取奖励
                </rp_do>
                """);

        JsonNode operations = action.path("operations");
        assertTrue(operations.isArray(), "operations must be an array: " + action);
        assertFalse(operations.isEmpty(), "expected at least one reward claim: " + action);
        assertEquals("rewards_claim", operations.path(0).path("tool").asText());
        assertEquals(2, operations.path(0).path("args").path("reward_index").asInt());
        for (JsonNode operation : operations) {
            assertEquals("rewards_claim", operation.path("tool").asText());
            assertFalse(operation.path("args").has("reward"));
        }
    }

    @Test
    void shouldKeepSemanticCardNameForCombatPlayCard() throws Exception {
        JsonNode action = parse("""
                <allowed_operations>
                - combat_play_card: 打出一张手牌。args 使用 {"card":"手牌名称"}；需要选择敌人的攻击牌再加 {"target":"敌人 entity_id"}。
                - combat_end_turn: 结束回合。
                </allowed_operations>

                <current_game_state_json>
                {
                  "state_type":"monster",
                  "battle":{
                    "turn":"player",
                    "is_play_phase":true,
                    "enemies":[
                      {"name":"海洋混混","entity_id":"SEAPUNK_0","hp":20}
                    ]
                  },
                  "player":{
                    "energy":3,
                    "hand":[
                      {"index":0,"name":"痛击","type":"Attack","cost":"2","description":"造成8点伤害。给予2层易伤。"},
                      {"index":1,"name":"打击","type":"Attack","cost":"1","description":"造成6点伤害。"}
                    ]
                  }
                }
                </current_game_state_json>

                <rp_do>
                打出痛击攻击海洋混混
                </rp_do>
                """);

        JsonNode operation = onlyOperation(action);
        assertEquals("combat_play_card", operation.path("tool").asText());
        JsonNode args = operation.path("args");
        assertEquals("痛击", args.path("card").asText());
        assertEquals("SEAPUNK_0", args.path("target").asText());
        assertFalse(args.has("card_index"));
    }

    private JsonNode parse(String parserInput) throws Exception {
        String raw = collect(parserAgent.parse(parserInput));
        JsonNode node = OBJECT_MAPPER.readTree(extractJson(raw));
        assertTrue(node.path("reason").asText("").isBlank(), "parser reason: " + node.path("reason").asText(""));
        return node;
    }

    private String collect(TokenStream stream) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        StringBuilder raw = new StringBuilder();
        Throwable[] error = new Throwable[1];
        stream.onPartialResponse(raw::append)
                .onCompleteResponse(ignored -> finished.countDown())
                .onError(throwable -> {
                    error[0] = throwable;
                    finished.countDown();
                })
                .start();
        assertTrue(finished.await(120, TimeUnit.SECONDS), "parser stream timed out");
        if (error[0] != null) {
            throw new IllegalStateException("parser stream failed", error[0]);
        }
        return raw.toString();
    }

    private JsonNode onlyOperation(JsonNode action) {
        JsonNode operations = action.path("operations");
        assertTrue(operations.isArray(), "operations must be an array: " + action);
        assertEquals(1, operations.size(), "expected exactly one operation: " + action);
        return operations.path(0);
    }

    private String extractJson(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    @SpringBootConfiguration
    @EnableConfigurationProperties(AssistantProperties.class)
    static class TestConfig {

        @Bean
        ChatModelFactory chatModelFactory() {
            return new ChatModelFactory();
        }

        @Bean
        RpActionParserAgent rpActionParserAgent(ChatModelFactory factory, AssistantProperties properties) {
            AssistantProperties.ChatModelConfig config = parserModelConfig(properties.activeParserModel());
            StreamingChatModel chatModel = factory.buildStreamingChatModel(config, null, 0.0);
            return AiServices.builder(RpActionParserAgent.class)
                    .streamingChatModel(chatModel)
                    .build();
        }

        private AssistantProperties.ChatModelConfig parserModelConfig(AssistantProperties.ChatModelConfig source) {
            AssistantProperties.ChatModelConfig copy = new AssistantProperties.ChatModelConfig();
            copy.setApiKey(source.getApiKey());
            copy.setBaseUrl(source.getBaseUrl());
            copy.setModelName(source.getModelName());
            copy.setTimeoutSeconds(source.getTimeoutSeconds());
            copy.setLogEnabled(false);
            copy.setPrompt(source.getPrompt());
            copy.setReturnThinking(false);
            copy.setSendThinking(false);
            copy.setReasoningEffort(null);
            copy.setThinkingType("disabled");
            return copy;
        }
    }
}
