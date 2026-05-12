package p1.component.agent.gamer.bridge.queue;

import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.adapter.GameAdapter;
import p1.component.agent.gamer.adapter.core.*;
import p1.component.agent.gamer.bridge.GameBridgeActionStatus;
import p1.component.agent.gamer.bridge.GameExpressionPayload;
import p1.component.agent.gamer.bridge.result.GameQueueResultRecorder;
import p1.component.agent.gamer.bridge.result.GameQueueResultRenderer;
import p1.component.agent.gamer.bridge.state.GameQueueStateStore;
import p1.component.agent.gamer.interrupt.GameInterruptService;
import p1.component.agent.gamer.memory.GamerWorkingMemoryService;
import p1.component.agent.gamer.trace.GamerDecisionTraceService;
import p1.component.agent.reasoning.ReasoningContentRecorder;
import p1.component.agent.rp.expression.GameExpressionService;
import p1.config.mcp.MCPProperties;

import java.util.ArrayDeque;
import java.util.List;

/**
 * 游戏操作队列处理器。
 * <p>
 * 该组件是桥接层门面：接收模型提交的虚拟批量工具参数，协调解析、执行、状态存储、
 * 结果记录和返回文本。具体职责已经下沉到专用组件，避免队列处理器继续膨胀。
 */
@Component
@Slf4j
public class GameOperationQueueProcessor {

    private final GameQueueStateStore stateStore;
    private final GameOperationBatchParser batchParser;
    private final GameQueueDrainService drainService;
    private final GameQueueResultRenderer resultRenderer;
    private final GameQueueResultRecorder resultRecorder;
    private final GameExpressionService expressionService;

    /**
     * 创建游戏操作队列处理器。
     * <p>
     * 该构造器供单元测试直接创建使用；生产环境使用 Spring 注入构造器。
     *
     * @param workingMemoryService gamer 纯内存工作记忆服务
     */
    public GameOperationQueueProcessor(GamerWorkingMemoryService workingMemoryService) {
        this(workingMemoryService, null, null, null);
    }

    /**
     * 创建游戏操作队列处理器。
     * <p>
     * 该构造器供单元测试验证 reasoning 记录逻辑。
     *
     * @param workingMemoryService     gamer 纯内存工作记忆服务
     * @param traceService             决策复盘日志服务
     * @param reasoningContentRecorder reasoning_content 记录器
     */
    public GameOperationQueueProcessor(GamerWorkingMemoryService workingMemoryService,
                                       GamerDecisionTraceService traceService,
                                       ReasoningContentRecorder reasoningContentRecorder) {
        this(workingMemoryService, traceService, reasoningContentRecorder, null);
    }

    /**
     * 创建游戏操作队列处理器。
     * <p>
     * 该构造器供单元测试注入外部打断服务。
     *
     * @param workingMemoryService     gamer 纯内存工作记忆服务
     * @param traceService             决策复盘日志服务
     * @param reasoningContentRecorder reasoning_content 记录器
     * @param interruptService         外部打断服务
     */
    public GameOperationQueueProcessor(GamerWorkingMemoryService workingMemoryService,
                                       GamerDecisionTraceService traceService,
                                       ReasoningContentRecorder reasoningContentRecorder,
                                       GameInterruptService interruptService) {
        GameQueueStateStore localStateStore = new GameQueueStateStore();
        GameQueueResultRenderer localRenderer = new GameQueueResultRenderer();
        this.stateStore = localStateStore;
        this.batchParser = new GameOperationBatchParser();
        this.drainService = new GameQueueDrainService(interruptService, null);
        this.resultRenderer = localRenderer;
        this.resultRecorder = new GameQueueResultRecorder(
                workingMemoryService,
                traceService,
                reasoningContentRecorder,
                localStateStore,
                localRenderer,
                null,
                null);
        this.expressionService = null;
    }

    /**
     * 创建生产环境使用的游戏操作队列处理器。
     *
     * @param stateStore     队列运行时状态存储
     * @param batchParser    虚拟批量工具参数解析器
     * @param drainService   队列执行器
     * @param resultRenderer 队列结果渲染器
     * @param resultRecorder 队列结果记录器
     * @param expressionService 游戏表达欲服务
     */
    @Autowired
    public GameOperationQueueProcessor(GameQueueStateStore stateStore,
                                       GameOperationBatchParser batchParser,
                                       GameQueueDrainService drainService,
                                       GameQueueResultRenderer resultRenderer,
                                       GameQueueResultRecorder resultRecorder,
                                       GameExpressionService expressionService) {
        this.stateStore = stateStore;
        this.batchParser = batchParser;
        this.drainService = drainService;
        this.resultRenderer = resultRenderer;
        this.resultRecorder = resultRecorder;
        this.expressionService = expressionService;
    }

    /**
     * 记录本轮 agent 决策时看到的状态。
     *
     * @param memoryId LangChain4j 的会话记忆 id
     * @param state    注入给 agent 的最新游戏状态
     */
    public void rememberPlanningState(String memoryId, GameStateSnapshot state) {
        stateStore.rememberPlanningState(memoryId, state);
    }

    /**
     * 取出上一轮桥接层中断或修复失败的提示。
     *
     * @param memoryId LangChain4j 的会话记忆 id
     * @return 需要注入给 agent 的桥接层提示；没有提示时返回 null
     */
    public String consumeNotice(String memoryId) {
        return stateStore.consumeNotice(memoryId);
    }

    /**
     * 清空本轮动作状态，避免 agent 未调用队列工具时读到上一轮结果。
     *
     * @param memoryId LangChain4j 的会话记忆 id
     */
    public void clearLastStatus(String memoryId) {
        stateStore.clearLastStatus(memoryId);
    }

    /**
     * 查询最近一次队列工具提交的结构化状态。
     *
     * @param memoryId LangChain4j 的会话记忆 id
     * @return 最近一次队列状态；如果本轮没有调用队列工具则返回 UNKNOWN
     */
    public GameBridgeActionStatus lastStatus(String memoryId) {
        return stateStore.lastStatus(memoryId);
    }

    /**
     * 查询上一批操作造成的结果和状态差异。
     *
     * @param memoryId LangChain4j 的会话记忆 id
     * @return 面向 agent 的上一批操作结果；没有结果时返回 null
     */
    public String peekLastActionResult(String memoryId) {
        return stateStore.peekLastActionResult(memoryId);
    }

    /**
     * 接收模型提交的操作队列并执行。
     *
     * @param gameName     游戏名
     * @param memoryId     LangChain4j 的会话记忆 id
     * @param adapter      当前游戏使用的适配器
     * @param config       当前游戏的 MCP 配置
     * @param rawTools     底层 MCP 工具集合
     * @param rawArguments enqueue_operations 的 JSON 参数
     * @return 返回给 LangChain4j 的工具执行结果文本
     */
    public String enqueueAndDrain(String gameName,
                                  String memoryId,
                                  GameAdapter adapter,
                                  MCPProperties.GameMCPConfig config,
                                  ToolProviderResult rawTools,
                                  String rawArguments) {
        String key = memoryId == null || memoryId.isBlank() ? gameName : memoryId;
        GameAdapterContext context = new GameAdapterContext(gameName, key, rawTools, config);
        GameBridgeActionStatus requestedStatus = GameBridgeActionStatus.CONTINUE;
        String summary = "";
        String reasoningContent = resultRecorder.consumeReasoningContent(key);
        List<GameOperation> operations = List.of();
        GameExpressionPayload expression = GameExpressionPayload.empty();
        try {
            ParsedGameOperationBatch batch = batchParser.parse(rawArguments);
            requestedStatus = batch.status();
            summary = batch.summary();
            operations = batch.operations();
            expression = batch.expression();

            if (operations.isEmpty()) {
                String result = "未提交操作。" + summary;
                stateStore.putLastStatus(key, requestedStatus);
                recordOutcome(gameName, key, requestedStatus, summary, reasoningContent,
                        operations, result, "无状态变化。", null);
                recordExpression(gameName, key, requestedStatus, summary, expression, operations, result, null);
                return resultRenderer.formatStatus(requestedStatus, result);
            }
            log.info("[游戏桥接] 收到操作队列: game={}, memoryId={}, operations={}", gameName, key, operations);

            GameStateSnapshot plannedState = loadPlanningState(key, adapter, context);
            ArrayDeque<QueuedGameOperation> queue = adapter.prepareBatch(operations, plannedState);
            if (queue.isEmpty()) {
                String result = "操作队列为空。 " + summary;
                stateStore.putLastStatus(key, requestedStatus);
                recordOutcome(gameName, key, requestedStatus, summary, reasoningContent,
                        operations, result, "无状态变化。", null);
                recordExpression(gameName, key, requestedStatus, summary, expression, operations, result, null);
                return resultRenderer.formatStatus(requestedStatus, result);
            }

            GameQueueDrainResult drainResult = drainService.drain(
                    key, adapter, context, queue, plannedState, batch.expectMoreOperations());
            stateStore.rememberPlanningState(key, drainResult.latestState());
            stateStore.putLastStatus(key, requestedStatus);
            if (!drainResult.softFailures().isEmpty()) {
                stateStore.putNotice(key, resultRenderer.renderSoftFailureNotice(drainResult.softFailures()));
            }
            String result = resultRenderer.renderDrainResult(summary, operations.size(), drainResult);
            String stateDiff = adapter.renderStateDiffForAgent(plannedState, drainResult.latestState());
            recordOutcome(gameName, key, requestedStatus, summary, reasoningContent,
                    operations, result, stateDiff, null);
            recordExpression(gameName, key, requestedStatus, summary, expression, operations, result, null);
            return resultRenderer.formatStatus(requestedStatus, result);
        } catch (GameBridgeException e) {
            log.warn("[游戏操作队列中断] game={}, memoryId={}, executed={}, reason={}",
                    gameName, key, e.getExecutedCount(), e.getMessage());
            String cleanReason = resultRenderer.compactInterruptReason(e.getMessage());
            stateStore.putLastStatus(key, GameBridgeActionStatus.INTERRUPTED);
            stateStore.putNotice(key, cleanReason);
            String result = "队列中断，已执行 " + e.getExecutedCount() + " 条操作，剩余操作已丢弃。";
            recordOutcome(gameName, key, GameBridgeActionStatus.INTERRUPTED, summary, reasoningContent,
                    operations, result, "队列中断，状态 diff 未完成。", cleanReason);
            recordExpression(gameName, key, GameBridgeActionStatus.INTERRUPTED, summary, expression, operations, result, cleanReason);
            return "[CONTINUE] 操作队列已中断，剩余指令已丢弃。" + cleanReason + "\n请基于下一次注入的最新状态重新决策。";
        } catch (Exception e) {
            log.error("[游戏桥接] 操作队列处理失败: game={}, memoryId={}", gameName, key, e);
            stateStore.putLastStatus(key, GameBridgeActionStatus.CONTINUE);
            stateStore.putNotice(key, "桥接层处理失败: " + e.getMessage());
            String result = "桥接层处理失败，队列已丢弃。";
            recordOutcome(gameName, key, GameBridgeActionStatus.CONTINUE, summary, reasoningContent,
                    operations, result, "桥接层处理失败，状态 diff 未完成。", e.getMessage());
            recordExpression(gameName, key, GameBridgeActionStatus.CONTINUE, summary, expression, operations, result, e.getMessage());
            return "[CONTINUE] 桥接层处理操作队列失败，已丢弃队列。原因: " + e.getMessage() + "\n请基于下一次注入的最新状态重新决策。";
        }
    }

    /**
     * 获取 agent 规划时的状态；没有缓存时主动从 MCP 拉取。
     *
     * @param key     gamer 会话 key
     * @param adapter 当前游戏适配器
     * @param context 适配器运行上下文
     * @return 规划基准状态
     */
    private GameStateSnapshot loadPlanningState(String key, GameAdapter adapter, GameAdapterContext context) {
        GameStateSnapshot plannedState = stateStore.planningState(key);
        if (plannedState == null) {
            plannedState = adapter.fetchState(context);
        }
        return plannedState;
    }

    /**
     * 统一记录队列结果。
     *
     * @param gameName        游戏名
     * @param key             gamer 会话 key
     * @param status          队列状态
     * @param summary         agent 决策摘要
     * @param reasoning       模型 reasoning_content
     * @param operations      操作列表
     * @param result          执行结果
     * @param stateDiff       状态差异
     * @param interruptReason 中断原因
     */
    private void recordOutcome(String gameName,
                               String key,
                               GameBridgeActionStatus status,
                               String summary,
                               String reasoning,
                               List<GameOperation> operations,
                               String result,
                               String stateDiff,
                               String interruptReason) {
        resultRecorder.record(gameName, key, status, summary, reasoning, operations, result, stateDiff, interruptReason);
    }

    /**
     * 统一处理本次 ACTION 的表达欲候选。
     * <p>
     * 表达欲是 RP 体验增强功能，任何异常都不能影响游戏操作主流程。
     *
     * @param gameName        游戏名
     * @param key             gamer 会话 key
     * @param status          队列状态
     * @param summary         agent 决策摘要
     * @param expression      agent 生成的表达欲候选
     * @param operations      操作列表
     * @param result          执行结果
     * @param interruptReason 中断原因
     */
    private void recordExpression(String gameName,
                                  String key,
                                  GameBridgeActionStatus status,
                                  String summary,
                                  GameExpressionPayload expression,
                                  List<GameOperation> operations,
                                  String result,
                                  String interruptReason) {
        if (expressionService == null) {
            return;
        }
        try {
            expressionService.handleActionExpression(
                    gameName, key, status, summary, expression, operations, result, interruptReason);
        } catch (Exception e) {
            log.warn("[RP表达欲] 处理游戏表达欲失败: game={}, memoryId={}, reason={}",
                    gameName, key, e.getMessage());
        }
    }
}
