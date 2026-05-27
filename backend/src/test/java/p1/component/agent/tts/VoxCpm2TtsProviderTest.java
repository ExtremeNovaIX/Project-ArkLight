package p1.component.agent.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxCpm2TtsProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldCallVoxCpm2SidecarTtsEndpoint() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        byte[] audioBytes = "fake-wav".getBytes(StandardCharsets.UTF_8);
        server = startServer(exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().add("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, audioBytes.length);
            exchange.getResponseBody().write(audioBytes);
        });

        TtsConfig config = voxConfig(server);
        VoxCpm2TtsProvider provider = new VoxCpm2TtsProvider(config, objectMapper);
        List<TtsAudioFrame> frames = new ArrayList<>();

        provider.synthesize(new TtsSynthesisRequest("testa", "rp", 1, "你好。"), frames::add);

        assertEquals("你好。", requestBody.get().get("text").asText());
        assertEquals("年轻女性，温柔自然。", requestBody.get().get("control_instruction").asText());
        assertEquals("voices/rossi.wav", requestBody.get().get("reference_wav_path").asText());
        assertEquals("参考文本。", requestBody.get().get("prompt_text").asText());
        assertEquals(2.2, requestBody.get().get("cfg_value").asDouble(), 0.001);
        assertEquals(8, requestBody.get().get("inference_timesteps").asInt());
        assertTrue(requestBody.get().get("normalize").asBoolean());
        assertFalse(requestBody.get().get("denoise").asBoolean());
        assertEquals(4, requestBody.get().get("extra").get("min_len").asInt());
        assertEquals(1, frames.size());
        assertEquals("audio/wav", frames.get(0).mediaType());
        assertEquals("fake-wav", new String(frames.get(0).audioBytes(), StandardCharsets.UTF_8));
    }

    private HttpServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/tts", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        httpServer.start();
        return httpServer;
    }

    private TtsConfig voxConfig(HttpServer httpServer) {
        TtsConfig config = new TtsConfig();
        config.setEnabled(true);
        config.setProvider("voxcpm2-http");
        TtsConfig.VoxCpm2Config vox = config.getVoxCpm2();
        vox.setBaseUrl("http://127.0.0.1:" + httpServer.getAddress().getPort());
        vox.setControlInstruction("年轻女性，温柔自然。");
        vox.setReferenceWavPath("voices/rossi.wav");
        vox.setPromptText("参考文本。");
        vox.setCfgValue(2.2);
        vox.setInferenceTimesteps(8);
        vox.setExtraBody(Map.of("min_len", 4));
        return config;
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
