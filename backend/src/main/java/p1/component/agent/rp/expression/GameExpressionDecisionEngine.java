package p1.component.agent.rp.expression;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.bridge.GameBridgeActionStatus;
import p1.component.agent.gamer.bridge.GameExpressionPayload;
import p1.config.prop.AssistantProperties;

/**
 * 游戏表达欲评分器。
 * <p>
 * gamer 负责给出主观表达欲，底层再根据执行结果、表达类型、紧急度和操作复杂度做校正，
 * 防止每次普通操作都打扰 RP，也避免技术执行反馈把角色表达方向带偏。
 */
@Component
@RequiredArgsConstructor
public class GameExpressionDecisionEngine {

    private final AssistantProperties assistantProperties;

    /**
     * 评估表达候选是否应注入给 RP。
     *
     * @param candidate 表达候选
     * @return 表达处理决策
     */
    public GameExpressionDecision evaluate(GameExpressionCandidate candidate) {
        if (candidate == null) {
            return new GameExpressionDecision(GameExpressionDecision.DecisionType.DROP, 0, "候选为空");
        }

        GameExpressionPayload payload = candidate.payload() == null
                ? GameExpressionPayload.empty()
                : candidate.payload();
        String innerThought = normalize(payload.innerThought());
        int score = payload.score();

        // 内心活动为空时没有可注入内容，但仍保留软降级，方便从日志观察模型是否漏填。
        if (innerThought.isBlank()) {
            score = Math.min(score, 30);
        }

        score += statusModifier(candidate.status());
        score += urgencyModifier(payload.urgency());
        score += typeModifier(payload.type());
        score += innerThoughtModifier(innerThought);
        score += operationModifier(candidate);
        score += resultModifier(candidate);
        score += expressionProperties().getScoreBias();
        score = clamp(score);

        if (innerThought.isBlank()) {
            return new GameExpressionDecision(GameExpressionDecision.DecisionType.DROP, score, "inner_thought 为空");
        }
        if (score >= expressionProperties().getPendingThreshold()) {
            return new GameExpressionDecision(GameExpressionDecision.DecisionType.PENDING_RP_EXPRESSION, score, "达到 RP 表达阈值");
        }
        if (score >= expressionProperties().getStoreOnlyThreshold()) {
            return new GameExpressionDecision(GameExpressionDecision.DecisionType.STORE_ONLY, score, "只进入行动记忆，不主动表达");
        }
        return new GameExpressionDecision(GameExpressionDecision.DecisionType.DROP, score, "表达欲分数较低");
    }

    /**
     * 根据队列最终状态修正分数。
     *
     * @param status ACTION 状态
     * @return 分数增量
     */
    private int statusModifier(GameBridgeActionStatus status) {
        if (status == GameBridgeActionStatus.INTERRUPTED) {
            return -15;
        }
        if (status == GameBridgeActionStatus.GAME_OVER) {
            return 25;
        }
        if (status == GameBridgeActionStatus.WAIT) {
            return -10;
        }
        return 0;
    }

    /**
     * 根据模型声明的紧急度修正分数。
     *
     * @param urgency 表达紧急度
     * @return 分数增量
     */
    private int urgencyModifier(String urgency) {
        return switch (normalize(urgency).toLowerCase()) {
            case "immediate", "now" -> 15;
            case "soon" -> 5;
            case "defer", "later" -> -10;
            case "silent", "none" -> -40;
            default -> 0;
        };
    }

    /**
     * 根据表达类型修正分数。
     *
     * @param type 表达类型
     * @return 分数增量
     */
    private int typeModifier(String type) {
        return switch (normalize(type).toLowerCase()) {
            case "risk", "replan" -> 15;
            case "regret", "mistake" -> 12;
            case "choice", "uncertain" -> 10;
            case "coordination" -> 8;
            case "confidence", "self_talk" -> 3;
            case "routine" -> -20;
            default -> 0;
        };
    }

    /**
     * 根据内心活动文本本身修正分数。
     * <p>
     * 表达冲动要服务 RP 口吻；如果模型把技术日志、工具参数或 JSON 细节写进来，就降低优先级。
     *
     * @param innerThought 内心活动文本
     * @return 分数增量
     */
    private int innerThoughtModifier(String innerThought) {
        String normalized = normalize(innerThought);
        String lower = normalized.toLowerCase();
        int modifier = 0;
        if (normalized.length() > 120) {
            modifier -= 10;
        }
        if (lower.contains("mcp")
                || lower.contains("json")
                || lower.contains("tool")
                || lower.contains("args")
                || lower.contains("combat_")
                || normalized.contains("桥接层")
                || normalized.contains("队列")
                || normalized.contains("{")
                || normalized.contains("}")) {
            modifier -= 35;
        }
        return modifier;
    }

    /**
     * 根据本次操作数量修正分数。
     *
     * @param candidate 表达候选
     * @return 分数增量
     */
    private int operationModifier(GameExpressionCandidate candidate) {
        int size = candidate.operations() == null ? 0 : candidate.operations().size();
        if (size == 0) {
            return -15;
        }
        if (size >= 3) {
            return 5;
        }
        return 0;
    }

    /**
     * 根据执行结果文本修正分数。
     *
     * @param candidate 表达候选
     * @return 分数增量
     */
    private int resultModifier(GameExpressionCandidate candidate) {
        String joined = (normalize(candidate.result()) + " " + normalize(candidate.interruptReason())).toLowerCase();
        int modifier = 0;
        if (joined.contains("中断")
                || joined.contains("interrupt")
                || joined.contains("失败")
                || joined.contains("错误")
                || joined.contains("跳过")
                || joined.contains("soft")) {
            modifier -= 15;
        }
        if (joined.contains("手牌数量")
                || joined.contains("校验")
                || joined.contains("监视")
                || joined.contains("状态不同步")
                || joined.contains("桥接")
                || joined.contains("mcp")) {
            modifier -= 25;
        }
        if (joined.contains("已成功执行 1/1") || joined.contains("无状态变化")) {
            modifier -= 5;
        }
        return modifier;
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

    /**
     * 限制分数范围。
     *
     * @param score 原始分数
     * @return 0-100 分
     */
    private int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }

    /**
     * 获取游戏表达评分配置。
     *
     * @return RP 游戏表达配置
     */
    private AssistantProperties.ExpressionConfig expressionProperties() {
        return assistantProperties.getRp().getExpression();
    }
}
