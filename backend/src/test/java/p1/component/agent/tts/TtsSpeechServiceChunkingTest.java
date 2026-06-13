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
        try (Harness harness = harness("gpt-sovits-http", 2, 40, 40)) {
            harness.session.accept("(warm)first sentence.(bright)second sentence.");
            harness.session.finish();

            assertTrue(harness.awaitDone(), "Timed out waiting for TTS requests");
            assertEquals(List.of("first sentence.", "second sentence."), harness.provider.texts());
        }
    }

    @Test
    void shouldUseShortFirstChunkThenDefaultChunks() throws Exception {
        try (Harness harness = harness("gpt-sovits-http", 3, 5, 10)) {
            harness.session.accept("abcde.1234567890.tail.");
            harness.session.finish();

            assertTrue(harness.awaitDone(), "Timed out waiting for TTS requests");
            assertEquals(List.of("abcde.", "1234567890.", "tail."), harness.provider.texts());
        }
    }

    @Test
    void shouldNotHardCutBeforeSentenceEnd() throws Exception {
        try (Harness harness = harness("gpt-sovits-http", 1, 5, 10)) {
            harness.session.accept("abcdefghij without sentence end");
            assertTrue(harness.provider.requests().isEmpty());

            harness.session.finish();

            assertTrue(harness.awaitDone(), "Timed out waiting for TTS requests");
            assertEquals(List.of("abcdefghij without sentence end"), harness.provider.texts());
        }
    }

    @Test
    void shouldWaitForStyleParenSplitAcrossStreamingChunks() throws Exception {
        try (Harness harness = harness("gpt-sovits-http", 1, 40, 40)) {
            harness.session.accept("(light");
            assertTrue(harness.provider.requests().isEmpty());

            harness.session.accept(" tone)hello nova.");
            harness.session.finish();

            assertTrue(harness.awaitDone(), "Timed out waiting for TTS requests");
            assertEquals(List.of("hello nova."), harness.provider.texts());
        }
    }

    @Test
    void shouldStopAfterCurrentChunkWithoutHardCancel() throws Exception {
        TtsConfig config = new TtsConfig();
        config.setEnabled(true);
        config.setProvider("gpt-sovits-http");
        config.setFirstChunkChars(1);
        config.setMaxChunkChars(5);
        config.getRuntime().setAutoStartEnabled(false);

        BlockingTtsProvider provider = new BlockingTtsProvider("gpt-sovits-http");
        TtsProviderRegistry providerRegistry = new TtsProviderRegistry(config, List.of(provider));
        RecordingAudioHub audioHub = new RecordingAudioHub();
        TtsSpeechService service = new TtsSpeechService(
                config,
                providerRegistry,
                new TtsRuntimeManager(config),
                new TtsTextNormalizer(),
                audioHub);
        try {
            TtsSpeechSession session = service.open("session-a", "rp");
            session.accept("first.");
            session.accept("second.");

            assertTrue(provider.awaitFirstStarted(), "Timed out waiting for first TTS request");
            session.stopAfterCurrentChunk("game action failed");
            provider.releaseFirst();

            assertTrue(audioHub.awaitFinal(), "Timed out waiting for final TTS event");
            assertEquals(List.of("first."), provider.texts());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldDiscardUnsynthesizedTailWhenStoppingAfterCurrentChunk() throws Exception {
        try (Harness harness = harness("gpt-sovits-http", 1, 1, 10)) {
            harness.session.accept("first.");
            assertTrue(harness.provider.awaitRequests(), "Timed out waiting for first TTS request");

            harness.session.accept("x");
            harness.session.stopAfterCurrentChunk("game action failed");

            assertTrue(harness.audioHub.awaitFinal(), "Timed out waiting for final TTS event");
            assertEquals(List.of("first."), harness.provider.texts());
        }
    }

    private Harness harness(String providerName, int expectedRequests, int firstChunkChars, int maxChunkChars) {
        TtsConfig config = new TtsConfig();
        config.setEnabled(true);
        config.setProvider(providerName);
        config.setFirstChunkChars(firstChunkChars);
        config.setMaxChunkChars(maxChunkChars);
        config.getRuntime().setAutoStartEnabled(false);

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

    private static final class BlockingTtsProvider implements TtsProvider {
        private final String providerName;
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final List<TtsSynthesisRequest> requests = new CopyOnWriteArrayList<>();

        private BlockingTtsProvider(String providerName) {
            this.providerName = providerName;
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
            if (requests.size() == 1) {
                firstStarted.countDown();
                try {
                    releaseFirst.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            audioConsumer.accept(new TtsAudioFrame("audio/wav", 0, new byte[]{1}));
        }

        private boolean awaitFirstStarted() throws InterruptedException {
            return firstStarted.await(2, TimeUnit.SECONDS);
        }

        private void releaseFirst() {
            releaseFirst.countDown();
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
