package p1.component.rp.game;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.GameSessionKey;
import p1.component.agent.gamer.loop.ActiveGameRegistry;
import p1.component.agent.gamer.projection.GamerActionSnapshotService;
import p1.component.agent.rp.game.context.RpCurrentGameContextService;
import p1.component.agent.rp.game.context.RpGameStateAppender;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RpGameStateAppenderTest {

    @Test
    void shouldKeepNonGameRequestFreeOfGameContext() {
        RpGameStateAppender appender = new RpGameStateAppender(new ActiveGameRegistry(), null, null);
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("普通闲聊")))
                .build();

        ChatRequest updated = appender.augment(request, "rp-session");

        assertSame(request, updated);
        assertEquals(1, updated.messages().size());
    }

    @Test
    void shouldInsertGameContextBeforeCurrentUserMessage() {
        ActiveGameRegistry registry = new ActiveGameRegistry();
        registry.register("STS2MCP", "game-session", "rp-session");
        RpCurrentGameContextService contextService = mock(RpCurrentGameContextService.class);
        GamerActionSnapshotService snapshotService = mock(GamerActionSnapshotService.class);
        when(contextService.build("STS2MCP", "game-session")).thenReturn("<current_game_state />");
        when(snapshotService.renderForRp(
                "STS2MCP", GameSessionKey.of("STS2MCP", "game-session")))
                .thenReturn("<recent_game_actions>刚打出一张牌</recent_game_actions>");
        RpGameStateAppender appender = new RpGameStateAppender(registry, contextService, snapshotService);
        UserMessage currentUserMessage = UserMessage.from("你可以直接结束回合吗？");
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(AiMessage.from("前一轮回复"), currentUserMessage))
                .build();

        ChatRequest updated = appender.augment(request, "rp-session");

        assertEquals(3, updated.messages().size());
        AiMessage gameContext = assertInstanceOf(AiMessage.class, updated.messages().get(1));
        assertTrue(gameContext.text().contains("APPLY_INSTRUCTION"));
        assertTrue(gameContext.text().contains("5 秒"));
        assertTrue(gameContext.text().contains("<recent_game_actions>刚打出一张牌</recent_game_actions>"));
        assertSame(currentUserMessage, updated.messages().get(2));
    }
}
