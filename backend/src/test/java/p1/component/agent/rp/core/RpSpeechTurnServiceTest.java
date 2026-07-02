package p1.component.agent.rp.core;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.trace.GamerDecisionTraceService;
import p1.component.agent.rp.game.control.RpControlBlock;
import p1.component.agent.rp.game.control.RpGameControlBlockExecutor;
import p1.component.agent.rp.game.control.RpGameControlTurnLockService;
import p1.component.agent.rp.game.interrupt.RpGameInterruptionService;
import p1.component.agent.tts.TtsSpeechService;
import p1.component.agent.tts.TtsSpeechSession;
import p1.config.prop.AssistantProperties;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class RpSpeechTurnServiceTest {

    @Test
    void shouldConsumeVoiceAndCommittedActJsonBlocks() {
        RpGameControlBlockExecutor controlBlockExecutor = mock(RpGameControlBlockExecutor.class);
        when(controlBlockExecutor.hasActiveGame("rp-session")).thenReturn(true);
        when(controlBlockExecutor.execute(eq("rp-session"), any(RpControlBlock.class))).thenReturn(Optional.empty());
        TtsSpeechService ttsSpeechService = mock(TtsSpeechService.class);
        TtsSpeechSession ttsSession = mock(TtsSpeechSession.class);
        when(ttsSpeechService.open("rp-session", "game-loop")).thenReturn(ttsSession);
        RpSpeechTurnService service = new RpSpeechTurnService(
                assistantProperties(),
                ttsSpeechService,
                controlBlockExecutor,
                mock(RpGameInterruptionService.class),
                new RpGameControlTurnLockService(),
                mock(GamerDecisionTraceService.class));

        String text = service.collect("rp-session", "game-loop", new ChunkTokenStream("""
                <turn>
                <event 1>
                {"type":"voice","kind":"chat","say":"我先打一张防御。"}
                </event 1>
                <event 2>
                {"type":"act","do":"打出防御","check":"合法","progress":"已防御","next":"","commit":true}
                </event 2>
                </turn>
                """));

        assertEquals("我先打一张防御。", text);
        verify(ttsSession).accept("我先打一张防御。");
        verify(ttsSession).finish();
        ArgumentCaptor<RpControlBlock> blockCaptor = ArgumentCaptor.forClass(RpControlBlock.class);
        verify(controlBlockExecutor, org.mockito.Mockito.times(2)).execute(eq("rp-session"), blockCaptor.capture());
        assertTrue(blockCaptor.getAllValues().get(0).isVoice());
        assertTrue(blockCaptor.getAllValues().get(1).isCommittedAction());
        assertEquals("打出防御", blockCaptor.getAllValues().get(1).doText());
    }

    @Test
    void shouldExecuteCommittedActFromCompleteResponseWhenPartialMissesTail() {
        RpGameControlBlockExecutor controlBlockExecutor = mock(RpGameControlBlockExecutor.class);
        when(controlBlockExecutor.hasActiveGame("rp-session")).thenReturn(true);
        when(controlBlockExecutor.execute(eq("rp-session"), any(RpControlBlock.class))).thenReturn(Optional.empty());
        RpSpeechTurnService service = new RpSpeechTurnService(
                assistantProperties(),
                mock(TtsSpeechService.class),
                controlBlockExecutor,
                mock(RpGameInterruptionService.class),
                new RpGameControlTurnLockService(),
                mock(GamerDecisionTraceService.class));

        String completeResponse = """
                <turn>
                <plan>End the turn.</plan>
                <event 1>
                {"type":"act","do":"end turn","check":"legal","progress":"done","next":"","commit":true}
                </event 1>
                </turn>
                """;

        String text = service.collect(
                "rp-session",
                "game-loop",
                ChunkTokenStream.withCompleteResponse(completeResponse, "<turn><plan>End"));

        assertEquals("", text);
        ArgumentCaptor<RpControlBlock> blockCaptor = ArgumentCaptor.forClass(RpControlBlock.class);
        verify(controlBlockExecutor).execute(eq("rp-session"), blockCaptor.capture());
        assertTrue(blockCaptor.getValue().isCommittedAction());
        assertEquals("end turn", blockCaptor.getValue().doText());
    }
    @Test
    void shouldStopGameStreamAfterAskAndIgnoreFollowingActions() {
        RpGameControlBlockExecutor controlBlockExecutor = mock(RpGameControlBlockExecutor.class);
        when(controlBlockExecutor.hasActiveGame("rp-session")).thenReturn(true);
        when(controlBlockExecutor.execute(eq("rp-session"), any(RpControlBlock.class))).thenReturn(Optional.empty());
        TtsSpeechService ttsSpeechService = mock(TtsSpeechService.class);
        RpSpeechTurnService service = new RpSpeechTurnService(
                assistantProperties(),
                ttsSpeechService,
                controlBlockExecutor,
                mock(RpGameInterruptionService.class),
                new RpGameControlTurnLockService(),
                mock(GamerDecisionTraceService.class));
        ChunkTokenStream stream = new ChunkTokenStream("""
                <event 1>{"type":"voice","kind":"ask","say":"这里要不要先拿药水？"}</event 1>
                <event 2>{"type":"act","do":"结束回合","check":"合法","progress":"","next":"","commit":true}</event 2>
                """);

        String text = service.collect("rp-session", "game-loop", stream);

        assertEquals("这里要不要先拿药水？", text);
        assertTrue(stream.cancelled());
        ArgumentCaptor<RpControlBlock> blockCaptor = ArgumentCaptor.forClass(RpControlBlock.class);
        verify(controlBlockExecutor, org.mockito.Mockito.times(1)).execute(eq("rp-session"), blockCaptor.capture());
        assertTrue(blockCaptor.getValue().isAsk());
        verify(ttsSpeechService, org.mockito.Mockito.never()).open(any(), any());
    }

    @Test
    void shouldSerializeGameModeStreamsUntilCurrentTurnFinishes() throws Exception {
        RpGameControlBlockExecutor controlBlockExecutor = mock(RpGameControlBlockExecutor.class);
        when(controlBlockExecutor.hasActiveGame("rp-session")).thenReturn(true);
        RpSpeechTurnService service = new RpSpeechTurnService(
                assistantProperties(),
                mock(TtsSpeechService.class),
                controlBlockExecutor,
                mock(RpGameInterruptionService.class),
                new RpGameControlTurnLockService(),
                mock(GamerDecisionTraceService.class));

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        BlockingTokenStream firstStream = new BlockingTokenStream(() -> {
            firstStarted.countDown();
            assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
        });
        BlockingTokenStream secondStream = new BlockingTokenStream(secondStarted::countDown);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> service.collect("rp-session", "game-loop", firstStream));
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

            Future<String> second = executor.submit(() -> service.collect("rp-session", "game-loop", secondStream));
            assertFalse(secondStarted.await(200, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            assertEquals("", first.get(5, TimeUnit.SECONDS));
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertEquals("", second.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRecordGameModeStreamFailureAsRuntimeEvent() {
        RpGameControlBlockExecutor controlBlockExecutor = mock(RpGameControlBlockExecutor.class);
        when(controlBlockExecutor.hasActiveGame("rp-session")).thenReturn(true);
        RpGameInterruptionService interruptionService = mock(RpGameInterruptionService.class);
        RpSpeechTurnService service = new RpSpeechTurnService(
                assistantProperties(),
                mock(TtsSpeechService.class),
                controlBlockExecutor,
                interruptionService,
                new RpGameControlTurnLockService(),
                mock(GamerDecisionTraceService.class));

        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> service.collect("rp-session", "game-loop", new BlockingTokenStream(() -> {
                    throw new RuntimeException("stream failed");
                })));

        verify(interruptionService).recordStreamFailure(
                org.mockito.Mockito.eq("rp-session"),
                org.mockito.Mockito.eq(""),
                org.mockito.Mockito.contains("stream failed"),
                org.mockito.Mockito.eq(0));
    }

    @Test
    void shouldRecordSplitGamePlanOnce() {
        RpGameControlBlockExecutor controlBlockExecutor = mock(RpGameControlBlockExecutor.class);
        when(controlBlockExecutor.hasActiveGame("rp-session")).thenReturn(true);
        GamerDecisionTraceService traceService = mock(GamerDecisionTraceService.class);
        RpSpeechTurnService service = new RpSpeechTurnService(
                assistantProperties(),
                mock(TtsSpeechService.class),
                controlBlockExecutor,
                mock(RpGameInterruptionService.class),
                new RpGameControlTurnLockService(),
                traceService);

        String text = service.collect(
                "rp-session",
                "game-loop",
                new ChunkTokenStream("<turn><plan>Alpha", "</plan></turn>"),
                "STS2MCP",
                "game-session");

        assertEquals("", text);
        verify(traceService).recordTurnPlan("STS2MCP", "game-session", "Alpha");
    }
    private AssistantProperties assistantProperties() {
        AssistantProperties assistantProperties = mock(AssistantProperties.class);
        AssistantProperties.ChatModelConfig chatModelConfig = new AssistantProperties.ChatModelConfig();
        chatModelConfig.setTimeoutSeconds(1L);
        when(assistantProperties.activeChatModel()).thenReturn(chatModelConfig);
        return assistantProperties;
    }

    private static final class ChunkTokenStream implements TokenStream {
        private final List<String> chunks;
        private final ChatResponse completeResponse;
        private final TestStreamingHandle handle = new TestStreamingHandle();
        private Consumer<ChatResponse> onComplete = ignored -> {
        };
        private Consumer<Throwable> onError = ignored -> {
        };
        private BiConsumer<PartialResponse, PartialResponseContext> onPartialWithContext = (ignored, context) -> {
        };

        private ChunkTokenStream(String... chunks) {
            this(null, List.of(chunks));
        }

        private ChunkTokenStream(ChatResponse completeResponse, List<String> chunks) {
            this.completeResponse = completeResponse;
            this.chunks = chunks;
        }

        private static ChunkTokenStream withCompleteResponse(String completeResponse, String... chunks) {
            return new ChunkTokenStream(
                    ChatResponse.builder().aiMessage(AiMessage.from(completeResponse)).build(),
                    List.of(chunks));
        }

        private boolean cancelled() {
            return handle.isCancelled();
        }

        @Override
        public TokenStream onPartialResponse(Consumer<String> ignored) {
            return this;
        }

        @Override
        public TokenStream onPartialResponseWithContext(
                BiConsumer<PartialResponse, PartialResponseContext> onPartialWithContext) {
            this.onPartialWithContext = onPartialWithContext == null ? (ignored, context) -> {
            } : onPartialWithContext;
            return this;
        }

        @Override
        public TokenStream onRetrieved(Consumer<List<Content>> ignored) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(Consumer<ToolExecution> ignored) {
            return this;
        }

        @Override
        public TokenStream onCompleteResponse(Consumer<ChatResponse> onComplete) {
            this.onComplete = onComplete == null ? ignored -> {
            } : onComplete;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> onError) {
            this.onError = onError == null ? ignored -> {
            } : onError;
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
            try {
                for (String chunk : chunks) {
                    if (handle.isCancelled()) {
                        break;
                    }
                    onPartialWithContext.accept(new PartialResponse(chunk), new PartialResponseContext(handle));
                }
                if (!handle.isCancelled()) {
                    onComplete.accept(completeResponse);
                }
            } catch (Throwable throwable) {
                onError.accept(throwable);
            }
        }
    }

    private static final class TestStreamingHandle implements StreamingHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    private static final class BlockingTokenStream implements TokenStream {
        private final ThrowingRunnable onStart;
        private Consumer<ChatResponse> onComplete = ignored -> {
        };
        private Consumer<Throwable> onError = ignored -> {
        };

        private BlockingTokenStream(ThrowingRunnable onStart) {
            this.onStart = onStart;
        }

        @Override
        public TokenStream onPartialResponse(Consumer<String> ignored) {
            return this;
        }

        @Override
        public TokenStream onPartialResponseWithContext(
                BiConsumer<PartialResponse, PartialResponseContext> ignored) {
            return this;
        }

        @Override
        public TokenStream onRetrieved(Consumer<List<Content>> ignored) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(Consumer<ToolExecution> ignored) {
            return this;
        }

        @Override
        public TokenStream onCompleteResponse(Consumer<ChatResponse> onComplete) {
            this.onComplete = onComplete;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> onError) {
            this.onError = onError;
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
            try {
                onStart.run();
                onComplete.accept(null);
            } catch (Throwable throwable) {
                onError.accept(throwable);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
