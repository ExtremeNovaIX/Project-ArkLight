package p1.component.agent.task.supervisor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import p1.component.agent.task.context.TaskSupervisorToolRenderer;
import p1.component.agent.task.state.TaskBlackboard;
import p1.component.agent.task.state.TaskSupervisorBlackboardAppendResult;
import p1.component.agent.task.state.TaskSupervisorBlackboardEntry;
import p1.component.agent.task.state.TaskSupervisorRoundBudget;
import p1.component.agent.tools.MemorySearchTools;

@RequiredArgsConstructor
public final class TaskSupervisorToolbox {

    private final MemorySearchTools memorySearchTools;
    private final TaskBlackboard blackboard;
    private final TaskSupervisorRoundBudget roundBudget;
    private final ObjectMapper objectMapper;
    private final TaskSupervisorToolRenderer toolRenderer = new TaskSupervisorToolRenderer();

    @Tool("""
            <tool_description>
            长期记忆的语义检索工具。底层采用向量相似度匹配。
            为了突破单一关键词的局限并防止语义偏移，你必须采用“核心提取 + 同义扩展”的策略来构建查询语句。
            </tool_description>
            
            <query_expansion_rules>
                <rule>【核心提取】：精准提取目标记忆中的专有名词（人名、地名、特定物品）和核心事件词。</rule>
                <rule>【同义扩展】：在核心词之后，必须补充 2-3 个相关的动作、情绪、身份描述或同义词，以增加向量命中面的广度。</rule>
                <rule>【禁止长句与幻觉】：绝不允许输入完整的陈述句或假想的剧情片段。必须提炼为高密度的词组序列（可用空格分隔）。</rule>
                <rule>【剥离指令噪音】：绝不允许包含“请查询”、“搜索关于”、“回忆一下”、“用户问了”等无意义的系统指令词。</rule>
            </query_expansion_rules>
            
            <examples>
                <example>
                    <scenario>用户问：“上次我们在后山遇到了谁来着？”</scenario>
                    <bad_input_1>请查询我们在后山遇到的人。</bad_input_1> (包含无效指令噪音)
                    <bad_input_2>我们在后山探险时遇到了一个叫暗影的神秘人。</bad_input_2> (长句幻觉，导致向量中心偏移)
                    <good_input>后山 遇到 陌生人 神秘人物 探险 剑客</good_input> (核心实体 + 合理的发散扩展)
                </example>
                <example>
                    <scenario>用户问：“上个星期我为什么那么难过？”</scenario>
                    <bad_input_1>用户上个星期难过的原因是什么</bad_input_1>
                    <good_input>难过 伤心 情绪低落 哭泣 安慰 陪伴 挫折</good_input>
                </example>
            </examples>
            """)
    public String searchLongTermMemory(@NonNull String query) {
        int round = roundBudget.claimNextRound("searchLongTermMemory");
        String request = query.trim();
        try {
            MemorySearchTools.MemorySearchResult result = memorySearchTools.searchLongTermMemory(request);
            TaskSupervisorBlackboardAppendResult appendResult = blackboard.appendMethodSuccess(
                    "searchLongTermMemory",
                    request,
                    toolRenderer.summarizeMemorySearchResult(result),
                    renderJson(result)
            );
            return toolRenderer.renderToolResult(round, roundBudget.maxRounds(), appendResult);
        } catch (RuntimeException e) {
            TaskSupervisorBlackboardAppendResult appendResult = blackboard.appendMethodError(
                    "searchLongTermMemory",
                    request,
                    toolRenderer.buildErrorSummary(e),
                    renderJson(new ToolErrorPayload("ERROR", renderError(e)))
            );
            return toolRenderer.renderToolResult(round, roundBudget.maxRounds(), appendResult);
        }
    }

    @Tool("""
            从黑板中移除一个当前判断为无用、误导、重复或不再需要的 evidence。
            传入必须是已经存在的 evidenceId，例如 method.searchLongTermMemory#01。
            """)
    public String removeBlackboardEvidence(@NonNull String evidenceId) {
        int round = roundBudget.claimNextRound("removeBlackboardEvidence");
        String normalizedEvidenceId = evidenceId.trim();
        TaskSupervisorBlackboardEntry removedEntry = blackboard.removeEvidence(normalizedEvidenceId);
        return toolRenderer.renderRemovalResult(
                round,
                roundBudget.maxRounds(),
                normalizedEvidenceId,
                removedEntry,
                blackboard.snapshotEntries().size()
        );
    }

    int usedRounds() {
        return roundBudget.usedRounds();
    }

    int maxRounds() {
        return roundBudget.maxRounds();
    }

    private String renderError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return exception.getClass().getSimpleName() + ": " + message.trim();
    }

    private String renderJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize task supervisor tool payload", e);
        }
    }

    private record ToolErrorPayload(String status, String message) {
    }
}
