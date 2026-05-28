package p1.component.agent.tts;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsSpeechServiceChunkingTest {

    @Test
    void shouldStripLegacyLeadingStyleParentheses() throws Exception {
        try (Harness harness = harness("voxcpm2-http", 2, 40, 40)) {
            harness.session.accept("(warm)first sentence.(bright)second sentence.");
            harness.session.finish();

            assertTrue(harness.awaitDone(), "Timed out waiting for TTS requests");
            assertEquals(List.of("first sentence.", "second sentence."), harness.provider.texts());
        }
    }

    @Test
    void shouldUseShortFirstChunkThenDefaultChunks() throws Exception {
        try (Harness harness = harness("voxcpm2-http", 3, 5, 10)) {
            harness.session.accept("abcde.1234567890.tail.");
            harness.session.finish();

            assertTrue(harness.awaitDone(), "Timed out waiting for TTS requests");
            assertEquals(List.of("abcde.", "1234567890.", "tail."), harness.provider.texts());
        }
    }

    @Test
    void shouldNotHardCutBeforeSentenceEnd() throws Exception {
        try (Harness harness = harness("voxcpm2-http", 1, 5, 10)) {
            harness.session.accept("abcdefghij without sentence end");
            assertTrue(harness.provider.requests().isEmpty());

            harness.session.finish();

            assertTrue(harness.awaitDone(), "Timed out waiting for TTS requests");
            assertEquals(List.of("abcdefghij without sentence end"), harness.provider.texts());
        }
    }

    @Test
    void shouldWaitForStyleParenSplitAcrossStreamingChunks() throws Exception {
        try (Harness harness = harness("voxcpm2-http", 1, 40, 40)) {
            harness.session.accept("(light");
            assertTrue(harness.provider.requests().isEmpty());

            harness.session.accept(" tone)hello nova.");
            harness.session.finish();

            assertTrue(harness.awaitDone(), "Timed out waiting for TTS requests");
            assertEquals(List.of("hello nova."), harness.provider.texts());
        }
    }

    private Harness harness(String providerName, int expectedRequests, int firstChunkChars, int maxChunkChars) {
        TtsConfig config = new TtsConfig();
        config.setEnabled(true);
        config.setProvider(providerName);
        config.setFirstChunkChars(firstChunkChars);
        config.setMaxChunkChars(maxChunkChars);
        config.getRuntime().setAutoStartEnabled(false);
        config.getVoxCpm2().getRuntime().setAutoStartEnabled(false);

        RecordingTtsProvider provider = new RecordingTtsProvider(providerName, expectedRequests);
        TtsProviderRegistry providerRegistry = new TtsProviderRegistry(config, List.of(provider));
        RecordingAudioHub audioHub = new RecordingAudioHub();
        TtsSpeechService service = new TtsSpeechService(
                config,
                providerRegistry,
                new TtsRuntimeManager(config),
                new TtsTextNormalizer(),
                audioHub);

        return new Harness(service, provider, service.open("session-a", "rp"), audioHub);
    }

    private record Harness(
            TtsSpeechService service,
            RecordingTtsProvider provider,
            TtsSpeechSession session,
            RecordingAudioHub audioHub
    ) implements AutoCloseable {
        private boolean awaitDone() throws InterruptedException {
            return provider.awaitRequests() && audioHub.awaitFinal();
        }

        @Override
        public void close() {
            service.shutdown();
        }
    }

    private static final class RecordingTtsProvider implements TtsProvider {
        private final String providerName;
        private final CountDownLatch latch;
        private final List<TtsSynthesisRequest> requests = new CopyOnWriteArrayList<>();

        private RecordingTtsProvider(String providerName, int expectedRequests) {
            this.providerName = providerName;
            this.latch = new CountDownLatch(expectedRequests);
        }

        @Override
        public String providerName() {
            return providerName;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void synthesize(TtsSynthesisRequest request, Consumer<TtsAudioFrame> audioConsumer) {
            requests.add(request);
            audioConsumer.accept(new TtsAudioFrame("audio/wav", 0, new byte[]{1}));
            latch.countDown();
        }

        private boolean awaitRequests() throws InterruptedException {
            return latch.await(2, TimeUnit.SECONDS);
        }

        private List<TtsSynthesisRequest> requests() {
            return requests;
        }

        private List<String> texts() {
            return requests.stream()
                    .map(TtsSynthesisRequest::text)
                    .toList();
        }
    }

    private static final class RecordingAudioHub extends TtsAudioHub {
        private final CountDownLatch finalLatch = new CountDownLatch(1);

        @Override
        public boolean hasSubscribers(String sessionId) {
            return true;
        }

        @Override
        public void publishAudio(String sessionId, String source, long sequence, String text, TtsAudioFrame frame) {
        }

        @Override
        public void publishFinal(String sessionId, String source, long sequence) {
            finalLatch.countDown();
        }

        private boolean awaitFinal() throws InterruptedException {
            return finalLatch.await(2, TimeUnit.SECONDS);
        }
    }
}
