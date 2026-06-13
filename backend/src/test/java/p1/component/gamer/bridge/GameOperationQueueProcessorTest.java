package p1.component.gamer.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.adapter.GameAdapter;
import p1.component.agent.gamer.adapter.core.GameAdapterContext;
import p1.component.agent.gamer.adapter.core.GameActionWindowSignature;
import p1.component.agent.gamer.adapter.core.GameOperationPrecondition;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.adapter.core.QueuedGameOperation;
import p1.component.agent.gamer.bridge.GameBridgeExecutionException;
import p1.component.agent.gamer.bridge.queue.GameOperationBatchParser;
import p1.component.agent.gamer.bridge.queue.GameOperationQueueProcessor;
import p1.component.agent.gamer.bridge.queue.GameQueueDrainService;
import p1.component.agent.gamer.bridge.result.GameQueueResultRecorder;
import p1.component.agent.gamer.bridge.result.GameQueueResultRenderer;
import p1.component.agent.gamer.interrupt.GameInterruptService;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.interaction.InteractionCoordinator;
import p1.component.agent.reasoning.ReasoningContentRecorder;
import p1.config.mcp.MCPProperties;
import p1.config.prop.AssistantProperties;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameOperationQueueProcessorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldTreatToolBusinessErrorAsHardFailure() throws Exception {
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor();
        GameStateSnapshot playState = state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        AtomicBoolean goodExecuted = new AtomicBoolean(false);
        ToolProviderResult.Builder builder = ToolProviderResult.builder();
        builder.add(tool("bad_tool"), (request, memoryId) -> "{\"status\":\"error\",\"error\":\"bad operation\"}");
        builder.add(tool("good_tool"), (request, memoryId) -> {
            goodExecuted.set(true);
            return "{\"status\":\"ok\",\"message\":\"done\"}";
        });

        GameBridgeExecutionException error = assertThrows(
                GameBridgeExecutionException.class,
                () -> processor.enqueueAndDrain(
                        "test-game",
                        "hard-failure-test",
                        new StaticStateAdapter(playState),
                        config(),
                        builder.build(),
                        """
                                {
                                  "operations":[
                                    {"tool":"bad_tool","args":{}},
                                    {"tool":"good_tool","args":{}}
                                  ]
                                }
                                """
                ));

        assertTrue(error.feedback().contains("操作队列中断"));
        assertTrue(error.feedback().contains("bad operation"));
        assertFalse(error.feedback().contains("最新状态"));
        assertFalse(goodExecuted.get());
    }

    @Test
    void shouldKeepReasoningContentOutOfFailureFeedback() throws Exception {
        ReasoningContentRecorder recorder = new ReasoningContentRecorder();
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor(null, recorder);
        GameStateSnapshot playState = state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        String memoryId = "reasoning-test";
        recorder.recordLatest(memoryId, "先处理高收益操作，再观察状态变化。");

        GameBridgeExecutionException error = assertThrows(
                GameBridgeExecutionException.class,
                () -> processor.enqueueAndDrain(
                        "test-game",
                        memoryId,
                        new StaticStateAdapter(playState),
                        config(),
                        tools(),
                        """
                                {
                                  "operations":[
                                    {"tool":"bad_tool","args":{}}
                                  ]
                                }
                                """
                ));

        assertFalse(error.feedback().contains("reasoning_content"));
        assertFalse(error.feedback().contains("先处理高收益操作"));
    }

    @Test
    void shouldSucceedWithoutSummaryOrReason() throws Exception {
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor();
        GameStateSnapshot playState = state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");

        String result = processor.enqueueAndDrain(
                "test-game",
                "no-reason-test",
                new StaticStateAdapter(playState),
                config(),
                tools(),
                """
                        {
                          "operations":[
                            {"tool":"good_tool","args":{}}
                          ]
                        }
                        """
        );

        assertTrue(result.contains("已成功执行 1/1 条操作"));
    }

    @Test
    void shouldFetchFreshStateWhenQueueStarts() throws Exception {
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor();
        GameStateSnapshot freshState = state("{\"state_type\":\"fresh\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        AtomicBoolean preparedWithFreshState = new AtomicBoolean(false);
        GameAdapter adapter = new StaticStateAdapter(freshState) {
            @Override
            public ArrayDeque<QueuedGameOperation> prepareBatch(
                    GameAdapterContext context,
                    java.util.List<p1.component.agent.gamer.adapter.core.GameOperation> operations,
                    GameStateSnapshot plannedState) {
                preparedWithFreshState.set("fresh".equals(plannedState.stateType()));
                return super.prepareBatch(context, operations, plannedState);
            }
        };

        processor.enqueueAndDrain(
                "test-game",
                "fresh-state-test",
                adapter,
                config(),
                tools(),
                """
                        {
                          "operations":[
                            {"tool":"good_tool","args":{}}
                          ]
                        }
                        """
        );

        assertTrue(preparedWithFreshState.get());
    }

    @Test
    void shouldAlwaysMonitorAfterSingleOperation() throws Exception {
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor();
        GameStateSnapshot playState = state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        AtomicBoolean monitorCalled = new AtomicBoolean(false);
        GameAdapter adapter = new StaticStateAdapter(playState) {
            @Override
            public void monitorAfterExecute(GameAdapterContext context,
                                            QueuedGameOperation operation,
                                            GameStateSnapshot beforeState,
                                            GameStateSnapshot afterState,
                                            String toolResult) {
                monitorCalled.set(true);
            }
        };

        processor.enqueueAndDrain(
                "test-game",
                "monitor-single-operation-test",
                adapter,
                config(),
                tools(),
                """
                        {
                          "operations":[
                            {"tool":"good_tool","args":{}}
                          ]
                        }
                """
        );

        assertTrue(monitorCalled.get());
    }

    @Test
    void shouldStopQueueAsSuccessWhenStateAdvancesAfterOperation() throws Exception {
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor();
        GameStateSnapshot beforeState = state("{\"state_type\":\"card_reward\"}");
        GameStateSnapshot afterState = state("{\"state_type\":\"rewards\"}");
        AtomicBoolean secondExecuted = new AtomicBoolean(false);
        GameAdapter adapter = new SequentialStateAdapter(beforeState, afterState) {
            @Override
            public void monitorAfterExecute(GameAdapterContext context,
                                            QueuedGameOperation operation,
                                            GameStateSnapshot beforeState,
                                            GameStateSnapshot afterState,
                                            String toolResult) {
                throw new p1.component.agent.gamer.adapter.core.GameBridgeException(
                        "STS2 state_type 从 card_reward 变为 rewards",
                        p1.component.agent.gamer.adapter.core.GameBridgeException.Kind.STATE_ADVANCED);
            }
        };
        ToolProviderResult.Builder toolBuilder = ToolProviderResult.builder();
        toolBuilder.add(tool("first_tool"), (request, memoryId) -> "{\"status\":\"ok\"}");
        toolBuilder.add(tool("second_tool"), (request, memoryId) -> {
            secondExecuted.set(true);
            return "{\"status\":\"ok\"}";
        });

        String result = processor.enqueueAndDrain(
                "test-game",
                "state-advanced-test",
                adapter,
                config(),
                toolBuilder.build(),
                """
                        {
                          "operations":[
                            {"tool":"first_tool","args":{}},
                            {"tool":"second_tool","args":{}}
                          ]
                        }
                        """);

        assertTrue(result.contains("已成功执行 1/2 条操作"));
        assertTrue(result.contains("状态已推进"));
        assertFalse(secondExecuted.get());
    }

    @Test
    void shouldInterruptBeforeMcpWhenActionWindowChangedBeforeDrain() throws Exception {
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor();
        GameStateSnapshot plannedState = state("{\"state_type\":\"monster\",\"window\":\"round-1\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        GameStateSnapshot currentState = state("{\"state_type\":\"monster\",\"window\":\"round-2\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolProviderResult.Builder builder = ToolProviderResult.builder();
        builder.add(tool("good_tool"), (request, memoryId) -> {
            executed.set(true);
            return "{\"status\":\"ok\"}";
        });

        GameBridgeExecutionException error = assertThrows(
                GameBridgeExecutionException.class,
                () -> processor.enqueueAndDrain(
                        "test-game",
                        "window-before-drain-test",
                        new WindowStateAdapter(List.of(plannedState, currentState)),
                        config(),
                        builder.build(),
                        """
                                {
                                  "operations":[
                                    {"tool":"good_tool","args":{}}
                                  ]
                                }
                                """
                ));

        assertTrue(error.feedback().contains("旧状态动作已过期"));
        assertFalse(executed.get());
    }

    @Test
    void shouldInterruptRemainingQueueWhenActionWindowChangesMidQueue() throws Exception {
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor();
        GameStateSnapshot plannedState = state("{\"state_type\":\"monster\",\"window\":\"round-1\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        GameStateSnapshot changedState = state("{\"state_type\":\"monster\",\"window\":\"round-2\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        AtomicBoolean firstExecuted = new AtomicBoolean(false);
        AtomicBoolean secondExecuted = new AtomicBoolean(false);
        ToolProviderResult.Builder builder = ToolProviderResult.builder();
        builder.add(tool("first_tool"), (request, memoryId) -> {
            firstExecuted.set(true);
            return "{\"status\":\"ok\"}";
        });
        builder.add(tool("second_tool"), (request, memoryId) -> {
            secondExecuted.set(true);
            return "{\"status\":\"ok\"}";
        });

        GameBridgeExecutionException error = assertThrows(
                GameBridgeExecutionException.class,
                () -> processor.enqueueAndDrain(
                        "test-game",
                        "window-mid-queue-test",
                        new WindowStateAdapter(List.of(plannedState, plannedState, changedState)),
                        config(),
                        builder.build(),
                        """
                                {
                                  "operations":[
                                    {"tool":"first_tool","args":{}},
                                    {"tool":"second_tool","args":{}}
                                  ]
                                }
                                """
                ));

        assertTrue(error.feedback().contains("已执行 1 条操作"));
        assertTrue(error.feedback().contains("旧状态动作已过期"));
        assertTrue(firstExecuted.get());
        assertFalse(secondExecuted.get());
    }

    @Test
    void shouldInterruptBeforeMcpWhenOperationPreconditionFails() throws Exception {
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor();
        GameStateSnapshot playState = state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        AtomicBoolean executed = new AtomicBoolean(false);
        GameAdapter adapter = new StaticStateAdapter(playState) {
            @Override
            public GameOperationPrecondition checkOperationPrecondition(QueuedGameOperation operation,
                                                                        GameStateSnapshot currentState) {
                return GameOperationPrecondition.failed("test_precondition", "测试对象已不存在");
            }
        };
        ToolProviderResult.Builder builder = ToolProviderResult.builder();
        builder.add(tool("good_tool"), (request, memoryId) -> {
            executed.set(true);
            return "{\"status\":\"ok\"}";
        });

        GameBridgeExecutionException error = assertThrows(
                GameBridgeExecutionException.class,
                () -> processor.enqueueAndDrain(
                        "test-game",
                        "precondition-test",
                        adapter,
                        config(),
                        builder.build(),
                        """
                                {
                                  "operations":[
                                    {"tool":"good_tool","args":{}}
                                  ]
                                }
                                """
                ));

        assertTrue(error.feedback().contains("操作前置条件不满足"));
        assertFalse(executed.get());
    }

    @Test
    void shouldInterruptRemainingQueueWhenExternalInterruptArrives() throws Exception {
        GameInterruptService interruptService = new GameInterruptService();
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor(
                null,
                null,
                interruptService);
        GameStateSnapshot playState = state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        String memoryId = "test-game-rp-session";

        GameBridgeExecutionException error = assertThrows(
                GameBridgeExecutionException.class,
                () -> processor.enqueueAndDrain(
                        "test-game",
                        memoryId,
                        new StaticStateAdapter(playState),
                        config(),
                        toolsWithInterrupt(interruptService),
                        """
                                {
                                  "operations":[
                                    {"tool":"first_tool","args":{}},
                                    {"tool":"second_tool","args":{}}
                                  ]
                                }
                                """
                ));

        assertTrue(error.feedback().contains("已执行 1 条操作"));
        assertTrue(error.feedback().contains("外部打断"));
        assertTrue(interruptService.peek(memoryId).isEmpty());

        String result = processor.enqueueAndDrain(
                "test-game",
                memoryId,
                new StaticStateAdapter(playState),
                config(),
                tools(),
                """
                        {
                          "operations":[
                            {"tool":"good_tool","args":{}}
                          ]
                        }
                        """
        );

        assertTrue(result.contains("已成功执行 1/1 条操作"));
    }

    @Test
    void shouldContinueCurrentQueueWhenRpIsSpeakingOrVoiceInputIsCollecting() throws Exception {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("test-game", "rp-session");
        InteractionCoordinator coordinator = new InteractionCoordinator(registry);
        GameQueueResultRenderer renderer = new GameQueueResultRenderer();
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor(
                new GameOperationBatchParser(),
                new GameQueueDrainService(null, coordinator),
                renderer,
                new GameQueueResultRecorder(null, null),
                null);
        GameStateSnapshot playState = state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        String memoryId = "test-game-rp-session";

        coordinator.beginGameVoiceInput("rp-session", Duration.ofSeconds(12));
        String result = processor.enqueueAndDrain(
                "test-game",
                memoryId,
                new StaticStateAdapter(playState),
                config(),
                tools(),
                """
                        {
                          "operations":[
                            {"tool":"good_tool","args":{}}
                          ]
                        }
                        """);

        assertTrue(result.contains("1/1"));
    }
    @Test
    void shouldRejectEmptyOperationBatch() {
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor();

        GameBridgeExecutionException error = assertThrows(
                GameBridgeExecutionException.class,
                () -> processor.enqueueAndDrain(
                        "test-game",
                        "empty-batch-test",
                        new StaticStateAdapter(null),
                        config(),
                        tools(),
                        "{\"operations\":[]}"
                ));

        assertTrue(error.feedback().contains("没有可执行操作"));
    }

    private MCPProperties.GameMCPConfig config() {
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setStateSettleMaxAttempts(1);
        config.setStateSettleDelayMs(0);
        return config;
    }

    private ToolProviderResult tools() {
        ToolProviderResult.Builder builder = ToolProviderResult.builder();
        builder.add(tool("bad_tool"), (request, memoryId) -> "{\"status\":\"error\",\"error\":\"bad operation\"}");
        builder.add(tool("good_tool"), (request, memoryId) -> "{\"status\":\"ok\",\"message\":\"done\"}");
        return builder.build();
    }

    private ToolProviderResult toolsWithInterrupt(GameInterruptService interruptService) {
        ToolProviderResult.Builder builder = ToolProviderResult.builder();
        builder.add(tool("first_tool"), (request, memoryId) -> {
            interruptService.requestInterrupt("test-game", "rp-session", "test", "用户要求改计划。");
            return "{\"status\":\"ok\",\"message\":\"done\"}";
        });
        builder.add(tool("second_tool"), (request, memoryId) -> "{\"status\":\"ok\",\"message\":\"should not run\"}");
        return builder.build();
    }

    private ToolSpecification tool(String name) {
        return ToolSpecification.builder()
                .name(name)
                .description(name)
                .parameters(JsonObjectSchema.builder().build())
                .build();
    }

    private GameStateSnapshot state(String raw) throws Exception {
        return new GameStateSnapshot(raw, objectMapper.readTree(raw), objectMapper.readTree(raw).path("state_type").asText(""));
    }

    private static class StaticStateAdapter extends GameAdapter {
        private final GameStateSnapshot latestState;

        private StaticStateAdapter(GameStateSnapshot latestState) {
            this.latestState = latestState;
        }

        @Override
        public String id() {
            return "static-state";
        }

        @Override
        public GameStateSnapshot fetchState(GameAdapterContext context) {
            return latestState;
        }
    }

    private static class SequentialStateAdapter extends StaticStateAdapter {
        private final GameStateSnapshot first;
        private final GameStateSnapshot second;
        private final AtomicInteger calls = new AtomicInteger();

        private SequentialStateAdapter(GameStateSnapshot first, GameStateSnapshot second) {
            super(second);
            this.first = first;
            this.second = second;
        }

        @Override
        public GameStateSnapshot fetchState(GameAdapterContext context) {
            return calls.getAndIncrement() == 0 ? first : second;
        }
    }

    private static class WindowStateAdapter extends GameAdapter {
        private final ArrayDeque<GameStateSnapshot> states;
        private GameStateSnapshot latestState;

        private WindowStateAdapter(List<GameStateSnapshot> states) {
            this.states = new ArrayDeque<>(states);
            this.latestState = states.isEmpty() ? null : states.getLast();
        }

        @Override
        public String id() {
            return "window-state";
        }

        @Override
        public boolean shouldRefreshStateBeforeDrain() {
            return true;
        }

        @Override
        public GameStateSnapshot fetchState(GameAdapterContext context) {
            if (!states.isEmpty()) {
                latestState = states.pollFirst();
            }
            return latestState;
        }

        @Override
        public GameActionWindowSignature actionWindowSignature(GameStateSnapshot state) {
            String value = state == null || state.json() == null ? "" : state.json().path("window").asText("");
            return GameActionWindowSignature.of("window", Map.of("value", value));
        }
    }
}