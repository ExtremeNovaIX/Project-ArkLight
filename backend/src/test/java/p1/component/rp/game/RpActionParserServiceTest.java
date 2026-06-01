package p1.component.rp.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import p1.component.agent.gamer.bridge.GameAvailableOperations;
import p1.component.agent.gamer.bridge.GameBridgeService;
import p1.component.agent.gamer.trace.GamerDecisionTraceService;
import p1.component.agent.rp.game.control.RpActionParserAgent;
import p1.component.agent.rp.game.control.RpActionParserService;
import p1.component.agent.rp.game.control.RpGameActionExecutionException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RpActionParserServiceTest {

    @Test
    void shouldSubmitSingleRpDoWithoutExpectedFollowUpFlag() throws Exception {
        RpActionParserAgent parserAgent = mock(RpActionParserAgent.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<GameBridgeService> bridgeProvider = mock(ObjectProvider.class);
        GameBridgeService bridgeService = mock(GameBridgeService.class);
        when(bridgeProvider.getObject()).thenReturn(bridgeService);
        when(bridgeService.describeAvailableOperations("STS2MCP", "game-session"))
                .thenReturn(available("play"));
        when(parserAgent.parse(anyString())).thenReturn(stream("""
                {"operations":[{"tool":"play","args":{}}],"reason":""}
                """));
        when(bridgeService.executeOperationQueue(eq("STS2MCP"), eq("game-session"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("ok");
        RpActionParserService service = new RpActionParserService(parserAgent, bridgeProvider,
                mock(GamerDecisionTraceService.class));

        service.execute("STS2MCP", "game-session", "play defend");

        ArgumentCaptor<String> rawArguments = ArgumentCaptor.forClass(String.class);
        verify(bridgeService).executeOperationQueue(eq("STS2MCP"), eq("game-session"), rawArguments.capture());
        JsonNode node = new ObjectMapper().readTree(rawArguments.getValue());
        assertFalse(node.has("status"));
        assertFalse(node.has("_expect_more_operations"));
        assertTrue(node.path("_ignore_rp_speaking").asBoolean(false));

        ArgumentCaptor<String> parserInput = ArgumentCaptor.forClass(String.class);
        verify(parserAgent).parse(parserInput.capture());
        assertTrue(parserInput.getValue().contains("<current_game_state_json>"));
        assertTrue(parserInput.getValue().contains("\"state_type\":\"monster\""));
        assertTrue(parserInput.getValue().contains("<allowed_operations>"));
        assertTrue(parserInput.getValue().contains("- play:"));
        assertTrue(parserInput.getValue().contains("<rp_do>"));
        assertTrue(parserInput.getValue().contains("play defend"));
        assertTrue(parserInput.getValue().indexOf("<allowed_operations>")
                < parserInput.getValue().indexOf("<current_game_state_json>"));
    }

    @Test
    void shouldExecuteConcurrentParserResultsInSubmissionOrder() throws Exception {
        RpActionParserAgent parserAgent = mock(RpActionParserAgent.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<GameBridgeService> bridgeProvider = mock(ObjectProvider.class);
        GameBridgeService bridgeService = mock(GameBridgeService.class);
        when(bridgeProvider.getObject()).thenReturn(bridgeService);
        when(bridgeService.describeAvailableOperations("STS2MCP", "game-session"))
                .thenReturn(available("play"));

        CountDownLatch firstParserStarted = new CountDownLatch(1);
        CountDownLatch secondParserFinished = new CountDownLatch(1);
        CountDownLatch releaseFirstParser = new CountDownLatch(1);
        AtomicInteger parserCalls = new AtomicInteger();
        when(parserAgent.parse(anyString())).thenAnswer(invocation -> {
            int call = parserCalls.incrementAndGet();
            if (call == 1) {
                return stream(() -> {
                    firstParserStarted.countDown();
                    assertTrue(releaseFirstParser.await(5, TimeUnit.SECONDS));
                }, "{\"operations\":[{\"tool\":\"play\",\"args\":{\"order\":1}}],\"reason\":\"\"}");
            }
            return stream(secondParserFinished::countDown,
                    "{\"operations\":[{\"tool\":\"play\",\"args\":{\"order\":2}}],\"reason\":\"\"}");
        });

        List<Integer> executedOrders = Collections.synchronizedList(new ArrayList<>());
        when(bridgeService.executeOperationQueue(eq("STS2MCP"), eq("game-session"), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    JsonNode node = new ObjectMapper().readTree(invocation.getArgument(2, String.class));
                    executedOrders.add(node.path("operations").path(0).path("args").path("order").asInt());
                    return "ok";
                });

        RpActionParserService service = new RpActionParserService(parserAgent, bridgeProvider,
                mock(GamerDecisionTraceService.class));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> service.execute("STS2MCP", "game-session", "first action"));
            assertTrue(firstParserStarted.await(5, TimeUnit.SECONDS));
            Future<String> second = executor.submit(() -> service.execute("STS2MCP", "game-session", "second action"));
            assertTrue(secondParserFinished.await(5, TimeUnit.SECONDS));
            releaseFirstParser.countDown();

            assertEquals("ok", first.get(5, TimeUnit.SECONDS));
            assertEquals("ok", second.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(List.of(1, 2), executedOrders);
    }

    @Test
    void shouldThrowParserFailureInsteadOfSilentWait() {
        RpActionParserAgent parserAgent = mock(RpActionParserAgent.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<GameBridgeService> bridgeProvider = mock(ObjectProvider.class);
        GameBridgeService bridgeService = mock(GameBridgeService.class);
        when(bridgeProvider.getObject()).thenReturn(bridgeService);
        when(bridgeService.describeAvailableOperations("STS2MCP", "game-session"))
                .thenReturn(available("play"));
        when(parserAgent.parse(anyString())).thenReturn(stream("not json"));
        GamerDecisionTraceService traceService = mock(GamerDecisionTraceService.class);
        RpActionParserService service = new RpActionParserService(parserAgent, bridgeProvider, traceService);

        RpGameActionExecutionException error = assertThrows(
                RpGameActionExecutionException.class,
                () -> service.execute("STS2MCP", "game-session", "play defend"));

        assertTrue(error.feedback().contains("RP 动作解析失败"));
        verify(traceService).appendActionParserFailureTrace(
                eq("STS2MCP"),
                eq("STS2MCP-game-session"),
                eq("play defend"),
                eq("not json"),
                anyString());
        verify(bridgeService, never()).executeOperationQueue(eq("STS2MCP"), eq("game-session"), anyString());
    }

    @Test
    void shouldRejectEmptyOperationsAsActionFailure() {
        RpActionParserAgent parserAgent = mock(RpActionParserAgent.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<GameBridgeService> bridgeProvider = mock(ObjectProvider.class);
        GameBridgeService bridgeService = mock(GameBridgeService.class);
        when(bridgeProvider.getObject()).thenReturn(bridgeService);
        when(bridgeService.describeAvailableOperations("STS2MCP", "game-session"))
                .thenReturn(available("play"));
        when(parserAgent.parse(anyString())).thenReturn(stream("""
                {"operations":[],"reason":"no matching tool"}
                """));
        RpActionParserService service = new RpActionParserService(parserAgent, bridgeProvider,
                mock(GamerDecisionTraceService.class));

        RpGameActionExecutionException error = assertThrows(
                RpGameActionExecutionException.class,
                () -> service.execute("STS2MCP", "game-session", "ambiguous action"));

        assertTrue(error.feedback().contains("no matching tool"));
        verify(bridgeService, never()).executeOperationQueue(eq("STS2MCP"), eq("game-session"), anyString());
    }

    @Test
    void shouldRejectParserInventedToolBeforeBridge() {
        RpActionParserAgent parserAgent = mock(RpActionParserAgent.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<GameBridgeService> bridgeProvider = mock(ObjectProvider.class);
        GameBridgeService bridgeService = mock(GameBridgeService.class);
        when(bridgeProvider.getObject()).thenReturn(bridgeService);
        when(bridgeService.describeAvailableOperations("STS2MCP", "game-session"))
                .thenReturn(available("menu_select"));
        when(parserAgent.parse(anyString())).thenReturn(stream("""
                {"operations":[{"tool":"select_character","args":{"character":"SILENT"}}],"reason":""}
                """));
        RpActionParserService service = new RpActionParserService(parserAgent, bridgeProvider,
                mock(GamerDecisionTraceService.class));

        RpGameActionExecutionException error = assertThrows(
                RpGameActionExecutionException.class,
                () -> service.execute("STS2MCP", "game-session", "select silent"));

        assertTrue(error.feedback().contains("不在白名单内的工具"));
        verify(bridgeService, never()).executeOperationQueue(eq("STS2MCP"), eq("game-session"), anyString());
    }

    @Test
    void shouldPreserveParserOptionIndexArgs() throws Exception {
        RpActionParserAgent parserAgent = mock(RpActionParserAgent.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<GameBridgeService> bridgeProvider = mock(ObjectProvider.class);
        GameBridgeService bridgeService = mock(GameBridgeService.class);
        when(bridgeProvider.getObject()).thenReturn(bridgeService);
        when(bridgeService.describeAvailableOperations("STS2MCP", "game-session"))
                .thenReturn(available("event_choose_option"));
        when(parserAgent.parse(anyString())).thenReturn(stream("""
                {"operations":[{"tool":"event_choose_option","args":{"option_index":0}}],"reason":""}
                """));
        when(bridgeService.executeOperationQueue(eq("STS2MCP"), eq("game-session"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("ok");
        RpActionParserService service = new RpActionParserService(parserAgent, bridgeProvider,
                mock(GamerDecisionTraceService.class));

        service.execute("STS2MCP", "game-session", "choose option 0");

        ArgumentCaptor<String> rawArguments = ArgumentCaptor.forClass(String.class);
        verify(bridgeService).executeOperationQueue(eq("STS2MCP"), eq("game-session"), rawArguments.capture());
        JsonNode args = new ObjectMapper().readTree(rawArguments.getValue())
                .path("operations").path(0).path("args");
        assertEquals(0, args.path("option_index").asInt());
        assertFalse(args.has("option"));
    }

    @Test
    void shouldCancelParserStreamAfterExecutableOperationsArrayCloses() throws Exception {
        RpActionParserAgent parserAgent = mock(RpActionParserAgent.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<GameBridgeService> bridgeProvider = mock(ObjectProvider.class);
        GameBridgeService bridgeService = mock(GameBridgeService.class);
        when(bridgeProvider.getObject()).thenReturn(bridgeService);
        when(bridgeService.describeAvailableOperations("STS2MCP", "game-session"))
                .thenReturn(available("play"));
        TestTokenStream parserStream = stream(
                "{\"operations\":[{\"tool\":\"play\",\"args\":{}}]",
                ",\"reason\":\"\"}");
        when(parserAgent.parse(anyString())).thenReturn(parserStream);
        when(bridgeService.executeOperationQueue(eq("STS2MCP"), eq("game-session"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("ok");
        RpActionParserService service = new RpActionParserService(parserAgent, bridgeProvider,
                mock(GamerDecisionTraceService.class));

        service.execute("STS2MCP", "game-session", "play defend");

        assertTrue(parserStream.cancelled());
        assertEquals(1, parserStream.emittedChunks());
        ArgumentCaptor<String> rawArguments = ArgumentCaptor.forClass(String.class);
        verify(bridgeService).executeOperationQueue(eq("STS2MCP"), eq("game-session"), rawArguments.capture());
        JsonNode node = new ObjectMapper().readTree(rawArguments.getValue());
        assertEquals("play", node.path("operations").path(0).path("tool").asText());
    }

    private GameAvailableOperations available(String... names) {
        StringBuilder detailed = new StringBuilder();
        StringBuilder summary = new StringBuilder();
        for (String name : names) {
            detailed.append("- ").append(name).append(": ").append(name).append(" description\n");
            summary.append("- ").append(name).append("\n");
        }
        return new GameAvailableOperations(
                detailed.toString(),
                summary.toString(),
                Set.of(names),
                "{\"state_type\":\"monster\"}"
        );
    }

    private TestTokenStream stream(String... chunks) {
        return stream(() -> {
        }, chunks);
    }

    private TestTokenStream stream(ThrowingRunnable onStart, String... chunks) {
        return new TestTokenStream(onStart, List.of(chunks));
    }

    private static final class TestTokenStream implements TokenStream {
        private final ThrowingRunnable onStart;
        private final List<String> chunks;
        private final TestStreamingHandle handle = new TestStreamingHandle();
        private final AtomicInteger emittedChunks = new AtomicInteger();
        private Consumer<ChatResponse> onComplete = ignored -> {
        };
        private Consumer<Throwable> onError = ignored -> {
        };
        private Consumer<String> onPartial = ignored -> {
        };
        private BiConsumer<PartialResponse, PartialResponseContext> onPartialWithContext = (ignored, context) -> {
        };

        private TestTokenStream(ThrowingRunnable onStart, List<String> chunks) {
            this.onStart = onStart;
            this.chunks = chunks;
        }

        private boolean cancelled() {
            return handle.isCancelled();
        }

        private int emittedChunks() {
            return emittedChunks.get();
        }

        @Override
        public TokenStream onPartialResponse(Consumer<String> onPartial) {
            this.onPartial = onPartial == null ? ignored -> {
            } : onPartial;
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
                onStart.run();
                for (String chunk : chunks) {
                    if (handle.isCancelled()) {
                        break;
                    }
                    emittedChunks.incrementAndGet();
                    onPartial.accept(chunk);
                    onPartialWithContext.accept(new PartialResponse(chunk), new PartialResponseContext(handle));
                }
                if (!handle.isCancelled()) {
                    onComplete.accept(null);
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
