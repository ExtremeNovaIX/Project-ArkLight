package p1.component.agent.gamer.loop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import p1.component.agent.gamer.GameSessionKey;
import p1.component.agent.gamer.adapter.core.GameActionability;
import p1.component.agent.gamer.adapter.core.GameActionabilityStatus;
import p1.component.agent.gamer.bridge.GameBridgeService;
import p1.component.agent.gamer.bridge.GameStateProbe;
import p1.component.agent.gamer.trace.GamerDecisionTraceService;
import p1.component.agent.interaction.InteractionCoordinator;
import p1.component.agent.rp.game.control.RpGameActionExecutionException;
import p1.component.agent.rp.game.control.RpGameTurnService;
import p1.component.agent.rp.proactive.RpProactiveSessionRegistry;
import p1.config.mcp.GameLoopProperties;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RP 游戏驱动器：短间隔唤醒 RP 观察局势，并在可行动窗口触发 RP 决策。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RpGameDriver {

    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final int SAME_STATE_MAX_WAIT_TICKS = 2;

    private final RpGameTurnService rpGameTurnService;
    private final GameBridgeService bridgeService;
    private final ActiveGameRegistry registry;
    private final GameLoopProperties props;
    private final InteractionCoordinator interactionCoordinator;
    private final GameLoopObservationBackoffService observationBackoffService;
    private final RpProactiveSessionRegistry proactiveSessionRegistry;
    private final GamerDecisionTraceService traceService;
    private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();
    private final Map<String, SameStateObservation> sameStateObservations = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${game.loop.poll-interval-ms:500}")
    public void pollTick() {
        Collection<ActiveGameSession> sessions = registry.listRunning();
        if (sessions.isEmpty()) {
            return;
        }

        for (ActiveGameSession session : sessions) {
            try {
                processSession(session);
            } catch (Exception e) {
                log.error("[RP游戏驱动] 会话处理异常: game={}, session={}",
                        session.getGameName(), session.getSessionId(), e);
            }
        }
    }

    private void processSession(ActiveGameSession session) {
        if (session.getState() != ActiveGameSession.State.RUNNING) {
            return;
        }
        if (observationBackoffService.shouldSkip(session, props)) {
            return;
        }
        ReentrantLock lock = sessionLock(session);
        if (!lock.tryLock()) {
            log.debug("[RP游戏驱动] 会话仍在处理，跳过本次 tick: game={}, session={}",
                    session.getGameName(), session.getSessionId());
            return;
        }
        try {
            processSessionOnce(session);
        } catch (Exception e) {
            log.error("[RP游戏驱动] 行动窗口探测失败: game={}, session={}",
                    session.getGameName(), session.getSessionId(), e);
            handleFailure(session);
            session.touch();
        } finally {
            lock.unlock();
        }
    }

    private void processSessionOnce(ActiveGameSession session) {
        InteractionCoordinator.GameTurnPermission turnPermission =
                interactionCoordinator.canGameActForRpSession(session.getRpSessionId());
        if (!turnPermission.allowed()) {
            log.debug("[RP游戏驱动] 交互窗口被占用: game={}, session={}, reason={}",
                    session.getGameName(), session.getSessionId(), turnPermission.reason());
            observationBackoffService.recordNoEffectiveAction(session, props, turnPermission.reason());
            session.touch();
            return;
        }

        GameStateProbe probe = bridgeService.probeState(session.getGameName(), session.getSessionId());
        GameLoopUserSpeechSignal userSpeechSignal = consumeUserSpeechSignal(session);
        SameStateDecision sameStateDecision = evaluateSameStateObservation(session, probe.stateFingerprint());
        if (sameStateDecision.waitCurrentTick()) {
            session.touch();
            return;
        }

        GameActionability actionability = probe.actionability();
        if (actionability.status() == GameActionabilityStatus.GAME_OVER) {
            log.info("[RP游戏驱动] 检测到游戏结束: game={}, session={}, reason={}",
                    session.getGameName(), session.getSessionId(), actionability.reason());
            session.setState(ActiveGameSession.State.STOPPED);
            observationBackoffService.reset(session);
            clearSameStateObservation(session);
            return;
        }
        if (!actionability.actionable()) {
            log.debug("[RP游戏驱动] 当前不可行动: game={}, session={}, status={}, reason={}",
                    session.getGameName(), session.getSessionId(), actionability.status(), actionability.reason());
            session.resetFailures();
            observationBackoffService.recordNoEffectiveAction(session, props, actionability.reason());
            session.touch();
            return;
        }
        observationBackoffService.reset(session);
        int totalSteps = session.incrementAndGetTotalSteps();
        try {
            rpGameTurnService.play(session, gameLoopInstruction(userSpeechSignal), sameStateDecision.replan());
            session.resetFailures();
            clearSameStateObservation(session);
            log.debug("[RP游戏驱动] 本轮 RP 决策完成: game={}, session={}, totalStep={}",
                    session.getGameName(), session.getSessionId(), totalSteps);
        } catch (RpGameActionExecutionException e) {
            if (e.interactionDeferred()) {
                session.resetFailures();
                observationBackoffService.recordNoEffectiveAction(session, props, e.feedback());
                log.debug("[RP游戏驱动] 动作暂缓，等待交互窗口恢复: game={}, session={}, reason={}",
                        session.getGameName(), session.getSessionId(), e.feedback());
                return;
            }
            session.resetFailures();
            rememberSameStateObservation(session, probe.stateFingerprint(), e.feedback());
            log.warn("[RP游戏驱动] 动作执行失败，等待下一 tick 观察状态: game={}, session={}, reason={}",
                    session.getGameName(), session.getSessionId(), e.feedback());
        } catch (Exception e) {
            log.error("[RP游戏回合] 调用失败: game={}, session={}", session.getGameName(), session.getSessionId(), e);
            handleFailure(session);
        } finally {
            session.touch();
        }
    }

    private SameStateDecision evaluateSameStateObservation(ActiveGameSession session, String currentFingerprint) {
        String key = sessionKey(session);
        SameStateObservation observation = sameStateObservations.get(key);
        if (observation == null) {
            return SameStateDecision.normalDecision();
        }
        if (!observation.stateFingerprint().equals(normalizeFingerprint(currentFingerprint))) {
            sameStateObservations.remove(key);
            log.debug("[RP游戏驱动] 游戏状态已推进，清除同状态观察: game={}, session={}",
                    session.getGameName(), session.getSessionId());
            return SameStateDecision.normalDecision();
        }

        int nextTicks = observation.unchangedTicks() + 1;
        if (nextTicks <= SAME_STATE_MAX_WAIT_TICKS) {
            sameStateObservations.put(key, observation.withUnchangedTicks(nextTicks));
            log.debug("[RP游戏驱动] 动作后状态未变化，等待下一 tick: game={}, session={}, unchanged={}/{}",
                    session.getGameName(), session.getSessionId(), nextTicks, SAME_STATE_MAX_WAIT_TICKS);
            return SameStateDecision.waitDecision();
        }

        sameStateObservations.remove(key);
        log.warn("[RP游戏驱动] 动作后状态连续未变化，触发静音重规划: game={}, session={}, unchanged={}, lastReason={}",
                session.getGameName(), session.getSessionId(), nextTicks, observation.failureFeedback());
        return SameStateDecision.replanDecision();
    }

    private void rememberSameStateObservation(ActiveGameSession session, String stateFingerprint, String feedback) {
        sameStateObservations.put(
                sessionKey(session),
                new SameStateObservation(normalizeFingerprint(stateFingerprint), 0, feedback == null ? "" : feedback.trim()));
    }

    private void clearSameStateObservation(ActiveGameSession session) {
        sameStateObservations.remove(sessionKey(session));
    }

    private GameLoopUserSpeechSignal consumeUserSpeechSignal(ActiveGameSession session) {
        Optional<RpProactiveSessionRegistry.SessionSnapshot> snapshot =
                proactiveSessionRegistry.findKnown(session.getRpSessionId());
        Instant lastUserSpeechAt = snapshot.map(RpProactiveSessionRegistry.SessionSnapshot::lastUserSpeechAt).orElse(null);
        return new GameLoopUserSpeechSignal(session.consumeUserSpeechTick(lastUserSpeechAt));
    }

    private String gameLoopInstruction(GameLoopUserSpeechSignal userSpeechSignal) {
        if (userSpeechSignal.hasFreshUserSpeech()) {
            return "用户刚刚有新的发言，请结合用户发言和当前游戏状态决策。";
        }
        return "用户没有新的发言，不要重复同一句话，除非当前游戏状态发生了新的可决策变化。";
    }

    private String sessionKey(ActiveGameSession session) {
        return GameSessionKey.of(session.getGameName(), session.getSessionId());
    }

    private String normalizeFingerprint(String value) {
        return value == null ? "" : value.trim();
    }

    private void handleFailure(ActiveGameSession session) {
        int failures = session.incrementFailures();
        log.warn("[RP游戏驱动] RP 游戏回合连续失败 {}/{} 次: game={}, session={}",
                failures, MAX_CONSECUTIVE_FAILURES, session.getGameName(), session.getSessionId());
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            log.error("[RP游戏驱动] 连续失败 {} 次，停止会话: game={}, session={}",
                    failures, session.getGameName(), session.getSessionId());
            session.setState(ActiveGameSession.State.STOPPED);
        }
    }

    private ReentrantLock sessionLock(ActiveGameSession session) {
        // 这里只防止定时轮询重入；MCP 实际执行锁在 GameBridgeService 内部持有，不能和流式 RP 回调共用。
        return sessionLocks.computeIfAbsent(sessionKey(session), ignored -> new ReentrantLock());
    }

    public ActiveGameSession start(String gameName, String sessionId) {
        return start(gameName, sessionId, sessionId, null, null);
    }

    public ActiveGameSession start(String gameName, String sessionId, String rpSessionId) {
        return start(gameName, sessionId, rpSessionId, null, null);
    }

    public ActiveGameSession start(String gameName,
                                   String sessionId,
                                   String rpSessionId,
                                   String characterName,
                                   Boolean shortMode) {
        requireRpCharacterContext(rpSessionId, characterName);
        ActiveGameSession existing = registry.get(gameName, sessionId);
        if (existing != null && existing.getState() == ActiveGameSession.State.RUNNING) {
            rememberSessionContext(existing, characterName, shortMode);
            observationBackoffService.reset(existing);
            return existing;
        }
        ActiveGameSession session = registry.register(gameName, sessionId, rpSessionId);
        rememberSessionContext(session, characterName, shortMode);
        observationBackoffService.reset(session);
        traceService.initSession(gameName, sessionId);
        return session;
    }

    private void requireRpCharacterContext(String rpSessionId, String characterName) {
        if (characterName != null && !characterName.isBlank()) {
            return;
        }
        if (proactiveSessionRegistry.findKnown(rpSessionId).isPresent()) {
            return;
        }
        throw new IllegalArgumentException("Game loop requires a selected RP character.");
    }

    private void rememberSessionContext(ActiveGameSession session, String characterName, Boolean shortMode) {
        if (session != null) {
            proactiveSessionRegistry.rememberSessionContext(session.getRpSessionId(), characterName, shortMode);
        }
    }

    public void stop(String gameName, String sessionId) {
        ActiveGameSession session = registry.get(gameName, sessionId);
        if (session != null) {
            clearSameStateObservation(session);
        }
        traceService.writeSummary(gameName, sessionId);
        registry.unregister(gameName, sessionId);
    }

    public void pause(String gameName, String sessionId) {
        ActiveGameSession session = registry.get(gameName, sessionId);
        if (session != null) {
            session.setState(ActiveGameSession.State.PAUSED);
            observationBackoffService.reset(session);
            clearSameStateObservation(session);
            log.info("[RP游戏驱动] 会话已暂停: game={}, session={}", gameName, sessionId);
        }
    }

    public void resume(String gameName, String sessionId) {
        resume(gameName, sessionId, null, null);
    }

    public void resume(String gameName, String sessionId, String characterName, Boolean shortMode) {
        ActiveGameSession session = registry.get(gameName, sessionId);
        if (session != null) {
            rememberSessionContext(session, characterName, shortMode);
            session.setState(ActiveGameSession.State.RUNNING);
            observationBackoffService.reset(session);
            clearSameStateObservation(session);
            log.info("[RP游戏驱动] 会话已恢复: game={}, session={}", gameName, sessionId);
        }
    }

    public ActiveGameSession status(String gameName, String sessionId) {
        return registry.get(gameName, sessionId);
    }

    private record SameStateObservation(String stateFingerprint, int unchangedTicks, String failureFeedback) {
        private SameStateObservation withUnchangedTicks(int ticks) {
            return new SameStateObservation(stateFingerprint, ticks, failureFeedback);
        }
    }

    private record SameStateDecision(boolean waitCurrentTick, boolean replan) {
        private static SameStateDecision normalDecision() {
            return new SameStateDecision(false, false);
        }

        private static SameStateDecision waitDecision() {
            return new SameStateDecision(true, false);
        }

        private static SameStateDecision replanDecision() {
            return new SameStateDecision(false, true);
        }
    }

    private record GameLoopUserSpeechSignal(boolean hasFreshUserSpeech) {
    }
}
