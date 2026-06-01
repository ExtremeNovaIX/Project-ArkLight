package p1.component.agent.gamer.bridge.queue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.adapter.GameAdapter;
import p1.component.agent.gamer.adapter.core.GameAdapterContext;
import p1.component.agent.gamer.adapter.core.GameActionWindowSignature;
import p1.component.agent.gamer.adapter.core.GameBridgeException;
import p1.component.agent.gamer.adapter.core.GameOperationPrecondition;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.adapter.core.QueuedGameOperation;
import p1.component.agent.gamer.interrupt.GameInterruptService;
import p1.component.agent.gamer.trace.GameQueueExecutionTraceBuilder;
import p1.component.agent.interaction.InteractionCoordinator;

import java.util.ArrayDeque;

/**
 * 游戏操作队列执行器。
 * <p>
 * 该组件只负责把已经准备好的队列逐条发送给 MCP，并处理执行前修复、
 * 状态重读和外部中断。单条操作失败会直接中断当前队列。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GameQueueDrainService {

    private final GameInterruptService interruptService;
    private final InteractionCoordinator interactionCoordinator;

    /**
     * 执行队列中的所有操作。
     *
     * @param key              gamer 会话 key
     * @param adapter          当前游戏适配器
     * @param context          适配器运行上下文
     * @param queue            待执行操作队列
     * @param initialState     队列开始时读取到的状态
     * @param ignoreRpSpeaking true 表示当前批次来自 RP 控制块内部，只忽略 RP 正在说话的 lease
     * @param traceBuilder     队列执行复盘收集器
     * @return 队列执行结果
     */
    public GameQueueDrainResult drain(String key,
                                      GameAdapter adapter,
                                      GameAdapterContext context,
                                      ArrayDeque<QueuedGameOperation> queue,
                                      GameStateSnapshot initialState,
                                      boolean ignoreRpSpeaking,
                                      GameQueueExecutionTraceBuilder traceBuilder) {
        int attempted = 0;
        int successful = 0;
        GameActionWindowSignature plannedWindow = safeActionWindow(adapter.actionWindowSignature(initialState));
        GameStateSnapshot currentState = loadDrainStartState(adapter, context, initialState);
        GameActionWindowSignature startWindow = safeActionWindow(adapter.actionWindowSignature(currentState));
        traceBuilder.markStart(currentState, startWindow);

        while (!queue.isEmpty()) {
            interruptIfInteractionBusy(context.gameName(), key, successful, ignoreRpSpeaking);
            interruptIfRequested(key, successful);

            QueuedGameOperation operation = queue.pollFirst();
            GameStateSnapshot beforeState = currentState != null ? currentState : adapter.fetchState(context);
            ToolExecutionRequest request = operation.request();
            GameActionWindowSignature currentWindow = safeActionWindow(adapter.actionWindowSignature(beforeState));
            if (!plannedWindow.sameWindow(currentWindow)) {
                GameOperationPrecondition uncheckedPrecondition = GameOperationPrecondition.passed("not_checked");
                GameQueueExecutionTraceBuilder.OperationScope scope =
                        traceBuilder.beginOperation(
                                attempted + 1,
                                operation,
                                beforeState,
                                plannedWindow,
                                currentWindow,
                                uncheckedPrecondition);
                String reason = staleActionWindowReason(plannedWindow, currentWindow, successful);
                traceBuilder.recordFailure(scope, request, beforeState, reason, "stale_action_window");
                throw new GameBridgeException(reason, successful);
            }

            GameOperationPrecondition precondition = safePrecondition(
                    adapter.checkOperationPrecondition(operation, beforeState));
            GameQueueExecutionTraceBuilder.OperationScope scope =
                    traceBuilder.beginOperation(
                            attempted + 1,
                            operation,
                            beforeState,
                            plannedWindow,
                            currentWindow,
                            precondition);
            if (!precondition.satisfied()) {
                String reason = "操作前置条件不满足: " + precondition.reason();
                traceBuilder.recordFailure(scope, request, beforeState, reason, "precondition_failed");
                throw new GameBridgeException(reason, successful);
            }
            attempted++;

            try {
                request = adapter.repairBeforeExecute(operation, beforeState);
            } catch (GameBridgeException e) {
                throw hardOperationFailure(
                        adapter,
                        context,
                        scope,
                        request,
                        "adapter执行指令修复失败: " + e.getMessage(),
                        successful,
                        traceBuilder,
                        "adapter_repair_failed");
            }

            ToolExecutor executor = context.tools().toolExecutorByName(request.name());
            if (executor == null) {
                traceBuilder.recordFailure(scope, request, beforeState, "MCP 工具不存在: " + request.name(), "mcp_error");
                throw new GameBridgeException("MCP 工具不存在: " + request.name(), successful);
            }

            String toolResult;
            try {
                toolResult = executor.execute(request, key);
            } catch (Exception e) {
                traceBuilder.recordFailure(
                        scope,
                        request,
                        beforeState,
                        "MCP 工具执行失败: " + request.name() + "，原因: " + e.getMessage(),
                        "mcp_error");
                throw new GameBridgeException(
                        "MCP 工具执行失败: " + request.name() + "，原因: " + e.getMessage(),
                        successful);
            }

            QueuedGameOperation executedOperation = operation.withRequest(request);
            String mcpError = adapter.extractToolError(toolResult);
            if (mcpError != null) {
                throw hardOperationFailure(
                        adapter,
                        context,
                        scope,
                        request,
                        "MCP 工具执行失败: " + mcpError,
                        successful,
                        traceBuilder,
                        "mcp_error");
            }
            if (isTextToolError(toolResult)) {
                traceBuilder.recordFailure(scope, request, beforeState, "MCP 工具执行失败: " + toolResult, "mcp_error");
                throw new GameBridgeException("MCP 工具执行失败: " + toolResult, successful);
            }

            GameStateSnapshot afterState;
            try {
                afterState = fetchStateUntilMonitorPasses(
                        key, adapter, context, executedOperation, beforeState, toolResult,
                        successful + 1);
            } catch (GameBridgeException e) {
                GameStateSnapshot latestState = e.getLatestState();
                if (e.getKind() == GameBridgeException.Kind.STATE_ADVANCED) {
                    currentState = latestState == null ? adapter.fetchState(context) : latestState;
                    successful++;
                    String reason = compactBoundaryReason(e.getMessage());
                    traceBuilder.recordSuccess(scope, request, currentState, toolResult, "state_boundary");
                    traceBuilder.markBoundary(reason);
                    queue.clear();
                    return new GameQueueDrainResult(
                            currentState,
                            attempted,
                            successful,
                            reason,
                            traceBuilder.build());
                }
                traceBuilder.recordFailure(scope, request, latestState, e.getMessage(), "state_monitor_failed");
                throw e;
            }

            traceBuilder.recordSuccess(scope, request, afterState, toolResult);
            currentState = afterState;
            successful++;
        }
        return new GameQueueDrainResult(currentState, attempted, successful, traceBuilder.build());
    }

    /**
     * 获取本批操作执行起点状态。
     *
     * @param adapter      当前游戏适配器
     * @param context      适配器运行上下文
     * @param initialState 队列开始时读取到的状态
     * @return 第一条指令执行前使用的状态
     */
    private GameStateSnapshot loadDrainStartState(GameAdapter adapter,
                                                  GameAdapterContext context,
                                                  GameStateSnapshot initialState) {
        if (!adapter.shouldRefreshStateBeforeDrain()) {
            return initialState;
        }
        return adapter.fetchState(context);
    }

    /**
     * 在安全边界检查外部打断。
     *
     * @param key        gamer 会话 key
     * @param successful 当前批次已经成功执行的 MCP 操作数
     */
    private void interruptIfRequested(String key, int successful) {
        if (interruptService == null) {
            return;
        }
        interruptService.peek(key).ifPresent(request -> {
            throw new GameBridgeException("外部打断: " + request.instruction(), successful);
        });
    }

    /**
     * 在每条 MCP 操作前让出被用户或 RP 发言占用的交互窗口。
     *
     * @param gameName         游戏名
     * @param key              gamer 会话 key
     * @param successful       当前批次已成功执行的 MCP 操作数
     * @param ignoreRpSpeaking true 表示忽略 RP 正在说话的 lease
     */
    private void interruptIfInteractionBusy(String gameName, String key, int successful, boolean ignoreRpSpeaking) {
        if (interactionCoordinator == null) {
            return;
        }
        InteractionCoordinator.GameTurnPermission permission = ignoreRpSpeaking
                ? interactionCoordinator.canGameActForGamerIgnoringRpSpeech(gameName, key)
                : interactionCoordinator.canGameActForGamer(gameName, key);
        if (!permission.allowed()) {
            throw new GameBridgeException(
                    "交互调度暂停: " + permission.reason(),
                    successful,
                    GameBridgeException.Kind.INTERACTION_DEFERRED);
        }
    }

    private GameBridgeException hardOperationFailure(GameAdapter adapter,
                                                     GameAdapterContext context,
                                                     GameQueueExecutionTraceBuilder.OperationScope scope,
                                                     ToolExecutionRequest request,
                                                     String reason,
                                                     int successful,
                                                     GameQueueExecutionTraceBuilder traceBuilder,
                                                     String outcomeKind) {
        GameStateSnapshot latestState;
        try {
            latestState = adapter.fetchState(context);
        } catch (Exception e) {
            String message = reason + "；失败后获取最新状态也失败: " + e.getMessage();
            traceBuilder.recordFailure(scope, request, null, message, outcomeKind);
            throw new GameBridgeException(message, successful);
        }

        traceBuilder.recordFailure(scope, request, latestState, reason, outcomeKind);
        throw new GameBridgeException(reason + "\n最新状态：\n" + adapter.renderStateForAgent(latestState), successful);
    }

    private GameActionWindowSignature safeActionWindow(GameActionWindowSignature signature) {
        return signature == null ? GameActionWindowSignature.unchecked() : signature;
    }

    private GameOperationPrecondition safePrecondition(GameOperationPrecondition precondition) {
        return precondition == null ? GameOperationPrecondition.passed() : precondition;
    }

    private String staleActionWindowReason(GameActionWindowSignature plannedWindow,
                                           GameActionWindowSignature currentWindow,
                                           int successful) {
        String phase = successful == 0 ? "执行前" : "队列中途";
        return "旧状态动作已过期：" + phase
                + "行动窗口已变化，计划窗口=" + plannedWindow.compact()
                + "，当前窗口=" + currentWindow.compact()
                + "，剩余操作已丢弃。";
    }

    /**
     * 获取操作后的状态，并在疑似读到过渡态时延迟重读。
     *
     * @param key          gamer 会话 key
     * @param adapter      当前游戏适配器
     * @param context      适配器运行上下文
     * @param operation    已执行的操作
     * @param beforeState  执行前状态
     * @param toolResult   MCP 工具返回文本
     * @param executed     当前批次已执行操作数
     * @return 通过监视检查的最新状态
     */
    private GameStateSnapshot fetchStateUntilMonitorPasses(String key,
                                                           GameAdapter adapter,
                                                           GameAdapterContext context,
                                                           QueuedGameOperation operation,
                                                           GameStateSnapshot beforeState,
                                                           String toolResult,
                                                           int executed) {
        int maxAttempts = Math.max(1, context.config().getStateSettleMaxAttempts());
        long delayMs = Math.max(0, context.config().getStateSettleDelayMs());
        GameBridgeException lastFailure = null;
        GameStateSnapshot afterState = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                sleepBeforeStateRetry(delayMs, key, attempt, maxAttempts);
            }

            afterState = adapter.fetchState(context);
            try {
                adapter.monitorAfterExecute(operation, beforeState, afterState, toolResult);
                if (attempt > 1) {
                    log.info("[游戏桥接] 状态延迟重读后通过监视: memoryId={}, attempt={}/{}",
                            key, attempt, maxAttempts);
                }
                return afterState;
            } catch (GameBridgeException e) {
                if (!shouldRetryMonitorFailure(e) || attempt >= maxAttempts) {
                    throw new GameBridgeException(
                            "状态监视触发中断: " + e.getMessage()
                                    + "\n最新状态：\n" + adapter.renderStateForAgent(afterState),
                            executed,
                            e.getKind(),
                            afterState);
                }
                lastFailure = e;
                log.debug("[游戏桥接] 状态监视未通过，疑似读取到过渡态，准备延迟重读: memoryId={}, attempt={}/{}, reason={}",
                        key, attempt, maxAttempts, e.getMessage());
            }
        }

        throw new GameBridgeException(
                "状态监视触发中断: " + (lastFailure == null ? "未知状态同步问题" : lastFailure.getMessage())
                        + "\n最新状态：\n" + (afterState == null ? "(未能获取状态)" : adapter.renderStateForAgent(afterState)),
                executed,
                lastFailure == null ? GameBridgeException.Kind.FAILURE : lastFailure.getKind(),
                afterState);
    }

    private String compactBoundaryReason(String message) {
        if (message == null || message.isBlank()) {
            return "状态已推进，剩余队列已停止。";
        }
        int stateIndex = message.indexOf("最新状态");
        String compact = stateIndex >= 0 ? message.substring(0, stateIndex) : message;
        return compact.replaceAll("\\s+", " ").trim();
    }

    /**
     * 判断监视失败是否值得重读确认。
     *
     * @param failure 适配器抛出的监视异常
     * @return true 表示可能是状态刷新延迟，可以短暂等待后重读
     */
    private boolean shouldRetryMonitorFailure(GameBridgeException failure) {
        if (failure.getKind() == GameBridgeException.Kind.STATE_ADVANCED) {
            return false;
        }
        String message = failure.getMessage();
        return message == null || !message.startsWith("MCP 工具执行失败:");
    }

    /**
     * 在状态重读前等待一小段时间。
     *
     * @param delayMs     等待时间（毫秒）
     * @param key         gamer 会话 key
     * @param attempt     当前重读次数
     * @param maxAttempts 最大重读次数
     */
    private void sleepBeforeStateRetry(long delayMs, String key, int attempt, int maxAttempts) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GameBridgeException("状态重读等待被中断: memoryId=" + key + ", attempt=" + attempt + "/" + maxAttempts, e);
        }
    }

    /**
     * 识别 Python MCP 包装层返回的文本错误。
     *
     * @param toolResult MCP 工具返回文本
     * @return true 表示是基础设施层文本错误
     */
    private boolean isTextToolError(String toolResult) {
        return toolResult != null && toolResult.stripLeading().startsWith("Error:");
    }
}
