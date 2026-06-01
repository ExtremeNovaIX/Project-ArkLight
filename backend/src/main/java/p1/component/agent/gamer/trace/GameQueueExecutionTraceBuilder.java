package p1.component.agent.gamer.trace;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import p1.component.agent.gamer.adapter.core.GameActionWindowSignature;
import p1.component.agent.gamer.adapter.core.GameOperationPrecondition;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.adapter.core.QueuedGameOperation;

import java.util.ArrayList;
import java.util.List;

/**
 * 收集队列执行过程中的复盘信息。
 */
public class GameQueueExecutionTraceBuilder {

    private static final int MAX_RESULT_PREVIEW = 500;

    private final String queueId;
    private final List<GameOperationExecutionTrace> operations = new ArrayList<>();
    private long startNanos;
    private long endNanos;
    private String startStateType = "";
    private String startStateHash = "";
    private String startActionWindow = "";
    private String boundaryReason = "";

    public GameQueueExecutionTraceBuilder(String queueId) {
        this.queueId = queueId == null || queueId.isBlank() ? "unknown" : queueId;
    }

    public void markStart(GameStateSnapshot state) {
        markStart(state, GameActionWindowSignature.unchecked());
    }

    public void markStart(GameStateSnapshot state, GameActionWindowSignature actionWindow) {
        startNanos = System.nanoTime();
        endNanos = 0;
        startStateType = stateType(state);
        startStateHash = GameStateTraceHasher.shortHash(state);
        startActionWindow = compactWindow(actionWindow);
    }

    public OperationScope beginOperation(int index,
                                         QueuedGameOperation operation,
                                         GameStateSnapshot beforeState) {
        return beginOperation(
                index,
                operation,
                beforeState,
                GameActionWindowSignature.unchecked(),
                GameActionWindowSignature.unchecked(),
                GameOperationPrecondition.passed());
    }

    public OperationScope beginOperation(int index,
                                         QueuedGameOperation operation,
                                         GameStateSnapshot beforeState,
                                         GameActionWindowSignature plannedWindow,
                                         GameActionWindowSignature currentWindow,
                                         GameOperationPrecondition precondition) {
        ToolExecutionRequest request = operation == null ? null : operation.request();
        return new OperationScope(
                index,
                request == null ? "" : request.name(),
                request == null ? "" : request.arguments(),
                stateType(beforeState),
                GameStateTraceHasher.shortHash(beforeState),
                compactWindow(plannedWindow),
                compactWindow(currentWindow),
                compactPrecondition(precondition),
                System.nanoTime());
    }

    public void recordSuccess(OperationScope scope,
                              ToolExecutionRequest request,
                              GameStateSnapshot afterState,
                              String toolResult) {
        recordSuccess(scope, request, afterState, toolResult, "success");
    }

    public void recordSuccess(OperationScope scope,
                              ToolExecutionRequest request,
                              GameStateSnapshot afterState,
                              String toolResult,
                              String outcomeKind) {
        operations.add(toTrace(scope, request, afterState, preview(toolResult), "", outcomeKind));
    }

    public void recordFailure(OperationScope scope,
                              ToolExecutionRequest request,
                              GameStateSnapshot latestState,
                              String reason) {
        recordFailure(scope, request, latestState, reason, "failure");
    }

    public void recordFailure(OperationScope scope,
                              ToolExecutionRequest request,
                              GameStateSnapshot latestState,
                              String reason,
                              String outcomeKind) {
        operations.add(toTrace(scope, request, latestState, "", reason, outcomeKind));
    }

    public void markBoundary(String reason) {
        boundaryReason = reason == null ? "" : reason.trim();
    }

    public GameQueueExecutionTrace build() {
        if (endNanos == 0) {
            endNanos = System.nanoTime();
        }
        long elapsedMs = startNanos == 0 ? 0 : Math.max(0, (endNanos - startNanos) / 1_000_000);
        return new GameQueueExecutionTrace(
                queueId,
                startStateType,
                startStateHash,
                startActionWindow,
                elapsedMs,
                List.copyOf(operations),
                boundaryReason);
    }

    private GameOperationExecutionTrace toTrace(OperationScope scope,
                                                ToolExecutionRequest request,
                                                GameStateSnapshot latestState,
                                                String result,
                                                String error,
                                                String outcomeKind) {
        ToolExecutionRequest actualRequest = request;
        String toolName = actualRequest == null || actualRequest.name() == null || actualRequest.name().isBlank()
                ? scope.toolName()
                : actualRequest.name();
        String arguments = actualRequest == null || actualRequest.arguments() == null || actualRequest.arguments().isBlank()
                ? scope.arguments()
                : actualRequest.arguments();
        long elapsedMs = Math.max(0, (System.nanoTime() - scope.startNanos()) / 1_000_000);
        return new GameOperationExecutionTrace(
                scope.index(),
                toolName,
                arguments,
                scope.beforeStateType(),
                scope.beforeStateHash(),
                stateType(latestState),
                GameStateTraceHasher.shortHash(latestState),
                scope.plannedActionWindow(),
                scope.currentActionWindow(),
                scope.preconditionSignature(),
                outcomeKind == null ? "" : outcomeKind,
                elapsedMs,
                result == null ? "" : result,
                error == null ? "" : error);
    }

    private String compactWindow(GameActionWindowSignature signature) {
        return signature == null ? "" : signature.compact();
    }

    private String compactPrecondition(GameOperationPrecondition precondition) {
        return precondition == null ? "" : precondition.compact();
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(trimmed.length(), MAX_RESULT_PREVIEW));
    }

    private String stateType(GameStateSnapshot state) {
        return state == null || state.stateType() == null ? "" : state.stateType();
    }

    public record OperationScope(
            int index,
            String toolName,
            String arguments,
            String beforeStateType,
            String beforeStateHash,
            String plannedActionWindow,
            String currentActionWindow,
            String preconditionSignature,
            long startNanos
    ) {
    }
}
