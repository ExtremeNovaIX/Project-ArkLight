package p1.component.agent.stt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 处理来自 Qt 前端的 STT 音频流 WebSocket 连接。
 * <p>
 * 协议：
 * <ol>
 *   <li>Qt 连接后先发送配置 JSON: {@code {"rpSessionId":"xxx","characterName":"yyy"}}</li>
 *   <li>Qt 发送二进制 PCM 音频帧 (int16, 16kHz, mono)</li>
 *   <li>Qt 发送 {@code "done"} 结束流</li>
 *   <li>服务端以 JSON 返回识别结果</li>
 * </ol>
 */
@Slf4j
public class SttStreamHandler extends TextWebSocketHandler {

    /** 可注入的 proxy 工厂，用于测试中替换 sherpa-onnx 连接。 */
    @FunctionalInterface
    public interface ProxyFactory {
        SttWebSocketProxy create(int port, ObjectMapper mapper,
                                 Consumer<String> onResult, Consumer<String> onPartial,
                                 Runnable onEnd, Consumer<Throwable> onError) throws Exception;
    }

    private final SttConfig config;
    private final SttResultDispatcher resultDispatcher;
    private final ObjectMapper objectMapper;
    private final SttGameIntentGate gameIntentGate;
    private final ProxyFactory proxyFactory;
    private final Map<String, SttWebSocketProxy> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> rpSessionIds = new ConcurrentHashMap<>();
    private final Map<String, String> characterNames = new ConcurrentHashMap<>();
    private final Map<String, Boolean> debugOnlySessions = new ConcurrentHashMap<>();

    public SttStreamHandler(SttConfig config, SttResultDispatcher resultDispatcher, ObjectMapper objectMapper) {
        this(config, resultDispatcher, objectMapper, null, SttWebSocketProxy::new);
    }

    public SttStreamHandler(SttConfig config,
                            SttResultDispatcher resultDispatcher,
                            ObjectMapper objectMapper,
                            SttGameIntentGate gameIntentGate) {
        this(config, resultDispatcher, objectMapper, gameIntentGate, SttWebSocketProxy::new);
    }

    /** 供测试使用，注入自定义 proxy 工厂。 */
    SttStreamHandler(SttConfig config, SttResultDispatcher resultDispatcher, ObjectMapper objectMapper,
                     ProxyFactory proxyFactory) {
        this(config, resultDispatcher, objectMapper, null, proxyFactory);
    }

    /** 供测试使用，注入自定义 proxy 工厂。 */
    SttStreamHandler(SttConfig config,
                     SttResultDispatcher resultDispatcher,
                     ObjectMapper objectMapper,
                     SttGameIntentGate gameIntentGate,
                     ProxyFactory proxyFactory) {
        this.config = config;
        this.resultDispatcher = resultDispatcher;
        this.objectMapper = objectMapper;
        this.gameIntentGate = gameIntentGate;
        this.proxyFactory = proxyFactory;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("[STT] Qt 客户端连接: session={}, waiting for config", session.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SttWebSocketProxy proxy = sessions.get(session.getId());
        if (proxy == null) {
            log.debug("[STT] 收到音频数据但 session 未初始化: {}", session.getId());
            return;
        }
        ByteBuffer buffer = message.getPayload();
        byte[] pcm = new byte[buffer.remaining()];
        buffer.get(pcm);
        proxy.sendAudio(pcm);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String text = message.getPayload();
        String sessionId = session.getId();

        // 已建立代理 → 控制消息
        SttWebSocketProxy proxy = sessions.get(sessionId);
        if (proxy != null) {
            if ("done".equalsIgnoreCase(text) || "eof".equalsIgnoreCase(text)) {
                proxy.endStream();
            }
            return;
        }

        // 未建立代理 → 解析配置消息
        parseConfigAndInit(session, text);
    }

    private void parseConfigAndInit(WebSocketSession session, String text) {
        String sessionId = session.getId();
        try {
            JsonNode configNode = objectMapper.readTree(text);
            rpSessionIds.put(sessionId, configNode.path("rpSessionId").asText(sessionId));
            characterNames.put(sessionId, configNode.path("characterName").asText(""));
            debugOnlySessions.put(sessionId, configNode.path("debugOnly").asBoolean(false));
        } catch (JsonProcessingException e) {
            log.debug("[STT] 无法解析配置 JSON，使用默认值: session={}", sessionId);
            rpSessionIds.put(sessionId, sessionId);
            characterNames.put(sessionId, "");
            debugOnlySessions.put(sessionId, false);
        }
        initProxy(session);
    }

    private void initProxy(WebSocketSession session) {
        String sessionId = session.getId();
        try {
            SttWebSocketProxy proxy = proxyFactory.create(
                    config.serverPort(),
                    objectMapper,
                    finalText -> onFinalResult(session, finalText),
                    partialText -> onPartialResult(session, partialText),
                    () -> onStreamEnd(session),
                    error -> onProxyError(session, error)
            );
            sessions.put(sessionId, proxy);
            proxy.begin(16000);
            log.info("[STT] 代理初始化完成: session={}", sessionId);
        } catch (Exception e) {
            log.warn("[STT] 连接 sherpa-onnx 失败: {}", e.getMessage());
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(session.getId());
        log.info("[STT] Qt 客户端断开: session={}, status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[STT] Qt 传输错误: session={}, error={}", session.getId(), exception.getMessage());
        cleanup(session.getId());
    }

    private void cleanup(String sessionId) {
        SttWebSocketProxy proxy = sessions.remove(sessionId);
        if (proxy != null) {
            proxy.close();
        }
        rpSessionIds.remove(sessionId);
        characterNames.remove(sessionId);
        debugOnlySessions.remove(sessionId);
        if (gameIntentGate != null) {
            gameIntentGate.cleanup(sessionId);
        }
    }

    private void onFinalResult(WebSocketSession session, String text) {
        String rpSessionId = rpSessionIds.getOrDefault(session.getId(), session.getId());
        String characterName = characterNames.getOrDefault(session.getId(), "");
        boolean debugOnly = debugOnlySessions.getOrDefault(session.getId(), false);
        sendResultMessage(session, text, true);
        SttGameIntentGate.Result gateResult = gameIntentGate == null
                ? SttGameIntentGate.Result.pass()
                : (debugOnly
                ? gameIntentGate.onFinalDryRun(session.getId(), rpSessionId, text)
                : gameIntentGate.onFinal(session.getId(), rpSessionId, text));
        if (debugOnly) {
            sendDebugMessage(session, text, true, gateResult);
            return;
        }
        if (!gateResult.consumed()) {
            resultDispatcher.dispatch(rpSessionId, characterName, text);
        }
    }

    private void onPartialResult(WebSocketSession session, String text) {
        sendResultMessage(session, text, false);
        boolean debugOnly = debugOnlySessions.getOrDefault(session.getId(), false);
        if (debugOnly) {
            String rpSessionId = rpSessionIds.getOrDefault(session.getId(), session.getId());
            SttGameIntentGate.Result gateResult = gameIntentGate == null
                    ? SttGameIntentGate.Result.pass()
                    : gameIntentGate.onPartialDryRun(session.getId(), rpSessionId, text);
            sendDebugMessage(session, text, false, gateResult);
            return;
        }
        if (gameIntentGate != null) {
            String rpSessionId = rpSessionIds.getOrDefault(session.getId(), session.getId());
            gameIntentGate.onPartial(session.getId(), rpSessionId, text);
        }
    }

    private void sendResultMessage(WebSocketSession session, String text, boolean finalResult) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "result",
                    "text", text,
                    "final", finalResult
            ));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            if (finalResult) {
                log.warn("[STT] 无法发送最终结果: {}", e.getMessage());
            } else {
                log.debug("[STT] 无法发送部分结果: {}", e.getMessage());
            }
        }
    }

    private void sendDebugMessage(WebSocketSession session,
                                  String text,
                                  boolean finalResult,
                                  SttGameIntentGate.Result gateResult) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "debug");
            payload.put("stream", "stt-game-intent");
            payload.put("receivedAt", Instant.now().toString());
            payload.put("text", text);
            payload.put("final", finalResult);
            payload.put("intent", gateResult.intent());
            payload.put("confidence", gateResult.confidence());
            payload.put("instruction", gateResult.instruction());
            payload.put("consumed", gateResult.consumed());
            payload.put("triggered", gateResult.triggered());
            payload.put("routed", gateResult.routed());
            payload.put("routeDurationMs", gateResult.routeDurationMs());
            payload.put("reason", gateResult.reason());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            log.debug("[STT] 无法发送调试事件: {}", e.getMessage());
        }
    }

    private void onStreamEnd(WebSocketSession session) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("type", "end"));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.debug("[STT] 无法发送结束信号: {}", e.getMessage());
        }
    }

    private void onProxyError(WebSocketSession session, Throwable error) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "error",
                    "message", error.getMessage()
            ));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.debug("[STT] 无法发送错误通知: {}", e.getMessage());
        }
    }
}
