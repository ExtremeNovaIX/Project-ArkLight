package p1.component.agent.gamer.bridge.queue;

import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.bridge.GameBridgeActionStatus;
import p1.component.agent.gamer.bridge.GameExpressionPayload;

import java.util.List;

/**
 * gamer提交的 enqueue_operations 参数解析后的结构化批次。
 *
 * @param status               模型声明的队列后状态
 * @param summary              本批操作摘要
 * @param operations           本批操作列表
 * @param expectMoreOperations 流式模式下是否还可能继续输出后续操作
 * @param expression           gamer 为本次 ACTION 生成的表达欲候选
 */
public record ParsedGameOperationBatch(
        GameBridgeActionStatus status,
        String summary,
        List<GameOperation> operations,
        boolean expectMoreOperations,
        GameExpressionPayload expression
) {
}
