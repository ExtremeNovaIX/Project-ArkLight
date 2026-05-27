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
import java.util.Base64;
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
        server = startServer("/tts", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().add("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, audioBytes.length);
            exchange.getResponseBody().write(audioBytes);
        });

        TtsConfig config = voxConfig(server);
        config.getVoxCpm2().setStreamingEnabled(false);
        VoxCpm2TtsProvider provider = new VoxCpm2TtsProvider(config, objectMapper);
        List<TtsAudioFrame> frames = new ArrayList<>();

        provider.synthesize(new TtsSynthesisRequest("testa", "rp", 1, "hello."), frames::add);

        assertEquals(List.of("text", "reference_wav_path", "cfg_value", "inference_timesteps",
                "normalize", "denoise", "media_type", "streaming", "badcase_retry_attempts", "extra"),
                fieldNames(requestBody.get()));
        assertEquals("hello.", requestBody.get().get("text").asText());
        assertEquals("voices/rossi.wav", requestBody.get().get("reference_wav_path").asText());
        assertFalse(requestBody.get().has("prompt_text"));
        assertEquals(2.2, requestBody.get().get("cfg_value").asDouble(), 0.001);
        assertEquals(8, requestBody.get().get("inference_timesteps").asInt());
        assertTrue(requestBody.get().get("normalize").asBoolean());
        assertFalse(requestBody.get().get("denoise").asBoolean());
        assertFalse(requestBody.get().get("streaming").asBoolean());
        assertEquals(1, requestBody.get().get("badcase_retry_attempts").asInt());
        assertEquals(4, requestBody.get().get("extra").get("min_len").asInt());
        assertEquals(1, frames.size());
        assertEquals("audio/wav", frames.getFirst().mediaType());
        assertEquals("fake-wav", new String(frames.getFirst().audioBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void shouldSendPromptTextForVoxCpm2HifiClone() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        byte[] audioBytes = "fake-wav".getBytes(StandardCharsets.UTF_8);
        server = startServer("/tts", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().add("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, audioBytes.length);
            exchange.getResponseBody().write(audioBytes);
        });

        TtsConfig config = voxConfig(server);
        config.getVoxCpm2().setStreamingEnabled(false);
        config.getVoxCpm2().setPromptText("reference transcript");
        VoxCpm2TtsProvider provider = new VoxCpm2TtsProvider(config, objectMapper);

        provider.synthesize(new TtsSynthesisRequest("testa", "rp", 1, "hello."), frame -> {
        });

        assertEquals("voices/rossi.wav", requestBody.get().get("reference_wav_path").asText());
        assertEquals("reference transcript", requestBody.get().get("prompt_text").asText());
    }

    @Test
    void shouldConsumeVoxCpm2StreamingFrames() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        byte[] firstAudio = "first-pcm".getBytes(StandardCharsets.UTF_8);
        byte[] secondAudio = "second-pcm".getBytes(StandardCharsets.UTF_8);
        server = startServer("/tts-stream", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(streamingLine(0, firstAudio).getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().write(streamingLine(1, secondAudio).getBytes(StandardCharsets.UTF_8));
        });

        TtsConfig config = voxConfig(server);
        VoxCpm2TtsProvider provider = new VoxCpm2TtsProvider(config, objectMapper);
        List<TtsAudioFrame> frames = new ArrayList<>();

        provider.synthesize(new TtsSynthesisRequest("testa", "rp", 1, "hello."), frames::add);

        assertTrue(requestBody.get().get("streaming").asBoolean());
        assertEquals(2, frames.size());
        assertEquals("audio/pcm;format=s16le;channels=1", frames.get(0).mediaType());
        assertEquals(48000, frames.get(0).sampleRate());
        assertEquals("first-pcm", new String(frames.get(0).audioBytes(), StandardCharsets.UTF_8));
        assertEquals("second-pcm", new String(frames.get(1).audioBytes(), StandardCharsets.UTF_8));
    }

    private HttpServer startServer(String path, ExchangeHandler handler) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext(path, exchange -> {
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
        vox.setReferenceWavPath("voices/rossi.wav");
        vox.setCfgValue(2.2);
        vox.setInferenceTimesteps(8);
        vox.setExtraBody(Map.of("min_len", 4));
        return config;
    }

    private String streamingLine(int index, byte[] audioBytes) {
        return """
                {"index":%d,"media_type":"audio/pcm;format=s16le;channels=1","sample_rate":48000,"audio_base64":"%s"}
                """.formatted(index, Base64.getEncoder().encodeToString(audioBytes));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
