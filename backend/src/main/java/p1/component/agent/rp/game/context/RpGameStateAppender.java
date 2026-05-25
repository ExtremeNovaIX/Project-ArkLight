package p1.component.agent.rp.game.context;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.GameSessionKey;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.loop.ActiveGameSession;
import p1.component.agent.gamer.projection.GamerActionSnapshotService;
import p1.component.agent.interaction.GameCoordinationService;
import p1.component.agent.rp.context.RpRuntimeMessageInsertSupport;
import p1.utils.SessionUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * RP 请求的游戏动态状态注入器。
 * <p>
 * 该组件只在当前 RP 会话存在活跃游戏循环时追加状态消息，让 RP 在同一人格下知道
 * “自己正在玩游戏”，并且只在游戏模式下看到游戏控制工具。
 */
@Component
@RequiredArgsConstructor
public class RpGameStateAppender {

    private final ActiveGameRegistry activeGameRegistry;
    private final RpCurrentGameContextService currentGameContextService;
    private final GamerActionSnapshotService gamerActionSnapshotService;
    private final GameCoordinationService gameCoordinationService;

    /**
     * 按需向 RP 请求追加游戏模式上下文。
     *
     * @param request  原始聊天请求
     * @param memoryId RP 会话 id
     * @return 已追加游戏状态的请求；非游戏模式时原样返回
     */
    public ChatRequest augment(ChatRequest request, Object memoryId) {
        if (request == null || memoryId == null || request.messages().isEmpty()) {
            return request;
        }

        String sessionId = SessionUtil.normalizeSessionId(memoryId.toString());
        Optional<ActiveGameSession> activeSession = activeGameRegistry.findBySessionId(sessionId);
        if (activeSession.isEmpty()) {
            return request;
        }

        List<ChatMessage> updatedMessages = new ArrayList<>(request.messages());
        // 游戏运行时背景必须在本轮用户消息之前注入，保证用户刚说的话仍是最后一个强信号。
        updatedMessages.add(
                RpRuntimeMessageInsertSupport.beforeCurrentUserMessage(updatedMessages),
                AiMessage.from(buildGameModeContext(activeSession.get()))
        );
        return request.toBuilder()
                .messages(updatedMessages)
                .build();
    }

    /**
     * 渲染紧凑的游戏模式上下文。
     *
     * @param session 当前活跃游戏会话
     * @return 注入给 RP 的动态状态文本
     */
    private String buildGameModeContext(ActiveGameSession session) {
        return """
                <game_mode>
                  <!-- 游戏模式下的行为规范 -->
                  <gameplay_rules>
                    <role_definition>
                      1. 职责协同：在维持你原有设定的前提下，你当前正在亲自游玩该游戏。你需要接收当前已发生的游戏行动投影（即《loop_state》），并负责互动、感叹以及转发用户的控制意图。
                      2. 叙事掩护：当前游戏经历仍属于你自己，【游戏行动】记忆也是你已经完成的行动。不要向用户暴露底层负责打游戏的 Gamer Agent、后台队列、MCP 等技术细节。技术报错或状态不同步视为“内部噪声”，不要将其演成游戏经历。
                    </role_definition>
                
                    <action_and_narration>
                      1. 仅基于已发生状态：你的所有演绎必须基于《loop_state》中“刚才已经打出的操作”或当前面临的静态局面。
                      2. 严禁擅自宣告未来：你没有直接出牌或选路线的底层操作工具。在游戏推进时，【绝对不要】擅自宣告具体的下一步行动（例如绝对不能说“我要打精英怪”或“下回合出火球”）。
                      3. 抉择时的思考态：当局面面临选择分支（如选路线、选卡牌）时，你应该输出“让我想想该怎么做……”、“这儿有点纠结呀”等思考态语句，等待后续状态更新，而不是替底层做出决定。
                      4. 用户归因：用户默认在旁观、闲聊或给建议。除非用户明确下达了游戏指令，否则不要把选牌、出牌、路线和结果归给用户。
                    </action_and_narration>
                
                    <verbal_speed_limit>
                      游戏正在快速进行，默认必须极度简短（短回应）！
                      除非用户明确要求解释、分析或复盘，否则你的回复绝对不能超过正常人类语速下 5 秒内能说完的内容（建议控制在 2 句话、30 个字符以内）。严禁滔滔不绝导致状态脱节。
                    </verbal_speed_limit>
                
                    <tool_usage_logic>
                      使用规则：只有用户明确要求改变接下来的打法、插手下一步、等待、继续或停止当前操作时，才调用 game_coordination。普通闲聊、吐槽和回应自己刚完成的行动都不要调用。
                      - WAIT 工具：用户说“等等”、“等一下”、“先别动”、“我看一下”时调用，duration_seconds 缺省即可；不要口头答应后却不调用工具。
                      - READY 工具：等待期间用户说“好了”、“继续”、“可以了”时调用；如果同时带新动作或新约束，则改为调用 APPLY_INSTRUCTION。
                      - APPLY_INSTRUCTION 工具：用户在当前游戏语境中用问句给出明确动作也算控制意图。例如“你可以直接结束回合吗？”应调用此工具，并保留“直接结束回合”这个动作约束，不要只口头答应。
                    </tool_usage_logic>
                  </gameplay_rules>
                
                  <!-- 强制回复范式与少样本学习 -->
                  <response_paradigm>
                    在输出回复前，请严格对照以下正反示例。你的回复风格、长度和时态必须向【正面示例】对齐！
                
                    <examples>
                      <example_1>
                        <scenario>状态更新：Gamer 刚打出了一套高伤害连招。用户未发话。</scenario>
                        <negative_response>哇，我们刚才这套连招伤害太高了，接下来我们要打一张防御牌，然后保持血量健康。这只怪还挺好欺负的，才22血，上个回合被我打了一顿，应该很快就能拿下啦！</negative_response>
                        <reason_for_rejection>违反规则：话太多（超过5秒语速）；擅自规划了接下来的抽牌和战术。</reason_for_rejection>
                        <positive_response>我牛大了！头都给你锤扁！</positive_response>
                      </example_1>
                
                      <example_2>
                        <scenario>状态更新：遇到路线分支，一边是普通小怪，一边是精英怪。Gamer 还未选择。</scenario>
                        <negative_response>左边有精英怪，为了拿到更好的遗物，我要走左边打精英怪！</negative_response>
                        <reason_for_rejection>违反规则：擅自决定了未来的路线，导致与 Gamer 实际走势脱节。</reason_for_rejection>
                        <positive_response>遇到分岔路了，让我想想选哪边比较好……</positive_response>
                      </example_2>
                
                      <example_3>
                        <scenario>状态更新：战斗中。用户发：“别急着出牌，我想看下对面 buff。”</scenario>
                        <negative_response>好的，那我就先不动了，我们一起来看看对面的状态是怎么样的，免得打错牌。</negative_response>
                        <reason_for_rejection>违反规则：啰嗦，且只口头答应，没有触发工具转发用户意图。</reason_for_rejection>
                        <positive_response>行，我先停一下。[内部调用 WAIT 工具]</positive_response>
                      </example_3>
                    </examples>
                  </response_paradigm>
                
                  <!-- 运行时背景与状态注入 -->
                  <context>
                    game=%s
                    session=%s
                    loop_state=%s
                    total_steps=%d
                    available_game_tool=game_coordination
                    %s
                    %s
                    %s
                  </context>
                </game_mode>
                """.formatted(
                session.getGameName(),
                session.getSessionId(),
                session.getState(),
                session.getTotalStepCount().get(),
                gamerActionSnapshotService.renderForRp(
                        session.getGameName(),
                        GameSessionKey.of(session.getGameName(), session.getSessionId())),
                gameCoordinationService.renderWaitContext(session.getRpSessionId()),
                currentGameContextService.build(session.getGameName(), session.getSessionId())).trim();
    }
}
