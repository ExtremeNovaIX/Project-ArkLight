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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 写入游戏操作复盘日志，供人工排查 RP、parser、MCP 执行链路问题。
 * <p>
 * 每轮游戏启动时（{@link #initSession}）创建时间戳命名的新文件。
 * 文件采用 frontmatter + 摘要 + 详细日志的单文件结构，
 * 方便 LLM 复盘时先读摘要快速定位问题 step，再按需深入详情。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GamerDecisionTraceService {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());

    private final GameTraceProperties properties;

    private final Map<String, AtomicLong> stepCounters = new ConcurrentHashMap<>();
    /** sessionKey → trace 文件路径 */
    private final Map<String, Path> sessionPaths = new ConcurrentHashMap<>();
    /** sessionKey → 最近一次 LLM 输出的 plan 块文本 */
    private final Map<String, String> pendingPlans = new ConcurrentHashMap<>();
    /** sessionKey → 最近一次 act block 的 check/progress/next/commit */
    private final Map<String, ActBlockContext> pendingActContexts = new ConcurrentHashMap<>();
    /** sessionKey → RP 本轮实际看到的游戏动态上下文 */
    private final Map<String, String> pendingRpVisibleContexts = new ConcurrentHashMap<>();
    /** sessionKey → 会话统计（供摘要） */
    private final Map<String, SessionStats> sessionStatsMap = new ConcurrentHashMap<>();

    // ── 生命周期 ──

    /**
     * 初始化会话追踪文件。
     * 创建带时间戳的 MD 文件并写入 frontmatter 头部。
     *
     * @return trace 文件路径；trace 未启用时返回 null
     */
    public Path initSession(String gameName, String memoryId) {
        if (!properties.isTraceEnabled()) {
            return null;
        }
        String key = sessionKey(gameName, memoryId);
        Path path = tracePath(gameName, memoryId);
        try {
            Files.createDirectories(path.getParent());
            String frontmatter = "---\n"
                    + "game: " + nullToBlank(gameName) + "\n"
                    + "session: " + nullToBlank(memoryId) + "\n"
                    + "started: " + Instant.now() + "\n"
                    + "---\n\n";
            Files.writeString(path, frontmatter, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            sessionPaths.put(key, path);
            sessionStatsMap.put(key, new SessionStats());
            stepCounters.computeIfAbsent(key, ignored -> new AtomicLong());
            log.info("[游戏复盘] 初始化会话追踪: game={}, memoryId={}, path={}", gameName, memoryId, path);
            return path;
        } catch (IOException e) {
            log.warn("[游戏复盘] 初始化会话追踪文件失败: game={}, memoryId={}, reason={}",
                    gameName, memoryId, e.getMessage());
            return null;
        }
    }

    /**
     * 流式解析到 LLM plan 块闭合时调用，暂存供下一次 queue trace 消费。
     */
    public void recordTurnPlan(String gameName, String memoryId, String planText) {
        if (!properties.isTraceEnabled() || planText == null || planText.isBlank()) {
            return;
        }
        pendingPlans.put(sessionKey(gameName, memoryId), planText.trim());
    }

    /**
     * 暂存 RP 本轮实际看到的游戏上下文，供下一次 committed act 的复盘日志消费。
     */
    public void recordRpVisibleContext(String gameName, String memoryId, String visibleContext) {
        if (!properties.isTraceEnabled() || visibleContext == null || visibleContext.isBlank()) {
            return;
        }
        pendingRpVisibleContexts.put(sessionKey(gameName, memoryId), visibleContext.trim());
    }

    /**
     * 写入会话摘要到文件头部（frontmatter 之后）。
     * 通常在 session 结束时调用。
     */
    public void writeSummary(String gameName, String memoryId) {
        if (!properties.isTraceEnabled()) {
            return;
        }
        String key = sessionKey(gameName, memoryId);
        Path path = sessionPaths.get(key);
        if (path == null || !Files.exists(path)) {
            return;
        }
        SessionStats stats = sessionStatsMap.get(key);
        if (stats == null || stats.steps.isEmpty()) {
            return;
        }
        try {
            String existingContent = Files.readString(path, StandardCharsets.UTF_8);
            int frontmatterEnd = existingContent.indexOf("---\n", 4);
            if (frontmatterEnd < 0) {
                frontmatterEnd = existingContent.indexOf("---", 4);
            }
            String frontmatter = frontmatterEnd >= 0
                    ? existingContent.substring(0, frontmatterEnd + 4)
                    : "---\n---\n";
            String body = frontmatterEnd >= 0
                    ? existingContent.substring(frontmatterEnd + 4).trim()
                    : existingContent;

            StringBuilder summary = new StringBuilder();
            summary.append("# 会话摘要\n\n");

            // 决策时间线
            summary.append("## 决策时间线\n\n");
            summary.append("| Step | 动作 | 结果 | 耗时 |\n");
            summary.append("|------|------|------|------|\n");
            for (StepRecord step : stats.steps) {
                String outcome = step.error ? "❌" : "✅";
                summary.append("| ").append(step.index)
                        .append(" | ").append(step.action)
                        .append(" | ").append(outcome)
                        .append(" | ").append(step.elapsedMs).append("ms |\n");
            }
            summary.append("\n## 统计\n\n");
            summary.append("- 总操作: ").append(stats.totalSteps()).append(" · 成功: ")
                    .append(stats.success.get()).append(" · 失败: ").append(stats.failure.get()).append("\n");
            if (!stats.interrupts.isEmpty()) {
                summary.append("- 中断: ").append(String.join(", ", stats.interrupts)).append("\n");
            }
            summary.append("\n---\n\n");

            Files.writeString(path,
                    frontmatter + "\n" + summary + body,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("[游戏复盘] 已写入会话摘要: game={}, memoryId={}, steps={}",
                    gameName, memoryId, stats.totalSteps());
        } catch (IOException e) {
            log.warn("[游戏复盘] 写入摘要失败: game={}, memoryId={}, reason={}",
                    gameName, memoryId, e.getMessage());
        }
    }

    // ── Trace 写入 ──

    public void appendRpControlBlockTrace(String gameName,
                                          String memoryId,
                                          RpControlBlock block) {
        if (!properties.isTraceEnabled() || block == null) {
            return;
        }
        if (block.isAct()) {
            // 暂存 act 上下文供 queue trace 消费
            pendingActContexts.put(sessionKey(gameName, memoryId),
                    new ActBlockContext(block.check(), block.progress(), block.next(), block.commit()));
        }

        try {
            Path path = sessionPaths.get(sessionKey(gameName, memoryId));
            if (path == null) {
                return;
            }
            Files.writeString(
                    path,
                    renderRpControlBlock(block),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[游戏复盘] 写入 RP 控制块复盘失败: game={}, memoryId={}, reason={}",
                    gameName, memoryId, e.getMessage());
        }
    }

    /**
     * @param planText        LLM 输出的 plan 块文本（已暂存于 pendingPlans 中，由调用方传入消费后清除）
     * @param check           act block 的 check 字段
     * @param progress        act block 的 progress 字段
     * @param next            act block 的 next 字段
     * @param commit          act block 的 commit 字段
     */
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
                                 GameQueueExecutionTrace executionTrace,
                                 String planText,
                                 String check,
                                 String progress,
                                 String next,
                                 boolean commit) {
        if (!properties.isTraceEnabled()) {
            return;
        }

        try {
            String visibleContext = consumePendingRpVisibleContext(gameName, memoryId);
            Path path = sessionPaths.get(sessionKey(gameName, memoryId));
            if (path == null) {
                return;
            }
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
                            executionTrace,
                            visibleContext,
                            planText,
                            check,
                            progress,
                            next,
                            commit),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[游戏复盘] 写入操作队列复盘失败: game={}, memoryId={}, reason={}",
                    gameName, memoryId, e.getMessage());
        }
    }

    public void appendRpCommandFailureTrace(String gameName,
                                                String memoryId,
                                                String rpDo,
                                                String reason) {
        if (!properties.isTraceEnabled()) {
            return;
        }
        String visibleContext = consumePendingRpVisibleContext(gameName, memoryId);
        if (!hasText(visibleContext)) {
            return;
        }

        try {
            Path path = sessionPaths.get(sessionKey(gameName, memoryId));
            if (path == null) {
                return;
            }
            Files.writeString(
                    path,
                    renderRpCommandFailure(gameName, memoryId, visibleContext, rpDo, reason),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[游戏复盘] 写入 RP 命令失败复盘失败: game={}, memoryId={}, reason={}",
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
            Path path = sessionPaths.get(sessionKey(gameName, memoryId));
            if (path == null) {
                return;
            }
            Files.writeString(
                    path,
                    renderActionParserFailure(gameName, memoryId, rpDo, parserRaw, reason),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[游戏复盘] 写入 RP 动作解析失败复盘失败: game={}, memoryId={}, reason={}",
                    gameName, memoryId, e.getMessage());
        }
    }

    // ── 暂存数据消费（供 GameOperationQueueProcessor 使用） ──

    /**
     * 消费并清除当前 session 的暂存 plan。
     */
    public String consumePendingPlan(String gameName, String memoryId) {
        return pendingPlans.remove(sessionKey(gameName, memoryId));
    }

    /**
     * 消费并清除当前 session 的暂存 act context。
     */
    public ActBlockContext consumePendingActContext(String gameName, String memoryId) {
        return pendingActContexts.remove(sessionKey(gameName, memoryId));
    }

    private String consumePendingRpVisibleContext(String gameName, String memoryId) {
        return pendingRpVisibleContexts.remove(sessionKey(gameName, memoryId));
    }

    // ── 渲染 ──

    private String renderRpControlBlock(RpControlBlock block) {
        StringBuilder sb = new StringBuilder();
        sb.append("### RP 事件\n\n");
        sb.append("- type=").append(block.type()).append("\n");
        if (block.isVoice()) {
            sb.append("- kind=").append(block.voiceKind()).append("\n");
            if (hasText(block.say())) {
                sb.append("- say=").append(block.say().trim()).append("\n");
            }
        }
        if (block.isAct()) {
            if (hasText(block.doText())) {
                sb.append("- do=").append(block.doText().trim()).append("\n");
            }
            if (hasText(block.check())) {
                sb.append("- check=").append(block.check().trim()).append("\n");
            }
            if (hasText(block.progress())) {
                sb.append("- progress=").append(block.progress().trim()).append("\n");
            }
            if (hasText(block.next())) {
                sb.append("- next=").append(block.next().trim()).append("\n");
            }
            sb.append("- commit=").append(block.commit()).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private String renderRpCommandFailure(String gameName,
                                          String memoryId,
                                          String visibleContext,
                                          String rpDo,
                                          String reason) {
        long step = nextStep(gameName, memoryId);
        recordStep(gameName, memoryId, step, compactDo(rpDo), true, 0, reason);

        StringBuilder sb = new StringBuilder();
        sb.append("## Step ").append(step).append(" | rp_command_failure · ❌\n\n");
        appendRpVisibleContext(sb, visibleContext);
        if (hasText(rpDo)) {
            sb.append("### RP do\n\n").append(rpDo.trim()).append("\n\n");
        }
        sb.append("### 执行异常\n\n").append(nullToBlank(reason)).append("\n\n");
        return sb.toString();
    }

    private String renderActionParserFailure(String gameName,
                                             String memoryId,
                                             String rpDo,
                                             String parserRaw,
                                             String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Step ").append(nextStep(gameName, memoryId))
                .append(" | parser_failure\n\n");

        if (hasText(rpDo)) {
            sb.append("### RP do\n\n").append(rpDo.trim()).append("\n\n");
        }
        if (hasText(parserRaw)) {
            sb.append("### parser raw\n\n").append(parserRaw.trim()).append("\n\n");
        }
        sb.append("### 失败原因\n\n").append(nullToBlank(reason)).append("\n\n");
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
                               GameQueueExecutionTrace executionTrace,
                               String rpVisibleContext,
                               String planText,
                               String check,
                               String progress,
                               String next,
                               boolean commit) {
        long step = nextStep(gameName, memoryId);
        String actionSummary = compactDo(rpDo);
        boolean isError = isErrorOutcome(executionTrace, interruptReason);
        long elapsedMs = executionTrace == null ? 0 : executionTrace.elapsedMs();

        // 记录统计
        recordStep(gameName, memoryId, step, actionSummary, isError, elapsedMs, interruptReason);

        StringBuilder sb = new StringBuilder();
        sb.append("## Step ").append(step)
                .append(" | ").append(actionSummary)
                .append(" · ").append(isError ? "❌" : "✅");
        if (elapsedMs > 0) {
            sb.append(" · ").append(elapsedMs).append("ms");
        }
        sb.append("\n\n");

        appendRpVisibleContext(sb, rpVisibleContext);

        // 局面分析
        if (hasText(planText)) {
            sb.append("### 局面分析\n\n").append(planText.trim()).append("\n\n");
        }

        // 思维链
        if (hasText(reasoning)) {
            sb.append("### 思维链\n\n").append(reasoning.trim()).append("\n\n");
        }

        // 操作协议
        boolean hasProtocol = hasText(rpDo) || hasText(check) || hasText(progress) || hasText(next);
        if (hasProtocol) {
            sb.append("### 操作协议\n\n");
            if (hasText(rpDo)) {
                sb.append("- do: ").append(rpDo.trim()).append("\n");
            }
            if (hasText(check)) {
                sb.append("- check: ").append(check.trim()).append("\n");
            }
            if (hasText(progress)) {
                sb.append("- progress: ").append(progress.trim()).append("\n");
            }
            if (hasText(next)) {
                sb.append("- next: ").append(next.trim()).append("\n");
            }
            sb.append("- commit: ").append(commit).append("\n");
            sb.append("\n");
        }

        // parser
        sb.append("### parser\n\n");
        sb.append("- latency_ms=").append(parserLatencyMs).append("\n");
        if (parserEarlyCompleted) {
            sb.append("- early_completed=true\n");
        }
        if (parserEarlyCancelled) {
            sb.append("- early_cancelled=true\n");
        }
        if (hasText(parserRaw)) {
            sb.append("\n```json\n").append(parserRaw.trim()).append("\n```\n");
        }
        sb.append("\n");

        // 操作队列
        if (operations != null && !operations.isEmpty()) {
            sb.append("### 操作队列\n\n");
            for (GameOperation operation : operations) {
                sb.append("- ").append(operation.toolName());
                if (operation.args() != null) {
                    sb.append(" args=").append(operation.args());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // MCP 执行
        appendExecutionTrace(sb, executionTrace);

        // 执行结果
        if (hasText(executionResult)) {
            sb.append("### 执行结果\n\n").append(executionResult.trim()).append("\n\n");
        }
        if (hasText(interruptReason)) {
            sb.append("### 中断\n\n").append(interruptReason.trim()).append("\n\n");
        }

        return sb.toString();
    }

    private void appendRpVisibleContext(StringBuilder sb, String visibleContext) {
        if (!hasText(visibleContext)) {
            return;
        }
        sb.append("### RP 可见上下文\n\n");
        sb.append("```xml\n").append(visibleContext.trim()).append("\n```\n\n");
    }

    private void appendExecutionTrace(StringBuilder sb, GameQueueExecutionTrace trace) {
        sb.append("### MCP 执行\n\n");
        if (trace == null) {
            sb.append("无\n\n");
            return;
        }
        sb.append("- queue_id=").append(nullToBlank(trace.queueId())).append("\n");
        sb.append("- start_state=").append(stateSignature(trace.startStateType(), trace.startStateHash())).append("\n");
        if (hasText(trace.startActionWindow())) {
            sb.append("- start_action_window=").append(trace.startActionWindow().trim()).append("\n");
        }
        sb.append("- elapsed_ms=").append(trace.elapsedMs()).append("\n");
        if (hasText(trace.boundaryReason())) {
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
                    .append(" tool=").append(nullToBlank(operation.toolName()))
                    .append(" · ").append(nullToBlank(operation.outcomeKind()))
                    .append(" · ").append(operation.elapsedMs()).append("ms")
                    .append(" · before=").append(stateSignature(operation.beforeStateType(), operation.beforeStateHash()))
                    .append(" · after=").append(stateSignature(operation.afterStateType(), operation.afterStateHash()))
                    .append("\n");
            if (hasText(operation.plannedActionWindow())) {
                sb.append("  planned_window=").append(operation.plannedActionWindow().trim()).append("\n");
            }
            if (hasText(operation.currentActionWindow())) {
                sb.append("  current_window=").append(operation.currentActionWindow().trim()).append("\n");
            }
            if (hasText(operation.preconditionSignature())) {
                sb.append("  precondition=").append(operation.preconditionSignature().trim()).append("\n");
            }
            if (hasText(operation.arguments())) {
                sb.append("  args=").append(operation.arguments().trim()).append("\n");
            }
            if (hasText(operation.error())) {
                sb.append("  error=").append(operation.error().trim()).append("\n");
            } else if (hasText(operation.result())) {
                sb.append("  result=").append(operation.result().trim()).append("\n");
            }
        }
        sb.append("\n");
    }

    private void recordStep(String gameName, String memoryId, long step, String action,
                            boolean isError, long elapsedMs, String interruptReason) {
        SessionStats stats = sessionStatsMap.get(sessionKey(gameName, memoryId));
        if (stats == null) {
            return;
        }
        stats.steps.add(new StepRecord(step, action, isError, elapsedMs));
        if (isError) {
            stats.failure.incrementAndGet();
        } else {
            stats.success.incrementAndGet();
        }
        if (hasText(interruptReason)) {
            stats.interrupts.add("step " + step);
        }
    }

    // ── 工具方法 ──

    private long nextStep(String gameName, String memoryId) {
        return stepCounters
                .computeIfAbsent(sessionKey(gameName, memoryId), ignored -> new AtomicLong())
                .incrementAndGet();
    }

    private String stateSignature(String stateType, String stateHash) {
        String type = stateType == null || stateType.isBlank() ? "?" : stateType.trim();
        String hash = stateHash == null || stateHash.isBlank() ? "nohash" : stateHash.trim();
        return type + "@" + hash;
    }

    private Path tracePath(String gameName, String memoryId) {
        String safeGame = safePathSegment(gameName);
        String safeMemory = safePathSegment(memoryId);
        String timestamp = TIMESTAMP_FMT.format(Instant.now());
        return Path.of(properties.getTraceDirectory(), safeGame, timestamp + "_" + safeMemory + ".md");
    }

    private String safePathSegment(String value) {
        String normalized = value == null || value.isBlank() ? "default" : value.trim();
        return normalized.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String sessionKey(String gameName, String memoryId) {
        return nullToBlank(gameName) + "::" + nullToBlank(memoryId);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    /**
     * 从 rpDo 中提取简短的动作摘要。
     */
    private String compactDo(String rpDo) {
        if (!hasText(rpDo)) {
            return "动作";
        }
        String text = rpDo.trim();
        // 取第一句或前 30 字符
        int cutoff = Math.min(text.length(), 30);
        String snippet = text.substring(0, cutoff);
        if (cutoff < text.length()) {
            snippet += "…";
        }
        return snippet.replace("|", "/");
    }

    private boolean isErrorOutcome(GameQueueExecutionTrace trace, String interruptReason) {
        if (hasText(interruptReason)) {
            return true;
        }
        if (trace != null && trace.operations() != null) {
            return trace.operations().stream()
                    .anyMatch(op -> hasText(op.error()));
        }
        return false;
    }

    // ── 内部类型 ──

    /**
     * act block 中供复盘的关键字段。
     */
    public record ActBlockContext(String check, String progress, String next, boolean commit) {
    }

    /**
     * 单步记录（供摘要生成）。
     */
    private record StepRecord(long index, String action, boolean error, long elapsedMs) {
    }

    /**
     * 会话统计。
     */
    private static class SessionStats {
        final AtomicLong success = new AtomicLong();
        final AtomicLong failure = new AtomicLong();
        final List<StepRecord> steps = new ArrayList<>();
        final List<String> interrupts = new ArrayList<>();

        int totalSteps() {
            return steps.size();
        }
    }
}
