package p1.component.agent.stt;

import lombok.CustomLog;
import org.springframework.stereotype.Service;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.component.agent.interaction.GameCoordinationService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
@CustomLog
public class SttGameIntentGate {

    static final String INTENT_WAIT = "WAIT";
    static final String INTENT_APPLY_INSTRUCTION = "APPLY_INSTRUCTION";
    static final String INTENT_READY = "READY";
    static final String INTENT_CHAT = "CHAT";
    static final String INTENT_VOICE_HOLD = "VOICE_HOLD";

    private static final Duration VOICE_HOLD_TTL = Duration.ofSeconds(12);
    private static final long DUPLICATE_COOLDOWN_MS = 3000L;
    private static final long HOLD_REFRESH_INTERVAL_MS = 500L;
    private static final Pattern ASR_PREFIX = Pattern.compile("^\\[ASR uncertain:[^\\]]*]\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PUNCTUATION = Pattern.compile(
            "[\\s\\p{Punct}，。！？、；：‘’“”（）【】《》「」『』…·]+",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PARTIAL_WAIT = Pattern.compile(
            ".*(?:等一下|等下|等等|稍等|等我一下|先停|停一下|暂停|先别动|别动|不要动|别操作|先别操作|别急|慢点).*");
    private static final Pattern PARTIAL_LISTEN = Pattern.compile(
            ".*(?:听我说|等我说完|我还没说完|先听我说).*");
    private static final Pattern PARTIAL_REQUEST = Pattern.compile(
            ".*(?:你能不能|能不能|你可以.{0,80}[吗么嘛]?|可以.{0,80}[吗么嘛]|可不可以|能.{1,80}[吗么嘛]|行不行|帮我|麻烦你|请你|要不|不如|是不是可以).*");
    private static final Pattern FINAL_WAIT = PARTIAL_WAIT;
    private static final Pattern FINAL_READY = Pattern.compile(
            ".*(?:好了|可以了|继续|你继续|开始吧|执行吧|动吧|没事了|继续吧|可以继续|好了继续).*");
    private static final Pattern FINAL_REQUEST = Pattern.compile(
            ".*(?:你能不能|能不能|你可以.{0,80}[吗么嘛]?|可以.{0,80}[吗么嘛]|可不可以|能.{1,80}[吗么嘛]|行不行|帮我|麻烦你|请你|要不|不如|是不是可以).*");

    private final GameCoordinationService gameCoordinationService;
    private final Clock clock;
    private final Map<String, UtteranceState> states = new ConcurrentHashMap<>();

    public SttGameIntentGate(GameCoordinationService gameCoordinationService) {
        this(gameCoordinationService, Clock.systemUTC());
    }

    public SttGameIntentGate() {
        this.gameCoordinationService = null;
        this.clock = null;
    }

    SttGameIntentGate(GameCoordinationService gameCoordinationService, Clock clock) {
        this.gameCoordinationService = gameCoordinationService;
        this.clock = clock;
    }

    public Result onPartial(String connectionId, String rpSessionId, String transcript) {
        return onPartialInternal(connectionId, rpSessionId, transcript, false);
    }

    public Result onPartialDryRun(String connectionId, String rpSessionId, String transcript) {
        return onPartialInternal(connectionId, rpSessionId, transcript, true);
    }

    private Result onPartialInternal(String connectionId, String rpSessionId, String transcript, boolean dryRun) {
        String text = normalizeText(transcript);
        String compact = compactText(text);
        if (compact.isBlank()) {
            return Result.pass("blank");
        }
        if (!dryRun && !hasActiveGame(rpSessionId)) {
            return Result.pass("no-active-game");
        }

        VoiceSignal signal = partialSignal(compact);
        if (signal == VoiceSignal.NONE) {
            return Result.pass("partial-no-match");
        }

        UtteranceState state = stateFor(connectionId);
        synchronized (state) {
            Instant now = clock.instant();
            state.currentText = text;
            state.rpSessionId = rpSessionId;
            boolean shouldRefresh = shouldRefreshHold(state, compact, now);
            state.lastPartialSignature = compact;
            state.lastPartialAt = now;
            if (!shouldRefresh) {
                return Result.consumed(INTENT_VOICE_HOLD, 1.0, text, false, true, 0L, "voice-hold-active");
            }

            boolean triggered = false;
            if (!dryRun) {
                triggered = gameCoordinationService.beginVoiceInputHold(rpSessionId, VOICE_HOLD_TTL);
                state.voiceHoldActive = triggered || state.voiceHoldActive;
            }
            log.debug("[STT游戏语音门控] partial 命中语音占用: signal={}, rpSession={}, text={}",
                    signal, rpSessionId, abbreviate(text));
            return Result.consumed(INTENT_VOICE_HOLD, 1.0, text, triggered, true, 0L,
                    dryRun ? "dry-run-voice-hold" : "voice-hold");
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
        String compact = compactText(text);
        UtteranceState state = stateFor(connectionId);
        synchronized (state) {
            releaseVoiceHold(state, dryRun);
            state.currentText = "";
            state.lastPartialSignature = "";
            state.lastPartialAt = null;
            if (compact.isBlank()) {
                return Result.pass("blank");
            }
            if (!dryRun && !hasActiveGame(rpSessionId)) {
                return Result.pass("no-active-game");
            }

            FinalDecision decision = finalDecision(compact, text);
            if (decision.intent().equals(INTENT_CHAT)) {
                return Result.pass("final-no-match");
            }
            if (decision.intent().equals(INTENT_READY) && !gameCoordinationService.isWaiting(rpSessionId)) {
                return Result.pass(INTENT_READY, 1.0, "", true, 0L, "ready-without-waiting");
            }

            Instant now = clock.instant();
            String signature = decision.intent() + "|" + compactText(decision.instruction().isBlank()
                    ? text
                    : decision.instruction());
            if (isDuplicate(state, signature, now)) {
                rememberEffect(state, text, decision.intent(), decision.instruction(), signature, now);
                return Result.consumed(decision.intent(), 1.0, decision.instruction(), false, true, 0L,
                        "duplicate-cooldown");
            }

            if (!dryRun) {
                applySideEffect(rpSessionId, decision.intent(), decision.instruction());
            }
            rememberEffect(state, text, decision.intent(), decision.instruction(), signature, now);
            log.info(LogDomain.STT, "stt.game_intent_consumed", LogOutcome.SUCCEEDED, "rpSessionId", rpSessionId, "intent", decision.intent(), "instruction", abbreviate(decision.instruction()));
            return Result.consumed(decision.intent(), 1.0, decision.instruction(), !dryRun, true, 0L,
                    dryRun ? "dry-run-would-trigger" : "triggered");
        }
    }

    public void cleanup(String connectionId) {
        if (connectionId == null) {
            return;
        }
        UtteranceState state = states.remove(connectionId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            releaseVoiceHold(state, false);
        }
    }

    private VoiceSignal partialSignal(String compact) {
        if (PARTIAL_WAIT.matcher(compact).matches()) {
            return VoiceSignal.WAIT;
        }
        if (PARTIAL_LISTEN.matcher(compact).matches()) {
            return VoiceSignal.LISTEN;
        }
        if (PARTIAL_REQUEST.matcher(compact).matches()) {
            return VoiceSignal.REQUEST;
        }
        return VoiceSignal.NONE;
    }

    private FinalDecision finalDecision(String compact, String text) {
        if (FINAL_WAIT.matcher(compact).matches()) {
            return new FinalDecision(INTENT_WAIT, text);
        }
        if (FINAL_READY.matcher(compact).matches()) {
            return new FinalDecision(INTENT_READY, "");
        }
        if (FINAL_REQUEST.matcher(compact).matches()) {
            return new FinalDecision(INTENT_APPLY_INSTRUCTION, text);
        }
        return new FinalDecision(INTENT_CHAT, "");
    }

    private boolean shouldRefreshHold(UtteranceState state, String compact, Instant now) {
        if (!state.voiceHoldActive || state.lastPartialAt == null) {
            return true;
        }
        if (!compact.equals(state.lastPartialSignature)) {
            return true;
        }
        return now.toEpochMilli() - state.lastPartialAt.toEpochMilli() >= HOLD_REFRESH_INTERVAL_MS;
    }

    private void releaseVoiceHold(UtteranceState state, boolean dryRun) {
        if (!state.voiceHoldActive) {
            return;
        }
        String rpSessionId = state.rpSessionId;
        state.voiceHoldActive = false;
        state.rpSessionId = "";
        if (!dryRun && rpSessionId != null && !rpSessionId.isBlank()) {
            gameCoordinationService.endVoiceInputHold(rpSessionId);
        }
    }

    private boolean isDuplicate(UtteranceState state, String signature, Instant now) {
        if (!signature.equals(state.lastEffectSignature) || state.lastEffectAt == null) {
            return false;
        }
        return now.toEpochMilli() - state.lastEffectAt.toEpochMilli() < DUPLICATE_COOLDOWN_MS;
    }

    private void rememberEffect(UtteranceState state,
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

    private boolean hasActiveGame(String rpSessionId) {
        try {
            return gameCoordinationService.hasActiveGame(rpSessionId);
        } catch (Exception e) {
            log.debug("[STT游戏语音门控] active game 检测失败: rpSession={}, reason={}", rpSessionId, e.getMessage());
            return false;
        }
    }

    private UtteranceState stateFor(String connectionId) {
        String key = connectionId == null || connectionId.isBlank() ? "default" : connectionId;
        return states.computeIfAbsent(key, ignored -> new UtteranceState());
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return ASR_PREFIX.matcher(text).replaceFirst("").replaceAll("\\s+", " ").trim();
    }

    private String compactText(String text) {
        return PUNCTUATION.matcher(normalizeText(text)).replaceAll("");
    }

    private String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = normalizeText(text);
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
    }

    private enum VoiceSignal {
        NONE,
        WAIT,
        LISTEN,
        REQUEST
    }

    private record FinalDecision(String intent, String instruction) {
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
        private String rpSessionId = "";
        private boolean voiceHoldActive;
        private String lastPartialSignature = "";
        private Instant lastPartialAt;
        private String lastConsumedText = "";
        private String lastEffectIntent = "";
        private String lastEffectInstruction = "";
        private String lastEffectSignature = "";
        private Instant lastEffectAt;
    }
}
