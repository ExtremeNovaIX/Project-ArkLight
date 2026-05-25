package p1.component.agent.gamer.bridge.result;

import org.springframework.stereotype.Component;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.bridge.GameBridgeActionStatus;
import p1.component.agent.gamer.bridge.queue.GameQueueDrainResult;
import p1.component.agent.gamer.bridge.queue.GameSoftOperationFailure;

import java.util.List;

/**
 * 游戏队列结果渲染器。
 * <p>
 * 该组件只负责把结构化执行结果转成面向 agent、日志和工具返回值的文本。
 */
@Component
public class GameQueueResultRenderer {

    /**
     * 渲染下一轮注入给 agent 的上一批操作结果。
     *
     * @param status          队列状态
     * @param summary         agent 提交的决策摘要
     * @param reasoning       模型 reasoning_content；当前不进入 prompt，只保留参数以便复盘扩展
     * @param operations      agent 提交的操作队列
     * @param result          桥接层执行结果
     * @param stateDiff       操作造成的状态差异
     * @param interruptReason 中断原因；没有中断时为空
     * @return 面向下一轮 agent 的结果文本
     */
    public String renderLastActionResult(GameBridgeActionStatus status,
                                         String summary,
                                         String reasoning,
                                         List<GameOperation> operations,
                                         String result,
                                         String stateDiff,
                                         String interruptReason) {
        StringBuilder sb = new StringBuilder();
        sb.append("status=").append(status == null ? GameBridgeActionStatus.UNKNOWN : status).append("\n");
        sb.append("decision=").append(summary == null || summary.isBlank() ? "无" : summary.trim()).append("\n");
        sb.append("operations:\n");
        if (operations == null || operations.isEmpty()) {
            sb.append("- 无\n");
        } else {
            for (GameOperation operation : operations) {
                sb.append("- ")
                        .append(operation.toolName())
                        .append(" args=")
                        .append(operation.args() == null ? "{}" : operation.args())
                        .append(" | 意图=")
                        .append(operation.note() == null || operation.note().isBlank() ? "无" : operation.note().trim())
                        .append("\n");
            }
        }
        sb.append("result=").append(result == null || result.isBlank() ? "无" : result.trim()).append("\n");
        if (interruptReason != null && !interruptReason.isBlank()) {
            sb.append("interrupt=").append(interruptReason.trim()).append("\n");
        }
        sb.append("state_diff:\n").append(stateDiff == null || stateDiff.isBlank() ? "无状态差异。" : stateDiff.trim());
        return sb.toString();
    }

    /**
     * 渲染本次队列执行结果，包含软错误统计。
     *
     * @param summary             agent 决策摘要
     * @param requestedOperations 本批请求操作数量
     * @param drainResult         队列执行结果
     * @return 工具返回文本中的执行摘要
     */
    public String renderDrainResult(String summary, int requestedOperations, GameQueueDrainResult drainResult) {
        int failed = drainResult.softFailures().size();
        String prefix = failed == 0
                ? "已成功执行 " + drainResult.successful() + "/" + requestedOperations + " 条操作。"
                : "已成功执行 " + drainResult.successful() + "/" + requestedOperations
                + " 条操作，跳过 " + failed + " 条软错误操作。";
        return prefix + " " + summary;
    }

    /**
     * 渲染下一轮注入给 agent 的软错误摘要。
     *
     * @param failures 软错误列表
     * @return 面向 agent 的软错误提示
     */
    public String renderSoftFailureNotice(List<GameSoftOperationFailure> failures) {
        StringBuilder sb = new StringBuilder("上一批队列中有 ")
                .append(failures.size())
                .append(" 条操作失败但游戏仍处于可行动窗口，已跳过失败操作并继续执行其余队列：\n");
        for (GameSoftOperationFailure failure : failures) {
            sb.append("- ")
                    .append(failure.toolName())
                    .append(" args=")
                    .append(failure.arguments())
                    .append(" | 意图=")
                    .append(failure.note())
                    .append(" | 原因=")
                    .append(failure.reason())
                    .append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 压缩中断原因，去掉异常消息中附带的完整状态文本。
     *
     * @param message 原始异常消息
     * @return 单行中断摘要
     */
    public String compactInterruptReason(String message) {
        String normalized = normalizeText(message);
        int stateIndex = normalized.indexOf("最新状态:");
        if (stateIndex >= 0) {
            normalized = normalized.substring(0, stateIndex).trim();
        }
        return normalized;
    }

    /**
     * 把结构化状态格式化为工具返回文本。
     *
     * @param requestedStatus 队列执行后的结构化状态
     * @param message         状态说明
     * @return 返回给 agent 的工具结果文本
     */
    public String formatStatus(GameBridgeActionStatus requestedStatus, String message) {
        GameBridgeActionStatus status = requestedStatus == null ? GameBridgeActionStatus.CONTINUE : requestedStatus;
        return switch (status) {
            case WAIT -> "[WAIT] " + message;
            case GAME_OVER -> "[GAME_OVER] " + message;
            default -> "[CONTINUE] " + message;
        };
    }

    /**
     * 把异常文本压成单行，便于下一轮 prompt 和日志读取。
     *
     * @param value 原始文本
     * @return 单行文本
     */
    private String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
