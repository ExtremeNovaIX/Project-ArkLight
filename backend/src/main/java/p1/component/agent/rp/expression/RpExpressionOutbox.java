package p1.component.agent.rp.expression;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import p1.config.prop.AssistantProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * RP 表达冲动待发送队列。
 * <p>
 * 每个 RP 会话只保留一个最应该被消化的表达冲动；短时间内的新冲动会按分数替换，
 * 避免游戏高频操作把 RP 请求上下文刷成一串碎碎念。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RpExpressionOutbox {

    private final ConcurrentMap<String, PendingRpExpression> pendingBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Instant> lastAcceptedAtBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ExpressionDesireState> desireBySession = new ConcurrentHashMap<>();
    private final AssistantProperties assistantProperties;

    /**
     * 尝试放入一个待表达冲动。
     *
     * @param expression 待表达冲动
     * @return 是否进入待发送队列
     */
    public boolean offer(PendingRpExpression expression) {
        if (expression == null || expression.rpSessionId() == null || expression.rpSessionId().isBlank()) {
            return false;
        }
        if (expression.innerThought() == null || expression.innerThought().isBlank()) {
            return false;
        }

        return offerPending(expression);
    }

    /**
     * 累积一次没有直接达到主动表达阈值的表达候选。
     * <p>
     * 该路径保留单次候选评分器的筛选结果，只把已经进入 STORE_ONLY 的候选按权重累计为
     * 会话级表达欲。表达欲越过阈值后，仍复用待发送队列的冷却与替换规则。
     *
     * @param expression 待累计的表达候选
     * @param finalScore 单次候选经评分器修正后的分数
     * @return 是否成功触发了一个待发送表达
     */
    public boolean accumulate(PendingRpExpression expression, int finalScore) {
        if (expression == null || expression.rpSessionId() == null || expression.rpSessionId().isBlank()) {
            return false;
        }
        if (expression.innerThought() == null || expression.innerThought().isBlank()) {
            return false;
        }

        DesireTrigger trigger = new DesireTrigger();
        desireBySession.compute(expression.rpSessionId(), (sessionId, existing) -> {
            ExpressionDesireState state = existing == null ? new ExpressionDesireState() : existing;
            state.addPressure(weightedDesire(finalScore));
            if (state.pressure() >= desireThreshold()) {
                trigger.pending = pressurePending(expression, state.pressure());
            }
            return state;
        });
        return trigger.pending != null && offerPending(trigger.pending);
    }

    /**
     * 记录 RP 已经实际开口。
     * <p>
     * 表达欲只在真正说出文本后清空；单纯消费待发送候选但被冷却或生成失败时，
     * 表达欲仍保留，后续事件可再次触发。
     *
     * @param rpSessionId RP 会话 id
     */
    public void clearDesireAfterSpeech(String rpSessionId) {
        if (rpSessionId == null || rpSessionId.isBlank()) {
            return;
        }
        desireBySession.remove(rpSessionId);
    }

    /**
     * 按原有队列规则尝试放入一个待表达冲动。
     *
     * @param expression 待表达冲动
     * @return 是否进入待发送队列
     */
    private boolean offerPending(PendingRpExpression expression) {
        Instant now = Instant.now();
        Holder accepted = new Holder(false);
        pendingBySession.compute(expression.rpSessionId(), (sessionId, existing) -> {
            if (shouldReplace(existing, expression, now)) {
                accepted.value = true;
                return expression;
            }
            return existing;
        });

        if (accepted.value) {
            lastAcceptedAtBySession.put(expression.rpSessionId(), now);
            log.debug("[RP表达欲] 已进入待表达队列: rpSession={}, score={}, type={}",
                    expression.rpSessionId(), expression.score(), expression.type());
        }
        return accepted.value;
    }

    /**
     * 取出并移除某个 RP 会话的待表达冲动。
     *
     * @param rpSessionId RP 会话 id
     * @return 待注入的表达冲动
     */
    public Optional<PendingRpExpression> consume(String rpSessionId) {
        if (rpSessionId == null || rpSessionId.isBlank()) {
            return Optional.empty();
        }
        PendingRpExpression pending = pendingBySession.remove(rpSessionId);
        if (pending == null) {
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    /**
     * 判断新表达是否应替换旧表达。
     *
     * @param existing 当前待表达冲动
     * @param incoming 新冲动
     * @param now      当前时间
     * @return true 表示接收新冲动
     */
    private boolean shouldReplace(PendingRpExpression existing, PendingRpExpression incoming, Instant now) {
        if (existing == null) {
            Instant lastAcceptedAt = lastAcceptedAtBySession.get(incoming.rpSessionId());
            if (lastAcceptedAt == null) {
                return true;
            }
            boolean coolingDown = Duration.between(lastAcceptedAt, now).compareTo(pendingCooldown()) < 0;
            return !coolingDown || incoming.score() >= 85;
        }

        boolean stale = Duration.between(existing.createdAt(), now).compareTo(pendingStaleAfter()) > 0;
        boolean muchStronger = incoming.score() >= existing.score() + expressionProperties().getReplaceBonus();
        boolean noCooldown = Duration.between(lastAcceptedAtBySession.getOrDefault(incoming.rpSessionId(), Instant.EPOCH), now)
                .compareTo(pendingCooldown()) >= 0;
        return stale || muchStronger || (noCooldown && incoming.score() >= existing.score());
    }

    /**
     * 获取表达候选接收冷却。
     *
     * @return 当前会话接收候选的最小间隔
     */
    private Duration pendingCooldown() {
        return Duration.ofMillis(Math.max(1L, expressionProperties().getPendingCooldownMs()));
    }

    /**
     * 获取待发送候选的过期时间。
     *
     * @return 表达候选可被视作过期的时长
     */
    private Duration pendingStaleAfter() {
        return Duration.ofMillis(Math.max(1L, expressionProperties().getPendingStaleMs()));
    }

    /**
     * 获取游戏表达配置。
     *
     * @return RP 游戏表达配置
     */
    private AssistantProperties.ExpressionConfig expressionProperties() {
        return assistantProperties.getRp().getExpression();
    }

    /**
     * 根据累计表达欲生成待表达对象。
     *
     * @param expression 最新表达候选
     * @param pressure   当前累计表达欲
     * @return 交给原待发送队列处理的表达对象
     */
    private PendingRpExpression pressurePending(PendingRpExpression expression, int pressure) {
        return new PendingRpExpression(
                expression.rpSessionId(),
                expression.gameName(),
                Math.min(100, pressure),
                firstNonBlank(expression.type(), "self_talk"),
                firstNonBlank(expression.urgency(), "soon"),
                expression.innerThought(),
                "近期游戏行动累积出表达冲动",
                expression.sourceAction(),
                expression.createdAt());
    }

    /**
     * 计算单次 STORE_ONLY 候选对累计表达欲的贡献。
     *
     * @param finalScore 单次候选最终分
     * @return 本次累计表达欲增量
     */
    private int weightedDesire(int finalScore) {
        int score = Math.max(0, finalScore);
        int percent = Math.max(0, expressionProperties().getDesireScoreWeightPercent());
        if (score == 0 || percent == 0) {
            return 0;
        }
        return Math.max(1, score * percent / 100);
    }

    /**
     * 获取累计表达欲触发阈值。
     *
     * @return 表达欲阈值
     */
    private int desireThreshold() {
        return Math.max(1, expressionProperties().getDesireThreshold());
    }

    /**
     * 合并多余空白字符。
     *
     * @param value 原始文本
     * @return 单行文本
     */
    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    /**
     * 返回第一段非空文本。
     *
     * @param values 候选文本
     * @return 第一段非空文本；没有时返回空字符串
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
     * compute lambda 内部使用的可变布尔值。
     */
    private static final class Holder {
        private boolean value;

        private Holder(boolean value) {
            this.value = value;
        }
    }

    /**
     * 单个 RP 会话的累计表达欲状态。
     */
    private static final class ExpressionDesireState {
        private int pressure;

        /**
         * 累加表达欲。
         *
         * @param delta 本次表达欲增量
         */
        private void addPressure(int delta) {
            pressure = Math.max(0, pressure + delta);
        }

        /**
         * 获取当前表达欲。
         *
         * @return 表达欲
         */
        private int pressure() {
            return pressure;
        }
    }

    /**
     * desireBySession.compute 中回传累计触发结果的容器。
     */
    private static final class DesireTrigger {
        private PendingRpExpression pending;
    }
}
