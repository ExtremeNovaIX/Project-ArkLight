package p1.component.rp.expression;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.bridge.GameBridgeActionStatus;
import p1.component.agent.gamer.bridge.GameExpressionPayload;
import p1.component.agent.gamer.bridge.queue.GameOperationBatchParser;
import p1.component.agent.gamer.bridge.queue.ParsedGameOperationBatch;
import p1.component.agent.rp.expression.GameExpressionDecisionEngine;
import p1.component.agent.rp.expression.GameExpressionService;
import p1.component.agent.rp.expression.PendingRpExpression;
import p1.component.agent.rp.expression.RpExpressionOutbox;
import p1.config.prop.AssistantProperties;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameExpressionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldParseExpressionFromActionJson() {
        GameOperationBatchParser parser = new GameOperationBatchParser();

        ParsedGameOperationBatch batch = parser.parse("""
                {
                  "status":"CONTINUE",
                  "summary":"先上易伤",
                  "operations":[
                    {"tool":"combat_play_card","args":{"card":"痛击"},"note":"先上易伤"}
                  ],
                  "expression":{
                    "score":72,
                    "type":"choice",
                    "urgency":"soon",
                    "inner_thought":"这一步先铺伤害窗口。",
                    "reason":"关键起手选择"
                  }
                }
                """);

        assertEquals(72, batch.expression().score());
        assertEquals("choice", batch.expression().type());
        assertEquals("这一步先铺伤害窗口。", batch.expression().innerThought());
    }

    @Test
    void shouldQueueHighScoreExpressionForRpSession() {
        AssistantProperties assistantProperties = new AssistantProperties();
        RpExpressionOutbox outbox = new RpExpressionOutbox(assistantProperties);
        GameExpressionService service = new GameExpressionService(
                new GameExpressionDecisionEngine(assistantProperties), outbox, null, null);

        service.handleActionExpression(
                "test-game",
                "test-game-rp-session",
                GameBridgeActionStatus.CONTINUE,
                "先打过牌找关键防御",
                new GameExpressionPayload(
                        80,
                        "choice",
                        "immediate",
                        "这回合要靠过牌找机会，不然会吃太多伤害。",
                        "关键风险选择"),
                List.of(new GameOperation("combat_play_card", objectMapper.createObjectNode(), "先过牌")),
                "已成功执行 1/1 条操作。",
                null);

        Optional<PendingRpExpression> expression = outbox.consume("rp-session");
        assertTrue(expression.isPresent());
        assertEquals("这回合要靠过牌找机会，不然会吃太多伤害。", expression.get().innerThought());
        assertEquals("先打过牌找关键防御", expression.get().sourceAction());
    }

    @Test
    void shouldAccumulateStoreOnlyExpressionsAndClearDesireAfterSpeech() {
        AssistantProperties assistantProperties = new AssistantProperties();
        assistantProperties.getRp().getExpression().setPendingThreshold(90);
        assistantProperties.getRp().getExpression().setStoreOnlyThreshold(20);
        assistantProperties.getRp().getExpression().setScoreBias(0);
        assistantProperties.getRp().getExpression().setDesireThreshold(50);
        assistantProperties.getRp().getExpression().setDesireScoreWeightPercent(100);
        RpExpressionOutbox outbox = new RpExpressionOutbox(assistantProperties);
        GameExpressionService service = new GameExpressionService(
                new GameExpressionDecisionEngine(assistantProperties), outbox, null, null);

        recordRoutineExpression(service, "先铺一张防御", "这回合先稳住。");
        assertFalse(outbox.consume("rp-session").isPresent());

        recordRoutineExpression(service, "再补一张防御", "防线差不多立住了。");
        PendingRpExpression accumulated = outbox.consume("rp-session").orElseThrow();
        assertEquals("再补一张防御", accumulated.sourceAction());
        assertEquals("防线差不多立住了。", accumulated.innerThought());

        outbox.clearDesireAfterSpeech("rp-session");
        recordRoutineExpression(service, "继续稳一下", "先别急着贪输出。");
        assertFalse(outbox.consume("rp-session").isPresent());
    }

    @Test
    void shouldNotPromoteTechnicalInterruptAsRpExpression() {
        AssistantProperties assistantProperties = new AssistantProperties();
        assistantProperties.getRp().getExpression().setPendingThreshold(60);
        assistantProperties.getRp().getExpression().setStoreOnlyThreshold(30);
        RpExpressionOutbox outbox = new RpExpressionOutbox(assistantProperties);
        GameExpressionService service = new GameExpressionService(
                new GameExpressionDecisionEngine(assistantProperties), outbox, null, null);

        service.handleActionExpression(
                "test-game",
                "test-game-rp-session",
                GameBridgeActionStatus.INTERRUPTED,
                "打出铸造继续过牌",
                new GameExpressionPayload(
                        35,
                        "routine",
                        "soon",
                        "刚摸到过牌，先看看后面还有什么。",
                        "常规过牌"),
                List.of(new GameOperation("combat_play_card", objectMapper.createObjectNode(), "打出铸造抽牌")),
                "操作队列中断：STS2 出牌后手牌数量变化不符合预期。",
                "状态监视触发中断：手牌数量校验失败");

        assertFalse(outbox.consume("rp-session").isPresent());
    }

    /**
     * 记录一个应落入 STORE_ONLY 的中低分表达候选。
     *
     * @param service      表达桥接服务
     * @param summary      gamer 行动摘要
     * @param innerThought gamer 内心活动
     */
    private void recordRoutineExpression(GameExpressionService service, String summary, String innerThought) {
        service.handleActionExpression(
                "test-game",
                "test-game-rp-session",
                GameBridgeActionStatus.CONTINUE,
                summary,
                new GameExpressionPayload(
                        40,
                        "self_talk",
                        "soon",
                        innerThought,
                        "连续行动中的自然念头"),
                List.of(new GameOperation("combat_play_card", objectMapper.createObjectNode(), summary)),
                "已成功执行 1/1 条操作。",
                null);
    }
}
