package p1.component.agent.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.loop.ActiveGameSession;
import p1.component.agent.interaction.GameCoordinationService;
import p1.component.agent.tts.TtsAudioFrame;
import p1.component.agent.tts.TtsConfig;
import p1.component.agent.tts.TtsProvider;
import p1.component.agent.tts.TtsProviderRegistry;
import p1.component.agent.tts.TtsSynthesisRequest;
import p1.config.ExternalConfigBootstrap;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "run.stt.real.audio.tests", matches = "true")
class SttRealAudioIntentGateManualTest {

    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int TARGET_BYTES_PER_SAMPLE = 2;
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    static {
        ExternalConfigBootstrap.prepare();
    }

    @Autowired
    private TtsProviderRegistry ttsProviderRegistry;

    @Autowired
    private TtsConfig ttsConfig;

    @Autowired
    private SttConfig sttConfig;

    @Autowired
    private ActiveGameRegistry activeGameRegistry;

    @Autowired
    private GameCoordinationService gameCoordinationService;

    @LocalServerPort
    private int localServerPort;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldTriggerWaitGateWithRealAudioWhenGameActive() throws Exception {
        waitForManagedSidecars();
        String gameName = "manual-real-audio-game";
        String gameSessionId = "manual-game-session";
        ActiveGameSession session = activeGameRegistry.register(gameName, gameSessionId, manualRpSessionId());
        session.setState(ActiveGameSession.State.PAUSED);

        try {
            byte[] pcm = synthesizePcm16Mono16k("先别动");
            List<SttEvent> events = feedPcmToSttStream(pcm, "gate-wait");
            String finalText = lastFinalText(events);

            assertFalse(finalText.isBlank(), "STT final transcript should not be blank for gate wait");
            assertTrue(gameCoordinationService.isWaiting(manualRpSessionId()), "WAIT voice intent should enter game wait");
            System.out.printf(
                    Locale.ROOT,
                    "stt-real-audio-gate | expected=WAIT | source=先别动 | final=%s | waiting=true | events=%s%n",
                    finalText,
                    events);
        } finally {
            gameCoordinationService.markReady(manualRpSessionId());
            activeGameRegistry.unregister(gameName, gameSessionId);
        }
    }

    @Test
    void shouldReplaySingleUtteranceTtsAudioThroughSttStream() throws Exception {
        waitForManagedSidecars();
        for (ManualCase testCase : singleUtteranceCases()) {
            byte[] pcm = synthesizePcm16Mono16k(testCase.text());
            List<SttEvent> events = feedPcmToSttStream(pcm, testCase.name());
            String finalText = lastFinalText(events);

            assertFalse(finalText.isBlank(), "STT final transcript should not be blank for " + testCase.name());
            System.out.printf(
                    Locale.ROOT,
                    "stt-real-audio | case=%s | expected=%s | source=%s | final=%s | events=%s%n",
                    testCase.name(),
                    testCase.expectedIntent(),
                    testCase.text(),
                    finalText,
                    events);
        }
    }

    @Test
    void shouldReplayMixedTtsAudioThroughSttStream() throws Exception {
        waitForManagedSidecars();
        byte[] wait = synthesizePcm16Mono16k("等一下");
        byte[] chat = synthesizePcm16Mono16k("这把好离谱");
        byte[] tactical = synthesizePcm16Mono16k("我觉得先防更好");
        byte[] mixedWaitWithChat = mixPcm16(wait, chat, 1.0, 0.55);
        byte[] mixedTacticalWithChat = mixPcm16(tactical, chat, 1.0, 0.65);

        for (ManualMixedCase testCase : List.of(
                new ManualMixedCase("mixed-wait-chat", mixedWaitWithChat, "WAIT or conservative CHAT"),
                new ManualMixedCase("mixed-tactical-chat", mixedTacticalWithChat, "APPLY_INSTRUCTION or conservative CHAT"))) {
            List<SttEvent> events = feedPcmToSttStream(testCase.pcm(), testCase.name());
            String finalText = lastFinalText(events);

            assertFalse(finalText.isBlank(), "STT final transcript should not be blank for " + testCase.name());
            System.out.printf(
                    Locale.ROOT,
                    "stt-mixed-audio | case=%s | expected=%s | final=%s | events=%s%n",
                    testCase.name(),
                    testCase.expectedIntent(),
                    finalText,
                    events);
        }
    }

    private List<ManualCase> singleUtteranceCases() {
        return List.of(
                new ManualCase("wait-short", "等一下", "WAIT"),
                new ManualCase("wait-hold", "先别动", "WAIT"),
                new ManualCase("apply-tactic", "我觉得先防更好", "APPLY_INSTRUCTION"),
                new ManualCase("ready", "好了继续", "READY only when waiting=true"),
                new ManualCase("chat", "这把好离谱", "CHAT"));
    }

    private void waitForManagedSidecars() throws Exception {
        Duration timeout = Duration.ofSeconds(Long.getLong("stt.manual.sidecar.timeout.seconds", 240L));
        waitForHttpOk(ttsConfig.getVoxCpm2().getBaseUrl().replaceAll("/+$", "") + "/health", timeout);
        waitForTcp("127.0.0.1", sttConfig.serverPort(), timeout);
    }

    private byte[] synthesizePcm16Mono16k(String text) throws Exception {
        TtsProvider provider = ttsProviderRegistry.activeProvider()
                .filter(TtsProvider::isAvailable)
                .orElseThrow(() -> new IllegalStateException("当前 TTS provider 不可用，请先启用并启动 TTS sidecar"));
        List<byte[]> pcmChunks = new ArrayList<>();
        provider.synthesize(
                new TtsSynthesisRequest(manualRpSessionId(), "stt-real-audio-test", pcmChunks.size(), text),
                frame -> pcmChunks.add(decodeFrameToPcm16Mono16k(frame)));
        return concat(pcmChunks);
    }

    private byte[] decodeFrameToPcm16Mono16k(TtsAudioFrame frame) {
        String mediaType = frame.mediaType() == null ? "" : frame.mediaType().toLowerCase(Locale.ROOT);
        try {
            if (mediaType.contains("wav") || mediaType.isBlank()) {
                return decodeWavToPcm16Mono16k(frame.audioBytes());
            }
            if (mediaType.contains("l16") || mediaType.contains("pcm")) {
                return convertRawPcm16MonoTo16k(frame.audioBytes(), frame.sampleRate());
            }
            throw new IllegalArgumentException("手动真实音频测试仅支持 WAV 或 PCM/L16，当前 mediaType=" + frame.mediaType());
        } catch (Exception e) {
            throw new IllegalStateException("TTS 音频解码失败: mediaType=" + frame.mediaType() + ", reason=" + e.getMessage(), e);
        }
    }

    private byte[] decodeWavToPcm16Mono16k(byte[] wavBytes) throws Exception {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavBytes))) {
            AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    TARGET_SAMPLE_RATE,
                    16,
                    1,
                    TARGET_BYTES_PER_SAMPLE,
                    TARGET_SAMPLE_RATE,
                    false);
            try (AudioInputStream converted = AudioSystem.getAudioInputStream(targetFormat, source)) {
                return converted.readAllBytes();
            }
        }
    }

    private byte[] convertRawPcm16MonoTo16k(byte[] pcmBytes, int sampleRate) {
        int sourceSampleRate = sampleRate <= 0 ? TARGET_SAMPLE_RATE : sampleRate;
        if (sourceSampleRate == TARGET_SAMPLE_RATE) {
            return pcmBytes;
        }

        short[] source = shortsFromPcm16(pcmBytes);
        int targetLength = Math.max(1, (int) Math.round(source.length * (TARGET_SAMPLE_RATE / (double) sourceSampleRate)));
        short[] target = new short[targetLength];
        for (int i = 0; i < target.length; i++) {
            int sourceIndex = Math.min(source.length - 1, (int) Math.floor(i * (sourceSampleRate / (double) TARGET_SAMPLE_RATE)));
            target[i] = source[sourceIndex];
        }
        return pcm16FromShorts(target);
    }

    private List<SttEvent> feedPcmToSttStream(byte[] pcm, String caseName) throws Exception {
        String wsUrl = System.getProperty("stt.manual.ws.url", "ws://127.0.0.1:" + localServerPort + "/stt/stream");
        int chunkMs = Integer.getInteger("stt.manual.chunk.ms", 40);
        int chunkBytes = Math.max(TARGET_BYTES_PER_SAMPLE, chunkMs * TARGET_SAMPLE_RATE * TARGET_BYTES_PER_SAMPLE / 1000);
        ResultCollector collector = new ResultCollector(objectMapper);
        WebSocket webSocket = HTTP_CLIENT.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create(wsUrl), collector)
                .get(10, TimeUnit.SECONDS);
        webSocket.sendText(objectMapper.writeValueAsString(Map.of(
                "rpSessionId", manualRpSessionId(),
                "characterName", System.getProperty("stt.manual.characterName", "Manual"))), true).join();

        for (int offset = 0; offset < pcm.length; offset += chunkBytes) {
            int length = Math.min(chunkBytes, pcm.length - offset);
            webSocket.sendBinary(ByteBuffer.wrap(pcm, offset, length), true).join();
            Thread.sleep(Math.max(1, chunkMs));
        }
        int trailingSilenceMs = Integer.getInteger("stt.manual.trailing.silence.ms", 800);
        byte[] silence = new byte[chunkBytes];
        for (int elapsedMs = 0; elapsedMs < trailingSilenceMs; elapsedMs += chunkMs) {
            webSocket.sendBinary(ByteBuffer.wrap(silence), true).join();
            Thread.sleep(Math.max(1, chunkMs));
        }
        webSocket.sendText("done", true).join();

        try {
            return collector.await(Duration.ofSeconds(Long.getLong("stt.manual.timeout.seconds", 30L)));
        } finally {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, caseName).join();
        }
    }

    private String manualRpSessionId() {
        return System.getProperty("stt.manual.rpSessionId", "manual-stt-game");
    }

    private void waitForHttpOk(String url, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        Exception lastError = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HttpResponse<Void> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return;
                }
                lastError = new IllegalStateException("HTTP " + response.statusCode());
            } catch (Exception e) {
                lastError = e;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("等待 TTS sidecar 健康检查超时: " + url, lastError);
    }

    private void waitForTcp(String host, int port, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        Exception lastError = null;
        while (Instant.now().isBefore(deadline)) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 1000);
                return;
            } catch (Exception e) {
                lastError = e;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("等待 STT sidecar 端口超时: " + host + ":" + port, lastError);
    }

    private String lastFinalText(List<SttEvent> events) {
        String finalText = "";
        for (SttEvent event : events) {
            if (event.finalResult()) {
                finalText = event.text();
            }
        }
        return finalText;
    }

    private byte[] mixPcm16(byte[] primary, byte[] distractor, double primaryGain, double distractorGain) {
        short[] left = shortsFromPcm16(primary);
        short[] right = shortsFromPcm16(distractor);
        int length = Math.max(left.length, right.length);
        short[] mixed = new short[length];
        for (int i = 0; i < length; i++) {
            double sample = 0.0;
            if (i < left.length) {
                sample += left[i] * primaryGain;
            }
            if (i < right.length) {
                sample += right[i] * distractorGain;
            }
            mixed[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample)));
        }
        return pcm16FromShorts(mixed);
    }

    private short[] shortsFromPcm16(byte[] pcmBytes) {
        ByteBuffer buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
        short[] samples = new short[pcmBytes.length / TARGET_BYTES_PER_SAMPLE];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = buffer.getShort();
        }
        return samples;
    }

    private byte[] pcm16FromShorts(short[] samples) {
        ByteBuffer buffer = ByteBuffer.allocate(samples.length * TARGET_BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) {
            buffer.putShort(sample);
        }
        return buffer.array();
    }

    private byte[] concat(List<byte[]> chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    private record ManualCase(String name, String text, String expectedIntent) {
    }

    private record ManualMixedCase(String name, byte[] pcm, String expectedIntent) {
    }

    private record SttEvent(String type, String text, boolean finalResult) {
    }

    private static final class ResultCollector implements WebSocket.Listener {
        private final ObjectMapper objectMapper;
        private final List<SttEvent> events = new CopyOnWriteArrayList<>();
        private final CompletableFuture<List<SttEvent>> finished = new CompletableFuture<>();

        private ResultCollector(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        List<SttEvent> await(Duration timeout) throws Exception {
            return finished.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                JsonNode node = objectMapper.readTree(data.toString());
                String type = node.path("type").asText("");
                if ("error".equals(type)) {
                    finished.completeExceptionally(new IllegalStateException(node.path("message").asText("STT stream error")));
                } else if ("result".equals(type)) {
                    SttEvent event = new SttEvent(type, node.path("text").asText(""), node.path("final").asBoolean(false));
                    events.add(event);
                    if (event.finalResult()) {
                        finished.complete(List.copyOf(events));
                    }
                } else if ("end".equals(type)) {
                    finished.complete(List.copyOf(events));
                }
            } catch (Exception e) {
                finished.completeExceptionally(e);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            finished.completeExceptionally(error);
        }
    }
}
