package p1.component.agent.context;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import p1.component.agent.rp.context.RpChatRequestAugmenter;
import p1.component.agent.rp.context.RpRequestTimeAppender;
import p1.component.agent.rp.game.context.RpGameStateAppender;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RpChatRequestAugmenterTest {

    @Test
    void shouldOnlyApplyTimeAndGameStateRuntimeContext() {
        RpRequestTimeAppender timeAppender = mock(RpRequestTimeAppender.class);
        RpGameStateAppender gameStateAppender = mock(RpGameStateAppender.class);
        RpChatRequestAugmenter augmenter = new RpChatRequestAugmenter(timeAppender, gameStateAppender);
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("current user"))
                .build();
        ChatRequest withTime = ChatRequest.builder()
                .messages(UserMessage.from("time"))
                .build();
        ChatRequest withGameState = ChatRequest.builder()
                .messages(UserMessage.from("game"))
                .build();
        when(timeAppender.augment(request, "rp-session")).thenReturn(withTime);
        when(gameStateAppender.augment(withTime, "rp-session")).thenReturn(withGameState);

        ChatRequest updated = augmenter.augment(request, "rp-session");

        assertSame(withGameState, updated);
    }
}
