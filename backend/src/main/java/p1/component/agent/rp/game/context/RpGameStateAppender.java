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
        updatedMessages.add(RpRuntimeMessageInsertSupport.beforeCurrentUserMessage(updatedMessages),
                buildGameModeMessage(activeSession.get()));
        return request.toBuilder()
                .messages(updatedMessages)
                .build();
    }

    /**
     * 构建 RP 可见的游戏模式状态消息。
     *
     * @param session 当前活跃游戏会话
     * @return 注入给 RP 的动态 AI 消息
     */
    private AiMessage buildGameModeMessage(ActiveGameSession session) {
        return AiMessage.from(buildGameModeContext(session));
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
                这是 RP 运行时背景，不是用户的新指令。
                内部职责定位：你接收已经发生的游戏行动投影并负责人格表达、互动和用户控制意图转发；你不是下一批游戏操作的规划器。
                对外叙事规则：当前游戏经历仍属于你自己；【游戏行动】记忆也是你已经完成的行动，不是用户代打。不要向用户暴露上述职责分层。
                用户默认是在旁观、闲聊或给建议。除非上下文明说用户下达了游戏指令，否则不要把你的选牌、出牌、路线和结果归给用户。
                你的职责是以正在亲自玩游戏的人格回应、感叹和闲聊，不要因为看到局势就自行计算费用、牌序、斩杀线或生成新的战术指令。
                游戏进行中默认短回应。除非用户明确要求解释、分析或复盘，否则不要输出正常人类语速下 5 秒内说不完的内容。
                你没有出牌、选奖励、查状态等游戏操作工具。除明确给出的控制意图转发通道外，不要猜测、编造或尝试调用其他游戏工具。
                对用户回应时不要提及底层自动玩家、gamer agent、后台队列或 MCP 细节。
                如果历史里残留中断、校验失败、监视器、状态不同步、接口反馈等技术过程，把它们视为不可说出的内部噪声，不要把它们演成游戏经历。
                game=%s
                session=%s
                loop_state=%s
                total_steps=%d
                available_game_tool=game_control
                使用规则：只有用户明确要求改变接下来的打法、插手下一步、暂停、继续或停止当前操作时，才调用 game_control；普通闲聊、吐槽和回应自己刚完成的行动都不要调用。
                用户在当前游戏语境中用问句给出明确动作也算控制意图。例如“你可以直接结束回合吗？”应调用 game_control 的 APPLY_INSTRUCTION，并保留“直接结束回合”这个动作，不要只口头答应。
                PAUSE 只用于暂停；RESUME 只用于没有新动作要求的“继续玩”；APPLY_INSTRUCTION 用于携带用户新的游戏意图，暂停态下会自动恢复游戏循环。
                %s
                %s
                </game_mode>
                """.formatted(
                session.getGameName(),
                session.getSessionId(),
                session.getState(),
                session.getTotalStepCount().get(),
                gamerActionSnapshotService.renderForRp(
                        session.getGameName(),
                        GameSessionKey.of(session.getGameName(), session.getSessionId())),
                currentGameContextService.build(session.getGameName(), session.getSessionId())).trim();
    }
}
