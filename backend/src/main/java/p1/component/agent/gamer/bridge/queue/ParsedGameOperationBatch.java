package p1.component.agent.gamer.bridge.queue;

import p1.component.agent.gamer.adapter.core.GameOperation;

import java.util.List;

/**
 * RP 动作 parser 提交的结构化操作批次。
 */
public record ParsedGameOperationBatch(
        List<GameOperation> operations,
        boolean ignoreRpSpeaking,
        String rpDo,
        String parserRaw,
        long parserLatencyMs,
        boolean parserEarlyCompleted,
        boolean parserEarlyCancelled
) {
}
