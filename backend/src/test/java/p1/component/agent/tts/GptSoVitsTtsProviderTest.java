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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GptSoVitsTtsProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldCallGptSoVitsTtsEndpoint() throws Exception {
        AtomicReference<String> acceptHeader = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        byte[] audioBytes = "fake-wav".getBytes(StandardCharsets.UTF_8);
        server = startServer(exchange -> {
            acceptHeader.set(exchange.getRequestHeaders().getFirst("Accept"));
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().add("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, audioBytes.length);
            exchange.getResponseBody().write(audioBytes);
        });

        TtsConfig config = gptSoVitsConfig(server);
        GptSoVitsTtsProvider provider = new GptSoVitsTtsProvider(config, objectMapper);
        List<TtsAudioFrame> frames = new ArrayList<>();

        provider.synthesize(new TtsSynthesisRequest("testa", "rp", 1, "你好。"), frames::add);

        assertEquals("audio/wav", acceptHeader.get());
        assertEquals("你好。", requestBody.get().get("text").asText());
        assertEquals("zh", requestBody.get().get("text_lang").asText());
        assertEquals("references/arklight.wav", requestBody.get().get("ref_audio_path").asText());
        assertEquals("这是一段参考文本。", requestBody.get().get("prompt_text").asText());
        assertEquals("zh", requestBody.get().get("prompt_lang").asText());
        assertEquals("cut5", requestBody.get().get("text_split_method").asText());
        assertEquals(4, requestBody.get().get("batch_size").asInt());
        assertEquals(0.08, requestBody.get().get("fragment_interval").asDouble(), 0.001);
        assertEquals("wav", requestBody.get().get("media_type").asText());
        assertEquals(0, requestBody.get().get("streaming_mode").asInt());
        assertTrue(requestBody.get().get("parallel_infer").asBoolean());
        assertEquals(1, frames.size());
        assertEquals("audio/wav", frames.get(0).mediaType());
        assertEquals("fake-wav", new String(frames.get(0).audioBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void shouldBeUnavailableWhenRequiredConfigIsMissing() {
        TtsConfig config = new TtsConfig();
        config.setEnabled(true);
        config.setProvider("gpt-sovits-http");
        config.getGptSoVits().setBaseUrl("http://127.0.0.1:9880");
        config.getGptSoVits().setRefAudioPath("");

        GptSoVitsTtsProvider provider = new GptSoVitsTtsProvider(config, objectMapper);

        assertFalse(provider.isAvailable());
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

    private TtsConfig gptSoVitsConfig(HttpServer httpServer) {
        TtsConfig config = new TtsConfig();
        config.setEnabled(true);
        config.setProvider("gpt-sovits-http");
        TtsConfig.GptSoVitsConfig gpt = config.getGptSoVits();
        gpt.setBaseUrl("http://127.0.0.1:" + httpServer.getAddress().getPort());
        gpt.setRefAudioPath("references/arklight.wav");
        gpt.setPromptText("这是一段参考文本。");
        gpt.setTextLang("zh");
        gpt.setPromptLang("zh");
        gpt.setMediaType("wav");
        gpt.setTextSplitMethod("cut5");
        gpt.setStreamingMode(0);
        return config;
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
