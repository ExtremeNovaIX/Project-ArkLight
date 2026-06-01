package p1.component.agent.gamer.trace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.rp.game.control.RpControlBlock;
import p1.config.mcp.GameTraceProperties;

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
 * 写入游戏操作复盘日志，供人工排查 RP、parser、MCP 执行链路问题。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GamerDecisionTraceService {

    private final GameTraceProperties properties;
    private final Map<String, AtomicLong> stepCounters = new ConcurrentHashMap<>();

    public void appendRpControlBlockTrace(String gameName,
                                          String memoryId,
                                          RpControlBlock block) {
        if (!properties.isTraceEnabled() || block == null) {
            return;
        }

        try {
            Path path = tracePath(gameName, memoryId);
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    renderRpControlBlock(gameName, memoryId, block),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[游戏复盘] 写入 RP 控制块复盘失败: game={}, memoryId={}, reason={}",
                    gameName, memoryId, e.getMessage());
        }
    }

    public void appendQueueTrace(String gameName,
                                 String memoryId,
                                 String reasoning,
                                 String rpDo,
                                 String parserRaw,
                                 long parserLatencyMs,
                                 boolean parserEarlyCompleted,
                                 boolean parserEarlyCancelled,
                                 List<GameOperation> operations,
                                 String executionResult,
                                 String interruptReason,
                                 GameQueueExecutionTrace executionTrace) {
        if (!properties.isTraceEnabled()) {
            return;
        }

        try {
            Path path = tracePath(gameName, memoryId);
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    renderBlock(
                            gameName,
                            memoryId,
                            reasoning,
                            rpDo,
                            parserRaw,
                            parserLatencyMs,
                            parserEarlyCompleted,
                            parserEarlyCancelled,
                            operations,
                            executionResult,
                            interruptReason,
                            executionTrace),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[游戏复盘] 写入操作队列复盘失败: game={}, memoryId={}, reason={}",
                    gameName, memoryId, e.getMessage());
        }
    }

    public void appendActionParserFailureTrace(String gameName,
                                               String memoryId,
                                               String rpDo,
                                               String parserRaw,
                                               String reason) {
        if (!properties.isTraceEnabled()) {
            return;
        }

        try {
            Path path = tracePath(gameName, memoryId);
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    renderActionParserFailure(gameName, memoryId, rpDo, parserRaw, reason),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[游戏复盘] 写入 RP 动作解析失败复盘失败: game={}, memoryId={}, reason={}",
                    gameName, memoryId, e.getMessage());
        }
    }

    private String renderRpControlBlock(String gameName,
                                        String memoryId,
                                        RpControlBlock block) {
        StringBuilder sb = new StringBuilder(header(gameName, memoryId, "rp_control"));
        sb.append("### RP 控制块\n\n")
                .append("- type=").append(block.type()).append("\n")
                .append("- kind=").append(block.voiceKind()).append("\n")
                .append("- commit=").append(block.commit()).append("\n")
                .append("- say=").append(blankText(block.say())).append("\n")
                .append("- do=").append(blankText(block.doText())).append("\n")
                .append("- check=").append(blankText(block.check())).append("\n")
                .append("- progress=").append(blankText(block.progress())).append("\n")
                .append("- next=").append(blankText(block.next())).append("\n\n");
        return sb.toString();
    }

    private String renderActionParserFailure(String gameName,
                                             String memoryId,
                                             String rpDo,
                                             String parserRaw,
                                             String reason) {
        StringBuilder sb = new StringBuilder(header(gameName, memoryId, "parser_failure"));
        sb.append("### RP do\n\n")
                .append(blankText(rpDo))
                .append("\n\n");

        sb.append("### parser raw\n\n")
                .append(blankText(parserRaw))
                .append("\n\n");

        sb.append("### 失败原因\n\n")
                .append(blankText(reason))
                .append("\n");
        return sb.toString();
    }

    private String renderBlock(String gameName,
                               String memoryId,
                               String reasoning,
                               String rpDo,
                               String parserRaw,
                               long parserLatencyMs,
                               boolean parserEarlyCompleted,
                               boolean parserEarlyCancelled,
                               List<GameOperation> operations,
                               String executionResult,
                               String interruptReason,
                               GameQueueExecutionTrace executionTrace) {
        StringBuilder sb = new StringBuilder(header(gameName, memoryId, "operation_queue"));

        sb.append("### reasoning_content\n\n")
                .append(blankText(reasoning))
                .append("\n\n");

        sb.append("### RP do\n\n")
                .append(blankText(rpDo))
                .append("\n\n");

        sb.append("### parser\n\n")
                .append("- latency_ms=").append(parserLatencyMs).append("\n")
                .append("- early_completed=").append(parserEarlyCompleted).append("\n")
                .append("- early_cancelled=").append(parserEarlyCancelled).append("\n\n");

        sb.append("### parser raw\n\n")
                .append(blankText(parserRaw))
                .append("\n\n");

        sb.append("### 操作队列\n\n");
        if (operations == null || operations.isEmpty()) {
            sb.append("无\n\n");
        } else {
            for (GameOperation operation : operations) {
                sb.append("- ")
                        .append(operation.toolName())
                        .append(" args=")
                        .append(operation.args() == null ? "{}" : operation.args())
                        .append("\n");
            }
            sb.append("\n");
        }

        appendExecutionTrace(sb, executionTrace);

        sb.append("### 执行结果\n\n")
                .append("- result=").append(blankText(executionResult)).append("\n");
        if (interruptReason != null && !interruptReason.isBlank()) {
            sb.append("- interrupt=").append(interruptReason.trim()).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private void appendExecutionTrace(StringBuilder sb, GameQueueExecutionTrace trace) {
        sb.append("### MCP 执行复盘\n\n");
        if (trace == null) {
            sb.append("无\n\n");
            return;
        }
        sb.append("- queue_id=").append(blankText(trace.queueId())).append("\n")
                .append("- start_state=").append(stateSignature(trace.startStateType(), trace.startStateHash())).append("\n")
                .append("- start_action_window=").append(blankText(trace.startActionWindow())).append("\n")
                .append("- elapsed_ms=").append(trace.elapsedMs()).append("\n");
        if (trace.boundaryReason() != null && !trace.boundaryReason().isBlank()) {
            sb.append("- boundary=").append(trace.boundaryReason().trim()).append("\n");
        }
        sb.append("\n");

        List<GameOperationExecutionTrace> operationTraces = trace.operations();
        if (operationTraces == null || operationTraces.isEmpty()) {
            sb.append("无操作明细\n\n");
            return;
        }
        for (GameOperationExecutionTrace operation : operationTraces) {
            sb.append("- #").append(operation.index())
                    .append(" tool=").append(blankText(operation.toolName()))
                    .append(" elapsed_ms=").append(operation.elapsedMs())
                    .append(" outcome=").append(blankText(operation.outcomeKind()))
                    .append(" before=").append(stateSignature(operation.beforeStateType(), operation.beforeStateHash()))
                    .append(" after=").append(stateSignature(operation.afterStateType(), operation.afterStateHash()))
                    .append("\n")
                    .append("  planned_window=").append(blankText(operation.plannedActionWindow())).append("\n")
                    .append("  current_window=").append(blankText(operation.currentActionWindow())).append("\n")
                    .append("  precondition=").append(blankText(operation.preconditionSignature())).append("\n")
                    .append("  args=").append(blankText(operation.arguments())).append("\n");
            if (operation.error() != null && !operation.error().isBlank()) {
                sb.append("  error=").append(operation.error().trim()).append("\n");
            } else if (operation.result() != null && !operation.result().isBlank()) {
                sb.append("  result=").append(operation.result().trim()).append("\n");
            }
        }
        sb.append("\n");
    }

    private String stateSignature(String stateType, String stateHash) {
        String type = stateType == null || stateType.isBlank() ? "unknown" : stateType.trim();
        String hash = stateHash == null || stateHash.isBlank() ? "nohash" : stateHash.trim();
        return type + "@" + hash;
    }

    private String header(String gameName, String memoryId, String kind) {
        long step = stepCounters
                .computeIfAbsent(gameName + "::" + memoryId, ignored -> new AtomicLong())
                .incrementAndGet();
        return "\n## Step " + step
                + " | " + Instant.now()
                + " | kind=" + nullToBlank(kind)
                + " | game=" + nullToBlank(gameName)
                + " | memoryId=" + nullToBlank(memoryId)
                + "\n\n";
    }

    private Path tracePath(String gameName, String memoryId) {
        String safeGame = safePathSegment(gameName);
        String safeMemory = safePathSegment(memoryId);
        return Path.of(properties.getTraceDirectory(), safeGame, safeMemory + ".md");
    }

    private String safePathSegment(String value) {
        String normalized = value == null || value.isBlank() ? "default" : value.trim();
        return normalized.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String blankText(String value) {
        return value == null || value.isBlank() ? "无" : value.trim();
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
