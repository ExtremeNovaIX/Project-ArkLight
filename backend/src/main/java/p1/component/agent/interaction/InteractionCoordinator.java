package p1.component.agent.interaction;

import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.loop.ActiveGameSession;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static p1.utils.SessionUtil.normalizeSessionId;

/**
 * 游戏循环交互门控。
 * <p>
 * 该组件只保留会影响 game loop 启动下一轮的显式游戏状态：等待用户和 ASR 正在收集语音。
 * 用户文字输入由 RP 会话的最近用户发言时间驱动，在下一次 game loop 中消费；不会通过这里占用交互窗口。
 */
@Component
@RequiredArgsConstructor
@CustomLog
public class InteractionCoordinator {

    private final ActiveGameRegistry activeGameRegistry;
    private final ConcurrentMap<String, SessionHolds> holdsByRpSession = new ConcurrentHashMap<>();

    /**
     * 登记游戏等待 hold。
     *
     * @param rpSessionId RP 会话 id
     * @param ttl         等待 hold 的兜底保留时间
     */
    public void beginGameWait(String rpSessionId, Duration ttl) {
        renew(rpSessionId, HoldKind.GAME_WAIT, ttl);
    }

    /**
     * 登记 ASR 正在收集语音片段。
     * <p>
     * 该 hold 只阻止 game loop 启动下一轮，不中断已经开始的本轮请求或队列执行。
     *
     * @param rpSessionId RP 会话 id
     * @param ttl         语音输入 hold 的兜底保留时间
     */
    public void beginGameVoiceInput(String rpSessionId, Duration ttl) {
        renew(rpSessionId, HoldKind.GAME_VOICE_INPUT, ttl);
    }

    /**
     * 释放游戏等待 hold。
     *
     * @param rpSessionId RP 会话 id
     */
    public void endGameWait(String rpSessionId) {
        removePulse(rpSessionId, HoldKind.GAME_WAIT);
    }

    /**
     * 释放 ASR 语音收集 hold。
     *
     * @param rpSessionId RP 会话 id
     */
    public void endGameVoiceInput(String rpSessionId) {
        removePulse(rpSessionId, HoldKind.GAME_VOICE_INPUT);
    }

    /**
     * 判断下一轮 game loop 是否可以启动。
     *
     * @param rpSessionId RP 会话 id
     * @return 允许结果；被等待或语音输入占用时携带阻塞原因
     */
    public GameTurnPermission canGameActForRpSession(String rpSessionId) {
        return canActForRpSession(rpSessionId, null);
    }

    /**
     * 判断后台主动发言是否可以启动。
     *
     * @param rpSessionId RP 会话 id
     * @return 允许结果
     */
    public GameTurnPermission canStartBackgroundSpeech(String rpSessionId) {
        return canGameActForRpSession(rpSessionId);
    }

    /**
     * 通过 gamer 会话定位其绑定的 RP 会话，再判断下一轮 game loop 能否启动。
     *
     * @param gameName      游戏名
     * @param gamerMemoryId gamer 跨游戏会话 key
     * @return 允许结果；未找到活跃游戏会话时默认放行
     */
    public GameTurnPermission canGameActForGamer(String gameName, String gamerMemoryId) {
        return withActiveRpSession(gameName, gamerMemoryId)
                .map(rpSessionId -> canActForRpSession(rpSessionId, null))
                .orElseGet(GameTurnPermission::allow);
    }

    /**
     * 判断已经开始的本轮 game 请求是否可以继续执行。
     * <p>
     * ASR 语音收集只暂停下一轮 game loop，不中断本轮请求。等待用户仍然是显式暂停，会阻止继续执行。
     * TODO: 后续允许使用正则、意图识别或其他规则识别玩家指令，并决定是否中断当前请求。
     *
     * @param gameName      游戏名
     * @param gamerMemoryId gamer 跨游戏会话 key
     * @return 允许结果
     */
    public GameTurnPermission canContinueCurrentGameRequestForGamer(String gameName, String gamerMemoryId) {
        return withActiveRpSession(gameName, gamerMemoryId)
                .map(rpSessionId -> canActForRpSession(rpSessionId, HoldKind.GAME_VOICE_INPUT))
                .orElseGet(GameTurnPermission::allow);
    }

    private Optional<String> withActiveRpSession(String gameName, String gamerMemoryId) {
        ActiveGameSession session = activeGameRegistry.get(gameName, gameSessionId(gameName, gamerMemoryId));
        if (session == null || session.getState() == ActiveGameSession.State.STOPPED) {
            return Optional.empty();
        }
        return Optional.of(session.getRpSessionId());
    }

    private GameTurnPermission canActForRpSession(String rpSessionId, HoldKind ignoredKind) {
        String normalizedSessionId = normalizeSessionId(rpSessionId);
        SessionHolds holds = holdsByRpSession.get(normalizedSessionId);
        if (holds == null) {
            return GameTurnPermission.allow();
        }
        Optional<HoldKind> blocker = holds.firstActiveBlocker(Instant.now(), ignoredKind);
        if (blocker.isEmpty()) {
            if (holds.empty()) {
                holdsByRpSession.remove(normalizedSessionId, holds);
            }
            return GameTurnPermission.allow();
        }
        return GameTurnPermission.block(blocker.get().description());
    }

    private void renew(String rpSessionId, HoldKind kind, Duration ttl) {
        String normalizedSessionId = normalizeSessionId(rpSessionId);
        Duration safeTtl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofSeconds(1) : ttl;
        Instant expiresAt = Instant.now().plus(safeTtl);
        holdsByRpSession.computeIfAbsent(normalizedSessionId, ignored -> new SessionHolds())
                .put(pulseId(kind), new Hold(kind, expiresAt));
        log.debug("[交互调度] 已续期游戏交互门控 session={}, kind={}, ttlMs={}",
                normalizedSessionId, kind, safeTtl.toMillis());
    }

    private void removePulse(String rpSessionId, HoldKind kind) {
        String normalizedSessionId = normalizeSessionId(rpSessionId);
        SessionHolds holds = holdsByRpSession.get(normalizedSessionId);
        if (holds == null) {
            return;
        }
        holds.remove(pulseId(kind));
        if (holds.empty()) {
            holdsByRpSession.remove(normalizedSessionId, holds);
        }
        log.debug("[交互调度] 已移除游戏交互门控 session={}, kind={}", normalizedSessionId, kind);
    }

    private String pulseId(HoldKind kind) {
        return "pulse-" + kind.name();
    }

    private String gameSessionId(String gameName, String gamerMemoryId) {
        String prefix = gameName + "-";
        if (gamerMemoryId != null && gamerMemoryId.startsWith(prefix)) {
            return gamerMemoryId.substring(prefix.length());
        }
        return gamerMemoryId == null || gamerMemoryId.isBlank() ? "default" : gamerMemoryId;
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

    private enum HoldKind {
        GAME_VOICE_INPUT("用户正在语音输入"),
        GAME_WAIT("等待用户确认继续");

        private final String description;

        HoldKind(String description) {
            this.description = description;
        }

        private String description() {
            return description;
        }
    }

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

        private Optional<HoldKind> firstActiveBlocker(Instant now, HoldKind ignoredKind) {
            holds.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
            return holds.values().stream()
                    .map(Hold::kind)
                    .filter(kind -> ignoredKind == null || kind != ignoredKind)
                    .findFirst();
        }
    }

    private record Hold(HoldKind kind, Instant expiresAt) {
    }
}
