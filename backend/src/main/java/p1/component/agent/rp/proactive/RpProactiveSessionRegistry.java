package p1.component.agent.rp.proactive;

import lombok.CustomLog;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static p1.utils.SessionUtil.normalizeSessionId;

/**
 * RP 主动发言的在线会话注册表。
 * <p>
 * 该组件记录前端订阅、最近说话时间和当前角色名。主动发言只对在线会话触发，
 * 这样空闲扫描不会对已经关闭的前端继续调用 LLM。
 */
@Component
@CustomLog
public class RpProactiveSessionRegistry {

    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    /**
     * 登记前端打开了一个实时消息订阅。
     *
     * @param sessionId     RP 会话 id
     * @param characterName 当前角色名
     * @param shortMode     前端订阅提供的短句模式初值
     */
    public void openSubscription(String sessionId, String characterName, Boolean shortMode) {
        SessionState state = state(sessionId);
        int previousSubscribers = state.subscriberCount.getAndIncrement();
        state.updateCharacter(characterName);
        state.updateShortMode(shortMode);
        state.ensureSpeechClockStarted(previousSubscribers == 0);
        log.debug("[RP主动发言] 前端已订阅实时消息: session={}, subscribers={}",
                state.sessionId, state.subscriberCount.get());
    }

    /**
     * 登记一个实时消息订阅关闭。
     *
     * @param sessionId RP 会话 id
     */
    public void closeSubscription(String sessionId) {
        SessionState state = sessions.get(normalizeSessionId(sessionId));
        if (state == null) {
            return;
        }
        int remaining = state.subscriberCount.updateAndGet(value -> Math.max(0, value - 1));
        log.debug("[RP主动发言] 前端实时消息订阅关闭: session={}, subscribers={}", state.sessionId, remaining);
    }

    /**
     * 记录用户在 RP 会话中开口。
     *
     * @param sessionId     RP 会话 id
     * @param characterName 当前角色名
     */
    public void observeUserSpeech(String sessionId, String characterName, boolean shortMode) {
        SessionState state = state(sessionId);
        state.updateCharacter(characterName);
        state.observeUserSpeech(shortMode);
    }

    /**
     * 记录 RP 已经说出一条消息。
     *
     * @param sessionId RP 会话 id
     */
    public void observeRpSpeech(String sessionId) {
        state(sessionId).touchSpeech();
    }

    /**
     * 记录一次空闲主动发言。
     * <p>
     * 非游戏状态下若用户持续不回复，空闲发言计数会逐步增加；
     * 游戏状态下不累计该衰减计数，避免游戏中途表达被静默压到很久以后。
     *
     * @param sessionId RP 会话 id
     * @param gameMode  true 表示当前仍在游戏模式
     */
    public void observeIdleProactiveSpeech(String sessionId, boolean gameMode) {
        SessionState state = state(sessionId);
        state.touchSpeech();
        if (!gameMode) {
            state.increaseNonGameIdleSpeechCount();
        }
    }

    /**
     * 记录 RP 会话的可复用上下文，但不把它标记为在线订阅。
     *
     * @param sessionId     RP 会话 id
     * @param characterName 当前角色名
     * @param shortMode     当前显示短句模式；缺省时不覆盖已有值
     */
    public void rememberSessionContext(String sessionId, String characterName, Boolean shortMode) {
        SessionState state = state(sessionId);
        state.updateCharacter(characterName);
        state.updateShortMode(shortMode);
    }

    /**
     * 查询已经记录过角色上下文的 RP 会话快照，不要求前端 live 订阅仍在线。
     *
     * @param sessionId RP 会话 id
     * @return 已知 RP 会话快照
     */
    public Optional<SessionSnapshot> findKnown(String sessionId) {
        SessionState state = sessions.get(normalizeSessionId(sessionId));
        if (state == null || state.characterName == null || state.characterName.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(state.snapshot());
    }

    /**
     * 查询可用于主动发言的在线会话快照。
     *
     * @param sessionId RP 会话 id
     * @return 当前会话快照
     */
    public Optional<SessionSnapshot> findOnline(String sessionId) {
        SessionState state = sessions.get(normalizeSessionId(sessionId));
        if (state == null || !state.online()) {
            return Optional.empty();
        }
        return Optional.of(state.snapshot());
    }

    /**
     * 判断指定快照之后用户是否又发来了新消息。
     *
     * @param sessionId             RP 会话 id
     * @param knownLastUserSpeechAt 调度主动发言时已知的最近用户发言时间
     * @return true 表示主动发言生成期间用户已经重新开口
     */
    public boolean userSpokeAfter(String sessionId, Instant knownLastUserSpeechAt) {
        SessionState state = sessions.get(normalizeSessionId(sessionId));
        if (state == null || knownLastUserSpeechAt == null) {
            return false;
        }
        return state.lastUserSpeechAt != null && state.lastUserSpeechAt.isAfter(knownLastUserSpeechAt);
    }

    /**
     * 找出已达到空闲阈值的在线会话。
     *
     * @param idleThreshold 空闲阈值
     * @return 可申请闲置发言的会话快照
     */
    public List<SessionSnapshot> findIdleOnlineSessions(Duration idleThreshold) {
        Instant now = Instant.now();
        return sessions.values().stream()
                .filter(SessionState::online)
                .filter(state -> state.characterName != null && !state.characterName.isBlank())
                .filter(state -> Duration.between(state.lastSpeechAt, now).compareTo(idleThreshold) >= 0)
                .map(SessionState::snapshot)
                .toList();
    }

    /**
     * 查询全部在线主动发言会话。
     *
     * @return 在线会话快照
     */
    public List<SessionSnapshot> findOnlineSessions() {
        return sessions.values().stream()
                .filter(SessionState::online)
                .filter(state -> state.characterName != null && !state.characterName.isBlank())
                .map(SessionState::snapshot)
                .toList();
    }

    /**
     * 获取或创建会话状态。
     *
     * @param sessionId RP 会话 id
     * @return 可更新的会话状态
     */
    private SessionState state(String sessionId) {
        String normalized = normalizeSessionId(sessionId);
        return sessions.computeIfAbsent(normalized, SessionState::new);
    }

    /**
     * 在线 RP 会话快照。
     *
     * @param sessionId     RP 会话 id
     * @param characterName 当前角色名
     * @param lastSpeechAt  最近一方开口时间
     * @param lastUserSpeechAt 最近用户开口时间
     * @param shortMode      最近一次聊天请求是否启用短句模式
     * @param nonGameIdleSpeechCount 用户持续不回复时的非游戏空闲发言次数
     */
    public record SessionSnapshot(
            String sessionId,
            String characterName,
            Instant lastSpeechAt,
            Instant lastUserSpeechAt,
            boolean shortMode,
            int nonGameIdleSpeechCount
    ) {
    }

    /**
     * 内部可变会话状态。
     */
    private static final class SessionState {
        private final String sessionId;
        private final AtomicInteger subscriberCount = new AtomicInteger();
        private volatile String characterName = "";
        private volatile Instant lastSpeechAt = Instant.now();
        private volatile Instant lastUserSpeechAt = Instant.now();
        private volatile boolean shortMode;
        private final AtomicInteger nonGameIdleSpeechCount = new AtomicInteger();

        private SessionState(String sessionId) {
            this.sessionId = sessionId;
        }

        /**
         * 更新最近说话时间。
         */
        private synchronized void touchSpeech() {
            lastSpeechAt = nextSpeechInstant();
        }

        /**
         * 记录用户发言并重置非游戏空闲衰减。
         *
         * @param nextShortMode 本次请求使用的短句模式
         */
        private synchronized void observeUserSpeech(boolean nextShortMode) {
            Instant now = nextSpeechInstant();
            lastSpeechAt = now;
            lastUserSpeechAt = now;
            shortMode = nextShortMode;
            nonGameIdleSpeechCount.set(0);
        }

        /**
         * 同一会话内的发言时间必须严格递增，否则低精度系统时钟会让相邻用户输入被当成同一 tick。
         */
        private Instant nextSpeechInstant() {
            Instant now = Instant.now();
            Instant previous = lastSpeechAt;
            if (lastUserSpeechAt != null && (previous == null || lastUserSpeechAt.isAfter(previous))) {
                previous = lastUserSpeechAt;
            }
            if (previous != null && !now.isAfter(previous)) {
                return previous.plusNanos(1);
            }
            return now;
        }

        /**
         * 使用前端订阅携带的展示模式初始化会话。
         *
         * @param nextShortMode 短句模式；缺省时不覆盖已有值
         */
        private void updateShortMode(Boolean nextShortMode) {
            if (nextShortMode != null) {
                shortMode = nextShortMode;
            }
        }

        /**
         * 增加用户未回复期间的非游戏主动发言次数。
         */
        private void increaseNonGameIdleSpeechCount() {
            nonGameIdleSpeechCount.incrementAndGet();
        }

        /**
         * 订阅刚建立时启动空闲计时，避免重新打开前端后立刻被判定为长期沉默。
         *
         * @param firstOnlineSubscriber true 表示当前会话刚从离线恢复
         */
        private synchronized void ensureSpeechClockStarted(boolean firstOnlineSubscriber) {
            if (firstOnlineSubscriber || lastSpeechAt == null) {
                lastSpeechAt = nextSpeechInstant();
            }
        }

        /**
         * 非空角色名才覆盖现有角色，防止一次缺省请求抹掉可用上下文。
         *
         * @param nextCharacterName 新角色名
         */
        private void updateCharacter(String nextCharacterName) {
            if (nextCharacterName != null && !nextCharacterName.isBlank()) {
                characterName = nextCharacterName.trim();
            }
        }

        /**
         * 判断会话是否仍有前端订阅。
         *
         * @return true 表示至少一个前端在线
         */
        private boolean online() {
            return subscriberCount.get() > 0;
        }

        /**
         * 生成不可变快照。
         *
         * @return 会话快照
         */
        private SessionSnapshot snapshot() {
            return new SessionSnapshot(
                    sessionId,
                    characterName,
                    lastSpeechAt,
                    lastUserSpeechAt,
                    shortMode,
                    nonGameIdleSpeechCount.get());
        }
    }
}
