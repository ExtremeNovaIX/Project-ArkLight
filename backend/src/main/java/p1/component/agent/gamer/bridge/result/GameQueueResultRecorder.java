package p1.component.agent.gamer.bridge.result;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.bridge.GameBridgeActionStatus;
import p1.component.agent.gamer.bridge.state.GameQueueStateStore;
import p1.component.agent.gamer.memory.GamerWorkingMemoryService;
import p1.component.agent.gamer.projection.GamerActionSnapshotService;
import p1.component.agent.gamer.trace.GamerDecisionTraceService;
import p1.component.agent.reasoning.ReasoningContentRecorder;
import p1.component.agent.rp.game.memory.RpGameMemoryAppender;

import java.util.List;

/**
 * 游戏队列结果记录器。
 * <p>
 * 该组件负责把队列结果写入短期状态、gamer 工作记忆和复盘日志；
 * 不参与 MCP 执行，也不解析模型提交的 JSON。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GameQueueResultRecorder {

    private final GamerWorkingMemoryService workingMemoryService;
    private final GamerDecisionTraceService traceService;
    private final ReasoningContentRecorder reasoningContentRecorder;
    private final GameQueueStateStore stateStore;
    private final GameQueueResultRenderer resultRenderer;
    private final RpGameMemoryAppender rpGameMemoryAppender;
    private final GamerActionSnapshotService actionSnapshotService;

    /**
     * 取出本次模型回复对应的 reasoning_content。
     *
     * @param key gamer 会话 key
     * @return 最近一次 reasoning_content；没有时为空字符串
     */
    public String consumeReasoningContent(String key) {
        if (reasoningContentRecorder == null) {
            return "";
        }
        return reasoningContentRecorder.consumeLatest(key);
    }

    /**
     * 记录本批队列结果。
     *
     * @param gameName        游戏名
     * @param key             gamer 会话 key
     * @param status          队列状态
     * @param summary         agent 提交的决策摘要
     * @param reasoning       模型返回的 reasoning_content；没有时为空
     * @param operations      agent 提交的操作队列
     * @param result          桥接层执行结果
     * @param stateDiff       操作造成的状态差异
     * @param interruptReason 中断原因；没有中断时为空
     */
    public void record(String gameName,
                       String key,
                       GameBridgeActionStatus status,
                       String summary,
                       String reasoning,
                       List<GameOperation> operations,
                       String result,
                       String stateDiff,
                       String interruptReason) {
        String rendered = resultRenderer.renderLastActionResult(
                status, summary, reasoning, operations, result, stateDiff, interruptReason);
        stateStore.rememberLastActionResult(key, rendered);

        if (traceService != null) {
            traceService.appendQueueTrace(gameName, key, status, summary, reasoning, operations, result, stateDiff, interruptReason);
        }

        recordMemorySafely(gameName, key, status, summary, operations, result, interruptReason);
        recordActionSnapshotSafely(gameName, key, summary, operations);
        recordRpMemorySafely(gameName, key, status, summary, operations, result, interruptReason);
    }

    /**
     * 安全记录 gamer 的 RP 动态行动投影。
     *
     * @param gameName   游戏名
     * @param key        gamer 会话 key
     * @param summary    gamer 决策摘要
     * @param operations gamer 提交的操作
     */
    private void recordActionSnapshotSafely(String gameName,
                                            String key,
                                            String summary,
                                            List<GameOperation> operations) {
        if (actionSnapshotService == null) {
            return;
        }
        try {
            actionSnapshotService.record(gameName, key, summary, operations);
        } catch (Exception e) {
            log.warn("[游戏桥接] gamer 行动投影记录失败: game={}, memoryId={}, reason={}",
                    gameName, key, e.getMessage());
        }
    }

    /**
     * 安全记录 gamer 工作记忆，避免记忆压缩失败影响真实游戏操作结果。
     *
     * @param gameName        游戏名
     * @param key             gamer 会话 key
     * @param status          本次队列状态
     * @param summary         agent 提交的决策摘要
     * @param operations      agent 提交的操作队列
     * @param result          桥接层执行结果
     * @param interruptReason 队列中断原因；没有中断时为空
     */
    private void recordMemorySafely(String gameName,
                                    String key,
                                    GameBridgeActionStatus status,
                                    String summary,
                                    List<GameOperation> operations,
                                    String result,
                                    String interruptReason) {
        try {
            // 工作记忆只保留决策结论和执行反馈；完整 reasoning 只进入复盘日志，避免下轮 prompt 膨胀。
            workingMemoryService.recordQueueResult(gameName, key, status, summary, "", operations, result, interruptReason);
        } catch (Exception e) {
            log.warn("[游戏桥接] gamer 工作记忆记录失败: game={}, memoryId={}, reason={}", gameName, key, e.getMessage());
        }
    }

    /**
     * 安全追加 RP 游戏行动记忆，避免 RP 记忆写入失败影响真实游戏操作。
     *
     * @param gameName        游戏名
     * @param key             gamer 会话 key
     * @param status          本次队列状态
     * @param summary         agent 提交的决策摘要
     * @param operations      agent 提交的操作队列
     * @param result          桥接层执行结果
     * @param interruptReason 队列中断原因；没有中断时为空
     */
    private void recordRpMemorySafely(String gameName,
                                      String key,
                                      GameBridgeActionStatus status,
                                      String summary,
                                      List<GameOperation> operations,
                                      String result,
                                      String interruptReason) {
        if (rpGameMemoryAppender == null) {
            return;
        }
        rpGameMemoryAppender.appendGameAction(gameName, key, status, summary, operations, result, interruptReason);
    }
}
