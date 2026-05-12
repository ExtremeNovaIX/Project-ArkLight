package p1.component.agent.gamer.loop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import p1.component.agent.gamer.GamerAgentService;
import p1.component.agent.gamer.adapter.core.GameActionability;
import p1.component.agent.gamer.adapter.core.GameActionabilityStatus;
import p1.component.agent.gamer.bridge.GameBridgeActionStatus;
import p1.component.agent.gamer.bridge.GameBridgeService;
import p1.component.agent.interaction.InteractionCoordinator;
import p1.config.mcp.GameLoopProperties;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 游戏循环服务。
 * <p>
 * 慢轮询只负责兜底唤醒运行中的会话；如果桥接层因为状态变化、手牌变化或修复失败中断队列，
 * 循环层会在同一个会话锁内立即再次请求游戏智能体，而不是等待下一次慢轮询。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GamerGameLoopService {

    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    private final GamerAgentService agentService;
    private final GameBridgeService bridgeService;
    private final ActiveGameRegistry registry;
    private final GameLoopProperties props;
    private final InteractionCoordinator interactionCoordinator;
    private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    /**
     * 定时扫描所有运行中的游戏会话。
     * <p>
     * 每个会话会先尝试获取循环锁；如果上一轮还在处理同一会话，则跳过本次 tick。
     */
    @Scheduled(fixedDelayString = "${gamer.game-loop.poll-interval-ms:500}")
    public void pollTick() {
        Collection<ActiveGameSession> sessions = registry.listRunning();
        if (sessions.isEmpty()) {
            return;
        }

        for (ActiveGameSession session : sessions) {
            try {
                processSession(session);
            } catch (Exception e) {
                log.error("[Gamer Agent循环] 会话处理异常: game={}, session={}", session.getGameName(), session.getSessionId(), e);
            }
        }
    }

    /**
     * 处理单个运行中会话。
     *
     * @param session 当前活跃游戏会话
     */
    private void processSession(ActiveGameSession session) {
        if (session.getState() != ActiveGameSession.State.RUNNING) {
            return;
        }
        ReentrantLock lock = sessionLock(session.getGameName(), session.getSessionId());
        if (!lock.tryLock()) {
            log.debug("[Gamer Agent循环] 会话仍在处理中，跳过本次 tick: game={}, session={}", session.getGameName(), session.getSessionId());
            return;
        }

        try {
            processSessionWithImmediateReplan(session);
        } catch (Exception e) {
            log.error("[Gamer Agent循环] 行动窗口探测失败: game={}, session={}",
                    session.getGameName(), session.getSessionId(), e);
            handleFailure(session);
            session.touch();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在同一个会话锁内处理一次循环，并按需进行即时重规划。
     *
     * @param session 当前活跃游戏会话
     */
    private void processSessionWithImmediateReplan(ActiveGameSession session) {
        int immediateReplans = 0;
        while (session.getState() == ActiveGameSession.State.RUNNING) {
            InteractionCoordinator.GameTurnPermission turnPermission =
                    interactionCoordinator.canGameActForRpSession(session.getRpSessionId());
            if (!turnPermission.allowed()) {
                log.debug("[Gamer Agent循环] 交互窗口被占用，暂停本次行动: game={}, session={}, reason={}",
                        session.getGameName(), session.getSessionId(), turnPermission.reason());
                session.touch();
                return;
            }
            GameActionability actionability = bridgeService.probeActionability(session.getGameName(), session.getSessionId());
            if (actionability.status() == GameActionabilityStatus.GAME_OVER) {
                log.info("[Gamer Agent循环] 探测到游戏结束，停止会话: game={}, session={}, reason={}",
                        session.getGameName(), session.getSessionId(), actionability.reason());
                session.setState(ActiveGameSession.State.STOPPED);
                return;
            }
            if (!actionability.actionable()) {
                log.debug("[Gamer Agent循环] 当前无需行动: game={}, session={}, status={}, reason={}",
                        session.getGameName(), session.getSessionId(), actionability.status(), actionability.reason());
                session.resetFailures();
                session.touch();
                return;
            }

            int totalSteps = session.incrementAndGetTotalSteps();
            try {
                agentService.play(session.getGameName(), session.getSessionId(), nextPrompt(immediateReplans));
                session.resetFailures();
            } catch (Exception e) {
                log.error("[Gamer Agent] 游戏操作失败: game={}, session={}", session.getGameName(), session.getSessionId(), e);
                handleFailure(session);
                return;
            } finally {
                session.touch();
            }

            GameBridgeActionStatus status = agentService.lastActionStatus(session.getGameName(), session.getSessionId());
            if (status == GameBridgeActionStatus.GAME_OVER) {
                log.info("[Gamer Agent循环] 游戏结束，停止会话: game={}, session={}", session.getGameName(), session.getSessionId());
                session.setState(ActiveGameSession.State.STOPPED);
                return;
            }

            if (status != GameBridgeActionStatus.INTERRUPTED) {
                log.debug("[Gamer Agent循环] 本轮决策完成: game={}, session={}, bridgeStatus={}, totalStep={}",
                        session.getGameName(), session.getSessionId(), status, totalSteps);
                return;
            }

            immediateReplans++;
            if (immediateReplans > props.getMaxImmediateReplans()) {
                log.warn("[Gamer Agent循环] 即时重规划达到上限，等待下一次慢轮询: game={}, session={}, limit={}",
                        session.getGameName(), session.getSessionId(), props.getMaxImmediateReplans());
                return;
            }

            if (session.getState() != ActiveGameSession.State.RUNNING) {
                log.info("[Gamer Agent循环] 队列中断后会话已不再运行，停止即时重规划: game={}, session={}, state={}",
                        session.getGameName(), session.getSessionId(), session.getState());
                return;
            }

            log.info("[Gamer Agent循环] 队列被状态变化中断，立即重新请求决策: game={}, session={}, replan={}/{}",
                    session.getGameName(), session.getSessionId(), immediateReplans, props.getMaxImmediateReplans());
        }
    }

    /**
     * 构造发送给游戏智能体的循环提示。
     *
     * @param immediateReplans 当前 tick 内已经即时重规划的次数
     * @return 本次请求的用户提示
     */
    private String nextPrompt(int immediateReplans) {
        if (immediateReplans == 0) {
            return "根据系统注入的最新状态，提交必要的操作队列；如果当前无法操作则等待。";
        }
        return "上一批操作队列因状态变化或手牌变化被桥接层中断。请基于系统注入的最新状态重新决策，丢弃旧计划。";
    }

    /**
     * 处理游戏智能体调用失败。
     *
     * @param session 当前活跃游戏会话
     */
    private void handleFailure(ActiveGameSession session) {
        int failures = session.incrementFailures();
        log.warn("[Gamer Agent循环] 游戏智能体调用失败，连续失败 {}/{} 次: game={}, session={}",
                failures, MAX_CONSECUTIVE_FAILURES, session.getGameName(), session.getSessionId());
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            log.error("[Gamer Agent循环] 连续失败 {} 次，停止会话: game={}, session={}",
                    failures, session.getGameName(), session.getSessionId());
            session.setState(ActiveGameSession.State.STOPPED);
        }
    }

    /**
     * 获取循环层会话锁。
     *
     * @param gameName  游戏名
     * @param sessionId 用户侧会话 id
     * @return 当前游戏会话独占的循环锁
     */
    private ReentrantLock sessionLock(String gameName, String sessionId) {
        return sessionLocks.computeIfAbsent(gameName + ":" + sessionId, ignored -> new ReentrantLock());
    }

    /**
     * 启动或复用一个游戏循环会话。
     *
     * @param gameName  游戏名
     * @param sessionId 用户侧会话 id
     * @return 正在运行的活跃会话
     */
    public ActiveGameSession start(String gameName, String sessionId) {
        return start(gameName, sessionId, sessionId);
    }

    /**
     * 启动或复用一个游戏循环会话，并显式绑定 RP 会话。
     *
     * @param gameName    游戏名
     * @param sessionId   游戏侧会话 id
     * @param rpSessionId RP 会话 id
     * @return 正在运行的活跃会话
     */
    public ActiveGameSession start(String gameName, String sessionId, String rpSessionId) {
        ActiveGameSession existing = registry.get(gameName, sessionId);
        if (existing != null && existing.getState() == ActiveGameSession.State.RUNNING) {
            return existing;
        }
        return registry.register(gameName, sessionId, rpSessionId);
    }

    /**
     * 停止并注销一个游戏循环会话。
     *
     * @param gameName  游戏名
     * @param sessionId 用户侧会话 id
     */
    public void stop(String gameName, String sessionId) {
        registry.unregister(gameName, sessionId);
    }

    /**
     * 暂停一个游戏循环会话。
     *
     * @param gameName  游戏名
     * @param sessionId 用户侧会话 id
     */
    public void pause(String gameName, String sessionId) {
        ActiveGameSession session = registry.get(gameName, sessionId);
        if (session != null) {
            session.setState(ActiveGameSession.State.PAUSED);
            log.info("[Gamer Agent循环] 会话已暂停: game={}, session={}", gameName, sessionId);
        }
    }

    /**
     * 恢复一个已暂停的游戏循环会话。
     *
     * @param gameName  游戏名
     * @param sessionId 用户侧会话 id
     */
    public void resume(String gameName, String sessionId) {
        ActiveGameSession session = registry.get(gameName, sessionId);
        if (session != null) {
            session.setState(ActiveGameSession.State.RUNNING);
            log.info("[Gamer Agent循环] 会话已恢复: game={}, session={}", gameName, sessionId);
        }
    }

    /**
     * 查询指定游戏循环会话。
     *
     * @param gameName  游戏名
     * @param sessionId 用户侧会话 id
     * @return 活跃会话；不存在时返回 null
     */
    public ActiveGameSession status(String gameName, String sessionId) {
        return registry.get(gameName, sessionId);
    }
}
