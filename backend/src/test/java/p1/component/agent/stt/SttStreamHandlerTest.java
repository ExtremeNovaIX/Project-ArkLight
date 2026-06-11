package p1.component.agent.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import p1.model.dto.ChatRequestDTO;
import p1.service.ChatService;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SttStreamHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SttConfig config = new SttConfig();

    @Test
    void shouldParseConfigAndInitOnFirstTextMessage() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttWebSocketProxy mockProxy = mock(SttWebSocketProxy.class);
        AtomicReference<Consumer<String>> onResultCapture = new AtomicReference<>();
        AtomicReference<Consumer<String>> onPartialCapture = new AtomicReference<>();
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

        // 首次文本消息 → 解析配置并初始化
        handler.handleTextMessage(session, new TextMessage("{\"rpSessionId\":\"rp-1\",\"characterName\":\"Nova\"}"));

        verify(mockProxy).begin(16000);
        assertNotNull(onResultCapture.get(), "应捕获 onResult 回调");
        assertNotNull(onPartialCapture.get(), "应捕获 onPartial 回调");
        assertNotNull(onEndCapture.get(), "应捕获 onEnd 回调");
        assertNotNull(onErrorCapture.get(), "应捕获 onError 回调");
    }

    @Test
    void shouldUseDefaultsWhenConfigJsonIsMalformed() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttWebSocketProxy mockProxy = mock(SttWebSocketProxy.class);

        SttStreamHandler.ProxyFactory factory = (port, mapper, onResult, onPartial, onEnd, onError) -> mockProxy;
        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper, factory);
        WebSocketSession session = session("s1");

        // 乱码文本 → 使用 session ID 作为默认值
        handler.handleTextMessage(session, new TextMessage("not valid json!!!"));

        verify(mockProxy).begin(16000);
    }

    @Test
    void shouldForwardDoneMessageToProxy() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttWebSocketProxy mockProxy = mock(SttWebSocketProxy.class);

        SttStreamHandler.ProxyFactory factory = (port, mapper, onResult, onPartial, onEnd, onError) -> mockProxy;
        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper, factory);
        WebSocketSession session = session("s1");

        // 先初始化
        handler.handleTextMessage(session, new TextMessage("{}"));
        verify(mockProxy).begin(16000);

        // 发送 "done" → 应转发到 proxy
        handler.handleTextMessage(session, new TextMessage("done"));
        verify(mockProxy).endStream();

        // "eof" 同理
        handler.handleTextMessage(session, new TextMessage("eof"));
        verify(mockProxy, times(2)).endStream();
    }

    @Test
    void shouldSkipBinaryMessagesBeforeInit() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper,
                (p, m, r, pr, e, er) -> mock(SttWebSocketProxy.class));
        WebSocketSession session = session("s1");

        // 初始化前发二进制 → 应跳过，不抛异常
        byte[] pcm = new byte[160];
        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(pcm)));

        // 不应抛异常
    }

    @Test
    void shouldForwardBinaryToProxyAfterInit() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttWebSocketProxy mockProxy = mock(SttWebSocketProxy.class);

        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper,
                (p, m, r, pr, e, er) -> mockProxy);
        WebSocketSession session = session("s1");

        // 初始化
        handler.handleTextMessage(session, new TextMessage("{}"));

        // 发二进制
        byte[] pcm = new byte[] { 0, 1, 2, 3 };
        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(pcm)));
        verify(mockProxy).sendAudio(pcm);
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

        verify(mockProxy).close();
        // 清理后再发二进制应跳过
        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[10])));
        verify(mockProxy, never()).sendAudio(any());
    }

    @Test
    void shouldDispatchFinalResultToRpAndSendJsonToQt() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        WebSocketSession session = session("s1");

        SttStreamHandler.ProxyFactory factory = (port, mapper, onResult, onPartial, onEnd, onError) -> {
            // 模拟识别结果回调
            onResult.accept("你好世界");
            onPartial.accept("你好");
            onEnd.run();
            onError.accept(new RuntimeException("测试错误"));
            return mock(SttWebSocketProxy.class);
        };

        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper, factory);
        handler.handleTextMessage(session, new TextMessage("{\"rpSessionId\":\"rp-1\",\"characterName\":\"Nova\"}"));

        // 验证 final 结果发送到 Qt
        verify(session, atLeastOnce()).sendMessage(argThat(msg -> {
            if (msg instanceof TextMessage tm) {
                return tm.getPayload().contains("\"type\":\"result\"") && tm.getPayload().contains("\"final\":true");
            }
            return false;
        }));

        // 验证 partial 结果发送到 Qt
        verify(session, atLeastOnce()).sendMessage(argThat(msg -> {
            if (msg instanceof TextMessage tm) {
                return tm.getPayload().contains("\"type\":\"result\"") && tm.getPayload().contains("\"final\":false");
            }
            return false;
        }));

        // 验证结束信号发送到 Qt
        verify(session, atLeastOnce()).sendMessage(argThat(msg -> {
            if (msg instanceof TextMessage tm) {
                return tm.getPayload().contains("\"type\":\"end\"");
            }
            return false;
        }));

        // 验证错误信号发送到 Qt
        verify(session, atLeastOnce()).sendMessage(argThat(msg -> {
            if (msg instanceof TextMessage tm) {
                return tm.getPayload().contains("\"type\":\"error\"");
            }
            return false;
        }));

        // 验证分发到 RP
        verify(dispatcher).dispatch("rp-1", "Nova", "你好世界");
    }

    @Test
    void shouldNotDispatchFinalResultWhenGameVoiceGateConsumesIt() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttGameIntentGate gameIntentGate = mock(SttGameIntentGate.class);
        when(gameIntentGate.onFinal("s1", "rp-1", "等一下"))
                .thenReturn(SttGameIntentGate.Result.consumed("WAIT", 0.93, "等一下", true));
        WebSocketSession session = session("s1");

        SttStreamHandler.ProxyFactory factory = (port, mapper, onResult, onPartial, onEnd, onError) -> {
            onResult.accept("等一下");
            return mock(SttWebSocketProxy.class);
        };

        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper, gameIntentGate, factory);
        handler.handleTextMessage(session, new TextMessage("{\"rpSessionId\":\"rp-1\",\"characterName\":\"Nova\"}"));

        verify(gameIntentGate).onFinal("s1", "rp-1", "等一下");
        verify(dispatcher, never()).dispatch(any(), any(), any());
    }

    @Test
    void debugOnlyShouldDryRunGateAndSkipRpDispatch() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttGameIntentGate gameIntentGate = mock(SttGameIntentGate.class);
        when(gameIntentGate.onFinalDryRun("s1", "rp-1", "等一下"))
                .thenReturn(SttGameIntentGate.Result.consumed(
                        "WAIT", 0.93, "等一下", false, true, 42L, "dry-run-would-trigger"));
        WebSocketSession session = session("s1");

        SttStreamHandler.ProxyFactory factory = (port, mapper, onResult, onPartial, onEnd, onError) -> {
            onResult.accept("等一下");
            return mock(SttWebSocketProxy.class);
        };

        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper, gameIntentGate, factory);
        handler.handleTextMessage(session, new TextMessage(
                "{\"rpSessionId\":\"rp-1\",\"characterName\":\"Nova\",\"debugOnly\":true}"));

        verify(gameIntentGate).onFinalDryRun("s1", "rp-1", "等一下");
        verify(gameIntentGate, never()).onFinal("s1", "rp-1", "等一下");
        verify(dispatcher, never()).dispatch(any(), any(), any());
        verify(session, atLeastOnce()).sendMessage(argThat(msg -> {
            if (msg instanceof TextMessage tm) {
                return tm.getPayload().contains("\"type\":\"debug\"")
                        && tm.getPayload().contains("\"intent\":\"WAIT\"")
                        && tm.getPayload().contains("\"routeDurationMs\":42");
            }
            return false;
        }));
    }

    @Test
    void shouldHandleTransportErrorAndCleanup() throws Exception {
        SttResultDispatcher dispatcher = mock(SttResultDispatcher.class);
        SttWebSocketProxy mockProxy = mock(SttWebSocketProxy.class);

        SttStreamHandler handler = new SttStreamHandler(config, dispatcher, objectMapper,
                (p, m, r, pr, e, er) -> mockProxy);
        WebSocketSession session = session("s1");
        handler.handleTextMessage(session, new TextMessage("{}"));

        handler.handleTransportError(session, new RuntimeException("网络断开"));

        verify(mockProxy).close();
        // 验证清理后再发二进制不抛异常
        assertDoesNotThrow(() ->
                handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[10]))));
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }
}
