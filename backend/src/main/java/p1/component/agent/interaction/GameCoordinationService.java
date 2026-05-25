package p1.component.agent.interaction;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import p1.component.agent.gamer.interrupt.GameInterruptService;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.loop.ActiveGameSession;
import p1.component.agent.gamer.loop.GameLoopObservationBackoffService;
import p1.component.agent.rp.proactive.RpLiveMessageHub;
import p1.component.agent.rp.proactive.RpProactiveSessionRegistry;
import p1.config.prop.AssistantProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static p1.utils.SessionUtil.normalizeSessionId;

/**
 * 游戏模式下 RP 与 gamer 的协调服务。
 * <p>
 * 该服务负责用户等待、确认继续和新游戏意图转发；RP 工具只登记意图，
 * gamer loop 与队列执行层通过 {@link InteractionCoordinator} 统一判断是否可以行动。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GameCoordinationService {

    private final ActiveGameRegistry activeGameRegistry;
    private final GameInterruptService interruptService;
    private final InteractionCoordinator interactionCoordinator;
    private final GameLoopObservationBackoffService observationBackoffService;
    private final RpLiveMessageHub liveMessageHub;
    private final RpProactiveSessionRegistry sessionRegistry;
    private final ChatMemoryProvider chatMemoryProvider;
    private final AssistantProperties assistantProperties;
    @Qualifier("asyncTaskExecutor")
    private final Executor asyncTaskExecutor;

    private final Map<String, WaitState> waitsByRpSession = new ConcurrentHashMap<>();

    /**
     * 判断 RP 会话当前是否绑定活跃游戏。
     *
     * @param rpSessionId RP 会话 id
     * @return true 表示当前处于游戏模式
     */
    public boolean hasActiveGame(String rpSessionId) {
        return activeGameRegistry.findBySessionId(normalizeSessionId(rpSessionId)).isPresent();
    }

    /**
     * 登记一次“等一下”意图。
     *
     * @param rpSessionId     RP 会话 id
     * @param requestedSeconds 多少秒后询问用户；小于等于 0 时使用默认值
     * @param reason          等待原因
     * @return 工具反馈
     */
    public String waitForUser(String rpSessionId, long requestedSeconds, String reason) {
        ActiveGameSession session = activeSession(rpSessionId).orElse(null);
        if (session == null) {
            return "当前没有可控制的游戏会话。";
        }

        String normalizedRpSessionId = normalizeSessionId(rpSessionId);
        long askDelaySeconds = requestedSeconds > 0 ? requestedSeconds : waitProperties().getDefaultSeconds();
        long normalizedAskDelaySeconds = Math.max(1L, askDelaySeconds);
        Duration maxHold = Duration.ofMinutes(Math.max(1L, waitProperties().getMaxHoldMinutes()));
        interactionCoordinator.beginGameWait(normalizedRpSessionId, maxHold);

        WaitState state = waitsByRpSession.compute(normalizedRpSessionId, (ignored, previous) -> {
            int nextGeneration = previous == null ? 1 : previous.generation + 1;
            return new WaitState(
                    session.getGameName(),
                    session.getSessionId(),
                    normalizedRpSessionId,
                    nextGeneration,
                    Instant.now(),
                    Instant.now().plusSeconds(normalizedAskDelaySeconds),
                    reason == null ? "" : reason.trim());
        });

        scheduleReminder(state);
        scheduleAutoClear(state, maxHold);
        log.info("[游戏协调] 已进入等待: game={}, session={}, rpSession={}, askDelaySeconds={}, reason={}",
                session.getGameName(), session.getSessionId(), normalizedRpSessionId, normalizedAskDelaySeconds, state.reason);
        return "已进入等待状态，" + normalizedAskDelaySeconds + " 秒后会询问用户是否继续。";
    }

    /**
     * 用户确认可以继续。
     *
     * @param rpSessionId RP 会话 id
     * @return 工具反馈
     */
    public String markReady(String rpSessionId) {
        ActiveGameSession session = activeSession(rpSessionId).orElse(null);
        if (session == null) {
            clearWait(normalizeSessionId(rpSessionId));
            return "已清除等待状态，但当前没有可控制的游戏会话。";
        }

        clearWait(session.getRpSessionId());
        if (session.getState() == ActiveGameSession.State.PAUSED) {
            session.setState(ActiveGameSession.State.RUNNING);
        }
        observationBackoffService.reset(session);
        session.touch();
        log.info("[游戏协调] 用户确认继续: game={}, session={}, rpSession={}",
                session.getGameName(), session.getSessionId(), session.getRpSessionId());
        return "已解除等待状态，游戏会基于最新状态继续。";
    }

    /**
     * 转发用户新的游戏意图，并解除等待。
     *
     * @param rpSessionId RP 会话 id
     * @param instruction 用户新的游戏意图
     * @return 工具反馈
     */
    public String applyInstruction(String rpSessionId, String instruction) {
        ActiveGameSession session = activeSession(rpSessionId).orElse(null);
        if (session == null) {
            return "当前没有可控制的游戏会话。";
        }

        String normalizedInstruction = instruction == null || instruction.isBlank()
                ? "用户确认继续，基于最新状态重新决策。"
                : instruction.trim();
        clearWait(session.getRpSessionId());
        if (session.getState() == ActiveGameSession.State.PAUSED) {
            session.setState(ActiveGameSession.State.RUNNING);
        }
        observationBackoffService.reset(session);
        interruptService.requestInterrupt(session.getGameName(), session.getSessionId(), "rp", normalizedInstruction);
        session.touch();
        log.info("[游戏协调] 已提交用户游戏意图: game={}, session={}, rpSession={}, instruction={}",
                session.getGameName(), session.getSessionId(), session.getRpSessionId(), normalizedInstruction);
        return "已收到新的游戏意图，会打断未执行操作并基于最新状态重新决策：" + normalizedInstruction;
    }

    /**
     * 渲染 RP 动态上下文中的等待状态。
     *
     * @param rpSessionId RP 会话 id
     * @return 等待状态文本；没有等待时为空
     */
    public String renderWaitContext(String rpSessionId) {
        WaitState state = waitsByRpSession.get(normalizeSessionId(rpSessionId));
        if (state == null) {
            return "";
        }
        return """
                <game_wait>
                waiting=true
                reason=%s
                ask_at=%s
                rule=系统已经登记等待，本轮不要重复调用 WAIT，只需自然回应用户正在等待；如果用户表示“好了/继续/可以了”，调用 game_coordination READY；如果用户给出新动作，调用 APPLY_INSTRUCTION。
                </game_wait>
                """.formatted(state.reason, state.askAt).trim();
    }

    /**
     * 判断当前 RP 会话是否已经处于游戏等待状态。
     *
     * @param rpSessionId RP 会话 id
     * @return true 表示等待 hold 已登记，gamer 应暂停行动
     */
    public boolean isWaiting(String rpSessionId) {
        return waitsByRpSession.containsKey(normalizeSessionId(rpSessionId));
    }

    /**
     * 清除等待状态和对应交互 hold。
     *
     * @param rpSessionId RP 会话 id
     */
    private void clearWait(String rpSessionId) {
        String normalizedRpSessionId = normalizeSessionId(rpSessionId);
        waitsByRpSession.remove(normalizedRpSessionId);
        interactionCoordinator.endGameWait(normalizedRpSessionId);
    }

    /**
     * 根据 RP 会话定位活跃游戏会话。
     *
     * @param rpSessionId RP 会话 id
     * @return 活跃游戏会话
     */
    private Optional<ActiveGameSession> activeSession(String rpSessionId) {
        return activeGameRegistry.findBySessionId(normalizeSessionId(rpSessionId));
    }

    /**
     * 安排等待到点后的询问。
     *
     * @param waitState 等待状态快照
     */
    private void scheduleReminder(WaitState waitState) {
        long delayMs = Math.max(1L, Duration.between(Instant.now(), waitState.askAt).toMillis());
        CompletableFuture.runAsync(
                () -> publishReminderIfStillWaiting(waitState),
                CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS, asyncTaskExecutor));
    }

    /**
     * 安排等待最长保留时间到期后的自动清理。
     *
     * @param waitState 当前等待状态
     * @param maxHold   最长保留时间
     */
    private void scheduleAutoClear(WaitState waitState, Duration maxHold) {
        long delayMs = Math.max(1L, maxHold.toMillis());
        CompletableFuture.runAsync(
                () -> clearIfSameGeneration(waitState),
                CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS, asyncTaskExecutor));
    }

    /**
     * 等待状态仍是同一代时清理，避免等待上下文比交互 hold 活得更久。
     *
     * @param expected 调度时的等待状态
     */
    private void clearIfSameGeneration(WaitState expected) {
        WaitState current = waitsByRpSession.get(expected.rpSessionId);
        if (current == null || current.generation != expected.generation) {
            return;
        }
        clearWait(expected.rpSessionId);
        log.info("[游戏协调] 等待状态已超过最长保留时间，自动清理: game={}, session={}, rpSession={}",
                expected.gameName, expected.gameSessionId, expected.rpSessionId);
    }

    /**
     * 等待仍有效时向前端投递 RP 询问。
     *
     * @param expected 调度时的等待状态
     */
    private void publishReminderIfStillWaiting(WaitState expected) {
        WaitState current = waitsByRpSession.get(expected.rpSessionId);
        if (current == null || current.generation != expected.generation) {
            return;
        }

        Optional<RpProactiveSessionRegistry.SessionSnapshot> online = sessionRegistry.findOnline(expected.rpSessionId);
        RpProactiveSessionRegistry.SessionSnapshot onlineSession = online == null ? null : online.orElse(null);
        if (onlineSession == null) {
            log.debug("[游戏协调] 等待提醒到点但前端不在线，跳过投递: rpSession={}", expected.rpSessionId);
            return;
        }

        String reminder = waitProperties().getReminderText();
        if (reminder == null || reminder.isBlank()) {
            reminder = "好了没？";
        }
        String speech = reminder.trim();
        appendRpMemory(expected.rpSessionId, speech);
        sessionRegistry.observeRpSpeech(expected.rpSessionId);
        liveMessageHub.publish(expected.rpSessionId, "game-wait", speech, onlineSession.shortMode());
        log.info("[游戏协调] 已投递等待提醒: game={}, session={}, rpSession={}",
                expected.gameName, expected.gameSessionId, expected.rpSessionId);
    }

    /**
     * 把等待提醒写入 RP 记忆。
     *
     * @param rpSessionId RP 会话 id
     * @param speech      RP 说出的提醒文本
     */
    private void appendRpMemory(String rpSessionId, String speech) {
        try {
            ChatMemory memory = chatMemoryProvider.get(rpSessionId);
            memory.add(AiMessage.from(speech));
        } catch (Exception e) {
            log.warn("[游戏协调] 写入等待提醒记忆失败: rpSession={}, reason={}", rpSessionId, e.getMessage());
        }
    }

    /**
     * 获取等待配置。
     *
     * @return 等待配置
     */
    private AssistantProperties.WaitConfig waitProperties() {
        return assistantProperties.getInteraction().getWait();
    }

    /**
     * 一次等待状态。
     */
    private record WaitState(
            String gameName,
            String gameSessionId,
            String rpSessionId,
            int generation,
            Instant createdAt,
            Instant askAt,
            String reason
    ) {
    }
}
