package p1.component.agent.gamer.memory;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * gamer 工作记忆压缩 AI 服务。
 * <p>
 * 该服务不接入聊天记忆，只做一次性摘要压缩，避免与 RP 记忆或 collecting 流程共享上下文。
 */
public interface GamerMemoryCompressorAiService {

    /**
     * 将近期决策压缩进阶段摘要。
     *
     * @param previousSummary 既有阶段摘要
     * @param trigger         本次压缩触发原因
     * @param decisions       需要压缩的近期决策记录
     * @return 新的阶段摘要正文
     */
    @SystemMessage("""
            你是游戏 AI 的工作记忆压缩器。
            你的任务是把近期决策记录压缩成“阶段摘要”，供后续游戏决策参考。

            要求：
            1. 只保留会影响后续决策的信息：当前策略倾向、已尝试的操作、重要约束、下一步应注意事项。
            2. 不要编造游戏状态；如果记录里没有事实，不要补充。
            3. 不要保存完整状态 JSON、工具 schema 或重复日志。
            4. 输出中文摘要正文，不要输出 JSON，不要解释压缩过程。
            5. 使用 3 到 6 条要点，保持紧凑。
            """)
    @UserMessage("""
            <previous_stage_summary>
            {{previousSummary}}
            </previous_stage_summary>

            <compress_trigger>
            {{trigger}}
            </compress_trigger>

            <recent_decisions>
            {{decisions}}
            </recent_decisions>
            """)
    String compressStage(@V("previousSummary") String previousSummary,
                         @V("trigger") String trigger,
                         @V("decisions") String decisions);

    /**
     * 将阶段摘要压缩进 run 摘要。
     *
     * @param previousRunSummary 既有 run 摘要
     * @param stageSummary       需要并入的阶段摘要
     * @return 新的 run 摘要正文
     */
    @SystemMessage("""
            你是游戏 AI 的长期 run 工作记忆压缩器。
            你的任务是把阶段摘要合并成“run 摘要”，供后续游戏决策参考。

            要求：
            1. 只保留跨楼层、跨战斗仍有意义的信息：核心策略、已验证/失败的打法、长期风险、资源倾向、后续优先级。
            2. 不要保存完整状态 JSON、工具 schema 或重复日志。
            3. 不要编造游戏事实。
            4. 输出中文摘要正文，不要输出 JSON，不要解释压缩过程。
            5. 使用 4 到 8 条要点，保持紧凑。
            """)
    @UserMessage("""
            <previous_run_summary>
            {{previousRunSummary}}
            </previous_run_summary>

            <stage_summary_to_merge>
            {{stageSummary}}
            </stage_summary_to_merge>
            """)
    String compressRun(@V("previousRunSummary") String previousRunSummary,
                       @V("stageSummary") String stageSummary);
}
