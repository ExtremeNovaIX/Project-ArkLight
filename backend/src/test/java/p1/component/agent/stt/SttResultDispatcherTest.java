package p1.component.agent.stt;

import org.junit.jupiter.api.Test;
import p1.service.ChatService;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class SttResultDispatcherTest {

    @Test
    void shouldDispatchToChatServiceWithCorrectParams() throws Exception {
        ChatService chatService = mock(ChatService.class);
        when(chatService.sendMsgToRpAgent(any())).thenReturn("RP 回复");
        SttResultDispatcher dispatcher = new SttResultDispatcher(chatService);

        dispatcher.dispatch("rp-session-1", "Nova", "你好世界");

        // @Async 会在另一个线程执行，等待一小段时间
        Thread.sleep(200);

        verify(chatService).sendMsgToRpAgent(argThat(request ->
                "rp-session-1".equals(request.getSessionId()) &&
                "Nova".equals(request.getCharacterName()) &&
                "你好世界".equals(request.getMessage())
        ));
    }

    @Test
    void shouldSkipBlankText() throws Exception {
        ChatService chatService = mock(ChatService.class);
        SttResultDispatcher dispatcher = new SttResultDispatcher(chatService);

        dispatcher.dispatch("rp-1", "Nova", "");
        dispatcher.dispatch("rp-1", "Nova", null);
        dispatcher.dispatch("rp-1", "Nova", "   ");

        Thread.sleep(100);

        verify(chatService, never()).sendMsgToRpAgent(any());
    }
}
