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
 */
@Slf4j
public class SttStreamHandler extends TextWebSocketHandler {

    @FunctionalInterface
    public interface ProxyFactory {
        SttWebSocketProxy create(int port, ObjectMapper mapper,
                                 Consumer<SttTranscriptEvent> onResult, Consumer<SttTranscriptEvent> onPartial,
                                 Runnable onEnd, Consumer<Throwable> onError) throws Exception;
    }

    @FunctionalInterface
    public interface SidecarAvailability {
        String unavailableMessage(String engine);
    }

    private final SttConfig config;
    private final SttResultDispatcher resultDispatcher;
    private final ObjectMapper objectMapper;
    private final SttGameIntentGate gameIntentGate;
    private final ProxyFactory proxyFactory;
    private final SidecarAvailability sidecarAvailability;
    private final Map<String, SttWebSocketProxy> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> rpSessionIds = new ConcurrentHashMap<>();
    private final Map<String, String> gameRpSessionIds = new ConcurrentHashMap<>();
    private final Map<String, String> characterNames = new ConcurrentHashMap<>();
    private final Map<String, Boolean> debugOnlySessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionSources = new ConcurrentHashMap<>();
    private final Map<String, String> pendingTranscriptTexts = new ConcurrentHashMap<>();

    public SttStreamHandler(SttConfig config, SttResultDispatcher resultDispatcher, ObjectMapper objectMapper) {
        this(config, resultDispatcher, objectMapper, null, SttWebSocketProxy::new, engine -> "");
    }

    public SttStreamHandler(SttConfig config,
                            SttResultDispatcher resultDispatcher,
                            ObjectMapper objectMapper,
                            SttGameIntentGate gameIntentGate) {
        this(config, resultDispatcher, objectMapper, gameIntentGate, SttWebSocketProxy::new, engine -> "");
    }

    public SttStreamHandler(SttConfig config,
                            SttResultDispatcher resultDispatcher,
                            ObjectMapper objectMapper,
                            SttGameIntentGate gameIntentGate,
                            SttServerManager serverManager) {
        this(config, resultDispatcher, objectMapper, gameIntentGate, SttWebSocketProxy::new,
                serverManager::clientUnavailableMessage);
    }

    SttStreamHandler(SttConfig config, SttResultDispatcher resultDispatcher, ObjectMapper objectMapper,
                     ProxyFactory proxyFactory) {
        this(config, resultDispatcher, objectMapper, null, proxyFactory, engine -> "");
    }

    SttStreamHandler(SttConfig config,
                     SttResultDispatcher resultDispatcher,
                     ObjectMapper objectMapper,
                     SttGameIntentGate gameIntentGate,
                     ProxyFactory proxyFactory) {
        this(config, resultDispatcher, objectMapper, gameIntentGate, proxyFactory, engine -> "");
    }

    SttStreamHandler(SttConfig config,
                     SttResultDispatcher resultDispatcher,
                     ObjectMapper objectMapper,
                     SttGameIntentGate gameIntentGate,
                     ProxyFactory proxyFactory,
                     SidecarAvailability sidecarAvailability) {
        this.config = config;
        this.resultDispatcher = resultDispatcher;
        this.objectMapper = objectMapper;
        this.gameIntentGate = gameIntentGate;
        this.proxyFactory = proxyFactory;
        this.sidecarAvailability = sidecarAvailability;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("[STT] Qt client connected: session={}, waiting for config", session.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SttWebSocketProxy proxy = sessions.get(session.getId());
        if (proxy == null) {
            log.debug("[STT] Received audio before session initialization: {}", session.getId());
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

        SttWebSocketProxy proxy = sessions.get(sessionId);
        if (proxy != null) {
            if ("done".equalsIgnoreCase(text) || "eof".equalsIgnoreCase(text)) {
                proxy.endStream();
            }
            return;
        }

        parseConfigAndInit(session, text);
    }

    private void parseConfigAndInit(WebSocketSession session, String text) {
        String sessionId = session.getId();
        try {
            JsonNode configNode = objectMapper.readTree(text);
            String rpSessionId = normalizedConfigValue(configNode, "rpSessionId", sessionId);
            rpSessionIds.put(sessionId, rpSessionId);
            gameRpSessionIds.put(sessionId, normalizedConfigValue(configNode, "gameRpSessionId", rpSessionId));
            characterNames.put(sessionId, configNode.path("characterName").asText(""));
            debugOnlySessions.put(sessionId, configNode.path("debugOnly").asBoolean(false));
            sessionSources.put(sessionId, normalizeSource(configNode.path("source").asText("unknown")));
        } catch (JsonProcessingException e) {
            log.debug("[STT] Could not parse STT config JSON, use defaults: session={}", sessionId);
            rpSessionIds.put(sessionId, sessionId);
            gameRpSessionIds.put(sessionId, sessionId);
            characterNames.put(sessionId, "");
            debugOnlySessions.put(sessionId, false);
            sessionSources.put(sessionId, "unknown");
        }
        initProxy(session);
    }

    private void initProxy(WebSocketSession session) {
        String sessionId = session.getId();
        String engine = config.sttEngine();
        if (!config.supportedEngine(engine)) {
            String message = unsupportedEngineMessage(engine);
            log.warn("[STT] Reject Qt client because ASR engine is unsupported: session={}, engine={}", sessionId, engine);
            sendErrorMessage(session, message);
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (Exception ignored) {
            }
            return;
        }
        String unavailableMessage = sidecarAvailability.unavailableMessage(engine);
        if (unavailableMessage != null && !unavailableMessage.isBlank()) {
            log.warn("[STT] Reject Qt client because ASR sidecar is unavailable: session={}, engine={}, reason={}",
                    sessionId, engine, unavailableMessage);
            sendErrorMessage(session, unavailableMessage);
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            int port = config.enginePort(engine);
            SttWebSocketProxy proxy = proxyFactory.create(
                    port,
                    objectMapper,
                    event -> onSegment(session, event),
                    event -> onSidecarEvent(session, event),
                    () -> onStreamEnd(session),
                    error -> onProxyError(session, error)
            );
            sessions.put(sessionId, proxy);
            proxy.begin(16000, sessionSources.getOrDefault(sessionId, "unknown"));
            log.info("[STT] ASR proxy initialized: session={}, engine={}, port={}, source={}",
                    sessionId, engine, port, sessionSources.getOrDefault(sessionId, "unknown"));
        } catch (Exception e) {
            log.warn("[STT] Failed to connect ASR sidecar: engine={}, error={}", engine, e.getMessage());
            sendErrorMessage(session, sidecarConnectionMessage(engine));
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (Exception ignored) {
            }
        }
    }

    @Override    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(session.getId());
        log.info("[STT] Qt client disconnected: session={}, status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[STT] Qt transport error: session={}, error={}", session.getId(), exception.getMessage());
        cleanup(session.getId());
    }

    private void cleanup(String sessionId) {
        SttWebSocketProxy proxy = sessions.remove(sessionId);
        if (proxy != null) {
            proxy.close();
        }
        rpSessionIds.remove(sessionId);
        gameRpSessionIds.remove(sessionId);
        characterNames.remove(sessionId);
        debugOnlySessions.remove(sessionId);
        sessionSources.remove(sessionId);
        pendingTranscriptTexts.remove(sessionId);
        if (gameIntentGate != null) {
            gameIntentGate.cleanup(sessionId);
        }
    }

    private void onSegment(WebSocketSession session, SttTranscriptEvent event) {
        if (!sessions.containsKey(session.getId())) {
            return;
        }
        SttTranscriptEvent normalized = withSessionSource(session, event);
        String text = SttTranscriptAccumulator.normalizePartialHypothesis(normalized.text());
        if (text.isBlank()) {
            return;
        }
        normalized = normalized.withText(text).asSegment();
        sendResultMessage(session, normalized);
        routeSegment(session, normalized);
    }

    private void onSidecarEvent(WebSocketSession session, SttTranscriptEvent event) {
        if (!sessions.containsKey(session.getId())) {
            return;
        }
        SttTranscriptEvent normalized = withSessionSource(session, event);
        if (normalized.error()) {
            sendErrorMessage(session, normalized.text().isBlank() ? normalized.reason() : normalized.text());
            return;
        }
        if (normalized.diagnostic()) {
            sendDebugMessage(session, normalized, SttGameIntentGate.Result.pass("diagnostic"));
            return;
        }

        String text = SttTranscriptAccumulator.normalizePartialHypothesis(normalized.text());
        normalized = normalized.withText(text).asPartial();
        sendResultMessage(session, normalized);
        routePartial(session, normalized);
    }

    private void routePartial(WebSocketSession session, SttTranscriptEvent event) {
        if (event.text().isBlank() || gameIntentGate == null) {
            return;
        }
        String sessionId = session.getId();
        String rpSessionId = routingRpSessionId(sessionId);
        boolean debugOnly = debugOnlySessions.getOrDefault(sessionId, false);
        SttGameIntentGate.Result gateResult = debugOnly
                ? gameIntentGate.onPartialDryRun(sessionId, rpSessionId, event.text())
                : gameIntentGate.onPartial(sessionId, rpSessionId, event.text());
        if (debugOnly && shouldSendDebugMessage(false, gateResult)) {
            sendDebugMessage(session, event, gateResult);
        }
    }

    private void routeSegment(WebSocketSession session, SttTranscriptEvent event) {
        if (event.text().isBlank()) {
            return;
        }
        String sessionId = session.getId();
        String dispatchRpSessionId = rpSessionIds.getOrDefault(sessionId, sessionId);
        String routingRpSessionId = routingRpSessionId(sessionId);
        String characterName = characterNames.getOrDefault(sessionId, "");
        boolean debugOnly = debugOnlySessions.getOrDefault(sessionId, false);
        if (!shouldAcceptForConversation(event)) {
            if (isTerminalSegment(event)) {
                pendingTranscriptTexts.remove(sessionId);
            }
            if (debugOnly) {
                sendDebugMessage(session, event, SttGameIntentGate.Result.pass("asr-filtered"));
            }
            return;
        }

        if (!isTerminalSegment(event)) {
            pendingTranscriptTexts.merge(sessionId, event.text(), SttStreamHandler::joinTranscriptText);
            if (debugOnly) {
                sendDebugMessage(session, event, SttGameIntentGate.Result.pass("asr-buffered"));
            }
            return;
        }

        String llmText = pendingTranscriptTexts.remove(sessionId);
        llmText = joinTranscriptText(llmText, event.text());
        SttGameIntentGate.Result gateResult = gameIntentGate == null
                ? SttGameIntentGate.Result.pass()
                : (debugOnly
                ? gameIntentGate.onFinalDryRun(sessionId, routingRpSessionId, llmText)
                : gameIntentGate.onFinal(sessionId, routingRpSessionId, llmText));
        if (debugOnly) {
            sendDebugMessage(session, event, gateResult);
            return;
        }
        if (!gateResult.consumed()) {
            resultDispatcher.dispatch(dispatchRpSessionId, characterName, llmText);
        }
    }

    private boolean shouldAcceptForConversation(SttTranscriptEvent event) {
        return !event.text().isBlank()
                && event.finalResult()
                && "clear".equals(event.quality())
                && !event.overlap();
    }

    private boolean isTerminalSegment(SttTranscriptEvent event) {
        String reason = event.reason() == null ? "" : event.reason().trim();
        return "speech-end".equals(reason) || "done".equals(reason);
    }

    private static String joinTranscriptText(String left, String right) {
        String normalizedLeft = SttTranscriptAccumulator.normalizePartialHypothesis(left);
        String normalizedRight = SttTranscriptAccumulator.normalizePartialHypothesis(right);
        if (normalizedLeft.isBlank()) {
            return normalizedRight;
        }
        if (normalizedRight.isBlank()) {
            return normalizedLeft;
        }
        if (normalizedLeft.endsWith(normalizedRight)) {
            return normalizedLeft;
        }
        if (normalizedRight.startsWith(normalizedLeft)) {
            return normalizedRight;
        }
        return normalizedLeft + " " + normalizedRight;
    }
    private String sidecarConnectionMessage(String engine) {
        if (!config.supportedEngine(engine)) {
            return unsupportedEngineMessage(engine);
        }
        String normalized = config.normalizeEngine(engine);
        return "sherpa-qwen ASR sidecar unavailable at 127.0.0.1:" + config.enginePort(normalized)
                + ". Run " + config.sherpaQwenBootstrapHint()
                + " and check " + config.sherpaQwenLogFilePath() + ".";
    }

    private String unsupportedEngineMessage(String engine) {
        return "Unsupported STT engine: " + config.normalizeEngine(engine) + ". Configure stt.engine=sherpa-qwen-onnx.";
    }

    private SttTranscriptEvent withSessionSource(WebSocketSession session, SttTranscriptEvent event) {
        if (event.source() != null && !event.source().isBlank()) {
            return event;
        }
        return event.withSource(sessionSources.getOrDefault(session.getId(), "unknown"));
    }

    private String routingRpSessionId(String sessionId) {
        String gameRpSessionId = gameRpSessionIds.get(sessionId);
        if (gameRpSessionId != null && !gameRpSessionId.isBlank()) {
            return gameRpSessionId;
        }
        return rpSessionIds.getOrDefault(sessionId, sessionId);
    }

    private String normalizedConfigValue(JsonNode configNode, String fieldName, String fallback) {
        String value = configNode.path(fieldName).asText(fallback);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String normalizeSource(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return switch (normalized) {
            case "microphone", "mic" -> "microphone";
            case "application", "app", "process" -> "application";
            default -> "unknown";
        };
    }

    private void sendResultMessage(WebSocketSession session, SttTranscriptEvent event) {
        try {
            Map<String, Object> payload = baseAsrPayload(event);
            payload.put("type", "result");
            payload.put("asrType", event.type());
            payload.put("final", event.finalResult());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            if (event.finalResult()) {
                log.warn("[STT] Could not send final ASR result: {}", e.getMessage());
            } else {
                log.debug("[STT] Could not send partial ASR result: {}", e.getMessage());
            }
        }
    }

    private Map<String, Object> baseAsrPayload(SttTranscriptEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", event.text());
        putIfPresent(payload, "segmentId", event.segmentId());
        putIfPresent(payload, "source", event.source());
        putIfPresent(payload, "speakerId", event.speakerId());
        payload.put("speakerConfidence", event.speakerConfidence());
        payload.put("quality", event.quality());
        payload.put("overlap", event.overlap());
        payload.put("noiseLevel", event.noiseLevel());
        putIfNonNegative(payload, "startMs", event.startMs());
        putIfNonNegative(payload, "endMs", event.endMs());
        payload.put("revision", event.revision());
        payload.put("stable", event.stable());
        putIfNonNegative(payload, "latencyMs", event.latencyMs());
        putIfPresent(payload, "reason", event.reason());
        return payload;
    }

    private void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }

    private void putIfNonNegative(Map<String, Object> payload, String key, long value) {
        if (value >= 0L) {
            payload.put(key, value);
        }
    }

    private void sendDebugMessage(WebSocketSession session,
                                  SttTranscriptEvent event,
                                  SttGameIntentGate.Result gateResult) {
        try {
            Map<String, Object> payload = baseAsrPayload(event);
            payload.put("type", "debug");
            payload.put("stream", event.diagnostic() ? "asr-diagnostic" : "stt-game-intent");
            payload.put("asrType", event.type());
            payload.put("receivedAt", Instant.now().toString());
            payload.put("final", event.finalResult());
            payload.put("intent", gateResult.intent());
            payload.put("confidence", gateResult.confidence());
            payload.put("instruction", gateResult.instruction());
            payload.put("consumed", gateResult.consumed());
            payload.put("triggered", gateResult.triggered());
            payload.put("routed", gateResult.routed());
            payload.put("routeDurationMs", gateResult.routeDurationMs());
            payload.put("reason", event.reason().isBlank() ? gateResult.reason() : event.reason());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            log.debug("[STT] Could not send debug event: {}", e.getMessage());
        }
    }

    private boolean shouldSendDebugMessage(boolean finalResult, SttGameIntentGate.Result gateResult) {
        return finalResult || gateResult.consumed() || gateResult.triggered() || gateResult.routed();
    }

    private void onStreamEnd(WebSocketSession session) {
        pendingTranscriptTexts.remove(session.getId());
        if (gameIntentGate != null) {
            gameIntentGate.cleanup(session.getId());
        }
        try {
            String json = objectMapper.writeValueAsString(Map.of("type", "end"));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.debug("[STT] Could not send stream end: {}", e.getMessage());
        }
    }

    private void onProxyError(WebSocketSession session, Throwable error) {
        sendErrorMessage(session, error.getMessage());
    }

    private void sendErrorMessage(WebSocketSession session, String message) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "error",
                    "message", message == null || message.isBlank() ? "Voice stream error." : message
            ));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.debug("[STT] Could not send error notification: {}", e.getMessage());
        }
    }
}
