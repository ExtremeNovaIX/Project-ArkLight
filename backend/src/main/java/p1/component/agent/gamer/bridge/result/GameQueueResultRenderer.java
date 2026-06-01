package p1.component.agent.gamer.bridge.result;

import org.springframework.stereotype.Component;
import p1.component.agent.gamer.bridge.queue.GameQueueDrainResult;

/**
 * 渲染操作队列的执行结果，用于日志、复盘和失败反馈。
 */
@Component
public class GameQueueResultRenderer {

    public String renderDrainResult(int requestedOperations, GameQueueDrainResult drainResult) {
        String boundary = drainResult.stoppedByStateBoundary()
                ? " 状态已推进，剩余队列已停止。"
                : "";
        return "已成功执行 " + drainResult.successful() + "/" + requestedOperations + " 条操作。" + boundary;
    }

    public String compactInterruptReason(String message) {
        String normalized = normalizeText(message);
        int stateIndex = normalized.indexOf("最新状态");
        if (stateIndex >= 0) {
            normalized = normalized.substring(0, stateIndex).trim();
        }
        return normalized;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
