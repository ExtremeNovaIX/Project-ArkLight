package p1.component.agent.tts;

import jakarta.annotation.PreDestroy;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static p1.utils.SessionUtil.normalizeSessionId;

/**
 * TTS 发言编排服务。
 * <p>
 * RP 流式文本进入这里后，会被清理、按句末标点分段合成并推送给前端。该服务不直接依赖 RP Agent，
 * 只把 session/source 当作音频路由信息使用。
 */
@Service
@RequiredArgsConstructor
@CustomLog
public class TtsSpeechService {

    private final TtsConfig config;
    private final TtsProviderRegistry providerRegistry;
    private final TtsRuntimeManager runtimeManager;
    private final TtsTextNormalizer textNormalizer;
    private final TtsAudioHub audioHub;
    private final AtomicBoolean unavailableLogged = new AtomicBoolean(false);
    private final AtomicBoolean disabledLogged = new AtomicBoolean(false);
    private final AtomicBoolean noSubscriberLogged = new AtomicBoolean(false);
    private final AtomicBoolean runtimeNotReadyLogged = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newCachedThreadPool(new TtsThreadFactory());

    private Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            fields.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return fields;
    }

    /**
     * 打开一次 TTS 发言会话。
     *
     * @param sessionId RP 会话 id
     * @param source    发言来源
     * @return TTS 发言会话；不可用时返回 no-op
     */
    public TtsSpeechSession open(String sessionId, String source) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        if (!config.enabled()) {
            if (disabledLogged.compareAndSet(false, true)) {
                log.info(LogDomain.TTS, "speech.skipped", LogOutcome.SKIPPED,
                        fields("reason", "disabled", "provider", config.getProvider(), "baseUrl", config.providerBaseUrl()));
            }
            return NoopTtsSpeechSession.INSTANCE;
        }
        if (!audioHub.hasSubscribers(normalizedSessionId)) {
            if (noSubscriberLogged.compareAndSet(false, true)) {
                log.info(LogDomain.TTS, "speech.skipped", LogOutcome.SKIPPED,
                        fields("session", normalizedSessionId, "reason", "no_audio_subscriber"));
            }
            return NoopTtsSpeechSession.INSTANCE;
        }
        TtsProvider provider = providerRegistry.activeProvider().orElse(null);
        if (provider == null || !provider.isAvailable()) {
            if (unavailableLogged.compareAndSet(false, true)) {
                log.info(LogDomain.TTS, "speech.skipped", LogOutcome.SKIPPED,
                        fields("reason", "provider_unavailable", "provider", config.getProvider(), "baseUrl", config.providerBaseUrl()));
            }
            return NoopTtsSpeechSession.INSTANCE;
        }
        if (runtimeManager.shouldWaitForManagedRuntime() && !runtimeManager.isManagedRuntimeReady()) {
            if (runtimeNotReadyLogged.compareAndSet(false, true)) {
                log.info(LogDomain.TTS, "speech.skipped", LogOutcome.SKIPPED,
                        fields("session", normalizedSessionId, "reason", "runtime_not_ready", "baseUrl", config.providerBaseUrl()));
            }
            return NoopTtsSpeechSession.INSTANCE;
        }
        runtimeNotReadyLogged.set(false);
        return new StreamingTtsSpeechSession(normalizedSessionId, safeSource(source), provider);
    }

    /**
     * 关闭 TTS 合成线程池。
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 实际执行流式文本到音频的会话。
     */
    private final class StreamingTtsSpeechSession implements TtsSpeechSession {

        private final String sessionId;
        private final String source;
        private final TtsProvider provider;
        private final TtsTextChunker chunker = new TtsTextChunker(config);
        private boolean styleBoundaryAllowed = true;
        private final StringBuilder pending = new StringBuilder();
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicLong activeSequence = new AtomicLong();
        private final AtomicLong completedSequence = new AtomicLong();
        private final AtomicLong stopAfterSequence = new AtomicLong(Long.MAX_VALUE);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean stopAfterCurrentChunk = new AtomicBoolean(false);
        private CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        private StreamingTtsSpeechSession(String sessionId, String source, TtsProvider provider) {
            this.sessionId = sessionId;
            this.source = source;
            this.provider = provider;
        }

        /**
         * 接收 RP 流式输出片段，并在形成完整文本段后排队合成。
         *
         * @param text 新到达的文本片段
         */
        @Override
        public void accept(String text) {
            if (closed.get() || text == null || text.isEmpty()) {
                return;
            }

            pending.append(text);
            processPending(false);
        }

        private void processPending(boolean finishing) {
            int start = 0;
            while (start < pending.length()) {
                int styleOpen = findNextStyleOpen(pending, start);
                if (styleOpen < 0) {
                    appendText(pending.substring(start));
                    pending.setLength(0);
                    return;
                }

                if (styleOpen > start) {
                    appendText(pending.substring(start, styleOpen));
                }

                int styleClose = findStyleClose(pending, styleOpen + 1);
                if (styleClose < 0) {
                    if (!finishing) {
                        pending.delete(0, styleOpen);
                        return;
                    }
                    pending.setLength(0);
                    return;
                }

                styleBoundaryAllowed = false;
                start = styleClose + 1;
            }
            pending.setLength(0);
        }

        private int findNextStyleOpen(CharSequence text, int from) {
            boolean styleAllowed = styleBoundaryAllowed;
            for (int i = from; i < text.length(); i++) {
                char c = text.charAt(i);
                if (isOpenParen(c) && styleAllowed) {
                    return i;
                }
                if (isSentenceEnd(c) || c == '\n' || c == '\r') {
                    styleAllowed = true;
                } else if (!Character.isWhitespace(c)) {
                    styleAllowed = false;
                }
            }
            return -1;
        }

        private int findStyleClose(CharSequence text, int from) {
            for (int i = from; i < text.length(); i++) {
                char c = text.charAt(i);
                if (isCloseParen(c)) {
                    return i;
                }
            }
            return -1;
        }

        private void appendText(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            updateStyleBoundary(text);
            enqueueAll(chunker.append(text));
        }

        private void updateStyleBoundary(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (isSentenceEnd(c) || c == '\n' || c == '\r') {
                    styleBoundaryAllowed = true;
                } else if (!Character.isWhitespace(c)) {
                    styleBoundaryAllowed = false;
                }
            }
        }

        private boolean isOpenParen(char c) {
            return c == '（' || c == '(';
        }

        private boolean isCloseParen(char c) {
            return c == '）' || c == ')';
        }

        private boolean isSentenceEnd(char c) {
            return config.sentenceEndMarks().contains(c);
        }

        /**
         * 结束本次发言，刷新剩余文本并向前端发送结束帧。
         */
        @Override
        public void finish() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            processPending(true);
            enqueueAll(chunker.flush());
            long finalSequence = sequence.incrementAndGet();
            synchronized (this) {
                chain = chain.thenRunAsync(() -> {
                    if (!cancelled.get()) {
                        audioHub.publishFinal(sessionId, source, finalSequence);
                    }
                }, executor);
            }
        }

        /**
         * 取消本次发言，通常发生在上层流式响应被打断时。
         *
         * @param reason 取消原因
         */
        @Override
        public void cancel(String reason) {
            cancelled.set(true);
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            log.debug("[TTS] 已取消发言合成: session={}, source={}, reason={}", sessionId, source, reason);
        }

        /**
         * 在音频 chunk 边界停止：刷新当前文本为一个 chunk，允许当前或下一个 chunk 播完，
         * 后续已排队 chunk 直接跳过，避免错误发生时切断正在播放的音频帧。
         *
         * @param reason 停止原因
         */
        @Override
        public void stopAfterCurrentChunk(String reason) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            stopAfterCurrentChunk.set(true);
            stopAfterSequence.set(resolveStopAfterSequence());
            long finalSequence = sequence.incrementAndGet();
            synchronized (this) {
                chain = chain.thenRunAsync(() -> {
                    if (!cancelled.get()) {
                        audioHub.publishFinal(sessionId, source, finalSequence);
                    }
                }, executor);
            }
            log.debug("[TTS] 将在当前音频片段后停止: session={}, source={}, stopAfter={}, reason={}",
                    sessionId, source, stopAfterSequence.get(), reason);
        }

        /**
         * 将多个文本段按顺序加入合成队列。
         *
         * @param chunks 文本段列表
         */
        private void enqueueAll(List<String> chunks) {
            for (String chunk : chunks) {
                String normalized = textNormalizer.normalize(chunk);
                if (!normalized.isBlank()) {
                    enqueue(normalized);
                }
            }
        }

        /**
         * 将单个文本段追加到串行合成链路。
         *
         * @param text 待合成文本
         */
        private void enqueue(String text) {
            if (cancelled.get()) {
                return;
            }
            if (text.isBlank()) {
                return;
            }
            long currentSequence = sequence.incrementAndGet();
            synchronized (this) {
                chain = chain.thenRunAsync(() -> synthesizeAndPublish(currentSequence, text), executor)
                        .exceptionally(error -> {
                            if (!cancelled.get()) {
                                log.warn(LogDomain.TTS, "speech.synthesis_failed", LogOutcome.DEGRADED,
                                        fields("session", sessionId, "sequence", currentSequence,
                                                "text", text, "reason", error.getMessage()));
                            }
                            return null;
                        });
            }
        }

        /**
         * 调用 provider 合成音频并推送给前端订阅者。
         *
         * @param currentSequence 当前短句序号
         * @param text            待合成文本
         */
        private void synthesizeAndPublish(long currentSequence, String text) {
            if (cancelled.get() || shouldSkipSequence(currentSequence)) {
                return;
            }
            activeSequence.set(currentSequence);
            try {
                provider.synthesize(
                        new TtsSynthesisRequest(sessionId, source, currentSequence, text),
                        frame -> {
                            if (!cancelled.get() && !shouldSkipSequence(currentSequence)) {
                                audioHub.publishAudio(sessionId, source, currentSequence, text, frame);
                            }
                        });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                completedSequence.updateAndGet(previous -> Math.max(previous, currentSequence));
                activeSequence.compareAndSet(currentSequence, 0);
            }
        }

        private long resolveStopAfterSequence() {
            long active = activeSequence.get();
            if (active > 0) {
                return active;
            }
            long enqueued = sequence.get();
            long completed = completedSequence.get();
            if (enqueued <= completed) {
                return completed;
            }
            return completed + 1;
        }

        private boolean shouldSkipSequence(long currentSequence) {
            return stopAfterCurrentChunk.get() && currentSequence > stopAfterSequence.get();
        }
    }

    /**
     * TTS 不可用时的空实现。
     */
    private enum NoopTtsSpeechSession implements TtsSpeechSession {
        INSTANCE;

        @Override
        public void accept(String text) {
        }

        @Override
        public void finish() {
        }

        @Override
        public void cancel(String reason) {
        }
    }

    private String safeSource(String source) {
        return source == null || source.isBlank() ? "unknown" : source.trim();
    }

    private static final class TtsThreadFactory implements ThreadFactory {
        private final AtomicLong counter = new AtomicLong();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "tts-synthesis-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
