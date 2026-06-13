package p1.component.agent.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * STT WebSocket 端点注册。
 * <p>
 * Qt 前端通过 {@code ws://backend:8080/stt/stream} 发送 PCM 音频流。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class SttWebSocketConfig implements WebSocketConfigurer {

    private final SttConfig config;
    private final SttResultDispatcher resultDispatcher;
    private final ObjectMapper objectMapper;
    private final SttServerManager serverManager;
    private final SttGameIntentGate gameIntentGate;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new SttStreamHandler(config, resultDispatcher, objectMapper, gameIntentGate, serverManager), "/stt/stream")
                .setAllowedOrigins("*");
    }
}
