package p1.component.agent.rp.game.control;

import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.loop.ActiveGameSession;
import p1.component.agent.memory.ChatMemoryWritePolicy;
import p1.component.agent.rp.context.SummaryCacheManager;
import p1.component.agent.rp.core.*;
import p1.component.agent.rp.game.context.RpGameRuntimeInstructionContext;
import p1.component.agent.rp.proactive.RpLiveMessageHub;
import p1.component.agent.rp.proactive.RpProactiveSessionRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RpGameTurnServiceTest {

    @Test
    void shouldPlayWithRememberedRpContextWhenNoLiveSubscriptionExists() {
        RpAgent rpAgent = mock(RpAgent.class);
        RpReasoningAgentFactory reasoningAgentFactory = mock(RpReasoningAgentFactory.class);
        when(reasoningAgentFactory.create(RpGameReasoningEffort.LOW)).thenReturn(rpAgent);
        CharacterPromptRegistry characterPromptRegistry = mock(CharacterPromptRegistry.class);
        SummaryCacheManager summaryCacheManager = mock(SummaryCacheManager.class);
        RpSystemPromptService systemPromptService = mock(RpSystemPromptService.class);
        RpSpeechTurnService speechTurnService = mock(RpSpeechTurnService.class);
        RpLiveMessageHub liveMessageHub = mock(RpLiveMessageHub.class);
        RpProactiveSessionRegistry sessionRegistry = new RpProactiveSessionRegistry();
        TokenStream tokenStream = mock(TokenStream.class);
        ActiveGameSession session = new ActiveGameSession("STS2MCP", "game-session", "rp-session");

        sessionRegistry.observeUserSpeech("rp-session", "Nova", true);
        when(characterPromptRegistry.getPrompt("Nova")).thenReturn("role prompt");
        when(summaryCacheManager.getSummary("rp-session")).thenReturn("summary");
        when(systemPromptService.build("rp-session", "role prompt", "summary")).thenReturn("system prompt");
        when(rpAgent.chatWithName(
                "rp-session",
                RpGameTurnService.GAME_LOOP_MESSAGE_NAME,
                ".",
                "system prompt")).thenReturn(tokenStream);
        when(speechTurnService.collect("rp-session", "game-loop", tokenStream, "STS2MCP", "game-session"))
                .thenReturn("done");

        RpGameTurnService service = new RpGameTurnService(
                reasoningAgentFactory,
                characterPromptRegistry,
                summaryCacheManager,
                systemPromptService,
                speechTurnService,
                sessionRegistry,
                liveMessageHub,
                new RpGameControlTurnLockService(),
                new RpGameRuntimeInstructionContext(),
                new ChatMemoryWritePolicy());

        assertEquals("done", service.play(session, "observe now"));
        verify(reasoningAgentFactory).create(eq(RpGameReasoningEffort.LOW));
        verify(speechTurnService).collect("rp-session", "game-loop", tokenStream, "STS2MCP", "game-session");
        verify(liveMessageHub).publish("rp-session", "game-loop", "done", true);
    }

    @Test
    void shouldRetryWithRequestedReasoningEffortForCurrentTurnOnly() {
        RpAgent lowAgent = mock(RpAgent.class);
        RpAgent highAgent = mock(RpAgent.class);
        RpReasoningAgentFactory reasoningAgentFactory = mock(RpReasoningAgentFactory.class);
        CharacterPromptRegistry characterPromptRegistry = mock(CharacterPromptRegistry.class);
        SummaryCacheManager summaryCacheManager = mock(SummaryCacheManager.class);
        RpSystemPromptService systemPromptService = mock(RpSystemPromptService.class);
        RpSpeechTurnService speechTurnService = mock(RpSpeechTurnService.class);
        RpLiveMessageHub liveMessageHub = mock(RpLiveMessageHub.class);
        RpProactiveSessionRegistry sessionRegistry = new RpProactiveSessionRegistry();
        TokenStream lowStream = mock(TokenStream.class);
        TokenStream highStream = mock(TokenStream.class);
        ActiveGameSession session = new ActiveGameSession("STS2MCP", "game-session", "rp-session");

        sessionRegistry.observeUserSpeech("rp-session", "Nova", true);
        when(characterPromptRegistry.getPrompt("Nova")).thenReturn("role prompt");
        when(summaryCacheManager.getSummary("rp-session")).thenReturn("summary");
        when(systemPromptService.build("rp-session", "role prompt", "summary")).thenReturn("system prompt");
        when(reasoningAgentFactory.create(RpGameReasoningEffort.LOW)).thenReturn(lowAgent);
        when(reasoningAgentFactory.create(RpGameReasoningEffort.XHIGH)).thenReturn(highAgent);
        when(lowAgent.chatWithName("rp-session", RpGameTurnService.GAME_LOOP_MESSAGE_NAME, ".", "system prompt"))
                .thenReturn(lowStream);
        when(highAgent.chatWithName("rp-session", RpGameTurnService.GAME_LOOP_MESSAGE_NAME, ".", "system prompt"))
                .thenReturn(highStream);
        when(speechTurnService.collect("rp-session", "game-loop", lowStream, "STS2MCP", "game-session"))
                .thenThrow(new RpGameReasoningUpgradeException(RpGameReasoningEffort.XHIGH, "hard state"));
        when(speechTurnService.collect("rp-session", "game-loop", highStream, "STS2MCP", "game-session"))
                .thenReturn("done");

        RpGameTurnService service = new RpGameTurnService(
                reasoningAgentFactory,
                characterPromptRegistry,
                summaryCacheManager,
                systemPromptService,
                speechTurnService,
                sessionRegistry,
                liveMessageHub,
                new RpGameControlTurnLockService(),
                new RpGameRuntimeInstructionContext(),
                new ChatMemoryWritePolicy());

        assertEquals("done", service.play(session, "observe now"));
        verify(reasoningAgentFactory).create(RpGameReasoningEffort.LOW);
        verify(reasoningAgentFactory).create(RpGameReasoningEffort.XHIGH);
        verify(liveMessageHub).publish("rp-session", "game-loop", "done", true);
    }
}
