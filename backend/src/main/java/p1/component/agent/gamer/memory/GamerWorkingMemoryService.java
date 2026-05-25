package p1.component.agent.gamer.memory;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.bridge.GameBridgeActionStatus;
import p1.config.mcp.GamerMemoryProperties;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * gamer 的纯内存工作记忆服务。
 * <p>
 * 该服务独立于 RP 聊天记忆，只保存决策摘要和执行反馈；最新游戏状态由桥接层每轮注入，不进入这里。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GamerWorkingMemoryService {

    private final GamerMemoryProperties properties;
    private final GamerMemoryCompressorAiService compressor;
    private final Map<String, SessionMemory> memories = new ConcurrentHashMap<>();

    @Lazy
    @Autowired
    private GamerWorkingMemoryService self;

    /**
     * 观察最新状态，用于发现楼层或大状态变化并触发阶段压缩。
     *
     * @param gameName 游戏名
     * @param memoryId 游戏会话 key
     * @param state    桥接层刚获取到的最新状态
     */
    public void observeState(String gameName, String memoryId, GameStateSnapshot state) {
        if (state == null) {
            return;
        }

        SessionMemory memory = memory(gameName, memoryId);
        String stageKey = extractStageKey(state);
        synchronized (memory) {
            // 第一次观察只建立基准，不产生摘要。
            if (memory.lastStageKey == null || memory.lastStageKey.isBlank()) {
                memory.lastStageKey = stageKey;
                return;
            }

            // 楼层或大状态变化时，旧的近期决策通常已经不适合作为完整上下文继续展开。
            if (!stageKey.isBlank() && !stageKey.equals(memory.lastStageKey)) {
                compressRecentIntoStage(memory, "游戏阶段变化：" + memory.lastStageKey + " -> " + stageKey);
                memory.lastStageKey = stageKey;
            }
        }
    }

    /**
     * 记录一次队列提交和执行结果。
     *
     * @param gameName        游戏名
     * @param memoryId        游戏会话 key
     * @param status          本次队列状态
     * @param summary         agent 提交的决策摘要
     * @param reasoning       模型返回的 reasoning_content；没有时为空
     * @param operations      agent 提交的操作队列
     * @param result          桥接层执行结果
     * @param interruptReason 队列中断原因；没有中断时为空
     */
    public void recordQueueResult(String gameName,
                                  String memoryId,
                                  GameBridgeActionStatus status,
                                  String summary,
                                  String reasoning,
                                  List<GameOperation> operations,
                                  String result,
                                  String interruptReason) {
        SessionMemory memory = memory(gameName, memoryId);
        synchronized (memory) {
            // 只记录 agent 可解释的决策与桥接执行反馈，不记录完整状态。
            GamerDecisionRecord record = new GamerDecisionRecord(
                    Instant.now(),
                    gameName,
                    memoryId,
                    status == null ? GameBridgeActionStatus.UNKNOWN : status,
                    normalize(summary),
                    normalize(reasoning),
                    renderOperations(operations),
                    normalize(result),
                    normalize(interruptReason));
            memory.recentDecisions.addLast(record);
            memory.decisionsSinceStageCompression++;

            // 近期窗口超过上限或达到压缩频率时，压缩进阶段摘要。
            if (shouldCompressStage(memory)) {
                compressRecentIntoStage(memory, "近期决策达到压缩阈值");
            }
        }
    }

    /**
     * 渲染可注入给 gamer agent 的工作记忆。
     *
     * @param gameName 游戏名
     * @param memoryId 游戏会话 key
     * @return 面向 prompt 的工作记忆文本
     */
    public String renderMemory(String gameName, String memoryId) {
        SessionMemory memory = memories.get(memoryKey(gameName, memoryId));
        if (memory == null) {
            return "暂无历史工作记忆。只根据最新游戏状态决策。";
        }

        synchronized (memory) {
            StringBuilder sb = new StringBuilder();
            sb.append("说明：这是 gamer 的纯内存工作记忆，只记录历史决策摘要和执行反馈；最新游戏状态以 <latest_game_state> 为准。\n");

            // run 摘要保存跨阶段仍有意义的长期倾向。
            appendSection(sb, "run_summary", memory.runSummary);

            // stage 摘要保存当前阶段已经压缩过的近期决策。
            appendSection(sb, "stage_summary", memory.stageSummary);

            // 近期决策保留未压缩的最新行动意图，便于模型连续决策。
            if (memory.recentDecisions.isEmpty()) {
                appendSection(sb, "recent_decisions", "暂无未压缩的近期决策。");
            } else {
                sb.append("<recent_decisions>\n")
                        .append(renderRecords(memory.recentDecisions))
                        .append("</recent_decisions>\n");
            }
            return sb.toString().trim();
        }
    }

    /**
     * 获取或创建指定会话的内存容器。
     *
     * @param gameName 游戏名
     * @param memoryId 游戏会话 key
     * @return 会话内存容器
     */
    private SessionMemory memory(String gameName, String memoryId) {
        String key = memoryKey(gameName, memoryId);
        return memories.computeIfAbsent(key, k -> {
            SessionMemory m = new SessionMemory(gameName);
            m.memoryKey = k;
            return m;
        });
    }

    /**
     * 构建内存 map 的 key。
     *
     * @param gameName 游戏名
     * @param memoryId 游戏会话 key
     * @return 稳定的内存 key
     */
    private String memoryKey(String gameName, String memoryId) {
        String safeMemoryId = memoryId == null || memoryId.isBlank() ? "default" : memoryId;
        return gameName + "::" + safeMemoryId;
    }

    /**
     * 从状态中提取用于阶段变化判断的轻量 key。
     *
     * @param state 游戏状态快照
     * @return 大状态和楼层组成的阶段 key
     */
    private String extractStageKey(GameStateSnapshot state) {
        JsonNode json = state.json();
        String stateType = normalize(state.stateType());
        String act = "";
        String floor = "";
        if (json != null) {
            // 通用规则只看大状态和 run 进度；具体游戏的细粒度变化交给 adapter 监视。
            stateType = firstNonBlank(stateType, text(json.path("state_type")));
            act = text(json.path("run").path("act"));
            floor = text(json.path("run").path("floor"));
        }
        return "state_type=" + firstNonBlank(stateType, "unknown")
                + ", act=" + firstNonBlank(act, "?")
                + ", floor=" + firstNonBlank(floor, "?");
    }

    /**
     * 判断当前近期窗口是否需要压缩。
     *
     * @param memory 会话内存容器
     * @return true 表示需要压缩进阶段摘要
     */
    private boolean shouldCompressStage(SessionMemory memory) {
        int recentLimit = Math.max(1, properties.getRecentDecisionLimit());
        int compressInterval = Math.max(1, properties.getStageCompressDecisionInterval());
        return memory.recentDecisions.size() > recentLimit
                || memory.decisionsSinceStageCompression >= compressInterval;
    }

    /**
     * 把近期决策压缩进阶段摘要。
     * <p>
     * 在调用者持有的 synchronized(memory) 内执行数据捕获和状态重置（毫秒级），
     * 然后通过 @Async 代理将 AI 调用发射到线程池（秒级），不阻塞游戏循环。
     */
    private void compressRecentIntoStage(SessionMemory memory, String trigger) {
        if (memory.recentDecisions.isEmpty()) {
            return;
        }
        if (!memory.compressionInProgress.compareAndSet(false, true)) {
            return;
        }

        String decisions = renderRecords(memory.recentDecisions);
        String prevStageSummary = memory.stageSummary;
        String prevRunSummary = memory.runSummary;
        boolean chainRun = memory.stageCompressionsSinceRunCompression + 1 >= Math.max(1, properties.getRunCompressStageInterval());

        memory.recentDecisions.clear();
        memory.decisionsSinceStageCompression = 0;
        memory.stageCompressionsSinceRunCompression++;

        // 通过代理发射异步任务；AI 调用不在此线程上执行。
        self.executeAsyncCompression(memory.memoryKey, trigger, decisions, prevStageSummary, prevRunSummary, chainRun);
    }

    /**
     * 异步执行阶段（及可选 run）压缩。
     * <p>
     * AI 调用在线程池中执行；需要修改 SessionMemory 时重新获取锁。
     */
    @Async("asyncTaskExecutor")
    public void executeAsyncCompression(String memoryKey, String trigger,
                                         String decisions, String prevStageSummary,
                                         String prevRunSummary, boolean chainRun) {
        SessionMemory memory = memories.get(memoryKey);
        if (memory == null) {
            return;
        }

        // ── 阶段压缩（异步线程，无锁） ──
        String fallback = fallbackStageSummary(prevStageSummary, trigger, decisions);
        String newStageSummary;
        try {
            String compressed = compressor.compressStage(prevStageSummary, trigger, decisions);
            newStageSummary = firstNonBlank(normalize(compressed), fallback);
        } catch (Exception e) {
            log.warn("[gamer记忆] 异步阶段摘要压缩失败: game={}, reason={}", memory.gameName, e.getMessage());
            newStageSummary = fallback;
        }

        // ── 更新阶段摘要，判断是否需要 run 压缩 ──
        String runSummaryBefore;
        boolean shouldCompressRun;
        synchronized (memory) {
            memory.stageSummary = newStageSummary;
            shouldCompressRun = chainRun && memory.stageCompressionsSinceRunCompression >= Math.max(1, properties.getRunCompressStageInterval());
            runSummaryBefore = shouldCompressRun ? memory.runSummary : "";
        }

        // ── run 压缩（异步线程，无锁） ──
        if (shouldCompressRun) {
            String runFallback = fallbackRunSummary(runSummaryBefore, newStageSummary);
            String newRunSummary;
            try {
                String compressed = compressor.compressRun(runSummaryBefore, newStageSummary);
                newRunSummary = firstNonBlank(normalize(compressed), runFallback);
            } catch (Exception e) {
                log.warn("[gamer记忆] 异步 run 摘要压缩失败: game={}, reason={}", memory.gameName, e.getMessage());
                newRunSummary = runFallback;
            }

            synchronized (memory) {
                memory.runSummary = newRunSummary;
                memory.stageSummary = "";
                memory.stageCompressionsSinceRunCompression = 0;
            }
        }

        memory.compressionInProgress.set(false);
    }

    /**
     * 将操作队列渲染成轻量摘要。
     *
     * @param operations agent 提交的操作队列
     * @return 操作摘要列表
     */
    private List<String> renderOperations(List<GameOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            return List.of();
        }
        List<String> rendered = new ArrayList<>();
        for (GameOperation operation : operations) {
            String args = operation.args() == null ? "{}" : operation.args().toString();
            String note = normalize(operation.note());
            rendered.add(operation.toolName() + " args=" + args + (note.isBlank() ? "" : " | 意图=" + note));
        }
        return rendered;
    }

    /**
     * 渲染一组决策记录。
     *
     * @param records 决策记录集合
     * @return 适合注入 prompt 或压缩器的文本
     */
    private String renderRecords(Iterable<GamerDecisionRecord> records) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (GamerDecisionRecord record : records) {
            // 每条记录保持“决策说明 -> 操作 -> 结果”的固定顺序，便于压缩器提取因果。
            sb.append(index++).append(". status=").append(record.status())
                    .append(" | time=").append(record.timestamp())
                    .append("\n   决策说明：").append(blankText(record.summary()));
            if (record.reasoning() != null && !record.reasoning().isBlank()) {
                sb.append("\n   模型推理：").append(record.reasoning());
            }
            sb.append("\n   操作：").append(record.operations().isEmpty() ? "无" : String.join("; ", record.operations()))
                    .append("\n   执行结果：").append(blankText(record.result()));
            if (record.interruptReason() != null && !record.interruptReason().isBlank()) {
                sb.append("\n   中断原因：").append(record.interruptReason());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 构建阶段压缩失败时的降级摘要。
     *
     * @param previousSummary 既有阶段摘要
     * @param trigger         压缩触发原因
     * @param decisions       近期决策文本
     * @return 不依赖模型的阶段摘要
     */
    private String fallbackStageSummary(String previousSummary, String trigger, String decisions) {
        StringBuilder sb = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append(previousSummary.trim()).append("\n");
        }
        sb.append("- ").append(trigger).append("；近期决策摘要如下：\n").append(decisions.trim());
        return sb.toString().trim();
    }

    /**
     * 构建 run 压缩失败时的降级摘要。
     *
     * @param previousRunSummary 既有 run 摘要
     * @param stageSummary       阶段摘要
     * @return 不依赖模型的 run 摘要
     */
    private String fallbackRunSummary(String previousRunSummary, String stageSummary) {
        StringBuilder sb = new StringBuilder();
        if (previousRunSummary != null && !previousRunSummary.isBlank()) {
            sb.append(previousRunSummary.trim()).append("\n");
        }
        sb.append("- 合并阶段摘要：\n").append(stageSummary.trim());
        return sb.toString().trim();
    }

    /**
     * 追加一个 XML 风格的 prompt 片段。
     *
     * @param sb      输出缓冲区
     * @param tag     片段标签
     * @param content 片段内容
     */
    private void appendSection(StringBuilder sb, String tag, String content) {
        sb.append("<").append(tag).append(">\n")
                .append(content == null || content.isBlank() ? "暂无。" : content.trim())
                .append("\n</").append(tag).append(">\n");
    }

    /**
     * 提取 JSON 节点的文本形式。
     *
     * @param node JSON 节点
     * @return 节点文本，缺失时返回空字符串
     */
    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.asText("");
    }

    /**
     * 标准化可选文本。
     *
     * @param value 原始文本
     * @return 去除首尾空白后的文本
     */
    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 返回第一个非空文本。
     *
     * @param values 候选文本
     * @return 第一个非空文本；如果都为空，返回空字符串
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    /**
     * 用于渲染空字段的占位文本。
     *
     * @param value 原始文本
     * @return 非空文本或“无”
     */
    private String blankText(String value) {
        return value == null || value.isBlank() ? "无" : value;
    }

    /**
     * 单个游戏会话的工作记忆容器。
     */
    private static class SessionMemory {
        private final String gameName;
        private String memoryKey = "";
        private final Deque<GamerDecisionRecord> recentDecisions = new ArrayDeque<>();
        private String runSummary = "";
        private String stageSummary = "";
        private String lastStageKey = "";
        private int decisionsSinceStageCompression = 0;
        private int stageCompressionsSinceRunCompression = 0;
        private final AtomicBoolean compressionInProgress = new AtomicBoolean(false);

        private SessionMemory(String gameName) {
            this.gameName = gameName;
        }
    }

}
