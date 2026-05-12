package p1.component.gamer.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.adapter.GameAdapter;
import p1.component.agent.gamer.adapter.core.GameAdapterContext;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.adapter.core.QueuedGameOperation;
import p1.component.agent.gamer.bridge.queue.GameOperationBatchParser;
import p1.component.agent.gamer.bridge.queue.GameOperationQueueProcessor;
import p1.component.agent.gamer.bridge.queue.GameQueueDrainService;
import p1.component.agent.gamer.bridge.result.GameQueueResultRecorder;
import p1.component.agent.gamer.bridge.result.GameQueueResultRenderer;
import p1.component.agent.gamer.bridge.state.GameQueueStateStore;
import p1.component.agent.gamer.interrupt.GameInterruptService;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.memory.GamerMemoryCompressorAiService;
import p1.component.agent.gamer.memory.GamerWorkingMemoryService;
import p1.component.agent.reasoning.ReasoningContentRecorder;
import p1.component.agent.interaction.InteractionCoordinator;
import p1.config.mcp.GamerMemoryProperties;
import p1.config.mcp.MCPProperties;
import p1.config.prop.AssistantProperties;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameOperationQueueProcessorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSkipSoftFailureAndContinueRemainingQueue() throws Exception {
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor(testWorkingMemoryService());
        GameStateSnapshot playState = state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        SoftAwareAdapter adapter = new SoftAwareAdapter(playState);
        String memoryId = "soft-failure-test";
        processor.rememberPlanningState(memoryId, playState);

        String result = processor.enqueueAndDrain(
                "test-game",
                memoryId,
                adapter,
                config(),
                tools(),
                """
                        {
                          "status":"CONTINUE",
                          "summary":"测试软错误继续",
                          "operations":[
                            {"tool":"bad_tool","args":{},"note":"失败操作"},
                            {"tool":"good_tool","args":{},"note":"成功操作"}
                          ]
                        }
                        """
        );

        assertTrue(result.contains("跳过 1 条软错误操作"));
        assertTrue(processor.consumeNotice(memoryId).contains("bad_tool"));
        assertTrue(processor.peekLastActionResult(memoryId).contains("state_diff"));
        assertTrue(processor.peekLastActionResult(memoryId).contains("bad_tool"));
    }

    @Test
    void shouldInterruptFailureWhenLatestStateIsNotActionable() throws Exception {
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor(testWorkingMemoryService());
        GameStateSnapshot playState = state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        GameStateSnapshot rewardsState = state("{\"state_type\":\"rewards\"}");
        SoftAwareAdapter adapter = new SoftAwareAdapter(rewardsState);
        String memoryId = "hard-failure-test";
        processor.rememberPlanningState(memoryId, playState);

        String result = processor.enqueueAndDrain(
                "test-game",
                memoryId,
                adapter,
                config(),
                tools(),
                """
                        {
                          "status":"CONTINUE",
                          "summary":"测试硬中断",
                          "operations":[
                            {"tool":"bad_tool","args":{},"note":"失败操作"},
                            {"tool":"good_tool","args":{},"note":"不应执行"}
                          ]
                        }
                        """
        );

        assertTrue(result.contains("操作队列已中断"));
        String notice = processor.consumeNotice(memoryId);
        assertTrue(notice.contains("MCP 工具执行失败"));
        assertFalse(notice.contains("最新状态"));
        assertTrue(processor.peekLastActionResult(memoryId).contains("队列中断"));
    }

    @Test
    void shouldKeepReasoningContentOutOfPromptFeedback() throws Exception {
        ReasoningContentRecorder recorder = new ReasoningContentRecorder();
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor(testWorkingMemoryService(), null, recorder);
        GameStateSnapshot playState = state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        String memoryId = "reasoning-test";
        processor.rememberPlanningState(memoryId, playState);
        recorder.recordLatest(memoryId, "先处理高收益操作，再观察状态变化。");

        processor.enqueueAndDrain(
                "test-game",
                memoryId,
                new SoftAwareAdapter(playState),
                config(),
                tools(),
                """
                        {
                          "status":"CONTINUE",
                          "summary":"测试推理保留",
                          "operations":[
                            {"tool":"good_tool","args":{},"note":"成功操作"}
                          ]
                        }
                        """
        );

        assertFalse(processor.peekLastActionResult(memoryId).contains("reasoning_content"));
        assertFalse(processor.peekLastActionResult(memoryId).contains("先处理高收益操作"));
    }

    @Test
    void shouldGenerateSummaryWhenModelLeavesSummaryBlank() throws Exception {
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor(testWorkingMemoryService());
        GameStateSnapshot playState = state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        String memoryId = "blank-summary-test";
        processor.rememberPlanningState(memoryId, playState);

        String result = processor.enqueueAndDrain(
                "test-game",
                memoryId,
                new SoftAwareAdapter(playState),
                config(),
                tools(),
                """
                        {
                          "status":"CONTINUE",
                          "summary":"",
                          "operations":[
                            {"tool":"good_tool","args":{},"note":"成功操作"}
                          ]
                        }
                        """
        );

        assertTrue(result.contains("执行：成功操作"));
        assertTrue(processor.peekLastActionResult(memoryId).contains("decision=执行：成功操作"));
    }

    @Test
    void shouldAcceptDecisionSummaryAsCompatibilityField() throws Exception {
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor(testWorkingMemoryService());
        GameStateSnapshot playState = state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        String memoryId = "decision-summary-test";
        processor.rememberPlanningState(memoryId, playState);

        processor.enqueueAndDrain(
                "test-game",
                memoryId,
                new SoftAwareAdapter(playState),
                config(),
                tools(),
                """
                        {
                          "status":"CONTINUE",
                          "decision_summary":"兼容旧字段并继续执行",
                          "operations":[
                            {"tool":"good_tool","args":{},"note":"成功操作"}
                          ]
                        }
                        """
        );

        assertTrue(processor.peekLastActionResult(memoryId).contains("decision=兼容旧字段并继续执行"));
    }

    @Test
    void shouldTreatStreamingActionAsHavingRemainingOperations() throws Exception {
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor(testWorkingMemoryService());
        GameStateSnapshot playState = state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        AtomicBoolean hasRemainingSeen = new AtomicBoolean(false);
        GameAdapter adapter = new SoftAwareAdapter(playState) {
            @Override
            public void monitorAfterExecute(QueuedGameOperation operation,
                                            GameStateSnapshot beforeState,
                                            GameStateSnapshot afterState,
                                            String toolResult,
                                            boolean hasRemainingOperations) {
                hasRemainingSeen.set(hasRemainingOperations);
            }
        };
        String memoryId = "streaming-remaining-test";
        processor.rememberPlanningState(memoryId, playState);

        processor.enqueueAndDrain(
                "test-game",
                memoryId,
                adapter,
                config(),
                tools(),
                """
                        {
                          "_expect_more_operations": true,
                          "status":"CONTINUE",
                          "summary":"测试流式后续操作标记",
                          "operations":[
                            {"tool":"good_tool","args":{},"note":"成功操作"}
                          ]
                        }
                        """
        );

        assertTrue(hasRemainingSeen.get());
    }

    @Test
    void shouldInterruptRemainingQueueWhenExternalInterruptArrives() throws Exception {
        GameInterruptService interruptService = new GameInterruptService();
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor(
                testWorkingMemoryService(),
                null,
                null,
                interruptService);
        GameStateSnapshot playState = state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        String memoryId = "test-game-rp-session";
        processor.rememberPlanningState(memoryId, playState);

        String result = processor.enqueueAndDrain(
                "test-game",
                memoryId,
                new SoftAwareAdapter(playState),
                config(),
                toolsWithInterrupt(interruptService),
                """
                        {
                          "status":"CONTINUE",
                          "summary":"测试外部打断",
                          "operations":[
                            {"tool":"first_tool","args":{},"note":"先执行"},
                            {"tool":"second_tool","args":{},"note":"应被打断"}
                          ]
                        }
                        """
        );

        assertTrue(result.contains("操作队列已中断"));
        assertTrue(processor.peekLastActionResult(memoryId).contains("已执行 1 条操作"));
        assertTrue(processor.consumeNotice(memoryId).contains("外部打断"));
        assertTrue(interruptService.peek(memoryId).orElseThrow().instruction().contains("用户要求改计划"));
    }

    @Test
    void shouldInterruptQueueBeforeMcpOperationWhenRpIsSpeaking() throws Exception {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("test-game", "rp-session");
        InteractionCoordinator coordinator = new InteractionCoordinator(registry, new AssistantProperties());
        GameQueueStateStore stateStore = new GameQueueStateStore();
        GameQueueResultRenderer renderer = new GameQueueResultRenderer();
        GameOperationQueueProcessor processor = new GameOperationQueueProcessor(
                stateStore,
                new GameOperationBatchParser(),
                new GameQueueDrainService(null, coordinator),
                renderer,
                new GameQueueResultRecorder(testWorkingMemoryService(), null, null, stateStore, renderer, null, null),
                null);
        GameStateSnapshot playState = state("{\"state_type\":\"monster\",\"battle\":{\"turn\":\"player\",\"is_play_phase\":true}}");
        String memoryId = "test-game-rp-session";
        processor.rememberPlanningState(memoryId, playState);

        String result;
        try (InteractionCoordinator.InteractionLease ignored = coordinator.beginRpSpeech("rp-session")) {
            result = processor.enqueueAndDrain(
                    "test-game",
                    memoryId,
                    new SoftAwareAdapter(playState),
                    config(),
                    tools(),
                    """
                            {
                              "status":"CONTINUE",
                              "summary":"测试 RP 发言让路",
                              "operations":[
                                {"tool":"good_tool","args":{},"note":"本条不应发往 MCP"}
                              ]
                            }
                            """);
        }

        assertTrue(result.contains("操作队列已中断"));
        assertTrue(processor.consumeNotice(memoryId).contains("RP 正在说话"));
        assertTrue(processor.peekLastActionResult(memoryId).contains("已执行 0 条操作"));
    }

    private GamerWorkingMemoryService testWorkingMemoryService() {
        GamerMemoryCompressorAiService compressor = new GamerMemoryCompressorAiService() {
            @Override
            public String compressStage(String previousSummary, String trigger, String decisions) {
                return previousSummary + "\n" + trigger + "\n" + decisions;
            }

            @Override
            public String compressRun(String previousRunSummary, String stageSummary) {
                return previousRunSummary + "\n" + stageSummary;
            }
        };
        return new GamerWorkingMemoryService(new GamerMemoryProperties(), compressor);
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

    private static class SoftAwareAdapter implements GameAdapter {
        private final GameStateSnapshot latestState;

        private SoftAwareAdapter(GameStateSnapshot latestState) {
            this.latestState = latestState;
        }

        @Override
        public String id() {
            return "soft-aware";
        }

        @Override
        public GameStateSnapshot fetchState(GameAdapterContext context) {
            return latestState;
        }

        @Override
        public boolean shouldContinueAfterOperationFailure(QueuedGameOperation operation,
                                                           GameStateSnapshot beforeState,
                                                           GameStateSnapshot afterState,
                                                           String reason) {
            return "monster".equals(afterState.stateType())
                    && "player".equals(afterState.json().path("battle").path("turn").asText(""))
                    && afterState.json().path("battle").path("is_play_phase").asBoolean(false);
        }
    }
}
