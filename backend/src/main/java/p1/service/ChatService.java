package p1.service;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import p1.component.agent.interaction.InteractionCoordinator;
import p1.component.agent.rp.context.SummaryCacheManager;
import p1.component.agent.rp.core.CharacterPromptRegistry;
import p1.component.agent.rp.core.RpAgent;
import p1.component.agent.rp.core.RpSpeechTurnService;
import p1.component.agent.rp.expression.RpExpressionOutbox;
import p1.component.agent.rp.game.control.RpGameControlIntentInterceptor;
import p1.component.agent.rp.proactive.RpProactiveSessionRegistry;
import p1.model.dto.ChatRequestDTO;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final RpAgent rpAgent;
    private final CharacterPromptRegistry characterPromptRegistry;
    private final SummaryCacheManager summaryCacheManager;
    private final RpProactiveSessionRegistry proactiveSessionRegistry;
    private final RpExpressionOutbox expressionOutbox;
    private final InteractionCoordinator interactionCoordinator;
    private final RpSpeechTurnService rpSpeechTurnService;
    private final RpGameControlIntentInterceptor gameControlIntentInterceptor;


    public String sendMsgToRpAgent(ChatRequestDTO request) {
        String sessionId = request.getSessionId();
        String userMessage = request.getMessage();
        proactiveSessionRegistry.observeUserSpeech(sessionId, request.getCharacterName(), request.isShortMode());
        gameControlIntentInterceptor.intercept(sessionId, userMessage);
        String rolePrompt = characterPromptRegistry.getPrompt(request.getCharacterName());
        String currentSummary = summaryCacheManager.getSummary(sessionId);
        String reply;
        try (InteractionCoordinator.InteractionLease ignored = interactionCoordinator.beginUserTurn(sessionId)) {
            reply = rpSpeechTurnService.collect(
                    sessionId,
                    "user-reply",
                    rpAgent.chat(sessionId, userMessage, rolePrompt, currentSummary));
            proactiveSessionRegistry.observeRpSpeech(sessionId);
            if (reply != null && !reply.isBlank()) {
                expressionOutbox.clearDesireAfterSpeech(sessionId);
            }
        }
        return reply;
    }

    public String sendChatToLLM(ChatRequestDTO request) {
        return sendMsgToRpAgent(request);
    }
}
