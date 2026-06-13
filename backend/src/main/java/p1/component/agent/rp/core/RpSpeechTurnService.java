package p1.component.agent.rp.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import p1.component.agent.gamer.trace.GamerDecisionTraceService;
import p1.component.agent.rp.game.control.RpControlBlock;
import p1.component.agent.rp.game.control.RpGameControlTurnLockService;
import p1.component.agent.rp.game.control.RpGameActionExecutionException;
import p1.component.agent.rp.game.control.RpGameControlBlockExecutor;
import p1.component.agent.rp.game.interrupt.RpGameInterruptionService;
import p1.component.agent.streaming.StreamingJsonInstruction;
import p1.component.agent.streaming.StreamingJsonInstructionParser;
import p1.component.agent.tts.TtsSpeechService;
import p1.component.agent.tts.TtsSpeechSession;
import p1.config.prop.AssistantProperties;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 收集一次 RP 流式响应，并把可见发言送入 TTS。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RpSpeechTurnService {

    private static final long GAME_ACTION_SPEECH_COOLDOWN_MS = 6000L;

    private final AssistantProperties assistantProperties;
    private final TtsSpeechService ttsSpeechService;
    private final RpGameControlBlockExecutor gameControlBlockExecutor;
    private final RpGameInterruptionService gameInterruptionService;
    private final RpGameControlTurnLockService gameControlTurnLockService;
    private final GamerDecisionTraceService traceService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Long> lastGameActionSpeechAt = new ConcurrentHashMap<>();

    public String collect(String rpSessionId, String source, TokenStream stream) {
        return collect(rpSessionId, source, stream, null, null);
    }

    /**
     * @param gameName  游戏名（game mode 时传入，供 plan 块捕获）
     * @param sessionId 游戏会话 id（game mode 时传入，供 plan 块捕获）
     */
    public String collect(String rpSessionId, String source, TokenStream stream,
                          String gameName, String sessionId) {
        if (stream == null) {
            return "";
        }
        SpeechCollector collector = new SpeechCollector(rpSessionId, source, gameName, sessionId);
        if (collector.requiresGameControlLock()) {
            return gameControlTurnLockService.withLock(
                    rpSessionId,
                    collector.source,
                    () -> startAndAwait(stream, collector));
        }
        return startAndAwait(stream, collector);
    }

    private String startAndAwait(TokenStream stream, SpeechCollector collector) {
        stream.onPartialResponseWithContext(collector::onPartialResponse)
                .onCompleteResponse(collector::onCompleteResponse)
                .onError(collector::onError)
                .start();
        return collector.awaitText();
    }

    private final class SpeechCollector {
        private final String rpSessionId;
        private final String source;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicReference<StreamingHandle> streamingHandle = new AtomicReference<>();
        private final StringBuilder rawResponse = new StringBuilder();
        private final StringBuilder visibleResponse = new StringBuilder();
        private final boolean gameMode;
        private final StreamingJsonInstructionParser controlParser =
                new StreamingJsonInstructionParser(objectMapper, 4096);
        private final GamePlanCapture planCapture;
        private volatile TtsSpeechSession ttsSession;
        private volatile Throwable error;
        private volatile boolean ttsStopAfterCurrentChunk;
        private volatile int completedActionBlocks;
        private final AtomicBoolean interruptionRecorded = new AtomicBoolean(false);

        private SpeechCollector(String rpSessionId, String source, String gameName, String sessionId) {
            this.rpSessionId = rpSessionId;
            this.source = source == null || source.isBlank() ? "unknown" : source.trim();
            this.gameMode = gameControlBlockExecutor.hasActiveGame(rpSessionId);
            this.planCapture = new GamePlanCapture(gameName, sessionId);
        }

        private boolean requiresGameControlLock() {
            return gameMode || source.startsWith("game-loop");
        }

        private synchronized void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
            if (closed.get() || partialResponse == null) {
                return;
            }
            rememberHandle(context == null ? null : context.streamingHandle());
            String text = partialResponse.text();
            if (text == null || text.isEmpty()) {
                return;
            }
            rawResponse.append(text);
            if (gameMode) {
                handleGameModeChunk(text);
            } else {
                acceptSpeech(text);
            }
        }

        private void handleGameModeChunk(String text) {
            String planText = planCapture.accept(text);
            if (planText != null && !planText.isBlank()) {
                traceService.recordTurnPlan(planCapture.gameName(), planCapture.sessionId(), planText);
            }
            List<StreamingJsonInstruction> instructions = controlParser.accept(text);
            for (StreamingJsonInstruction instruction : instructions) {
                if (closed.get()) {
                    return;
                }
                RpControlBlock.from(instruction.json()).ifPresent(this::handleControlBlock);
            }
        }

        private void handleControlBlock(RpControlBlock block) {
            if (block.hasSpeech()) {
                appendVisible(block.say());
                if (shouldSubmitControlBlockToTts(block)) {
                    acceptSpeech(block.say());
                }
            }
            try {
                gameControlBlockExecutor.execute(rpSessionId, block)
                        .ifPresent(result -> log.debug("[RP控制块] 执行反馈: session={}, type={}, result={}",
                                rpSessionId, block.type(), result));
                if (block.isCommittedAction()) {
                    completedActionBlocks++;
                }
                if (block.isAsk()) {
                    finishAfterAsk();
                }
            } catch (RpGameActionExecutionException e) {
                interruptForGameActionFailure(e);
            } catch (RuntimeException e) {
                interruptForUnexpectedGameFailure(e);
            }
        }

        private boolean shouldSubmitControlBlockToTts(RpControlBlock block) {
            if (!block.isVoice() || block.voiceKind() != RpControlBlock.VoiceKind.CHAT) {
                return false;
            }
            if (!source.startsWith("game-loop")) {
                return true;
            }
            if ("game-loop-replan".equals(source)) {
                log.debug("[RP发言流] 重规划动作台词已静音: session={}", rpSessionId);
                return false;
            }
            long now = System.currentTimeMillis();
            Long last = lastGameActionSpeechAt.get(rpSessionId);
            if (last != null && now - last < GAME_ACTION_SPEECH_COOLDOWN_MS) {
                log.debug("[RP发言流] 游戏动作台词处于冷却中: session={}, remainingMs={}",
                        rpSessionId, GAME_ACTION_SPEECH_COOLDOWN_MS - (now - last));
                return false;
            }
            lastGameActionSpeechAt.put(rpSessionId, now);
            return true;
        }

        private void finishAfterAsk() {
            StreamingHandle handle = streamingHandle.get();
            if (handle != null && !handle.isCancelled()) {
                handle.cancel();
            }
            log.debug("[RP发言流] RP 已发起游戏询问，结束当前流: session={}, source={}", rpSessionId, source);
            finish();
        }

        private void interruptForGameActionFailure(RpGameActionExecutionException failure) {
            if (failure.interactionDeferred()) {
                error = failure;
                stopStreamAfterCurrentSpeechChunk("游戏动作暂缓: " + failure.feedback());
                finish();
                return;
            }
            recordActionFailure(failure.feedback());
            error = failure;
            stopStreamAfterCurrentSpeechChunk("游戏动作失败: " + failure.feedback());
            finish();
        }

        private void interruptForUnexpectedGameFailure(RuntimeException failure) {
            String feedback = "RP 控制块执行异常：" + failure.getMessage();
            recordActionFailure(feedback);
            error = failure;
            stopStreamAfterCurrentSpeechChunk(feedback);
            finish();
        }

        private void recordActionFailure(String reason) {
            if (interruptionRecorded.compareAndSet(false, true)) {
                gameInterruptionService.recordActionFailure(
                        rpSessionId,
                        rawResponse.toString(),
                        reason,
                        completedActionBlocks);
            }
        }

        private void recordStreamFailure(String reason) {
            if (gameMode && interruptionRecorded.compareAndSet(false, true)) {
                gameInterruptionService.recordStreamFailure(
                        rpSessionId,
                        rawResponse.toString(),
                        reason,
                        completedActionBlocks);
            }
        }

        private void acceptSpeech(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            if (ttsSession == null && !text.isBlank()) {
                ttsSession = ttsSpeechService.open(rpSessionId, source);
                log.debug("[RP发言流] 首个可见字符到达: session={}, source={}", rpSessionId, source);
            }
            if (ttsSession != null) {
                ttsSession.accept(text);
            }
            if (!gameMode) {
                visibleResponse.append(text);
            }
        }

        private void appendVisible(String text) {
            if (text == null || text.isBlank()) {
                return;
            }
            if (!visibleResponse.isEmpty()) {
                visibleResponse.append("\n");
            }
            visibleResponse.append(text.trim());
        }

        private synchronized void onCompleteResponse(ChatResponse ignored) {
            finish();
        }

        private synchronized void onError(Throwable throwable) {
            if (closed.get()) {
                return;
            }
            error = throwable;
            recordStreamFailure("RP 响应流异常：" + (throwable == null ? "unknown" : throwable.getMessage()));
            finish();
        }

        private String awaitText() {
            try {
                boolean done = finished.await(streamWaitSeconds(), TimeUnit.SECONDS);
                if (!done) {
                    String reason = "RP 响应流超时";
                    recordStreamFailure(reason);
                    cancelStream(reason);
                    finish();
                    throw new IllegalStateException(reason + ": session=" + rpSessionId + ", source=" + source);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                String reason = "等待 RP 响应流被中断";
                recordStreamFailure(reason);
                cancelStream(reason);
                finish();
                throw new IllegalStateException(reason + ": session=" + rpSessionId + ", source=" + source, e);
            }
            if (error instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (error != null) {
                throw new IllegalStateException("RP 响应流失败: " + error.getMessage(), error);
            }
            if (gameMode) {
                return visibleResponse.toString().trim();
            }
            return rawResponse.toString().trim();
        }

        private void rememberHandle(StreamingHandle handle) {
            if (handle != null) {
                streamingHandle.compareAndSet(null, handle);
            }
        }

        private void cancelStream(String reason) {
            StreamingHandle handle = streamingHandle.get();
            if (handle != null && !handle.isCancelled()) {
                handle.cancel();
            }
            cancelTts(reason);
            log.warn("[RP发言流] 已取消: session={}, source={}, reason={}", rpSessionId, source, reason);
        }

        private void stopStreamAfterCurrentSpeechChunk(String reason) {
            StreamingHandle handle = streamingHandle.get();
            if (handle != null && !handle.isCancelled()) {
                handle.cancel();
            }
            if (ttsSession != null) {
                ttsStopAfterCurrentChunk = true;
                ttsSession.stopAfterCurrentChunk(reason);
            }
            log.warn("[RP发言流] 已请求在当前语音片段后停止: session={}, source={}, reason={}",
                    rpSessionId, source, reason);
        }

        private void cancelTts(String reason) {
            if (ttsSession != null) {
                ttsSession.cancel(reason);
            }
        }

        private void finish() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (ttsSession != null) {
                if (error == null) {
                    ttsSession.finish();
                } else if (!ttsStopAfterCurrentChunk) {
                    ttsSession.cancel(error.getMessage());
                }
            }
            finished.countDown();
        }
    }

    private static final class GamePlanCapture {
        private static final String START_TAG = "<plan>";
        private static final String END_TAG = "</plan>";

        private final String gameName;
        private final String sessionId;
        private final StringBuilder buffer = new StringBuilder();
        private boolean capturing;
        private boolean closed;

        private GamePlanCapture(String gameName, String sessionId) {
            this.gameName = gameName;
            this.sessionId = sessionId;
        }

        private String gameName() {
            return gameName;
        }

        private String sessionId() {
            return sessionId;
        }

        private String accept(String chunk) {
            if (closed || gameName == null || sessionId == null || chunk == null || chunk.isEmpty()) {
                return null;
            }
            String planChunk = chunk;
            if (!capturing) {
                int start = chunk.indexOf(START_TAG);
                if (start < 0) {
                    return null;
                }
                capturing = true;
                planChunk = chunk.substring(start + START_TAG.length());
            }
            int end = planChunk.indexOf(END_TAG);
            if (end >= 0) {
                buffer.append(planChunk, 0, end);
                closed = true;
                String planText = buffer.toString().trim();
                buffer.setLength(0);
                return planText;
            }
            buffer.append(planChunk);
            return null;
        }
    }

    private long streamWaitSeconds() {
        Long configured = assistantProperties.activeChatModel().getTimeoutSeconds();
        return Math.max(5L, configured == null ? 120L : configured + 5L);
    }
}
