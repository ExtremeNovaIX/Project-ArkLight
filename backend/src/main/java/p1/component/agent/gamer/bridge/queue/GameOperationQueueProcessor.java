package p1.component.agent.gamer.bridge.queue;

import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.adapter.GameAdapter;
import p1.component.agent.gamer.adapter.core.GameAdapterContext;
import p1.component.agent.gamer.adapter.core.GameBridgeException;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.adapter.core.QueuedGameOperation;
import p1.component.agent.gamer.bridge.GameBridgeExecutionException;
import p1.component.agent.gamer.bridge.result.GameQueueResultRecorder;
import p1.component.agent.gamer.bridge.result.GameQueueResultRenderer;
import p1.component.agent.gamer.interrupt.GameInterruptService;
import p1.component.agent.gamer.trace.GameQueueExecutionTrace;
import p1.component.agent.gamer.trace.GameQueueExecutionTraceBuilder;
import p1.component.agent.gamer.trace.GamerDecisionTraceService;
import p1.component.agent.reasoning.ReasoningContentRecorder;
import p1.config.mcp.MCPProperties;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

/**
 * 执行由 RP 的 do 文本翻译出的游戏操作队列。
 */
@Component
@Slf4j
public class GameOperationQueueProcessor {

    private final GameOperationBatchParser batchParser;
    private final GameQueueDrainService drainService;
    private final GameQueueResultRenderer resultRenderer;
    private final GameQueueResultRecorder resultRecorder;
    private final GamerDecisionTraceService traceService;

    public GameOperationQueueProcessor() {
        this(null, null, null);
    }

    public GameOperationQueueProcessor(GamerDecisionTraceService traceService,
                                       ReasoningContentRecorder reasoningContentRecorder) {
        this(traceService, reasoningContentRecorder, null);
    }

    public GameOperationQueueProcessor(GamerDecisionTraceService traceService,
                                       ReasoningContentRecorder reasoningContentRecorder,
                                       GameInterruptService interruptService) {
        this.batchParser = new GameOperationBatchParser();
        this.drainService = new GameQueueDrainService(interruptService, null);
        this.resultRenderer = new GameQueueResultRenderer();
        this.resultRecorder = new GameQueueResultRecorder(traceService, reasoningContentRecorder);
        this.traceService = traceService;
    }

    @Autowired
    public GameOperationQueueProcessor(GameOperationBatchParser batchParser,
                                       GameQueueDrainService drainService,
                                       GameQueueResultRenderer resultRenderer,
                                       GameQueueResultRecorder resultRecorder,
                                       GamerDecisionTraceService traceService) {
        this.batchParser = batchParser;
        this.drainService = drainService;
        this.resultRenderer = resultRenderer;
        this.resultRecorder = resultRecorder;
        this.traceService = traceService;
    }

    public String enqueueAndDrain(String gameName,
                                  String memoryId,
                                  GameAdapter adapter,
                                  MCPProperties.GameMCPConfig config,
                                  ToolProviderResult rawTools,
                                  String rawArguments) {
        String key = memoryId == null || memoryId.isBlank() ? gameName : memoryId;
        String traceSessionId = traceSessionId(gameName, key);
        String queueId = UUID.randomUUID().toString();
        GameQueueExecutionTraceBuilder traceBuilder = new GameQueueExecutionTraceBuilder(queueId);
        GameAdapterContext context = new GameAdapterContext(gameName, key, rawTools, config);
        String reasoningContent = reasoningContent(gameName, traceSessionId, key);
        List<GameOperation> operations = List.of();
        ParsedGameOperationBatch batch = null;
        try {
            batch = batchParser.parse(rawArguments);
            operations = batch.operations();
            if (operations.isEmpty()) {
                throw new GameBridgeExecutionException("RP 动作没有可执行操作，队列未提交。");
            }

            log.info("[游戏桥接] 收到操作队列: game={}, memoryId={}, queueId={}, operations={}",
                    gameName, key, queueId, operations);
            GameStateSnapshot queueStartState = adapter.fetchState(context);
            ArrayDeque<QueuedGameOperation> queue = adapter.prepareBatch(context, operations, queueStartState);
            if (queue.isEmpty()) {
                throw new GameBridgeExecutionException("桥接层修复后操作队列为空，未执行动作。");
            }

            GameQueueDrainResult drainResult = drainService.drain(
                    key,
                    adapter,
                    context,
                    queue,
                    queueStartState,
                    batch.ignoreRpSpeaking(),
                    traceBuilder);
            String result = resultRenderer.renderDrainResult(operations.size(), drainResult);
            recordOutcome(gameName, traceSessionId, reasoningContent, batch, operations, result, null, drainResult.trace());
            return result;
        } catch (GameBridgeExecutionException e) {
            recordOutcome(gameName, traceSessionId, reasoningContent, batch, operations, e.feedback(), e.feedback(), traceBuilder.build());
            throw e;
        } catch (GameBridgeException e) {
            log.warn("[游戏桥接] 操作队列中断: game={}, memoryId={}, executed={}, reason={}",
                    gameName, key, e.getExecutedCount(), e.getMessage());
            String cleanReason = resultRenderer.compactInterruptReason(e.getMessage());
            String result = "操作队列中断，已执行 " + e.getExecutedCount() + " 条操作，剩余操作已丢弃。";
            recordOutcome(gameName, traceSessionId, reasoningContent, batch, operations, result, cleanReason, traceBuilder.build());
            GameBridgeExecutionException.Kind kind = e.getKind() == GameBridgeException.Kind.INTERACTION_DEFERRED
                    ? GameBridgeExecutionException.Kind.INTERACTION_DEFERRED
                    : GameBridgeExecutionException.Kind.FAILURE;
            throw new GameBridgeExecutionException(result + " 原因：" + cleanReason, kind, e);
        } catch (Exception e) {
            log.error("[游戏桥接] 操作队列处理失败: game={}, memoryId={}", gameName, key, e);
            String result = "桥接层处理操作队列失败，队列已丢弃。";
            recordOutcome(gameName, traceSessionId, reasoningContent, batch, operations, result, e.getMessage(), traceBuilder.build());
            throw new GameBridgeExecutionException(result + " 原因：" + e.getMessage(), e);
        }
    }

    private void recordOutcome(String gameName,
                               String key,
                               String reasoning,
                               ParsedGameOperationBatch batch,
                               List<GameOperation> operations,
                               String result,
                               String interruptReason,
                               GameQueueExecutionTrace executionTrace) {
        // 消费暂存的 plan 和 act 上下文
        String planText = traceService != null ? traceService.consumePendingPlan(gameName, key) : "";
        GamerDecisionTraceService.ActBlockContext actCtx =
                traceService != null ? traceService.consumePendingActContext(gameName, key) : null;
        String check = actCtx != null ? actCtx.check() : "";
        String progress = actCtx != null ? actCtx.progress() : "";
        String next = actCtx != null ? actCtx.next() : "";
        boolean commit = actCtx != null && actCtx.commit();

        resultRecorder.record(
                gameName,
                key,
                reasoning,
                rpDo(batch),
                parserRaw(batch),
                parserLatencyMs(batch),
                parserEarlyCompleted(batch),
                parserEarlyCancelled(batch),
                operations,
                result,
                interruptReason,
                executionTrace,
                planText,
                check,
                progress,
                next,
                commit);
    }

    private String reasoningContent(String gameName, String traceSessionId, String executionKey) {
        String pending = traceService == null ? "" : traceService.consumePendingReasoning(gameName, traceSessionId);
        if (pending != null && !pending.isBlank()) {
            return pending;
        }
        return resultRecorder.consumeReasoningContent(executionKey);
    }

    private String traceSessionId(String gameName, String executionKey) {
        if (executionKey == null || executionKey.isBlank()) {
            return gameName;
        }
        String prefix = gameName == null || gameName.isBlank() ? "" : gameName + "-";
        if (!prefix.isBlank() && executionKey.startsWith(prefix) && executionKey.length() > prefix.length()) {
            return executionKey.substring(prefix.length());
        }
        return executionKey;
    }
    private String rpDo(ParsedGameOperationBatch batch) {
        return batch == null ? "" : batch.rpDo();
    }

    private String parserRaw(ParsedGameOperationBatch batch) {
        return batch == null ? "" : batch.parserRaw();
    }

    private long parserLatencyMs(ParsedGameOperationBatch batch) {
        return batch == null ? 0 : batch.parserLatencyMs();
    }

    private boolean parserEarlyCompleted(ParsedGameOperationBatch batch) {
        return batch != null && batch.parserEarlyCompleted();
    }

    private boolean parserEarlyCancelled(ParsedGameOperationBatch batch) {
        return batch != null && batch.parserEarlyCancelled();
    }
}
