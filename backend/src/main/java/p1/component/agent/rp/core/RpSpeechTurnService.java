package p1.component.agent.rp.core;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import p1.component.agent.interaction.InteractionCoordinator;
import p1.component.agent.tts.TtsSpeechService;
import p1.component.agent.tts.TtsSpeechSession;
import p1.config.prop.AssistantProperties;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RP 发言流执行器。
 * <p>
 * 统一普通回复和主动发言的流收集生命周期，保证两点：
 * <ol>
 * <li>发言占用（speech lease）覆盖"角色确实在说话"的时间窗口，thinking阶段不占窗口，首字到达才占，流结束或失败立即释放</li>
 * <li>调用方不用自己处理流回调、超时、取消。给一个TokenStream，拿回最终文本</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RpSpeechTurnService {

    private final InteractionCoordinator interactionCoordinator;
    private final AssistantProperties assistantProperties;
    private final TtsSpeechService ttsSpeechService;

    /**
     * 执行并收集一次 RP 文本流。
     *
     * @param rpSessionId RP 会话 id
     * @param source      发言来源，便于日志定位
     * @param stream      LangChain4j 响应流
     * @return 当前流最终说出的文本
     */
    public String collect(String rpSessionId, String source, TokenStream stream) {
        if (stream == null) {
            return "";
        }
        SpeechCollector collector = new SpeechCollector(rpSessionId, source);
        stream.onPartialResponseWithContext(collector::onPartialResponse)
                .onCompleteResponse(collector::onCompleteResponse)
                .onError(collector::onError)
                .start();
        return collector.awaitText();
    }

    /**
     * 单次 RP 响应流收集器。
     */
    private final class SpeechCollector {
        private final String rpSessionId;
        private final String source;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicReference<StreamingHandle> streamingHandle = new AtomicReference<>();
        private final StringBuilder response = new StringBuilder();
        private volatile InteractionCoordinator.InteractionLease speechLease;
        private volatile TtsSpeechSession ttsSession;
        private volatile Throwable error;

        private SpeechCollector(String rpSessionId, String source) {
            this.rpSessionId = rpSessionId;
            this.source = source == null || source.isBlank() ? "unknown" : source.trim();
        }

        /**
         * 处理一个可见响应片段。
         *
         * @param partialResponse 响应片段
         * @param context         流控制上下文
         */
        private synchronized void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
            if (closed.get() || partialResponse == null) {
                return;
            }
            rememberHandle(context == null ? null : context.streamingHandle());
            String text = partialResponse.text();
            if (text == null || text.isEmpty()) {
                return;
            }
            response.append(text);
            if (speechLease == null && !text.isBlank()) {
                // thinking 不会进入该回调；首个可见字符到达才认为 RP 真正开始说话。
                speechLease = interactionCoordinator.beginRpSpeech(rpSessionId);
                ttsSession = ttsSpeechService.open(rpSessionId, source);
                log.debug("[RP发言流] 首个响应字符已到达: session={}, source={}", rpSessionId, source);
            }
            if (ttsSession != null) {
                ttsSession.accept(text);
            }
        }

        /**
         * 标记响应流正常完成。
         *
         * @param ignored 完整响应对象
         */
        private void onCompleteResponse(ChatResponse ignored) {
            finish();
        }

        /**
         * 标记响应流失败。
         *
         * @param throwable 失败原因
         */
        private void onError(Throwable throwable) {
            error = throwable;
            finish();
        }

        /**
         * 等待响应流结束并返回收集文本。
         *
         * @return RP 最终文本
         */
        private String awaitText() {
            try {
                boolean done = finished.await(streamWaitSeconds(), TimeUnit.SECONDS);
                if (!done) {
                    cancelStream("等待 RP 响应流超时");
                    finish();
                    throw new IllegalStateException("RP 响应流超时: session=" + rpSessionId + ", source=" + source);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelStream("等待 RP 响应流被中断");
                finish();
                throw new IllegalStateException("等待 RP 响应流被中断: session=" + rpSessionId + ", source=" + source, e);
            }
            if (error != null) {
                throw new IllegalStateException("RP 响应流失败: " + error.getMessage(), error);
            }
            return response.toString().trim();
        }

        /**
         * 保存可用于取消的流句柄。
         *
         * @param handle 流句柄
         */
        private void rememberHandle(StreamingHandle handle) {
            if (handle != null) {
                streamingHandle.compareAndSet(null, handle);
            }
        }

        /**
         * 尝试取消仍在运行的流。
         *
         * @param reason 取消原因
         */
        private void cancelStream(String reason) {
            StreamingHandle handle = streamingHandle.get();
            if (handle != null && !handle.isCancelled()) {
                handle.cancel();
            }
            cancelTts(reason);
            log.warn("[RP发言流] 已取消响应流: session={}, source={}, reason={}", rpSessionId, source, reason);
        }

        /**
         * 取消 TTS 合成。
         *
         * @param reason 取消原因
         */
        private void cancelTts(String reason) {
            if (ttsSession != null) {
                ttsSession.cancel(reason);
            }
        }

        /**
         * 完成本次发言生命周期。
         */
        private void finish() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (ttsSession != null) {
                if (error == null) {
                    ttsSession.finish();
                } else {
                    ttsSession.cancel(error.getMessage());
                }
            }
            if (speechLease != null) {
                speechLease.close();
            }
            finished.countDown();
        }
    }

    /**
     * 获取一次响应流的等待秒数。
     *
     * @return 正数秒数
     */
    private long streamWaitSeconds() {
        Long configured = assistantProperties.activeChatModel().getTimeoutSeconds();
        return Math.max(5L, configured == null ? 120L : configured + 5L);
    }
}
