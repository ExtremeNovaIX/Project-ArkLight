package p1.component.agent.gamer.bridge.queue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.adapter.GameAdapter;
import p1.component.agent.gamer.adapter.core.*;
import p1.component.agent.gamer.interrupt.GameInterruptService;
import p1.component.agent.interaction.InteractionCoordinator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 游戏操作队列执行器。
 * <p>
 * 该组件只负责把已经准备好的队列逐条发送给 MCP，并处理执行前修复、软错误、状态重读和外部打断
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
     * @param key                  gamer 会话 key
     * @param adapter              当前游戏适配器
     * @param context              适配器运行上下文
     * @param queue                待执行操作队列
     * @param initialState         agent 规划时的初始状态
     * @param expectMoreOperations true 表示当前批次结束后同一 stream 仍可能继续输出后续操作
     * @return 队列执行结果，包含最新状态、成功数和软错误列表
     */
    public GameQueueDrainResult drain(String key,
                                      GameAdapter adapter,
                                      GameAdapterContext context,
                                      ArrayDeque<QueuedGameOperation> queue,
                                      GameStateSnapshot initialState,
                                      boolean expectMoreOperations) {
        int attempted = 0;
        int successful = 0;
        List<GameSoftOperationFailure> softFailures = new ArrayList<>();
        GameStateSnapshot currentState = loadDrainStartState(adapter, context, initialState);
        while (!queue.isEmpty()) {
            interruptIfInteractionBusy(context.gameName(), key, successful);
            interruptIfRequested(key, successful);
            QueuedGameOperation operation = queue.pollFirst();
            GameStateSnapshot beforeState = currentState != null ? currentState : adapter.fetchState(context);
            ToolExecutionRequest request;
            try {
                request = adapter.repairBeforeExecute(operation, beforeState);
            } catch (GameBridgeException e) {
                attempted++;
                OperationFailureHandling handling = handleOperationFailure(
                        key, adapter, context, operation, beforeState,
                        "adapter执行指令修复失败: " + e.getMessage(),
                        successful, softFailures);
                currentState = handling.latestState();
                continue;
            }

            ToolExecutor executor = context.tools().toolExecutorByName(request.name());
            if (executor == null) {
                throw new GameBridgeException("MCP 工具不存在: " + request.name(), successful);
            }

            String toolResult;
            try {
                toolResult = executor.execute(request, key);
            } catch (Exception e) {
                throw new GameBridgeException("MCP 工具执行失败: " + request.name() + "，原因: " + e.getMessage(), successful);
            }

            attempted++;

            QueuedGameOperation executedOperation = operation.withRequest(request);
            String mcpError = adapter.extractToolError(toolResult);
            if (mcpError != null) {
                OperationFailureHandling handling = handleOperationFailure(
                        key, adapter, context, executedOperation, beforeState,
                        "MCP 工具执行失败: " + mcpError,
                        successful, softFailures);
                currentState = handling.latestState();
                continue;
            }
            if (isTextToolError(toolResult)) {
                throw new GameBridgeException("MCP 工具执行失败: " + toolResult, successful);
            }

            GameStateSnapshot afterState = fetchStateUntilMonitorPasses(
                    key, adapter, context, executedOperation, beforeState, toolResult,
                    !queue.isEmpty() || expectMoreOperations,
                    successful + 1);
            currentState = afterState;
            successful++;
        }
        return new GameQueueDrainResult(currentState, attempted, successful, List.copyOf(softFailures));
    }

    /**
     * 获取本批操作执行起点状态。
     * <p>
     * 某些游戏会在模型思考期间被队友、倒计时或异步动画推进；这些适配器需要在真正执行前重读状态，
     * 避免第一条 MCP 指令仍然基于 prompt 注入时的旧快照运行。
     *
     * @param adapter      当前游戏适配器
     * @param context      适配器运行上下文
     * @param initialState agent 规划时看到的状态
     * @return 队列第一条指令执行前使用的状态
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
     * @param gameName   游戏名
     * @param key        gamer 会话 key
     * @param successful 当前批次已成功执行的 MCP 操作数
     */
    private void interruptIfInteractionBusy(String gameName, String key, int successful) {
        if (interactionCoordinator == null) {
            return;
        }
        InteractionCoordinator.GameTurnPermission permission =
                interactionCoordinator.canGameActForGamer(gameName, key);
        if (!permission.allowed()) {
            throw new GameBridgeException("交互调度暂停: " + permission.reason(), successful);
        }
    }

    /**
     * 处理单条操作失败：获取最新状态，并交给 adapter 判断是否可以作为软错误继续。
     *
     * @param key          gamer 会话 key
     * @param adapter      当前游戏适配器
     * @param context      适配器运行上下文
     * @param operation    失败操作
     * @param beforeState  失败前状态
     * @param reason       失败原因
     * @param successful   当前批次已成功执行的 MCP 操作数
     * @param softFailures 当前批次累计的软错误列表
     * @return 失败处理结果；如果不能软化会直接抛出硬中断异常
     */
    private OperationFailureHandling handleOperationFailure(String key,
                                                           GameAdapter adapter,
                                                           GameAdapterContext context,
                                                           QueuedGameOperation operation,
                                                           GameStateSnapshot beforeState,
                                                           String reason,
                                                           int successful,
                                                           List<GameSoftOperationFailure> softFailures) {
        GameStateSnapshot latestState;
        try {
            latestState = adapter.fetchState(context);
        } catch (Exception e) {
            throw new GameBridgeException(reason + "；失败后获取最新状态也失败: " + e.getMessage(), successful);
        }

        if (adapter.shouldContinueAfterOperationFailure(operation, beforeState, latestState, reason)) {
            GameSoftOperationFailure failure = new GameSoftOperationFailure(
                    operation.request().name(),
                    operation.request().arguments(),
                    operation.note(),
                    reason);
            softFailures.add(failure);
            log.info("[游戏桥接] 操作失败但状态仍可继续，已跳过该操作: memoryId={}, tool={}, reason={}",
                    key, failure.toolName(), reason);
            return new OperationFailureHandling(latestState);
        }

        throw new GameBridgeException(reason + "\n最新状态:\n" + adapter.renderStateForAgent(latestState), successful);
    }

    /**
     * 获取操作后的状态，并在疑似读到过渡态时延迟重读。
     *
     * @param key                    gamer 会话 key
     * @param adapter                当前游戏适配器
     * @param context                适配器运行上下文
     * @param operation              已执行的操作
     * @param beforeState            执行前状态
     * @param toolResult             MCP 工具返回文本
     * @param hasRemainingOperations 当前队列是否仍有后续操作
     * @param executed               当前批次已执行操作数
     * @return 通过监视检查的最新状态
     */
    private GameStateSnapshot fetchStateUntilMonitorPasses(String key,
                                                           GameAdapter adapter,
                                                           GameAdapterContext context,
                                                           QueuedGameOperation operation,
                                                           GameStateSnapshot beforeState,
                                                           String toolResult,
                                                           boolean hasRemainingOperations,
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
                adapter.monitorAfterExecute(operation, beforeState, afterState, toolResult, hasRemainingOperations);
                if (attempt > 1) {
                    log.info("[游戏桥接] 状态延迟重读后通过监视: memoryId={}, attempt={}/{}",
                            key, attempt, maxAttempts);
                }
                return afterState;
            } catch (GameBridgeException e) {
                if (!shouldRetryMonitorFailure(e) || attempt >= maxAttempts) {
                    throw new GameBridgeException(
                            "状态监视触发中断: " + e.getMessage()
                                    + "\n最新状态:\n" + adapter.renderStateForAgent(afterState),
                            executed);
                }
                lastFailure = e;
                log.debug("[游戏桥接] 状态监视未通过，疑似读取到过渡态，准备延迟重读: memoryId={}, attempt={}/{}, reason={}",
                        key, attempt, maxAttempts, e.getMessage());
            }
        }

        throw new GameBridgeException(
                "状态监视触发中断: " + (lastFailure == null ? "未知状态同步问题" : lastFailure.getMessage())
                        + "\n最新状态:\n" + (afterState == null ? "(未能获取状态)" : adapter.renderStateForAgent(afterState)),
                executed);
    }

    /**
     * 判断监视失败是否值得重读确认。
     *
     * @param failure 适配器抛出的监视异常
     * @return true 表示可能是状态刷新延迟，可以短暂等待后重读
     */
    private boolean shouldRetryMonitorFailure(GameBridgeException failure) {
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

    /**
     * 单条失败处理后的最新状态。
     *
     * @param latestState 失败后重新读取的状态
     */
    private record OperationFailureHandling(GameStateSnapshot latestState) {
    }
}
