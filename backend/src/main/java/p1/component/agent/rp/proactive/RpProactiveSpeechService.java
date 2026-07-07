package p1.component.agent.rp.proactive;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.loop.ActiveGameSession;
import p1.component.agent.interaction.InteractionCoordinator;
import p1.component.agent.rp.context.SummaryCacheManager;
import p1.component.agent.rp.core.CharacterPromptRegistry;
import p1.component.agent.rp.core.RpSpeechTurnService;
import p1.config.prop.AssistantProperties;
import p1.infrastructure.mdc.ChatSessionMetrics;
import p1.utils.ChatMessageUtil;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RP 主动发言调度服务。
 * <p>
 * 空闲触发会落到该服务：先确认前端在线和冷却窗口，再异步调用
 * 主动发言生成器，最后只把真实说出的 AI 文本写回 RP 记忆并实时投递给前端。
 */
@Service
@RequiredArgsConstructor
@CustomLog
public class RpProactiveSpeechService {

    private static final int RECENT_CONTEXT_LIMIT = 8;

    private final RpProactiveAgent proactiveAgent;
    private final CharacterPromptRegistry characterPromptRegistry;
    private final SummaryCacheManager summaryCacheManager;
    private final ChatMemoryProvider chatMemoryProvider;
    private final RpProactiveSessionRegistry sessionRegistry;
    private final RpLiveMessageHub messageHub;
    private final ActiveGameRegistry activeGameRegistry;
    private final AssistantProperties assistantProperties;
    private final ChatSessionMetrics chatSessionMetrics;
    private final RpSpeechTurnService rpSpeechTurnService;
    private final InteractionCoordinator interactionCoordinator;
    @Qualifier("asyncTaskExecutor")
    private final Executor asyncTaskExecutor;

    private final ConcurrentMap<String, AtomicBoolean> speakingBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Instant> lastAttemptAtBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, GameSpeechBudget> gameSpeechBudgetBySession = new ConcurrentHashMap<>();

    /**
     * 定时为长期沉默的在线 RP 会话申请闲置发言。
     */
    @Scheduled(fixedDelayString = "${assistant.rp.proactive.idle-scan-interval-ms:10000}")
    public void scheduleIdleSpeech() {
        if (!enabled()) {
            return;
        }
        Instant now = Instant.now();
        for (RpProactiveSessionRegistry.SessionSnapshot session : sessionRegistry.findOnlineSessions()) {
            boolean gameMode = activeGameRegistry.findBySessionId(session.sessionId()).isPresent();
            Duration threshold = idleThreshold(session, gameMode);
            if (Duration.between(session.lastSpeechAt(), now).compareTo(threshold) >= 0) {
                requestIdleSpeech(session);
            }
        }
    }

    /**
     * 为一个空闲会话生成主动发言。
     *
     * @param session 空闲会话快照
     */
    private void speakFromIdle(RpProactiveSessionRegistry.SessionSnapshot session) {
        RpProactiveSessionRegistry.SessionSnapshot latest = sessionRegistry.findOnline(session.sessionId()).orElse(null);
        if (latest == null) {
            return;
        }
        boolean gameMode = activeGameRegistry.findBySessionId(latest.sessionId()).isPresent();
        if (Duration.between(latest.lastSpeechAt(), Instant.now()).compareTo(idleThreshold(latest, gameMode)) < 0) {
            log.debug("[RP主动发言] 空闲 grace 窗口内已有新活动，跳过主动消息: session={}", latest.sessionId());
            return;
        }
        String triggerContext = """
                当前会话已经明显安静了一段时间。
                这次开口可以是自言自语，也可以自然地向对方搭一句话。
                不要假设对方刚刚发来了新消息。
                """.trim();
        speak(latest, "idle", triggerContext, null);
    }

    /**
     * 统一执行一次主动发言。
     *
     * @param session        在线 RP 会话
     * @param source         触发来源
     * @param triggerContext 触发说明
     * @param preferredGame  指定游戏；空闲触发时为空
     */
    private void speak(RpProactiveSessionRegistry.SessionSnapshot session,
                       String source,
                       String triggerContext,
                       String preferredGame) {
        boolean gameMode = activeGameRegistry.findBySessionId(session.sessionId()).isPresent();
        InteractionCoordinator.GameTurnPermission speechPermission =
                interactionCoordinator.canStartBackgroundSpeech(session.sessionId());
        if (!speechPermission.allowed()) {
            log.debug("[RP主动发言] 当前交互窗口被占用，跳过后台发言: session={}, source={}, reason={}",
                    session.sessionId(), source, speechPermission.reason());
            return;
        }
        if (!enabled() || !tryStart(session.sessionId(), gameMode, source)) {
            return;
        }

        try {
            // 主动发言不经过 ChatController，这里补齐日志所需的会话上下文。
            MDC.put("sessionId", session.sessionId());
            MDC.put("chatRound", String.valueOf(chatSessionMetrics.incrementAndGetRound(session.sessionId())));
            String rolePrompt = characterPromptRegistry.getPrompt(session.characterName());
            String summary = summaryCacheManager.getSummary(session.sessionId());
            String recentContext = recentContext(session.sessionId());
            String gameContext = gameContext(session.sessionId(), preferredGame);
            String speech = normalize(rpSpeechTurnService.collect(
                    session.sessionId(),
                    "proactive-" + source,
                    proactiveAgent.speak(
                            rolePrompt,
                            summary,
                            recentContext,
                            gameContext,
                            source,
                            triggerContext)));
            if (speech.isBlank()) {
                log.debug("[RP主动发言] 模型返回空文本，跳过投递: session={}, source={}", session.sessionId(), source);
                return;
            }
            if (sessionRegistry.userSpokeAfter(session.sessionId(), session.lastUserSpeechAt())) {
                log.debug("[RP主动发言] 生成期间用户已开口，丢弃主动消息: session={}, source={}", session.sessionId(), source);
                return;
            }

            // 只把角色真正说出口的文本写回现有 RP 记忆链路，内部触发上下文不入库。
            chatMemoryProvider.get(session.sessionId()).add(AiMessage.from(speech));
            if ("idle".equals(source)) {
                sessionRegistry.observeIdleProactiveSpeech(session.sessionId(), gameMode);
            } else {
                sessionRegistry.observeRpSpeech(session.sessionId());
            }
            messageHub.publish(session.sessionId(), source, speech, session.shortMode());
            log.info(LogDomain.GAME, "speech.proactive_published", LogOutcome.SUCCEEDED, "sessionId", session.sessionId(), "source", source);
        } catch (Exception e) {
            log.warn(LogDomain.GAME, "speech.proactive_failed", LogOutcome.DEGRADED, "sessionId", session.sessionId(), "source", source, "reason", e.getMessage());
        } finally {
            MDC.remove("chatRound");
            MDC.remove("sessionId");
            speakingBySession.get(session.sessionId()).set(false);
        }
    }

    /**
     * 获取主动发言所需的最近短期上下文。
     *
     * @param sessionId RP 会话 id
     * @return 已裁剪的上下文文本
     */
    private String recentContext(String sessionId) {
        List<ChatMessage> messages = chatMemoryProvider.get(sessionId).messages();
        int fromIndex = Math.max(0, messages.size() - RECENT_CONTEXT_LIMIT);
        return ChatMessageUtil.formatMessageList(messages.subList(fromIndex, messages.size()));
    }

    /**
     * 构建主动发言用的游戏上下文块。
     *
     * @param rpSessionId   RP 会话 id
     * @param preferredGame 指定游戏
     * @return 当前游戏上下文块；非游戏上下文时为空
     */
    private String gameContext(String rpSessionId, String preferredGame) {
        Optional<ActiveGameSession> activeSession = activeGameRegistry.findBySessionId(rpSessionId);
        if (activeSession.isEmpty()) {
            return "";
        }
        ActiveGameSession session = activeSession.get();
        if (preferredGame != null && !preferredGame.isBlank() && !preferredGame.equals(session.getGameName())) {
            return "";
        }
        return """
                <current_game_context>
                <game_mode>
                你当前正在亲自玩游戏。
                game=%s
                loop_state=%s
                本次主动发言只需要根据触发内容和最近对话自然开口，不需要读取当前局势做战术判断。
                </game_mode>
                </current_game_context>
                """.formatted(session.getGameName(), session.getState()).trim();
    }

    /**
     * 尝试占用一个会话的主动发言槽位，并执行冷却判断。
     *
     * @param sessionId RP 会话 id
     * @param gameMode  true 表示当前处于游戏模式
     * @param source    主动发言触发来源
     * @return true 表示本次可以继续调用 LLM
     */
    private boolean tryStart(String sessionId, boolean gameMode, String source) {
        AtomicBoolean speaking = speakingBySession.computeIfAbsent(sessionId, ignored -> new AtomicBoolean());
        if (!speaking.compareAndSet(false, true)) {
            return false;
        }

        Instant now = Instant.now();
        Instant lastAttempt = lastAttemptAtBySession.get(sessionId);
        Duration cooldown = speechCooldown(gameMode);
        if (lastAttempt != null && Duration.between(lastAttempt, now).compareTo(cooldown) < 0) {
            speaking.set(false);
            return false;
        }
        if (gameMode && !reserveGameSpeechBudget(sessionId, now)) {
            speaking.set(false);
            return false;
        }
        lastAttemptAtBySession.put(sessionId, now);
        return true;
    }

    /**
     * 判断主动发言开关。
     *
     * @return true 表示可以主动发言
     */
    private boolean enabled() {
        return proactiveProperties().isEnabled();
    }

    /**
     * 计算空闲主动发言的触发间隔。
     * <p>
     * 游戏中保持固定阈值；非游戏状态下，用户连续不回复时按倍增退避，
     * 直到频率降低到配置的最长间隔。
     *
     * @param session  在线 RP 会话
     * @param gameMode true 表示当前处于游戏模式
     * @return 本轮空闲扫描所需等待时间
     */
    private Duration idleThreshold(RpProactiveSessionRegistry.SessionSnapshot session, boolean gameMode) {
        long baseMs = Math.max(1L, proactiveProperties().getIdleThresholdMs());
        if (gameMode) {
            return Duration.ofMillis(Math.max(1L, proactiveProperties().getGameIdleThresholdMs()));
        }

        long maxMs = Math.max(baseMs, proactiveProperties().getNonGameMaxIdleIntervalMs());
        long intervalMs = baseMs;
        for (int index = 0; index < session.nonGameIdleSpeechCount() && intervalMs < maxMs; index++) {
            intervalMs = intervalMs > maxMs / 2 ? maxMs : intervalMs * 2;
        }
        return Duration.ofMillis(Math.min(intervalMs, maxMs));
    }

    /**
     * 按当前模式选择主动发言冷却。
     *
     * @param gameMode true 表示当前处于游戏模式
     * @return 主动发言最小间隔
     */
    private Duration speechCooldown(boolean gameMode) {
        long cooldownMs;
        if (!gameMode) {
            cooldownMs = proactiveProperties().getSpeechCooldownMs();
        } else {
            cooldownMs = proactiveProperties().getGameSpeechCooldownMs();
        }
        return Duration.ofMillis(Math.max(0L, cooldownMs));
    }

    /**
     * 延迟申请一次空闲主动发言。
     * <p>
     * 空闲扫描命中和用户真实发送消息之间可能只差几秒；先留出 grace 窗口，
     * 再由 {@link #speakFromIdle(RpProactiveSessionRegistry.SessionSnapshot)} 复查活动时间。
     *
     * @param session 空闲扫描命中的会话快照
     */
    private void requestIdleSpeech(RpProactiveSessionRegistry.SessionSnapshot session) {
        CompletableFuture.runAsync(
                () -> speakFromIdle(session),
                CompletableFuture.delayedExecutor(
                        Math.max(0L, proactiveProperties().getIdleSpeechGraceMs()),
                        TimeUnit.MILLISECONDS,
                        asyncTaskExecutor));
    }

    /**
     * 预占一次游戏模式主动发言预算。
     * <p>
     * 预算使用滑动窗口而非固定窗口，避免窗口边界附近连续投递超过配置次数。
     *
     * @param sessionId RP 会话 id
     * @param now       当前时间
     * @return true 表示本次仍在预算内
     */
    private boolean reserveGameSpeechBudget(String sessionId, Instant now) {
        GameSpeechBudget budget = gameSpeechBudgetBySession.computeIfAbsent(sessionId, ignored -> new GameSpeechBudget());
        return budget.reserve(
                now,
                Duration.ofMillis(Math.max(1L, proactiveProperties().getGameProactiveRateWindowMs())),
                Math.max(1, proactiveProperties().getGameProactiveMaxSpeechesPerWindow()));
    }

    /**
     * 获取主动发言配置。
     *
     * @return RP 主动发言配置
     */
    private AssistantProperties.ProactiveConfig proactiveProperties() {
        return assistantProperties.getRp().getProactive();
    }

    /**
     * 合并多余空白。
     *
     * @param value 原始文本
     * @return 单行或保留必要换行的最终文本
     */
    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 单个 RP 会话的游戏主动发言滑动窗口预算。
     */
    private static final class GameSpeechBudget {
        private final Deque<Instant> speechSlots = new ArrayDeque<>();

        /**
         * 尝试预占一次主动发言槽位。
         *
         * @param now       当前时间
         * @param window    滑动窗口时长
         * @param maxSpeech 窗口内最多允许的发言次数
         * @return true 表示预算允许本次发言
         */
        private synchronized boolean reserve(Instant now, Duration window, int maxSpeech) {
            Instant oldestAllowed = now.minus(window);
            while (!speechSlots.isEmpty() && !speechSlots.peekFirst().isAfter(oldestAllowed)) {
                speechSlots.removeFirst();
            }
            if (speechSlots.size() >= maxSpeech) {
                return false;
            }
            speechSlots.addLast(now);
            return true;
        }
    }
}
