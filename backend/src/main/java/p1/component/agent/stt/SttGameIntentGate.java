package p1.component.agent.stt;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import p1.component.agent.interaction.GameCoordinationService;
import p1.component.agent.router.InstructionRouteDecision;
import p1.component.agent.router.InstructionRouteRequest;
import p1.component.agent.router.InstructionRouter;
import p1.component.agent.router.InstructionRouterTask;
import p1.component.agent.router.InstructionRouterTaskRegistry;
import p1.config.prop.AssistantProperties;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SttGameIntentGate {

    static final String TASK_ID = "stt-game-control";
    private static final String INTENT_WAIT = "WAIT";
    private static final String INTENT_APPLY_INSTRUCTION = "APPLY_INSTRUCTION";
    private static final String INTENT_READY = "READY";
    private static final String INTENT_CHAT = "CHAT";
    private static final int MIN_PARTIAL_CONTROL_CHARS = 2;
    private static final int MIN_PARTIAL_APPLY_CHARS = 4;
    private static final List<String> ALLOWED_INTENTS = List.of(
            INTENT_WAIT,
            INTENT_APPLY_INSTRUCTION,
            INTENT_READY,
            INTENT_CHAT);
    private static final String SCENE_INSTRUCTION = """
            你负责判断混合语音转写是否是在直接给 gamer agent 下游戏指令。
            音频可能来自多人混合声道，必须保守，宁可漏掉暧昧讨论，也不要把闲聊当成指令。

            意图定义：
            - WAIT：用户明确要求 gamer 暂停、先别动、等一下、不要继续操作。
            - APPLY_INSTRUCTION：用户明确给出新的游戏行动偏好、战术建议或操作方向，例如“先防”“打这个”“这回合别贪”。
            - READY：只有用户明确表示可以继续、好了、继续吧时使用；如果 runtime_context 中 waiting=false，通常应归为 CHAT。
            - CHAT：闲聊、评价、情绪、复盘、暧昧讨论、听不清、多人混杂、低置信转写。

            输出要求：
            - 只能输出 JSON，intent 必须来自 allowed_intents。
            - 只有像直接对 gamer agent 发出的当前游戏控制意图，才输出 WAIT、APPLY_INSTRUCTION 或 READY。
            - “我觉得这样打更好”“先防更稳”这类明确战术偏好可归为 APPLY_INSTRUCTION。
            - “这把好离谱”“太搞了”“他怎么这样玩”这类评价必须归为 CHAT。
            - 混合语音、多人同时说话、上下文不足或不确定时，输出 CHAT 且 confidence 不高于 0.5。
            - instruction 用极短中文保留可执行意图；WAIT 可写等待原因，READY 为空。
            """;

    private final InstructionRouter instructionRouter;
    private final InstructionRouterTaskRegistry taskRegistry;
    private final GameCoordinationService gameCoordinationService;
    private final AssistantProperties assistantProperties;
    private final Clock clock;
    private final Map<String, UtteranceState> states = new ConcurrentHashMap<>();

    @Autowired
    public SttGameIntentGate(InstructionRouter instructionRouter,
                             InstructionRouterTaskRegistry taskRegistry,
                             GameCoordinationService gameCoordinationService,
                             AssistantProperties assistantProperties) {
        this(instructionRouter, taskRegistry, gameCoordinationService, assistantProperties, Clock.systemUTC());
    }

    SttGameIntentGate(InstructionRouter instructionRouter,
                      InstructionRouterTaskRegistry taskRegistry,
                      GameCoordinationService gameCoordinationService,
                      AssistantProperties assistantProperties,
                      Clock clock) {
        this.instructionRouter = instructionRouter;
        this.taskRegistry = taskRegistry;
        this.gameCoordinationService = gameCoordinationService;
        this.assistantProperties = assistantProperties;
        this.clock = clock;
    }

    @PostConstruct
    void registerRouterTask() {
        taskRegistry.register(new InstructionRouterTask(TASK_ID, ALLOWED_INTENTS, SCENE_INSTRUCTION));
    }

    public Result onPartial(String connectionId, String rpSessionId, String transcript) {
        return onPartialInternal(connectionId, rpSessionId, transcript, false);
    }

    public Result onPartialDryRun(String connectionId, String rpSessionId, String transcript) {
        return onPartialInternal(connectionId, rpSessionId, transcript, true);
    }

    private Result onPartialInternal(String connectionId, String rpSessionId, String transcript, boolean dryRun) {
        String text = normalizeText(transcript);
        if (text.isBlank()) {
            return Result.pass("blank");
        }
        if (!dryRun && !hasActiveGame(rpSessionId)) {
            return Result.pass("no-active-game");
        }

        UtteranceState state = stateFor(connectionId);
        synchronized (state) {
            state.currentText = text;
            Instant now = clock.instant();
            if (!shouldRoutePartial(state, text, now)) {
                return Result.pass("partial-unchanged");
            }
            state.lastPartialRouteAt = now;
            state.lastRoutedPartialText = text;
            return routeAndApply(state, rpSessionId, text, false, now, dryRun);
        }
    }

    public Result onFinal(String connectionId, String rpSessionId, String transcript) {
        return onFinalInternal(connectionId, rpSessionId, transcript, false);
    }

    public Result onFinalDryRun(String connectionId, String rpSessionId, String transcript) {
        return onFinalInternal(connectionId, rpSessionId, transcript, true);
    }

    private Result onFinalInternal(String connectionId, String rpSessionId, String transcript, boolean dryRun) {
        String text = normalizeText(transcript);
        if (text.isBlank()) {
            resetUtterance(connectionId);
            return Result.pass("blank");
        }
        if (!dryRun && !hasActiveGame(rpSessionId)) {
            resetUtterance(connectionId);
            return Result.pass("no-active-game");
        }

        UtteranceState state = stateFor(connectionId);
        synchronized (state) {
            Instant now = clock.instant();
            if (matchesConsumedUtterance(state, text, now)) {
                state.currentText = "";
                state.lastRoutedPartialText = "";
                return Result.consumed(state.lastEffectIntent, 1.0, state.lastEffectInstruction,
                        false, false, 0L, "matched-consumed-partial");
            }
            Result result = routeAndApply(state, rpSessionId, text, true, now, dryRun);
            state.currentText = "";
            state.lastRoutedPartialText = "";
            return result;
        }
    }

    public void cleanup(String connectionId) {
        if (connectionId != null) {
            states.remove(connectionId);
        }
    }

    private Result routeAndApply(UtteranceState state,
                                 String rpSessionId,
                                 String text,
                                 boolean finalResult,
                                 Instant now,
                                 boolean dryRun) {
        long routeStartedAt = System.nanoTime();
        InstructionRouteDecision decision = instructionRouter.route(InstructionRouteRequest.byTask(
                TASK_ID,
                TASK_ID,
                runtimeContext(rpSessionId, dryRun),
                text));
        long routeDurationMs = Math.max(0L, (System.nanoTime() - routeStartedAt) / 1_000_000L);
        String intent = decision.normalizedIntent();
        if (!decision.confidentEnough(config().getConfidenceThreshold()) || INTENT_CHAT.equals(intent)) {
            log.debug("[STT游戏语音门控] 跳过语音意图: final={}, intent={}, confidence={}, text={}",
                    finalResult, intent, String.format(Locale.ROOT, "%.2f", decision.confidence()), abbreviate(text));
            return Result.pass(intent, decision.confidence(), decision.instruction(), true, routeDurationMs, "chat-or-low-confidence");
        }

        if (!finalResult && shouldDeferPartialIntent(intent, text)) {
            log.debug("[STT游戏语音门控] 延迟短 partial 语音意图: intent={}, confidence={}, text={}",
                    intent, String.format(Locale.ROOT, "%.2f", decision.confidence()), abbreviate(text));
            return Result.pass(intent, decision.confidence(), decision.instruction(), true, routeDurationMs, "deferred-short-partial");
        }

        if (INTENT_READY.equals(intent) && !gameCoordinationService.isWaiting(rpSessionId)) {
            log.debug("[STT游戏语音门控] READY 语音被忽略，当前没有等待状态: rpSession={}, text={}",
                    rpSessionId, abbreviate(text));
            return Result.pass(intent, decision.confidence(), decision.instruction(), true, routeDurationMs, "ready-without-waiting");
        }

        String instruction = instructionFor(intent, decision.instruction(), text);
        String signature = intent + "|" + canonical(instruction.isBlank() ? text : instruction);
        if (isDuplicate(state, signature, now)) {
            rememberConsumed(state, text, intent, instruction, signature, now);
            return Result.consumed(intent, decision.confidence(), instruction, false, true, routeDurationMs, "duplicate-cooldown");
        }

        if (!dryRun) {
            applySideEffect(rpSessionId, intent, instruction);
        }
        rememberConsumed(state, text, intent, instruction, signature, now);
        log.info("[STT游戏语音门控] 已消费语音意图: rpSession={}, final={}, intent={}, confidence={}, instruction={}",
                rpSessionId, finalResult, intent, String.format(Locale.ROOT, "%.2f", decision.confidence()), instruction);
        return Result.consumed(intent, decision.confidence(), instruction, !dryRun,
                true, routeDurationMs, dryRun ? "dry-run-would-trigger" : "triggered");
    }

    private boolean shouldRoutePartial(UtteranceState state, String text, Instant now) {
        if (!hasEnoughTextChange(state.lastRoutedPartialText, text)) {
            return false;
        }
        if (state.lastPartialRouteAt == null) {
            return true;
        }
        long intervalMs = Math.max(1L, config().getRouteIntervalMs());
        return now.toEpochMilli() - state.lastPartialRouteAt.toEpochMilli() >= intervalMs;
    }

    private boolean shouldDeferPartialIntent(String intent, String text) {
        int length = canonical(text).length();
        if (INTENT_APPLY_INSTRUCTION.equals(intent)) {
            return length < MIN_PARTIAL_APPLY_CHARS;
        }
        return length < MIN_PARTIAL_CONTROL_CHARS;
    }

    private boolean hasEnoughTextChange(String previous, String current) {
        String before = canonical(previous);
        String after = canonical(current);
        if (before.equals(after)) {
            return false;
        }
        if (before.isBlank()) {
            return true;
        }
        int minChangedChars = Math.max(1, config().getMinChangedChars());
        if (after.startsWith(before) || before.startsWith(after)) {
            return Math.abs(after.length() - before.length()) >= minChangedChars;
        }
        return true;
    }

    private boolean matchesConsumedUtterance(UtteranceState state, String text, Instant now) {
        if (state.lastConsumedText.isBlank() || state.lastEffectAt == null) {
            return false;
        }
        long associationWindowMs = Math.max(1000L, Math.max(1L, config().getRouteIntervalMs()) * 3L);
        if (now.toEpochMilli() - state.lastEffectAt.toEpochMilli() > associationWindowMs) {
            return false;
        }
        String consumed = canonical(state.lastConsumedText);
        String current = canonical(text);
        return current.equals(consumed)
                || current.startsWith(consumed)
                || consumed.startsWith(current)
                || current.contains(consumed)
                || consumed.contains(current);
    }

    private boolean isDuplicate(UtteranceState state, String signature, Instant now) {
        if (!signature.equals(state.lastEffectSignature) || state.lastEffectAt == null) {
            return false;
        }
        long cooldownMs = Math.max(0L, config().getDuplicateCooldownMs());
        return now.toEpochMilli() - state.lastEffectAt.toEpochMilli() < cooldownMs;
    }

    private void rememberConsumed(UtteranceState state,
                                  String text,
                                  String intent,
                                  String instruction,
                                  String signature,
                                  Instant now) {
        state.lastConsumedText = text;
        state.lastEffectIntent = intent;
        state.lastEffectInstruction = instruction;
        state.lastEffectSignature = signature;
        state.lastEffectAt = now;
    }

    private void applySideEffect(String rpSessionId, String intent, String instruction) {
        switch (intent) {
            case INTENT_WAIT -> gameCoordinationService.waitForUser(rpSessionId, 0, instruction);
            case INTENT_APPLY_INSTRUCTION -> gameCoordinationService.applyInstruction(rpSessionId, instruction);
            case INTENT_READY -> gameCoordinationService.markReady(rpSessionId);
            default -> {
            }
        }
    }

    private String instructionFor(String intent, String routedInstruction, String text) {
        if (INTENT_READY.equals(intent)) {
            return "";
        }
        if (StringUtils.hasText(routedInstruction)) {
            return routedInstruction.trim();
        }
        return text;
    }

    private String runtimeContext(String rpSessionId, boolean dryRun) {
        boolean waiting = gameCoordinationService.isWaiting(rpSessionId);
        boolean activeGame = hasActiveGame(rpSessionId);
        return """
                active_game=%s
                waiting=%s
                audio_source=mixed
                speaker_identity=unknown
                policy=high_precision_voice_gate
                dry_run=%s
                confidence_threshold=%.2f
                """.formatted(activeGame, waiting, dryRun, config().getConfidenceThreshold()).trim();
    }

    private boolean hasActiveGame(String rpSessionId) {
        try {
            return gameCoordinationService.hasActiveGame(rpSessionId);
        } catch (Exception e) {
            log.debug("[STT游戏语音门控] 活跃游戏检测失败: rpSession={}, reason={}", rpSessionId, e.getMessage());
            return false;
        }
    }

    private UtteranceState stateFor(String connectionId) {
        String key = connectionId == null || connectionId.isBlank() ? "default" : connectionId;
        return states.computeIfAbsent(key, ignored -> new UtteranceState());
    }

    private void resetUtterance(String connectionId) {
        UtteranceState state = connectionId == null ? null : states.get(connectionId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.currentText = "";
            state.lastRoutedPartialText = "";
        }
    }

    private AssistantProperties.VoiceGateConfig config() {
        if (assistantProperties == null
                || assistantProperties.getInteraction() == null
                || assistantProperties.getInteraction().getVoiceGate() == null) {
            return new AssistantProperties.VoiceGateConfig();
        }
        return assistantProperties.getInteraction().getVoiceGate();
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String canonical(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", "").trim();
    }

    private String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = normalizeText(text);
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
    }

    public record Result(
            boolean consumed,
            String intent,
            double confidence,
            String instruction,
            boolean triggered,
            boolean routed,
            long routeDurationMs,
            String reason
    ) {
        static Result pass() {
            return pass("pass");
        }

        static Result pass(String reason) {
            return new Result(false, INTENT_CHAT, 0.0, "", false, false, 0L, reason == null ? "" : reason);
        }

        static Result pass(String intent, double confidence, String instruction) {
            return pass(intent, confidence, instruction, false, 0L, "pass");
        }

        static Result pass(String intent, double confidence, String instruction, boolean routed, long routeDurationMs, String reason) {
            return new Result(false, intent == null ? "" : intent, confidence,
                    instruction == null ? "" : instruction, false, routed, routeDurationMs, reason == null ? "" : reason);
        }

        static Result consumed(String intent, double confidence, String instruction, boolean triggered) {
            return consumed(intent, confidence, instruction, triggered, false, 0L, triggered ? "triggered" : "consumed");
        }

        static Result consumed(String intent, double confidence, String instruction, boolean triggered,
                               boolean routed, long routeDurationMs, String reason) {
            return new Result(true, intent == null ? "" : intent, confidence,
                    instruction == null ? "" : instruction, triggered, routed, routeDurationMs, reason == null ? "" : reason);
        }
    }

    private static final class UtteranceState {
        private String currentText = "";
        private String lastRoutedPartialText = "";
        private Instant lastPartialRouteAt;
        private String lastConsumedText = "";
        private String lastEffectIntent = "";
        private String lastEffectInstruction = "";
        private String lastEffectSignature = "";
        private Instant lastEffectAt;
    }
}
