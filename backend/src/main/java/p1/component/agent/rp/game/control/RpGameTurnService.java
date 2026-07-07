package p1.component.agent.rp.game.control;

import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.component.agent.gamer.loop.ActiveGameSession;
import p1.component.agent.memory.ChatMemoryWritePolicy;
import p1.component.agent.rp.context.SummaryCacheManager;
import p1.component.agent.rp.core.*;
import p1.component.agent.rp.game.context.RpGameRuntimeInstructionContext;
import p1.component.agent.rp.proactive.RpLiveMessageHub;
import p1.component.agent.rp.proactive.RpProactiveSessionRegistry;

import java.util.Optional;

/**
 * 游戏循环唤醒 RP 进行一轮受控行动。
 */
@Service
@RequiredArgsConstructor
@CustomLog
public class RpGameTurnService {

    public static final String GAME_LOOP_MESSAGE_NAME = "system_game_loop";
    public static final String GAME_REPLAN_MESSAGE_NAME = "system_game_replan";

    private final RpReasoningAgentFactory rpReasoningAgentFactory;
    private final CharacterPromptRegistry characterPromptRegistry;
    private final SummaryCacheManager summaryCacheManager;
    private final RpSystemPromptService rpSystemPromptService;
    private final RpSpeechTurnService rpSpeechTurnService;
    private final RpProactiveSessionRegistry sessionRegistry;
    private final RpLiveMessageHub liveMessageHub;
    private final RpGameControlTurnLockService gameControlTurnLockService;
    private final RpGameRuntimeInstructionContext runtimeInstructionContext;
    private final ChatMemoryWritePolicy memoryWritePolicy;

    public String play(ActiveGameSession session, String prompt) {
        return play(session, prompt, false);
    }

    public String play(ActiveGameSession session, String prompt, boolean replan) {
        Optional<RpProactiveSessionRegistry.SessionSnapshot> snapshot = sessionRegistry.findKnown(session.getRpSessionId());
        if (snapshot.isEmpty()) {
            log.debug("[RP游戏回合] RP 会话缺少角色上下文，跳过本轮: game={}, session={}, rpSession={}",
                    session.getGameName(), session.getSessionId(), session.getRpSessionId());
            return "";
        }
        String source = replan ? "game-loop-replan" : "game-loop";
        String messageName = replan ? GAME_REPLAN_MESSAGE_NAME : GAME_LOOP_MESSAGE_NAME;
        return gameControlTurnLockService.withLock(
                session.getRpSessionId(),
                source,
                () -> playLocked(session, prompt, snapshot.get(), source, messageName));
    }

    private String playLocked(ActiveGameSession session,
                              String prompt,
                              RpProactiveSessionRegistry.SessionSnapshot snapshot,
                              String source,
                              String messageName) {
        String rolePrompt = characterPromptRegistry.getPrompt(snapshot.characterName());
        String summary = summaryCacheManager.getSummary(session.getRpSessionId());
        String systemPrompt = rpSystemPromptService.build(session.getRpSessionId(), rolePrompt, summary);
        String speech = playLockedWithEffort(
                session,
                source,
                messageName,
                systemPrompt,
                prompt,
                RpGameReasoningEffort.LOW,
                false);
        if (speech != null && !speech.isBlank()) {
            sessionRegistry.observeRpSpeech(session.getRpSessionId());
            liveMessageHub.publish(session.getRpSessionId(), source, speech, snapshot.shortMode());
        }
        return speech == null ? "" : speech;
    }

    private String playLockedWithEffort(ActiveGameSession session,
                                        String source,
                                        String messageName,
                                        String systemPrompt,
                                        String prompt,
                                        RpGameReasoningEffort effort,
                                        boolean upgraded) {
        String userMessage = ".";
        var agent = rpReasoningAgentFactory.create(effort);
        String effectivePrompt = upgraded
                ? prompt + "\nReasoning effort has been successfully raised to " + effort.apiValue() + ". Continue from the latest game context."
                : prompt;
        try {
            return runtimeInstructionContext.withInstruction(effectivePrompt, () ->
                    memoryWritePolicy.suppressUserMessage(session.getRpSessionId(), null, () ->
                            rpSpeechTurnService.collect(
                                    session.getRpSessionId(),
                                    source,
                                    agent.chatWithName(session.getRpSessionId(), messageName, userMessage, systemPrompt),
                                    session.getGameName(),
                                    session.getSessionId())));
        } catch (RpGameReasoningUpgradeException upgrade) {
            if (upgraded) {
                throw upgrade;
            }
            log.info(LogDomain.GAME, "reasoning.effort_upgraded", LogOutcome.SUCCEEDED, "gameName", session.getGameName(), "sessionId", session.getSessionId(), "reasoningEffort", upgrade.effort().apiValue(), "reason", upgrade.reason());
            return playLockedWithEffort(
                    session,
                    source,
                    messageName,
                    systemPrompt,
                    prompt,
                    upgrade.effort(),
                    true);
        }
    }
}
