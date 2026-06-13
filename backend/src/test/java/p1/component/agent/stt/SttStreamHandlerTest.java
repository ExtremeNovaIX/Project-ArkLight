package p1.component.agent.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SttStreamHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SttConfig config = new SttConfig();

    @Test
    void shouldParseConfigInitProxyAndPassSourceToSidecar() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttWebSocketProxy mockProxy = mock(SttWebSocketProxy.class);
        AtomicReference<Consumer<SttTranscriptEvent>> onResultCapture = new AtomicReference<>();
        AtomicReference<Consumer<SttTranscriptEvent>> onPartialCapture = new AtomicReference<>();
        AtomicReference<Runnable> onEndCapture = new AtomicReference<>();
        AtomicReference<Consumer<Throwable>> onErrorCapture = new AtomicReference<>();

        SttStreamHandler.ProxyFactory factory = (port, mapper, onResult, onPartial, onEnd, onError) -> {
            onResultCapture.set(onResult);
            onPartialCapture.set(onPartial);
            onEndCapture.set(onEnd);
            onErrorCapture.set(onError);
            return mockProxy;
        };

        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper, factory);
        WebSocketSession session = session("s1");

        handler.handleTextMessage(session, new TextMessage(
                "{\"rpSessionId\":\"rp-1\",\"characterName\":\"Nova\",\"source\":\"application\"}"));

        verify(mockProxy).begin(16000, "application");
        assertNotNull(onResultCapture.get());
        assertNotNull(onPartialCapture.get());
        assertNotNull(onEndCapture.get());
        assertNotNull(onErrorCapture.get());
    }


    @Test
    void shouldRejectUnsupportedConfiguredEngineWithoutCreatingProxy() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        AtomicReference<Boolean> proxyCreated = new AtomicReference<>(false);
        SttConfig legacyConfig = new SttConfig() {
            @Override
            public String sttEngine() {
                return "funasr-2pass-win";
            }
        };

        SttStreamHandler handler = new SttStreamHandler(legacyConfig, dispatcher, objectMapper, null,
                (p, m, r, pr, e, er) -> {
                    proxyCreated.set(true);
                    return mock(SttWebSocketProxy.class);
                },
                engine -> "");
        WebSocketSession session = session("s1");

        handler.handleTextMessage(session, new TextMessage("{\"rpSessionId\":\"rp-1\"}"));

        org.junit.jupiter.api.Assertions.assertFalse(proxyCreated.get());
        verify(session).sendMessage(argThat(msg -> msg instanceof TextMessage tm
                && tm.getPayload().contains("\"type\":\"error\"")
                && tm.getPayload().contains("Unsupported STT engine")));
        verify(session).close(CloseStatus.SERVER_ERROR);
    }

    @Test
    void shouldUseDefaultsWhenConfigJsonIsMalformed() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttWebSocketProxy mockProxy = mock(SttWebSocketProxy.class);

        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper,
                (p, m, r, pr, e, er) -> mockProxy);
        WebSocketSession session = session("s1");

        handler.handleTextMessage(session, new TextMessage("not valid json!!!"));

        verify(mockProxy).begin(16000, "unknown");
    }

    @Test
    void shouldNotifyQtWhenProxyInitFails() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper,
                (p, m, r, pr, e, er) -> {
                    throw new RuntimeException("connection refused");
                });
        WebSocketSession session = session("s1");

        handler.handleTextMessage(session, new TextMessage("{}"));

        verify(session).sendMessage(argThat(msg -> msg instanceof TextMessage tm
                && tm.getPayload().contains("\"type\":\"error\"")
                && tm.getPayload().contains("sherpa-qwen ASR sidecar unavailable")));
        verify(session).close(CloseStatus.SERVER_ERROR);
    }

    @Test
    void shouldFailFastWhenManagedSidecarIsUnavailable() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        AtomicReference<Boolean> proxyCreated = new AtomicReference<>(false);
        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper, null,
                (p, m, r, pr, e, er) -> {
                    proxyCreated.set(true);
                    return mock(SttWebSocketProxy.class);
                },
                engine -> "sherpa-qwen runtime is not ready. Check sherpa-qwen-sidecar.log.");
        WebSocketSession session = session("s1");

        handler.handleTextMessage(session, new TextMessage("{}"));

        org.junit.jupiter.api.Assertions.assertFalse(proxyCreated.get());
        verify(session).sendMessage(argThat(msg -> msg instanceof TextMessage tm
                && tm.getPayload().contains("\"type\":\"error\"")
                && tm.getPayload().contains("sherpa-qwen runtime is not ready")));
        verify(session).close(CloseStatus.SERVER_ERROR);
    }

    @Test
    void shouldForwardDoneAndBinaryMessagesToProxyAfterInit() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttWebSocketProxy mockProxy = mock(SttWebSocketProxy.class);
        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper,
                (p, m, r, pr, e, er) -> mockProxy);
        WebSocketSession session = session("s1");

        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[] {1, 2})));
        handler.handleTextMessage(session, new TextMessage("{}"));
        byte[] pcm = new byte[] {0, 1, 2, 3};
        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(pcm)));
        handler.handleTextMessage(session, new TextMessage("done"));
        handler.handleTextMessage(session, new TextMessage("eof"));

        verify(mockProxy).sendAudio(pcm);
        verify(mockProxy, times(2)).endStream();
    }

    @Test
    void shouldCleanupSessionsOnConnectionClose() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttWebSocketProxy mockProxy = mock(SttWebSocketProxy.class);
        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper,
                (p, m, r, pr, e, er) -> mockProxy);
        WebSocketSession session = session("s1");
        handler.handleTextMessage(session, new TextMessage("{\"rpSessionId\":\"rp-1\"}"));

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[10])));

        verify(mockProxy).close();
        verify(mockProxy, never()).sendAudio(any());
    }

    @Test
    void shouldDispatchTerminalMicrophoneSegmentToRpAndQt() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        AtomicReference<Consumer<SttTranscriptEvent>> onSegment = new AtomicReference<>();
        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper,
                (p, m, r, pr, e, er) -> {
                    onSegment.set(r);
                    return mock(SttWebSocketProxy.class);
                });
        WebSocketSession session = session("s1");
        handler.handleTextMessage(session, new TextMessage("{\"rpSessionId\":\"rp-1\",\"characterName\":\"Nova\"}"));

        onSegment.get().accept(segment("hello", "SPEAKER_00", 0.82, "clear", false, "speech-end"));

        verify(dispatcher).dispatch("rp-1", "Nova", "hello");
        verify(session, atLeastOnce()).sendMessage(argThat(msg -> msg instanceof TextMessage tm
                && tm.getPayload().contains("\"type\":\"result\"")
                && tm.getPayload().contains("\"final\":true")
                && tm.getPayload().contains("\"speakerId\":\"SPEAKER_00\"")
                && tm.getPayload().contains("\"quality\":\"clear\"")));
    }

    @Test
    void shouldBufferClearMicrophoneMaxWindowUntilSpeechEnd() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        AtomicReference<Consumer<SttTranscriptEvent>> onSegment = new AtomicReference<>();
        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper,
                (p, m, r, pr, e, er) -> {
                    onSegment.set(r);
                    return mock(SttWebSocketProxy.class);
                });
        WebSocketSession session = session("s1");
        handler.handleTextMessage(session, new TextMessage("{\"rpSessionId\":\"rp-1\",\"characterName\":\"Nova\"}"));

        onSegment.get().accept(segment("first half", "SPEAKER_00", 0.82, "clear", false, "max-window"));

        verify(dispatcher, never()).dispatch(any(), any(), any());

        onSegment.get().accept(segment("second half", "SPEAKER_00", 0.82, "clear", false, "speech-end"));

        verify(dispatcher).dispatch("rp-1", "Nova", "first half second half");
    }

    @Test
    void shouldRoutePartialToGameGateImmediately() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttGameIntentGate gameIntentGate = mock(SttGameIntentGate.class);
        when(gameIntentGate.onPartial("s1", "game-rp", "wait"))
                .thenReturn(SttGameIntentGate.Result.pass("WAIT", 0.91, "wait", true, 12L, "routed"));
        AtomicReference<Consumer<SttTranscriptEvent>> onPartial = new AtomicReference<>();
        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper, gameIntentGate,
                (p, m, r, pr, e, er) -> {
                    onPartial.set(pr);
                    return mock(SttWebSocketProxy.class);
                });
        WebSocketSession session = session("s1");
        handler.handleTextMessage(session, new TextMessage(
                "{\"rpSessionId\":\"rp-1\",\"gameRpSessionId\":\"game-rp\"}"));

        onPartial.get().accept(partial("wait", "SPEAKER_00", 0.75, "clear", false));

        verify(gameIntentGate).onPartial("s1", "game-rp", "wait");
        verify(dispatcher, never()).dispatch(any(), any(), any());
    }

    @Test
    void shouldRouteApplicationPartialToGameGate() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttGameIntentGate gameIntentGate = mock(SttGameIntentGate.class);
        when(gameIntentGate.onPartial("s1", "game-rp", "wait"))
                .thenReturn(SttGameIntentGate.Result.pass("WAIT", 0.91, "wait", true, 12L, "routed"));
        AtomicReference<Consumer<SttTranscriptEvent>> onPartial = new AtomicReference<>();
        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper, gameIntentGate,
                (p, m, r, pr, e, er) -> {
                    onPartial.set(pr);
                    return mock(SttWebSocketProxy.class);
                });
        WebSocketSession session = session("s1");
        handler.handleTextMessage(session, new TextMessage(
                "{\"rpSessionId\":\"rp-1\",\"gameRpSessionId\":\"game-rp\",\"source\":\"application\"}"));

        onPartial.get().accept(partial("wait", "SPEAKER_00", 0.75, "clear", false).withSource("application"));

        verify(gameIntentGate).onPartial("s1", "game-rp", "wait");
        verify(dispatcher, never()).dispatch(any(), any(), any());
    }

    @Test
    void shouldDispatchTerminalApplicationSegmentToRp() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        AtomicReference<Consumer<SttTranscriptEvent>> onSegment = new AtomicReference<>();
        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper,
                (p, m, r, pr, e, er) -> {
                    onSegment.set(r);
                    return mock(SttWebSocketProxy.class);
                });
        WebSocketSession session = session("s1");
        handler.handleTextMessage(session, new TextMessage(
                "{\"rpSessionId\":\"rp-1\",\"characterName\":\"Nova\",\"source\":\"application\"}"));

        onSegment.get().accept(segment("video speech", "SPEAKER_01", 0.83, "clear", false, "speech-end")
                .withSource("application"));

        verify(dispatcher).dispatch("rp-1", "Nova", "video speech");
    }

    @Test
    void shouldBufferApplicationMaxWindowUntilSpeechEnd() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        AtomicReference<Consumer<SttTranscriptEvent>> onSegment = new AtomicReference<>();
        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper,
                (p, m, r, pr, e, er) -> {
                    onSegment.set(r);
                    return mock(SttWebSocketProxy.class);
                });
        WebSocketSession session = session("s1");
        handler.handleTextMessage(session, new TextMessage(
                "{\"rpSessionId\":\"rp-1\",\"characterName\":\"Nova\",\"source\":\"application\"}"));

        onSegment.get().accept(segment("first half", "SPEAKER_01", 0.83, "clear", false, "max-window")
                .withSource("application"));

        verify(dispatcher, never()).dispatch(any(), any(), any());

        onSegment.get().accept(segment("second half", "SPEAKER_01", 0.83, "clear", false, "speech-end")
                .withSource("application"));

        verify(dispatcher).dispatch("rp-1", "Nova", "first half second half");
    }

    @Test
    void shouldNotDispatchUnclearMicrophoneSegmentToRpOrGameGate() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttGameIntentGate gameIntentGate = mock(SttGameIntentGate.class);
        when(gameIntentGate.onFinal(any(), any(), any())).thenReturn(SttGameIntentGate.Result.pass());
        AtomicReference<Consumer<SttTranscriptEvent>> onSegment = new AtomicReference<>();
        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper, gameIntentGate,
                (p, m, r, pr, e, er) -> {
                    onSegment.set(r);
                    return mock(SttWebSocketProxy.class);
                });
        WebSocketSession session = session("s1");
        handler.handleTextMessage(session, new TextMessage("{\"rpSessionId\":\"rp-1\"}"));

        onSegment.get().accept(segment("maybe stop", "SPEAKER_01", 0.54, "overlapped", true, "speech-end"));

        verify(gameIntentGate, never()).onFinal(any(), any(), any());
        verify(dispatcher, never()).dispatch(any(), any(), any());
    }

    @Test
    void shouldDropBlankSegmentBeforeQtAndRouter() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttGameIntentGate gameIntentGate = mock(SttGameIntentGate.class);
        AtomicReference<Consumer<SttTranscriptEvent>> onSegment = new AtomicReference<>();
        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper, gameIntentGate,
                (p, m, r, pr, e, er) -> {
                    onSegment.set(r);
                    return mock(SttWebSocketProxy.class);
                });
        WebSocketSession session = session("s1");
        handler.handleTextMessage(session, new TextMessage("{\"rpSessionId\":\"rp-1\"}"));

        onSegment.get().accept(segment("", "SPEAKER_99", 0.96, "noisy", false, "speech-end"));

        verify(gameIntentGate, never()).onFinal(any(), any(), any());
        verify(dispatcher, never()).dispatch(any(), any(), any());
        verify(session, never()).sendMessage(argThat(msg -> msg instanceof TextMessage tm
                && tm.getPayload().contains("\"type\":\"result\"")));
    }

    @Test
    void debugOnlyShouldForwardAsrV2MetadataToDebugEvents() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttGameIntentGate gameIntentGate = mock(SttGameIntentGate.class);
        when(gameIntentGate.onFinalDryRun(any(), any(), any()))
                .thenReturn(SttGameIntentGate.Result.consumed(
                        "WAIT", 0.93, "hold", false, true, 42L, "dry-run-would-trigger"));
        AtomicReference<Consumer<SttTranscriptEvent>> onSegment = new AtomicReference<>();
        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper, gameIntentGate,
                (p, m, r, pr, e, er) -> {
                    onSegment.set(r);
                    return mock(SttWebSocketProxy.class);
                });
        WebSocketSession session = session("s1");
        handler.handleTextMessage(session, new TextMessage(
                "{\"rpSessionId\":\"rp-1\",\"characterName\":\"Nova\",\"debugOnly\":true}"));

        onSegment.get().accept(segment("hold", "SPEAKER_00", 0.79, "clear", false, "speech-end"));

        verify(dispatcher, never()).dispatch(any(), any(), any());
        verify(session, atLeastOnce()).sendMessage(argThat(msg -> msg instanceof TextMessage tm
                && tm.getPayload().contains("\"type\":\"debug\"")
                && tm.getPayload().contains("\"speakerId\":\"SPEAKER_00\"")
                && tm.getPayload().contains("\"quality\":\"clear\"")
                && tm.getPayload().contains("\"speakerConfidence\":0.79")
                && tm.getPayload().contains("\"latencyMs\":88")));
    }

    @Test
    void shouldIgnoreLateSegmentAfterConnectionClose() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        AtomicReference<Consumer<SttTranscriptEvent>> onSegment = new AtomicReference<>();
        SttWebSocketProxy mockProxy = mock(SttWebSocketProxy.class);
        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper,
                (p, m, r, pr, e, er) -> {
                    onSegment.set(r);
                    return mockProxy;
                });
        WebSocketSession session = session("s1");
        handler.handleTextMessage(session, new TextMessage("{\"rpSessionId\":\"rp-1\"}"));

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        onSegment.get().accept(segment("late text", "SPEAKER_00", 0.82, "clear", false, "speech-end"));

        verify(dispatcher, never()).dispatch(any(), any(), any());
        verify(session, never()).sendMessage(argThat(msg -> msg instanceof TextMessage tm
                && tm.getPayload().contains("late text")));
    }

    @Test
    void shouldHandleTransportErrorAndCleanup() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttWebSocketProxy mockProxy = mock(SttWebSocketProxy.class);

        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper,
                (p, m, r, pr, e, er) -> mockProxy);
        WebSocketSession session = session("s1");
        handler.handleTextMessage(session, new TextMessage("{}"));

        handler.handleTransportError(session, new RuntimeException("network down"));

        verify(mockProxy).close();
        assertDoesNotThrow(() ->
                handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[10]))));
    }

    private SttTranscriptEvent segment(String text,
                                       String speakerId,
                                       double confidence,
                                       String quality,
                                       boolean overlap,
                                       String reason) {
        return new SttTranscriptEvent("segment", "seg-1", "microphone", text,
                speakerId, confidence, quality, overlap, 0.12d,
                100L, 900L, 2, true, 88L, reason);
    }

    private SttTranscriptEvent partial(String text,
                                       String speakerId,
                                       double confidence,
                                       String quality,
                                       boolean overlap) {
        return new SttTranscriptEvent("partial", "seg-1", "microphone", text,
                speakerId, confidence, quality, overlap, 0.12d,
                100L, 500L, 1, false, 40L, "streaming");
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }
}
