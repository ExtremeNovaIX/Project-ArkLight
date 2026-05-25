package p1.component.agent.rp.expression;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.bridge.GameBridgeActionStatus;
import p1.component.agent.gamer.bridge.GameExpressionPayload;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.loop.ActiveGameSession;
import p1.component.agent.rp.proactive.RpProactiveSpeechService;

import java.time.Instant;
import java.util.List;

/**
 * 游戏行动到 RP 表达欲的桥接服务。
 * <p>
 * 队列处理器只把每次 ACTION 的表达候选和执行结果交给该组件；
 * 该组件负责评分、冷却、替换和投递到 RP 表达待发送队列。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GameExpressionService {

    private final GameExpressionDecisionEngine decisionEngine;
    private final RpExpressionOutbox outbox;
    private final RpProactiveSpeechService proactiveSpeechService;
    private final ActiveGameRegistry activeGameRegistry;

    /**
     * 处理一次游戏 ACTION 的表达候选。
     *
     * @param gameName        游戏名
     * @param gamerMemoryId   gamer 会话 key
     * @param status          本次 ACTION 状态
     * @param summary         gamer 决策摘要
     * @param expression      gamer 输出的表达欲字段
     * @param operations      本次 ACTION 操作列表
     * @param result          桥接层执行结果
     * @param interruptReason 中断原因；正常执行时为空
     */
    public void handleActionExpression(String gameName,
                                       String gamerMemoryId,
                                       GameBridgeActionStatus status,
                                       String summary,
                                       GameExpressionPayload expression,
                                       List<GameOperation> operations,
                                       String result,
                                       String interruptReason) {
        String rpSessionId = rpSessionId(gameName, gamerMemoryId);
        GameExpressionCandidate candidate = new GameExpressionCandidate(
                gameName,
                gamerMemoryId,
                rpSessionId,
                status,
                expression == null ? GameExpressionPayload.empty() : expression,
                operations == null ? List.of() : operations,
                sourceAction(summary, operations),
                result,
                interruptReason,
                Instant.now()
        );

        GameExpressionDecision decision = decisionEngine.evaluate(candidate);
        if (decision.type() == GameExpressionDecision.DecisionType.DROP) {
            log.debug("[RP表达欲] 未进入待表达队列: game={}, rpSession={}, decision={}, score={}, reason={}",
                    gameName, rpSessionId, decision.type(), decision.finalScore(), decision.reason());
            return;
        }

        PendingRpExpression pending = toPendingExpression(gameName, rpSessionId, candidate, decision);
        boolean accepted = decision.type() == GameExpressionDecision.DecisionType.PENDING_RP_EXPRESSION
                ? outbox.offer(pending)
                : outbox.accumulate(pending, decision.finalScore());
        if (!accepted) {
            log.debug("[RP表达欲] 表达候选已处理但本次不触发开口: game={}, rpSession={}, decision={}, score={}",
                    gameName, rpSessionId, decision.type(), pending.score());
            return;
        }

        // 高分候选和累计达到阈值的候选都立即交给主动发言服务异步消费。
        if (proactiveSpeechService != null) {
            proactiveSpeechService.requestExpressionSpeech(rpSessionId);
        }
    }

    /**
     * 把评分后的表达候选压成待发送对象。
     *
     * @param gameName    游戏名
     * @param rpSessionId RP 会话 id
     * @param candidate   原始表达候选
     * @param decision    底层评分决策
     * @return 可交给表达队列处理的对象
     */
    private PendingRpExpression toPendingExpression(String gameName,
                                                    String rpSessionId,
                                                    GameExpressionCandidate candidate,
                                                    GameExpressionDecision decision) {
        return new PendingRpExpression(
                rpSessionId,
                gameName,
                decision.finalScore(),
                normalize(candidate.payload().type()),
                normalize(candidate.payload().urgency()),
                normalize(candidate.payload().innerThought()),
                normalize(firstNonBlank(candidate.payload().reason(), decision.reason())),
                candidate.sourceAction(),
                candidate.createdAt()
        );
    }

    /**
     * 从 gamer 会话 key 还原 RP 会话 id。
     *
     * @param gameName      游戏名
     * @param gamerMemoryId gamer 会话 key
     * @return RP 会话 id
     */
    private String rpSessionId(String gameName, String gamerMemoryId) {
        String gameSessionId = gameSessionId(gameName, gamerMemoryId);
        if (activeGameRegistry != null) {
            ActiveGameSession activeSession = activeGameRegistry.get(gameName, gameSessionId);
            if (activeSession != null) {
                return activeSession.getRpSessionId();
            }
        }
        return gameSessionId;
    }

    /**
     * 从 gamer 会话 key 还原游戏侧 session id。
     *
     * @param gameName      游戏名
     * @param gamerMemoryId gamer 会话 key
     * @return 游戏侧 session id
     */
    private String gameSessionId(String gameName, String gamerMemoryId) {
        String prefix = gameName + "-";
        if (gamerMemoryId != null && gamerMemoryId.startsWith(prefix)) {
            return gamerMemoryId.substring(prefix.length());
        }
        return gamerMemoryId == null || gamerMemoryId.isBlank() ? "default" : gamerMemoryId;
    }

    /**
     * 生成人类可读的触发行动摘要。
     *
     * @param summary    gamer 决策摘要
     * @param operations 操作列表
     * @return 优先使用 summary，缺失时回退到 operation.note
     */
    private String sourceAction(String summary, List<GameOperation> operations) {
        String normalizedSummary = normalize(summary);
        if (!normalizedSummary.isBlank()) {
            return normalizedSummary;
        }
        if (operations == null || operations.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (GameOperation operation : operations) {
            String note = operation == null ? "" : normalize(operation.note());
            if (note.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(note);
            count++;
            if (count >= 2) {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * 返回第一段非空文本。
     *
     * @param values 候选文本
     * @return 非空文本；都为空时返回空字符串
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    /**
     * 合并空白字符。
     *
     * @param value 原始文本
     * @return 单行文本
     */
    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
