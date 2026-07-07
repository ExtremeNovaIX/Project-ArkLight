package p1.component.agent.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.CustomLog;

import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * 到本地 ASR WebSocket sidecar 的客户端代理。
 * <p>
 * 每路 Qt 音频流对应一个 {@link SttWebSocketProxy} 实例，负责：
 * <ol>
 *   <li>连接 ASR sidecar 的 WebSocket 服务</li>
 *   <li>转发 PCM 音频数据</li>
 *   <li>接收识别结果并通过回调通知上层</li>
 * </ol>
 */
@CustomLog
public class SttWebSocketProxy implements AutoCloseable {

    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final WebSocket downstream;
    private final Consumer<SttTranscriptEvent> onResult;
    private final Consumer<SttTranscriptEvent> onPartialResult;
    private final Runnable onStreamEnd;
    private final Consumer<Throwable> onError;
    private final TranscriptDispatcher transcriptDispatcher;
    private volatile boolean endRequested;

    SttWebSocketProxy(int sttPort,
                      ObjectMapper objectMapper,
                      Consumer<SttTranscriptEvent> onResult,
                      Consumer<SttTranscriptEvent> onPartialResult,
                      Runnable onStreamEnd,
                      Consumer<Throwable> onError) throws Exception {
        this.onResult = onResult;
        this.onPartialResult = onPartialResult;
        this.onStreamEnd = onStreamEnd;
        this.onError = onError;
        this.transcriptDispatcher = new TranscriptDispatcher(
                objectMapper,
                onResult,
                onPartialResult,
                onStreamEnd);

        URI uri = URI.create("ws://127.0.0.1:" + sttPort);
        this.downstream = SHARED_HTTP_CLIENT.newWebSocketBuilder()
                .buildAsync(uri, new DownstreamListener(objectMapper))
                .get();
    }

    /** 发送音频配置并开始识别。 */
    public void begin(int sampleRate, String source) {
        String config = String.format(
                "{\"protocol\":\"%s\",\"sample_rate\":%d,\"format\":\"int16\",\"source\":\"%s\"}",
                SttConfig.ASR_V2_PROTOCOL,
                sampleRate,
                source == null || source.isBlank() ? "unknown" : source.trim());
        downstream.sendText(config, true);
    }

    /** 发送 PCM 音频数据块。 */
    public void sendAudio(byte[] pcmChunk) {
        if (pcmChunk == null || pcmChunk.length == 0) {
            return;
        }
        downstream.sendBinary(ByteBuffer.wrap(pcmChunk), true);
    }

    /** 通知流结束。 */
    public void endStream() {
        endRequested = true;
        downstream.sendText("Done", true);
    }

    @Override
    public void close() {
        try {
            downstream.sendClose(WebSocket.NORMAL_CLOSURE, "");
        } catch (Exception ignored) {
        }
    }

    private class DownstreamListener implements WebSocket.Listener {

        DownstreamListener(ObjectMapper objectMapper) {
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info(LogDomain.STT, "stt.proxy_opened", LogOutcome.SUCCEEDED);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                if (transcriptDispatcher.dispatch(data, endRequested)) {
                    endRequested = false;
                }
            } catch (Exception e) {
                log.debug("[STT] 解析识别结果失败: {}", e.getMessage());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info(LogDomain.STT, "stt.proxy_closed", LogOutcome.SUCCEEDED, "statusCode", statusCode, "reason", reason);
            onStreamEnd.run();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn(LogDomain.STT, "stt.proxy_error", LogOutcome.DEGRADED, "reason", error.getMessage());
            onError.accept(error);
        }
    }

    static final class TranscriptDispatcher {

        private final ObjectMapper objectMapper;
        private final Consumer<SttTranscriptEvent> onResult;
        private final Consumer<SttTranscriptEvent> onPartialResult;
        private final Runnable onStreamEnd;
        TranscriptDispatcher(ObjectMapper objectMapper,
                             Consumer<SttTranscriptEvent> onResult,
                             Consumer<SttTranscriptEvent> onPartialResult,
                             Runnable onStreamEnd) {
            this.objectMapper = objectMapper;
            this.onResult = onResult;
            this.onPartialResult = onPartialResult;
            this.onStreamEnd = onStreamEnd;
        }

        boolean dispatch(CharSequence data, boolean endRequested) throws Exception {
            JsonNode node = objectMapper.readTree(data.toString());
            SttTranscriptEvent event = SttTranscriptEvent.fromJson(node);
            String text = event.text();

            if (event.finalResult()) {
                if (!text.isBlank()) {
                    onResult.accept(event);
                }
                if (endRequested) {
                    onStreamEnd.run();
                    return true;
                }
            } else if (event.partialResult() || event.diagnostic() || event.error()) {
                onPartialResult.accept(event);
            }
            return false;
        }
    }
}
