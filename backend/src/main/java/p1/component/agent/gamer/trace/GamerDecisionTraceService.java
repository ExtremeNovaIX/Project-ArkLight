package p1.component.agent.gamer.trace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.bridge.GameBridgeActionStatus;
import p1.config.mcp.GamerMemoryProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * gamer 决策复盘日志服务。
 * <p>
 * 该服务只追加写入 Markdown，不参与 agent 工作记忆；它用于人工复盘一次决策中的
 * LLM 分析、工具调用、桥接层执行结果和状态差异。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GamerDecisionTraceService {

    private final GamerMemoryProperties properties;
    private final Map<String, AtomicLong> stepCounters = new ConcurrentHashMap<>();

    /**
     * 追加一次队列执行复盘记录。
     *
     * @param gameName        游戏名
     * @param memoryId        会话 key
     * @param status          队列状态
     * @param summary         LLM 提交的决策说明
     * @param reasoning       模型返回的 reasoning_content；没有时为空
     * @param operations      LLM 提交的操作队列
     * @param executionResult 桥接层执行结果
     * @param stateDiff       操作前后状态差异
     * @param interruptReason 中断原因；没有中断时为空
     */
    public void appendQueueTrace(String gameName,
                                 String memoryId,
                                 GameBridgeActionStatus status,
                                 String summary,
                                 String reasoning,
                                 List<GameOperation> operations,
                                 String executionResult,
                                 String stateDiff,
                                 String interruptReason) {
        if (!properties.isTraceEnabled()) {
            return;
        }

        try {
            String safeGame = safePathSegment(gameName);
            String safeMemory = safePathSegment(memoryId);
            Path path = Path.of(properties.getTraceDirectory(), safeGame, safeMemory + ".md");
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    renderBlock(gameName, memoryId, status, summary, reasoning, operations, executionResult, stateDiff, interruptReason),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[gamer复盘] 写入决策复盘日志失败: game={}, memoryId={}, reason={}", gameName, memoryId, e.getMessage());
        }
    }

    /**
     * 渲染单次复盘 Markdown 块。
     */
    private String renderBlock(String gameName,
                               String memoryId,
                               GameBridgeActionStatus status,
                               String summary,
                               String reasoning,
                               List<GameOperation> operations,
                               String executionResult,
                               String stateDiff,
                               String interruptReason) {
        long step = stepCounters
                .computeIfAbsent(gameName + "::" + memoryId, ignored -> new AtomicLong())
                .incrementAndGet();
        StringBuilder sb = new StringBuilder();
        sb.append("\n## Step ").append(step)
                .append(" | ").append(Instant.now())
                .append(" | game=").append(nullToBlank(gameName))
                .append(" | memoryId=").append(nullToBlank(memoryId))
                .append("\n\n");

        sb.append("### LLM 分析\n\n")
                .append(blankText(summary))
                .append("\n\n");

        sb.append("### reasoning_content\n\n")
                .append(blankText(reasoning))
                .append("\n\n");

        sb.append("### LLM Tool Calling\n\n");
        if (operations == null || operations.isEmpty()) {
            sb.append("无。\n\n");
        } else {
            for (GameOperation operation : operations) {
                sb.append("- ")
                        .append(operation.toolName())
                        .append(" args=")
                        .append(operation.args() == null ? "{}" : operation.args())
                        .append(" | 意图=")
                        .append(blankText(operation.note()))
                        .append("\n");
            }
            sb.append("\n");
        }

        sb.append("### 执行结果\n\n")
                .append("- status=").append(status == null ? GameBridgeActionStatus.UNKNOWN : status).append("\n")
                .append("- result=").append(blankText(executionResult)).append("\n");
        if (interruptReason != null && !interruptReason.isBlank()) {
            sb.append("- interrupt=").append(interruptReason.trim()).append("\n");
        }
        sb.append("\n");

        sb.append("### 状态 Diff\n\n")
                .append(blankText(stateDiff))
                .append("\n");
        return sb.toString();
    }

    /**
     * 清洗文件路径片段，避免会话名中的特殊字符影响落盘路径。
     */
    private String safePathSegment(String value) {
        String normalized = value == null || value.isBlank() ? "default" : value.trim();
        return normalized.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * 渲染空文本占位。
     */
    private String blankText(String value) {
        return value == null || value.isBlank() ? "无。" : value.trim();
    }

    /**
     * 空值转空字符串。
     */
    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
