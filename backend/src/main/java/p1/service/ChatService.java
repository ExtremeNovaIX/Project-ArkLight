package p1.service;

import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import p1.component.agent.gamer.GamerPendingQuestionService;
import p1.component.agent.rp.context.SummaryCacheManager;
import p1.component.agent.rp.core.CharacterPromptRegistry;
import p1.component.agent.rp.core.RpAgent;
import p1.component.agent.rp.core.RpSpeechTurnService;
import p1.component.agent.rp.core.RpSystemPromptService;
import p1.component.agent.rp.proactive.RpProactiveSessionRegistry;
import p1.model.dto.ChatRequestDTO;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@CustomLog
public class ChatService {

    private final RpAgent rpAgent;
    private final CharacterPromptRegistry characterPromptRegistry;
    private final SummaryCacheManager summaryCacheManager;
    private final RpSystemPromptService rpSystemPromptService;
    private final RpProactiveSessionRegistry proactiveSessionRegistry;
    private final RpSpeechTurnService rpSpeechTurnService;
    private final GamerPendingQuestionService pendingQuestionService;


    public String sendMsgToRpAgent(ChatRequestDTO request) {
        String sessionId = request.getSessionId();
        String userMessage = request.getMessage();
        proactiveSessionRegistry.observeUserSpeech(sessionId, request.getCharacterName(), request.isShortMode());
        Optional<String> tacticalAnswer = pendingQuestionService.consumeAnswerForRp(sessionId, userMessage);
        if (tacticalAnswer.isPresent()) {
            userMessage = tacticalAnswer.get() + "\n\n<user_reply>\n" + userMessage + "\n</user_reply>";
        }
        String rolePrompt = characterPromptRegistry.getPrompt(request.getCharacterName());
        String currentSummary = summaryCacheManager.getSummary(sessionId);
        String systemPrompt = rpSystemPromptService.build(sessionId, rolePrompt, currentSummary);
        String reply = rpSpeechTurnService.collect(
                sessionId,
                "user-reply",
                rpAgent.chat(sessionId, userMessage, systemPrompt));
        proactiveSessionRegistry.observeRpSpeech(sessionId);
        return reply;
    }

    public String sendChatToLLM(ChatRequestDTO request) {
        return sendMsgToRpAgent(request);
    }
}
