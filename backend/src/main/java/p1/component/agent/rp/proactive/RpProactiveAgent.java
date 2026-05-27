package p1.component.agent.rp.proactive;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * RP 主动发言生成器。
 * <p>
 * 该服务不绑定 RP chat memory；调用方只把最终说出口的文本写回记忆，
 * 避免内部触发说明污染角色对话历史。
 */
public interface RpProactiveAgent {

    /**
     * 根据主动触发上下文生成一句角色发言。
     *
     * @param rolePrompt     角色提示词
     * @param currentSummary RP 历史摘要
     * @param recentContext  当前 RP 会话的短期上下文
     * @param gameContext    当前游戏上下文；非游戏触发时可为空
     * @param triggerKind    触发类型
     * @param triggerContext 触发说明
     * @return RP 最终说出口的文本
     */
    @SystemMessage("""
            <role>
            {{rolePrompt}}
            </role>

            <summary>
            {{currentSummary}}
            </summary>

            你正在主动开口，不是在回复一条新的用户消息。
            你可以自言自语、轻微感叹、向对方搭一句话，或者表达正在玩游戏时自然冒出的念头。
            主动开口默认要短：优先一句话，确实需要转折时最多两句短话，不要主动铺成多段安慰、复盘或邀约。
            游戏触发时，游戏行动属于你自己已经做过的事，不要说成用户替你选牌、出牌或规划路线。
            你不是游戏决策模块。不要在主动发言里分析费用、牌序、斩杀线、路线收益或下一批操作；只表达自然的人格反应。
            游戏触发时要结合最近对话和游戏行动历史形成自然反应，而不是机械复述最后一条事件。
            游戏触发时发言要短平快：除非触发内容确实需要强调，否则不要输出正常人类语速下 5 秒内说不完的内容。
            输出必须是角色最终说出口的文本，不要解释触发机制，不要提到系统、agent、工具、队列、MCP、日志或 JSON。
            如果历史里残留中断、校验失败、监视器、状态不同步、接口反馈等技术过程，把它们视为不可说出的内部噪声；不要把“系统拦住了我”“校验报错了”这类内容演成游戏经历。
            不要伪造用户刚刚说过的话；没有明确依据时不要编造具体外部事实。
            不要添加句首括号提示、舞台提示或动作说明，直接输出要说的话。
            """)
    @UserMessage("""
            <proactive_trigger>
            kind={{triggerKind}}
            {{triggerContext}}
            </proactive_trigger>

            <recent_context>
            {{recentContext}}
            </recent_context>

            <current_game_context>
            {{gameContext}}
            </current_game_context>

            现在自然开口一次。
            """)
    TokenStream speak(@V("rolePrompt") String rolePrompt,
                      @V("currentSummary") String currentSummary,
                      @V("recentContext") String recentContext,
                      @V("gameContext") String gameContext,
                      @V("triggerKind") String triggerKind,
                      @V("triggerContext") String triggerContext);
}
