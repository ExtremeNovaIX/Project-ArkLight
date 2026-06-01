package p1.component.agent.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import p1.config.ExternalConfigBootstrap;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 真实 router LLM 的动作解析可行性测试。
 * <p>
 * 默认不跑。手动加 -Drun.router.parser.llm.tests=true 时，按生产 router 加载路径调用真实 LLM。
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "run.router.parser.llm.tests", matches = "true")
class RouterAsParserRealLlmTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        ExternalConfigBootstrap.prepare();
    }

    @Autowired
    private InstructionRouter router;

    @Test
    void shouldMeasureRouterParserFeasibilityWithRealLlm() {
        List<CaseSpec> cases = cases();

        Evaluation baseline = evaluate("baseline", baselinePrompt(), cases);
        Evaluation improved = evaluate("improved", improvedPrompt(), cases);

        System.out.printf("router-as-parser baseline: %d/%d = %.2f%%%n",
                baseline.passed(), baseline.total(), baseline.accuracy() * 100.0);
        baseline.failures().forEach(failure -> System.out.println("baseline failure: " + failure));

        System.out.printf("router-as-parser improved: %d/%d = %.2f%%%n",
                improved.passed(), improved.total(), improved.accuracy() * 100.0);
        improved.failures().forEach(failure -> System.out.println("improved failure: " + failure));

        assertNotNull(improved);
    }

    private Evaluation evaluate(String name, String prompt, List<CaseSpec> cases) {
        int passed = 0;
        List<String> failures = new ArrayList<>();
        for (CaseSpec testCase : cases) {
            InstructionRouteDecision decision = router.route(InstructionRouteRequest.byPrompt(
                    "router-as-rp-action-parser-" + name,
                    prompt,
                    renderRuntimeContext(testCase),
                    testCase.rpDo(),
                    List.of("EXECUTE", "FAIL")));

            boolean ok = matches(testCase, decision);
            if (ok) {
                passed++;
            } else {
                failures.add(testCase.name()
                        + " intent=" + decision.normalizedIntent()
                        + " instruction=" + decision.instruction()
                        + " raw=" + decision.rawResponse());
            }
        }
        return new Evaluation(passed, cases.size(), failures);
    }

    private boolean matches(CaseSpec testCase, InstructionRouteDecision decision) {
        if (!"EXECUTE".equals(decision.normalizedIntent())) {
            return false;
        }
        try {
            JsonNode actual = OBJECT_MAPPER.readTree(decision.instruction());
            JsonNode operations = actual.isArray() ? actual : actual.path("operations");
            if (!operations.isArray() || operations.size() != testCase.expected().size()) {
                return false;
            }
            for (int i = 0; i < testCase.expected().size(); i++) {
                ExpectedOperation expected = testCase.expected().get(i);
                JsonNode operation = operations.path(i);
                if (!expected.tool().equals(operation.path("tool").asText())) {
                    return false;
                }
                for (ExpectedArg arg : expected.args()) {
                    JsonNode value = operation.path("args").path(arg.name());
                    if (value.isMissingNode()) {
                        return false;
                    }
                    if (!arg.value().equals(value.isNumber() ? value.asText() : value.asText())) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String renderRuntimeContext(CaseSpec testCase) {
        return """
                <allowed_operations>
                %s
                </allowed_operations>

                <current_game_state_json>
                %s
                </current_game_state_json>
                """.formatted(testCase.allowedOperations(), testCase.currentStateJson()).trim();
    }

    private String baselinePrompt() {
        return """
                你把 rp_do 翻译成 allowed_operations 里的 MCP operations。
                只能输出 router schema：{"intent":"EXECUTE|FAIL","confidence":0-1,"instruction":"..."}。
                可执行时 intent=EXECUTE，instruction 必须是 JSON 字符串：{"operations":[{"tool":"工具名","args":{}}]}。
                不能翻译时 intent=FAIL，instruction 写简短原因。
                tool 必须来自 allowed_operations，禁止发明。
                战斗打牌不要计算手牌 index，使用 {"card":"牌名"}；其他选择类动作按 current_game_state_json 读取当前 index。
                """;
    }

    private String improvedPrompt() {
        return """
                你是低延迟动作翻译器，只翻译 rp_do，不做决策。
                输出固定 router JSON：{"intent":"EXECUTE|FAIL","confidence":0-1,"instruction":"..."}。
                EXECUTE 时 instruction 是一行 JSON 字符串：{"operations":[{"tool":"...","args":{...}}]}。
                FAIL 时 instruction 写原因。

                规则：
                1. tool 逐字取自 allowed_operations。
                2. 战斗出牌只写牌名，不写 card_index：{"tool":"combat_play_card","args":{"card":"痛击","target":"SEAPUNK_0"}}。
                3. 事件、奖励、地图、手牌选择要从 current_game_state_json 找当前 index。
                4. 名称找不到或不唯一就 FAIL。
                5. 多个明确动作按原顺序输出多个 operations。

                例：
                rp_do=选择突破
                state.card_reward.cards 里 突破 index=2
                instruction={"operations":[{"tool":"rewards_pick_card","args":{"card_index":2}}]}

                rp_do=打出痛击攻击海洋混混
                state.battle.enemies 里 海洋混混 entity_id=SEAPUNK_0
                instruction={"operations":[{"tool":"combat_play_card","args":{"card":"痛击","target":"SEAPUNK_0"}}]}
                """;
    }

    private List<CaseSpec> cases() {
        return List.of(
                new CaseSpec(
                        "reward_pick_card_by_name",
                        "- rewards_pick_card: 选择一张奖励卡。Args: card_index",
                        """
                                {"state_type":"card_reward","card_reward":{"cards":[
                                  {"index":0,"name":"燃烧契约"},
                                  {"index":1,"name":"放血"},
                                  {"index":2,"name":"突破"}
                                ]}}
                                """,
                        "选择突破",
                        List.of(new ExpectedOperation("rewards_pick_card", List.of(arg("card_index", "2"))))),
                new CaseSpec(
                        "combat_play_card_by_name",
                        "- combat_play_card: 打出一张手牌。Args: card, target\n- combat_end_turn: 结束回合",
                        """
                                {"state_type":"monster","battle":{"turn":"player","is_play_phase":true,"enemies":[
                                  {"name":"海洋混混","entity_id":"SEAPUNK_0","hp":20}
                                ]},"player":{"hand":[
                                  {"index":0,"name":"痛击"},{"index":1,"name":"打击"}
                                ]}}
                                """,
                        "打出痛击攻击海洋混混",
                        List.of(new ExpectedOperation("combat_play_card", List.of(
                                arg("card", "痛击"),
                                arg("target", "SEAPUNK_0"))))),
                new CaseSpec(
                        "event_option_by_title",
                        "- event_choose: 选择事件选项。Args: option_index",
                        """
                                {"state_type":"event","event":{"options":[
                                  {"index":0,"title":"小型扭蛋","description":"获得一件普通遗物。"},
                                  {"index":1,"title":"获得涅奥护符","description":"升级你的1张打击和1张防御。"}
                                ]}}
                                """,
                        "选择获得涅奥护符",
                        List.of(new ExpectedOperation("event_choose", List.of(arg("option_index", "1"))))),
                new CaseSpec(
                        "hand_select_pick_and_confirm",
                        "- combat_select_card: 战斗内选择一张手牌。Args: card_index\n- combat_confirm_selection: 确认当前选择。Args: none",
                        """
                                {"state_type":"hand_select","hand_select":{"cards":[
                                  {"index":0,"name":"打击"},{"index":1,"name":"防御"}
                                ],"can_confirm":true}}
                                """,
                        "选择打击并确认",
                        List.of(
                                new ExpectedOperation("combat_select_card", List.of(arg("card_index", "0"))),
                                new ExpectedOperation("combat_confirm_selection", List.of()))),
                new CaseSpec(
                        "claim_rewards_reverse_order",
                        "- rewards_claim: 领取奖励。Args: reward_index\n- proceed_to_map: 继续到地图",
                        """
                                {"state_type":"rewards","rewards":{"items":[
                                  {"index":0,"type":"gold","description":"获得25金币"},
                                  {"index":1,"type":"potion","description":"获得一瓶药水"},
                                  {"index":2,"type":"card","description":"将一张牌加入牌组"}
                                ]}}
                                """,
                        "领取所有奖励",
                        List.of(
                                new ExpectedOperation("rewards_claim", List.of(arg("reward_index", "2"))),
                                new ExpectedOperation("rewards_claim", List.of(arg("reward_index", "1"))),
                                new ExpectedOperation("rewards_claim", List.of(arg("reward_index", "0")))))
        );
    }

    private ExpectedArg arg(String name, String value) {
        return new ExpectedArg(name, value);
    }

    private record CaseSpec(
            String name,
            String allowedOperations,
            String currentStateJson,
            String rpDo,
            List<ExpectedOperation> expected
    ) {
    }

    private record ExpectedOperation(String tool, List<ExpectedArg> args) {
    }

    private record ExpectedArg(String name, String value) {
    }

    private record Evaluation(int passed, int total, List<String> failures) {
        double accuracy() {
            return total == 0 ? 0.0 : (double) passed / total;
        }
    }
}
