package p1.component.agent.interaction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.loop.ActiveGameSession;
import p1.config.prop.AssistantProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static p1.utils.SessionUtil.normalizeSessionId;

/**
 * RP 与 gamer 之间的交互调度器。
 * <p>
 * 该组件只管理“此刻谁占用交互窗口”的短生命周期 hold。它不替代游戏循环生命周期，
 * 也不承载用户交给 gamer 的新意图；后者仍由游戏会话状态和GameInterruptService负责。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InteractionCoordinator {

    private final ActiveGameRegistry activeGameRegistry;
    private final AssistantProperties assistantProperties;
    private final ConcurrentMap<String, SessionHolds> holdsByRpSession = new ConcurrentHashMap<>();

    /**
     * 标记一次用户消息回合已经开始。
     * <p>
     * HTTP 文本请求进入后立即持有该 lease，使 gamer 在 RP 准备回应期间先让出交互窗口。
     *
     * @param rpSessionId RP 会话 id
     * @return 需要在回复结束后关闭的交互 lease
     */
    public InteractionLease beginUserTurn(String rpSessionId) {
        return acquire(rpSessionId, HoldKind.USER_TURN, userTurnTtl());
    }

    /**
     * 预留给输入框活动、语音 VAD 等未来上游信号的用户活动 lease。
     *
     * @param rpSessionId RP 会话 id
     * @return 由上游活动结束或超时关闭的交互 lease
     */
    public InteractionLease beginUserActivity(String rpSessionId) {
        return acquire(rpSessionId, HoldKind.USER_ACTIVITY, userActivityTtl());
    }

    /**
     * 记录一次前端输入活动并续期用户活动窗口。
     * <p>
     * typing 上报是心跳，不要求前端显式关闭；同一会话只维护一条可过期占用，
     * 当输入停止超过配置窗口后会自动让出 gamer 行动权。
     *
     * @param rpSessionId RP 会话 id
     */
    public void observeUserActivity(String rpSessionId) {
        renew(rpSessionId, HoldKind.USER_ACTIVITY, userActivityTtl());
    }

    /**
     * 在 RP 首个可见响应字符到达时标记角色开始说话。
     *
     * @param rpSessionId RP 会话 id
     * @return 需要在当前响应流结束时关闭的交互 lease
     */
    public InteractionLease beginRpSpeech(String rpSessionId) {
        return acquire(rpSessionId, HoldKind.RP_SPEAKING, rpSpeechTtl());
    }

    /**
     * 判断当前 RP 会话绑定的 gamer 是否可以开始或继续行动。
     *
     * @param rpSessionId RP 会话 id
     * @return 允许结果；被占用时携带阻塞原因
     */
    public GameTurnPermission canGameActForRpSession(String rpSessionId) {
        String normalizedSessionId = normalizeSessionId(rpSessionId);
        SessionHolds holds = holdsByRpSession.get(normalizedSessionId);
        if (holds == null) {
            return GameTurnPermission.allow();
        }
        Optional<HoldKind> blocker = holds.firstActiveBlocker(Instant.now());
        if (blocker.isEmpty()) {
            holdsByRpSession.remove(normalizedSessionId, holds);
            return GameTurnPermission.allow();
        }
        return GameTurnPermission.block(blocker.get().description());
    }

    /**
     * 判断后台主动发言是否可以开始生成。
     * <p>
     * 用户消息或另一段 RP 发言已经占住交互窗口时，主动发言应让路；
     * 用户请求对应的正常 RP 回复不走该入口。
     *
     * @param rpSessionId RP 会话 id
     * @return 可开始结果
     */
    public GameTurnPermission canStartBackgroundSpeech(String rpSessionId) {
        return canGameActForRpSession(rpSessionId);
    }

    /**
     * 通过 gamer 会话定位其绑定的 RP 会话，再判断当前操作能否继续。
     *
     * @param gameName      游戏名
     * @param gamerMemoryId gamer 跨游戏会话 key
     * @return 允许结果；未找到活跃游戏会话时默认放行
     */
    public GameTurnPermission canGameActForGamer(String gameName, String gamerMemoryId) {
        ActiveGameSession session = activeGameRegistry.get(gameName, gameSessionId(gameName, gamerMemoryId));
        if (session == null || session.getState() == ActiveGameSession.State.STOPPED) {
            return GameTurnPermission.allow();
        }
        return canGameActForRpSession(session.getRpSessionId());
    }

    /**
     * 申请一个短生命周期交互占用。
     *
     * @param rpSessionId RP 会话 id
     * @param kind        占用类型
     * @param ttl         兜底超时，防止异常路径遗留 hold
     * @return 可关闭 lease
     */
    private InteractionLease acquire(String rpSessionId, HoldKind kind, Duration ttl) {
        String normalizedSessionId = normalizeSessionId(rpSessionId);
        String leaseId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(ttl);
        holdsByRpSession.computeIfAbsent(normalizedSessionId, ignored -> new SessionHolds())
                .put(leaseId, new Hold(kind, expiresAt));
        log.debug("[交互调度] 已占用会话交互窗口: session={}, kind={}, ttlMs={}",
                normalizedSessionId, kind, ttl.toMillis());
        return new InteractionLease(normalizedSessionId, leaseId, kind);
    }

    /**
     * 续期无需显式释放的上游活动心跳。
     *
     * @param rpSessionId RP 会话 id
     * @param kind        占用类型
     * @param ttl         本次心跳续期时长
     */
    private void renew(String rpSessionId, HoldKind kind, Duration ttl) {
        String normalizedSessionId = normalizeSessionId(rpSessionId);
        Instant expiresAt = Instant.now().plus(ttl);
        holdsByRpSession.computeIfAbsent(normalizedSessionId, ignored -> new SessionHolds())
                .put(pulseId(kind), new Hold(kind, expiresAt));
        log.debug("[交互调度] 已续期会话交互窗口: session={}, kind={}, ttlMs={}",
                normalizedSessionId, kind, ttl.toMillis());
    }

    /**
     * 给无需显式释放的占用心跳生成稳定键。
     *
     * @param kind 占用类型
     * @return 同一占用类型复用的心跳键
     */
    private String pulseId(HoldKind kind) {
        return "pulse-" + kind.name();
    }

    /**
     * 释放指定 lease。
     *
     * @param lease 待释放 lease
     */
    private void release(InteractionLease lease) {
        SessionHolds holds = holdsByRpSession.get(lease.rpSessionId);
        if (holds == null) {
            return;
        }
        holds.remove(lease.leaseId);
        if (holds.empty()) {
            holdsByRpSession.remove(lease.rpSessionId, holds);
        }
        log.debug("[交互调度] 已释放会话交互窗口: session={}, kind={}", lease.rpSessionId, lease.kind);
    }

    /**
     * 从 gamer memoryId 还原游戏侧 session id。
     *
     * @param gameName      游戏名
     * @param gamerMemoryId gamer 跨游戏会话 key
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
     * 获取用户文本回合的超时兜底。
     *
     * @return 用户文本回合 TTL
     */
    private Duration userTurnTtl() {
        return Duration.ofMillis(Math.max(1000L, interactionProperties().getUserTurnTtlMs()));
    }

    /**
     * 获取未来输入活动信号的超时兜底。
     *
     * @return 用户活动 TTL
     */
    private Duration userActivityTtl() {
        return Duration.ofMillis(Math.max(1000L, interactionProperties().getUserActivityTtlMs()));
    }

    /**
     * 获取 RP 发言流的超时兜底。
     *
     * @return RP 发言 TTL
     */
    private Duration rpSpeechTtl() {
        return Duration.ofMillis(Math.max(1000L, interactionProperties().getRpSpeechTtlMs()));
    }

    /**
     * 获取交互调度配置。
     *
     * @return 当前交互调度配置
     */
    private AssistantProperties.InteractionConfig interactionProperties() {
        return assistantProperties.getInteraction();
    }

    /**
     * 一次可关闭的交互占用 lease。
     */
    public final class InteractionLease implements AutoCloseable {
        private final String rpSessionId;
        private final String leaseId;
        private final HoldKind kind;
        private volatile boolean closed;

        private InteractionLease(String rpSessionId, String leaseId, HoldKind kind) {
            this.rpSessionId = rpSessionId;
            this.leaseId = leaseId;
            this.kind = kind;
        }

        /**
         * 释放本次交互占用。
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            release(this);
        }
    }

    /**
     * gamer 行动许可。
     *
     * @param allowed true 表示当前可行动
     * @param reason  阻塞说明
     */
    public record GameTurnPermission(boolean allowed, String reason) {
        private static GameTurnPermission allow() {
            return new GameTurnPermission(true, "");
        }

        private static GameTurnPermission block(String reason) {
            return new GameTurnPermission(false, reason);
        }
    }

    /**
     * 会阻塞 gamer 的短生命周期占用类型。
     */
    private enum HoldKind {
        USER_TURN("用户消息正在处理"),
        USER_ACTIVITY("用户正在输入或说话"),
        RP_SPEAKING("RP 正在说话");

        private final String description;

        HoldKind(String description) {
            this.description = description;
        }

        private String description() {
            return description;
        }
    }

    /**
     * 单个会话当前持有的交互占用。
     */
    private static final class SessionHolds {
        private final Map<String, Hold> holds = new ConcurrentHashMap<>();

        private void put(String leaseId, Hold hold) {
            holds.put(leaseId, hold);
        }

        private void remove(String leaseId) {
            holds.remove(leaseId);
        }

        private boolean empty() {
            return holds.isEmpty();
        }

        private Optional<HoldKind> firstActiveBlocker(Instant now) {
            holds.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
            return holds.values().stream()
                    .map(Hold::kind)
                    .findFirst();
        }
    }

    /**
     * 单条占用记录。
     *
     * @param kind      占用类型
     * @param expiresAt 兜底过期时间
     */
    private record Hold(HoldKind kind, Instant expiresAt) {
    }
}
