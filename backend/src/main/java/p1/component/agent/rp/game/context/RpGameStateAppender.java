package p1.component.agent.rp.game.context;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.loop.ActiveGameSession;
import p1.component.agent.interaction.GameCoordinationService;
import p1.component.agent.rp.context.RpRuntimeMessageInsertSupport;
import p1.component.agent.rp.game.control.RpGameTurnService;
import p1.component.agent.rp.game.interrupt.RpGameInterruptionService;
import p1.utils.SessionUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * RP 请求的游戏动态状态注入器。
 * <p>
 * 这里只注入当前局势和等待上下文；RP 的历史操作与错误反馈通过普通记忆链路进入，不在动态上下文重复同步。
 */
@Component
@RequiredArgsConstructor
public class RpGameStateAppender {

    private final ActiveGameRegistry activeGameRegistry;
    private final RpCurrentGameContextService currentGameContextService;
    private final GameCoordinationService gameCoordinationService;
    private final RpGameRuntimeInstructionContext runtimeInstructionContext;
    private final RpGameInterruptionService gameInterruptionService;

    /**
     * 如果当前 RP 会话正在游戏中，就在本轮用户消息之前插入最新游戏状态。
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

        UserMessage gameContext = UserMessage.from("current game state", buildGameModeContext(activeSession.get()));
        List<ChatMessage> updatedMessages = new ArrayList<>(request.messages());
        int currentUserMessageIndex = RpRuntimeMessageInsertSupport.beforeCurrentUserMessage(updatedMessages);
        if (isGameLoopTrigger(updatedMessages, currentUserMessageIndex)) {
            updatedMessages.set(currentUserMessageIndex, gameContext);
        } else {
            // 游戏状态必须靠近当前请求，但用户刚说的话仍然保留为最后一个强信号。
            updatedMessages.add(currentUserMessageIndex, gameContext);
        }
        return request.toBuilder()
                .messages(updatedMessages)
                .build();
    }

    /**
     * 渲染当前游戏动态上下文。这里不包含 RP 近期动作投影。
     */
    private String buildGameModeContext(ActiveGameSession session) {
        return """
                <game_mode>
                  %s
                  %s
                  %s
                  %s
                </game_mode>
                """.formatted(
                gameCoordinationService.renderWaitContext(session.getRpSessionId()),
                currentGameContextService.build(session.getGameName(), session.getSessionId()),
                gameInterruptionService.consumeRuntimeEvent(session.getRpSessionId()),
                runtimeInstructionContext.render()).trim();
    }

    private boolean isGameLoopTrigger(List<ChatMessage> messages, int index) {
        if (index < 0 || index >= messages.size()) {
            return false;
        }
        if (!(messages.get(index) instanceof UserMessage userMessage)) {
            return false;
        }
        String name = userMessage.name();
        return RpGameTurnService.GAME_LOOP_MESSAGE_NAME.equals(name)
                || RpGameTurnService.GAME_REPLAN_MESSAGE_NAME.equals(name);
    }
}
