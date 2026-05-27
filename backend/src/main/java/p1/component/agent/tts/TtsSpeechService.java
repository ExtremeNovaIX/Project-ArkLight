package p1.component.agent.tts;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
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
 * RP 流式文本进入这里后，会被清理、切句、顺序合成并推送给前端。该服务不直接依赖 RP Agent，
 * 只把 session/source 当作音频路由信息使用。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TtsSpeechService {

    private final TtsConfig config;
    private final TtsProviderRegistry providerRegistry;
    private final TtsRuntimeManager runtimeManager;
    private final TtsTextNormalizer textNormalizer;
    private final TtsStyleExtractor styleExtractor;
    private final TtsAudioHub audioHub;
    private final AtomicBoolean unavailableLogged = new AtomicBoolean(false);
    private final AtomicBoolean disabledLogged = new AtomicBoolean(false);
    private final AtomicBoolean noSubscriberLogged = new AtomicBoolean(false);
    private final AtomicBoolean runtimeNotReadyLogged = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newCachedThreadPool(new TtsThreadFactory());

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
                log.info("[TTS] TTS 未启用，跳过语音合成: provider={}, baseUrl={}",
                        config.getProvider(), config.providerBaseUrl());
            }
            return NoopTtsSpeechSession.INSTANCE;
        }
        if (!audioHub.hasSubscribers(normalizedSessionId)) {
            if (noSubscriberLogged.compareAndSet(false, true)) {
                log.info("[TTS] 当前会话没有前端音频订阅，跳过语音合成: session={}", normalizedSessionId);
            }
            return NoopTtsSpeechSession.INSTANCE;
        }
        TtsProvider provider = providerRegistry.activeProvider().orElse(null);
        if (provider == null || !provider.isAvailable()) {
            if (unavailableLogged.compareAndSet(false, true)) {
                log.info("[TTS] 外部 TTS provider 不可用，跳过语音合成: provider={}, baseUrl={}",
                        config.getProvider(), config.providerBaseUrl());
            }
            return NoopTtsSpeechSession.INSTANCE;
        }
        if (runtimeManager.shouldWaitForManagedRuntime() && !runtimeManager.isManagedRuntimeReady()) {
            if (runtimeNotReadyLogged.compareAndSet(false, true)) {
                log.info("[TTS] 本地 TTS 服务尚未就绪，跳过本次语音合成: session={}, baseUrl={}",
                        normalizedSessionId, config.providerBaseUrl());
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
        private volatile String currentControlInstruction = "";
        private volatile String currentVoxTag = "";
        private final StringBuilder pending = new StringBuilder();
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        private StreamingTtsSpeechSession(String sessionId, String source, TtsProvider provider) {
            this.sessionId = sessionId;
            this.source = source;
            this.provider = provider;
        }

        /**
         * 接收 RP 流式输出片段，并在形成完整短句后排队合成。
         *
         * @param text 新到达的文本片段
         */
        @Override
        public void accept(String text) {
            if (closed.get()) {
                return;
            }

            pending.append(text);

            // 流式 chunk 可能拆散句首括号（...），等闭合后再提取风格
            String remaining;
            if (config.voxCpmProviderEnabled() && hasLeadingOpenParen(pending)) {
                TtsStyleExtractor.StyleExtractionResult result = styleExtractor.extract(pending.toString());
                if (result.hasStyle()) {
                    currentControlInstruction = result.controlInstruction();
                    currentVoxTag = result.voxTag();
                    remaining = result.cleanText();
                    pending.setLength(0);
                } else if (noCloseParen(pending)) {
                    if (pending.length() > 80) {
                        remaining = drainPending();
                    } else {
                        return;
                    }
                } else {
                    remaining = drainPending();
                }
            } else {
                remaining = drainPending();
            }

            if (remaining.isBlank()) {
                return;
            }

            String normalized = textNormalizer.normalize(remaining);
            if (normalized.isBlank()) {
                return;
            }
            // VoxCPM2 非语言标签插入文本头部（在 normalize 之后，避免被当作情绪标签剥掉）
            if (!currentVoxTag.isEmpty()) {
                normalized = "[" + currentVoxTag + "]" + normalized;
                currentVoxTag = "";
            }
            enqueueAll(chunker.append(normalized));
        }

        private boolean hasLeadingOpenParen(CharSequence s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '（' || c == '(') return true;
                if (!Character.isWhitespace(c)) return false;
            }
            return false;
        }

        private boolean noCloseParen(CharSequence s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '）' || c == ')') return false;
            }
            return true;
        }

        private String drainPending() {
            String drained = pending.toString();
            pending.setLength(0);
            return drained;
        }

        /**
         * 结束本次发言，刷新剩余文本并向前端发送结束帧。
         */
        @Override
        public void finish() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
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
         * 将多个短句按顺序加入合成队列。
         *
         * @param chunks 短句列表
         */
        private void enqueueAll(List<String> chunks) {
            for (String chunk : chunks) {
                enqueue(chunk);
            }
        }

        /**
         * 将单个短句追加到串行合成链路。
         *
         * @param text 待合成短句
         */
        private void enqueue(String text) {
            if (cancelled.get()) {
                return;
            }
            if (text.isBlank()) {
                return;
            }
            long currentSequence = sequence.incrementAndGet();
            String ctrl = currentControlInstruction;
            synchronized (this) {
                chain = chain.thenRunAsync(() -> synthesizeAndPublish(currentSequence, text, ctrl), executor)
                        .exceptionally(error -> {
                            if (!cancelled.get()) {
                                log.warn("[TTS] 合成短句失败: session={}, sequence={}, text={}, reason={}",
                                        sessionId, currentSequence, text, error.getMessage());
                            }
                            return null;
                        });
            }
        }

        /**
         * 调用 provider 合成音频并推送给前端订阅者。
         *
         * @param currentSequence 当前短句序号
         * @param text            待合成短句
         */
        private void synthesizeAndPublish(long currentSequence, String text, String ctrl) {
            if (cancelled.get()) {
                return;
            }
            try {
                provider.synthesize(
                        new TtsSynthesisRequest(sessionId, source, currentSequence, text, ctrl),
                        frame -> {
                            if (!cancelled.get()) {
                                audioHub.publishAudio(sessionId, source, currentSequence, text, frame);
                            }
                        });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
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
