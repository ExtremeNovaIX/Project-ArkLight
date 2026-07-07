package p1.component.agent.gamer.bridge.result;

import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.trace.GameQueueExecutionTrace;
import p1.component.agent.gamer.trace.GamerDecisionTraceService;
import p1.component.agent.reasoning.ReasoningContentRecorder;

import java.util.List;

/**
 * 记录操作队列执行细节。该记录只进入复盘日志，不写入 RP 记忆。
 */
@Component
@CustomLog
@RequiredArgsConstructor
public class GameQueueResultRecorder {

    private final GamerDecisionTraceService traceService;
    private final ReasoningContentRecorder reasoningContentRecorder;

    public String consumeReasoningContent(String key) {
        if (reasoningContentRecorder == null) {
            return "";
        }
        return reasoningContentRecorder.consumeLatest(key);
    }

    public void record(String gameName,
                       String key,
                       String reasoning,
                       String rpDo,
                       String parserRaw,
                       long parserLatencyMs,
                       boolean parserEarlyCompleted,
                       boolean parserEarlyCancelled,
                       List<GameOperation> operations,
                       String result,
                       String interruptReason,
                       GameQueueExecutionTrace executionTrace,
                       String planText,
                       String check,
                       String progress,
                       String next,
                       boolean commit) {
        if (traceService != null) {
            traceService.appendQueueTrace(
                    gameName,
                    key,
                    reasoning,
                    rpDo,
                    parserRaw,
                    parserLatencyMs,
                    parserEarlyCompleted,
                    parserEarlyCancelled,
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
    }
}
