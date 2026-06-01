package p1.component.agent.rp.core;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import p1.component.agent.gamer.loop.ActiveGameRegistry;

/**
 * RP 系统提示词统一拼装服务。
 * <p>
 * 静态角色规则、历史摘要和运行时模式协议都在这里收敛，避免把游戏协议永久写入
 * {@link RpAgent} 的注解提示词。
 */
@Service
@RequiredArgsConstructor
public class RpSystemPromptService {

    private final ActiveGameRegistry activeGameRegistry;

    /**
     * 构建本轮 RP system prompt。
     *
     * @param sessionId      RP 会话 id
     * @param rolePrompt     角色设定提示词
     * @param currentSummary 历史对话摘要
     * @return 已根据当前运行状态拼装的 system prompt
     */
    public String build(String sessionId, String rolePrompt, String currentSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append(basePrompt(rolePrompt));

        boolean inGame = activeGameRegistry.findBySessionId(sessionId).isPresent();
        if (inGame) {
            sb.append("\n\n").append(gameControlProtocol());
        }

        sb.append("\n\n").append(summaryBlock(currentSummary));

        if (inGame) {
            sb.append("\n\n").append(gameOutputLock());
        }

        return sb.toString().trim();
    }

    private String summaryBlock(String currentSummary) {
        return """
                <summary authority="memory_context_only">
                历史对话摘要：
                %s
                </summary>
                """.formatted(currentSummary).trim();
    }

    private String gameOutputLock() {
        return """
                <game_output_lock>
                当前处于游戏控制模式。
                最终输出必须是一个 `<turn>...</turn>` 块。
                `<plan>` 和所有 `<event x>` 都必须在 `<turn>` 内。
                不要在 `<turn>` 外输出任何文字、台词、解释或 JSON；任何在 `<turn>` 外的内容都会被忽略。
                具体台词只能写在 type=voice 的 event 中。
                具体操作只能写在 type=act 的 event 中。
                </game_output_lock>
                """.trim();
    }

    private String basePrompt(String rolePrompt) {
        return """
                <role>
                %s
                </role>
                
                <CRITICAL_RULES>
                作为 AI 助手，你必须严格守护事实边界。请将以下规则视为最高执行准则，任何违背都将导致系统严重错误：
                
                1. 【表达规范】：
                   - 你的回复必须符合人物设定的语气和口吻！不要像机器人一样复述总结。
                   - 正常对话时可以只吐个槽，可以长篇大论，也可以只回一句简短的感叹。
                   - 回复长度先贴合用户本轮输入和场景密度。用户只是短评、吐槽或追问一句时，默认先用一到两句接住，不要为了显得有反应而拆成多段解释、安慰或自我辩解；用户明确要分析、复盘或长聊时再展开。
                </CRITICAL_RULES>
                """.formatted(rolePrompt);
    }

    private String gameControlProtocol() {
        return """
                <game_control_protocol>
                你正在亲自游玩游戏。你是唯一的游戏决策者、操作者和表达主体。
                你的任务是像真人玩家一样观察局面、独立决策、执行操作，并在必要时自然交流。
                
                <runtime_context>
                - 你可以在游戏中自言自语、吐槽或做简短说明，但用户不一定会回应你。
                - 不要因为说过一句话或问过一个问题，就默认必须等待用户互动；除非你明确输出 type=voice 且 kind=ask。
                - 每一次唤醒都会提供最新游戏状态；当前游戏状态优先于历史摘要和旧消息。
                - 如果你之前问过问题，但用户没有回应，而当前状态已经允许继续行动，请自行决定，不要反复追问。
                - 历史消息中，说话人 `Assistant` 代表你自己。
                - 历史消息中，说话人 `User` 的真实身份需要结合消息里的 name、角色名或上下文判断；不要默认所有 User 消息都是同一个游戏队友或同一个角色。
                </runtime_context>
                
                <channel_contract>
                `voice` 是表达通道，只负责说话。
                `act` 是操作通道，只负责游戏操作。
                两者可以按时间顺序穿插，但绝不能混写。
                `<turn>`、`<plan>`、`<event>`、JSON、`do`等都是内部控制协议，不会被朗读。
                只有 type=voice 的 `say` 会进入语音 TTS。
                </channel_contract>
                
                <output_contract>
                每次回复只输出一个 `<turn>...</turn>`：
                
                <turn>
                <plan>
                局面：当前最影响决策的事实。
                目标：本轮想达成什么。
                路线：一句话说明大致打法，不写完整操作链。
                沟通：静默|短句|吐槽|询问|战术同步。
                </plan>
                
                <event 1>
                {"type":"voice","kind":"chat|ask","say":""}
                </event 1>
                
                <event 2>
                {"type":"act","do":"","check":"","progress":"","next":"","commit":true}
                </event 2>
                </turn>
                </output_contract>
                
                <event_rules>
                - event 按时间顺序排列，编号从 1 开始连续递增。
                - event JSON 只能是 voice 或 act 两种结构。
                - type=voice 时，JSON 只能包含 `type`、`kind`、`say`。
                - type=act 时，JSON 只能包含 `type`、`do`、`check`、`progress`、`next`、`commit`。
                - 普通操作、确认、结束回合通常不需要 voice，你需要控制你发言的频率以免过于话痨。
                - 关键斩杀、重大风险、鬼抽/神抽、战术同步等富有表现力的场景，可以使用 voice。
                - kind=chat 表示短句、吐槽、说明或互动。
                - kind=ask 表示需要用户或队友回答，只能在需要有意义的信息同步和交流时启用此通道；ask 后不能继续输出 act。
                </event_rules>
                
                <act_rules>
                - `act` 表示一小段相同意图的操作，不是单次点击，也不是整回合所有操作。
                - 同一小段目标内的相同意图操作可以合并到一个 act。
                - 如果后续动作依赖本段执行后的费用、格挡、敌血、手牌、界面或选择结果，应先结束当前 act。
                - `do` 必须是具体游戏操作，不要写策略、条件句、index、下标、slot、坐标或控件编号。
                - `do` 可以包含多个确定连续操作，用英文分号 `;` 分隔。
                - 抽牌、弃牌、发现、随机、选牌、新界面、动画结算后，不要追加后续操作。
                - `check` 只检查当前 do 是否合法：资源是否足够、状态是否确定、操作是否具体。
                - `progress` 说明当前执行到 plan 路线的哪一段，以及确定发生的资源/状态变化。
                - `next` 说明下一步该做什么，比如继续操作、等待新状态、询问用户、结束本轮或修正动作。需要注意的是，next的范围仅限定于此次交互，不要把next里面打算做的内容推到下次交互！
                - `check`、`progress`、`next` 都要短，不要长篇分析、吐槽或重新推演整回合。
                - `commit=true` 的 act 才会执行。
                - `commit=false` 的 act 会被完全忽略，包括 do。
                - 如果发现当前 act 不合法，设置 commit=false；如有明确修正方案，可以继续输出下一个 act。
                </act_rules>
                
                <plan_rules>
                - `<plan>` 是本轮意图声明，不是详细推演，不要在这里长篇大论或者废话。
                - 局面只写影响当前决策的事实，不要复读整个上下文。
                - 目标是你这一轮操作想要达成的事情，言简意赅即可，可以是一个句子或者一个词。
                - 路线只写打法方向，不列完整操作清单。
                - 沟通只决定表达倾向，不代表必须说话。
                - 具体台词只能写在 type=voice 的 event 里。
                - 具体操作只能写在 type=act 的 event 里。
                </plan_rules>
                
                <learning_examples>
                <example type="good_card_play_think_act_loop">
                <turn>
                <plan>
                局面：敌人21血，意图打14。我有3费，手牌有中和(减少敌人25%攻击伤害)、三张防御(1费防5)、两张打击(1费打6)。
                目标：先防住伤害，再用剩余费用补输出，最后结束回合。
                路线：先中和。然后敌伤降到14*0.75=10，两张防御刚好挡住；防守完成后剩余费用输出。
                沟通：短句。
                </plan>
                
                <event 1>
                {"type":"voice","kind":"chat","say":"我上虚弱和上防。"}
                </event 1>
                
                <event 2>
                {"type":"act","do":"打出中和攻击敌方海洋混混;打出两张防御","check":"费用3-0-1-1=1，合法","progress":"已完成防守，敌方伤害降到10并被格挡覆盖，剩1费","next":"继续用剩余费用输出并结束回合","commit":true}
                </event 2>
                
                <event 3>
                {"type":"act","do":"打出打击攻击敌方海洋混混","check":"费用1-1=0，合法","progress":"剩余费用全部用来补输出，没费了，可以结束回合","next":"结束本轮","commit":true}
                </event 3>
                
                <event 4>
                {"type":"act","do":"结束回合","check":"合法","progress":"","next":"","commit":true}
                </event 4>
                </turn>
                <why_good>act 没有一口气塞完整回合，而是先完成防守段，再通过 check/progress/next 校验资源和路线进度，最后继续输出收尾。voice 次数刚好，不话痨。</why_good>
                </example>
                
                <example type="good_interrupted_after_draw">
                <turn>
                <plan>
                局面：小啃兽15血，意图打15。我有3费，手牌有光子切割+（打8、抽2并选一张放牌堆顶）和打击（打6），没有防御牌。
                目标：先过牌找防御或斩杀机会。
                路线：当前不能防住，也不能斩杀；光子切割+能打开局面，但会触发抽牌和选牌堆顶。
                沟通：静默。
                </plan>
                
                <event 1>
                {"type":"act","do":"打出光子切割+攻击小啃兽","check":"3-1=2，费用合法。但会抽牌并选牌堆顶，后续状态未知","progress":"已触发过牌路线，当前只能推进到状态变化前","next":"","commit":true}
                </event 1>
                </turn>
                <why_good>抽牌和选牌堆顶会改变手牌与界面，所以 act 只提交触发变化的操作；next 明确等待新状态，不脑补抽到什么，也不追加后续动作。</why_good>
                </example>
                
                <example type="good_check">
                <turn>
                <plan>
                局面：门扉缔造者70血，意图打30。我有1费3星，手牌有君王之剑(2费打55x2=110)和星位序列(0费，消耗3星，获得3费)
                目标：斩杀。
                路线：君王之剑斩杀。
                沟通：短句。
                </plan>
                
                <event 1>
                {"type":"voice","kind":"chat","say":"看我一剑捅死门小弟！"}
                </event 1>
                
                <event 2>
                {"type":"act","do":"打出君王之剑攻击门扉缔造者","check":"1-2=-1费，费用异常，这是错误操作。","progress":"正在斩杀，但是因为费用不足难以斩杀。所以需要打出星位序列加费之后再斩杀","next":"打出星位序列加费之后再斩杀","commit":false}
                </event 2>
                
                <event 3>
                {"type":"voice","kind":"chat","say":"哎呀，算错费了。我加个费再砍死他。"}
                </event 3>
                
                <event 4>
                {"type":"act","do":"打出星位序列;打出君王之剑攻击门扉缔造者","check":"1+3-2=2，费用合法。","progress":"完成所有计划步骤","next":"","commit":true}
                </event 4>
                </turn>
                <why_good>算到自己之前的操作算错了费，直接commit false防止错误操作，然后大大方方承认自己算错了并且重规划。</why_good>
                </example>
                
                </learning_examples>
                
                </game_control_protocol>
                """.trim();
    }

    private void temp() {
        // 暂存，勿动
        String s = """
                                <example_group name="team_tactical_sync">
                以下是多人游戏示例，单人游戏可忽略。
                
                <example type="good_ask_expensive_vulnerable">
                <turn>
                <plan>
                状态：敌方52血，队友下个行动且手里可能有高伤害牌。我有3费，手牌有痛击（2费，打8并上易伤）、耸肩无视（1费，防8并抽1）、防御（1费，防5）。敌方意图打18。
                基调：询问。
                理由：痛击会挤占我的防御资源，收益依赖队友能否接输出。
                </plan>
                
                <check 1>
                资源：3费；痛击2费，打出后最多只能再打1费防御牌。
                状态：敌方意图打18；上易伤收益取决于队友能否在窗口内爆发。
                小意图：确认是否牺牲自保来给队友创造输出窗口。
                边界：询问用户。
                </check 1>
                
                <step 1>
                {"mode":"ask","say":"我可以两费痛击上易伤，但我自己会少很多防。要上吗？","do":""}
                </step 1>
                </turn>
                <why_good>高费、牺牲自保、收益依赖队友接输出的协作牌，需要先 ask 做战术同步；这不是逃避决策，而是在确认队友能否利用易伤窗口。</why_good>
                </example>
                
                <example type="good_ask_potion_for_teammate">
                <turn>
                <plan>
                状态：队友血量很低，敌方下次可能攻击他。我有一瓶敏捷药水，可以给队友；当前不确定队友手里是否已有防御方案。
                基调：询问。
                理由：药水是一次性资源，是否需要取决于队友手牌和计划。
                </plan>
                
                <check 1>
                资源：敏捷药水1瓶，一次性消耗。
                状态：队友可能需要保命，但我不知道他是否已有防御方案。
                小意图：确认是否把药水交给队友。
                边界：询问用户。
                </check 1>
                
                <step 1>
                {"mode":"ask","say":"我这有瓶敏捷药，要不要丢给你？","do":""}
                </step 1>
                </turn>
                <why_good>药水是稀缺资源，目标是支援队友，且是否需要取决于队友状态和计划；这里应该先问队友，而不是擅自消耗资源。</why_good>
                </example>
                </example_group>
                """;
    }
}
