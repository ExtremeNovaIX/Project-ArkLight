package p1.component.agent.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * 到 sherpa-onnx WebSocket 服务器的客户端代理。
 * <p>
 * 每路 Qt 音频流对应一个 {@link SttWebSocketProxy} 实例，负责：
 * <ol>
 *   <li>连接 sherpa-onnx 的 WebSocket 服务器</li>
 *   <li>转发 PCM 音频数据</li>
 *   <li>接收识别结果并通过回调通知上层</li>
 * </ol>
 */
@Slf4j
public class SttWebSocketProxy implements AutoCloseable {

    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final WebSocket downstream;
    private final Consumer<String> onResult;
    private final Consumer<String> onPartialResult;
    private final Runnable onStreamEnd;
    private final Consumer<Throwable> onError;
    private volatile boolean endRequested;

    SttWebSocketProxy(int sttPort,
                      ObjectMapper objectMapper,
                      Consumer<String> onResult,
                      Consumer<String> onPartialResult,
                      Runnable onStreamEnd,
                      Consumer<Throwable> onError) throws Exception {
        this.onResult = onResult;
        this.onPartialResult = onPartialResult;
        this.onStreamEnd = onStreamEnd;
        this.onError = onError;

        URI uri = URI.create("ws://127.0.0.1:" + sttPort);
        this.downstream = SHARED_HTTP_CLIENT.newWebSocketBuilder()
                .buildAsync(uri, new DownstreamListener(objectMapper))
                .get();
    }

    /** 发送音频配置并开始识别。 */
    public void begin(int sampleRate) {
        String config = String.format(
                "{\"method\":\"online\",\"sample_rate\":%d,\"format\":\"int16\"}", sampleRate);
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

        private final ObjectMapper objectMapper;
        private String partialHypothesis = "";

        DownstreamListener(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("[STT] 已连接到 sherpa-onnx WebSocket");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                JsonNode node = objectMapper.readTree(data.toString());
                String text = node.path("text").asText("");
                boolean isFinal = node.path("final").asBoolean(false);

                if (isFinal) {
                    String accumulated = SttTranscriptAccumulator.completeTranscript(partialHypothesis, text);
                    partialHypothesis = "";
                    if (!accumulated.isBlank()) {
                        onResult.accept(accumulated);
                    }
                    if (endRequested) {
                        endRequested = false;
                        onStreamEnd.run();
                    }
                } else if (!text.isEmpty()) {
                    partialHypothesis = SttTranscriptAccumulator.normalizePartialHypothesis(text);
                    onPartialResult.accept(partialHypothesis);
                }
            } catch (Exception e) {
                log.debug("[STT] 解析识别结果失败: {}", e.getMessage());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("[STT] sherpa-onnx WebSocket 连接关闭: status={}, reason={}", statusCode, reason);
            onStreamEnd.run();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("[STT] sherpa-onnx WebSocket 错误: {}", error.getMessage());
            onError.accept(error);
        }
    }
}
