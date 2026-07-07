package p1.component.agent.stt;

import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.model.dto.ChatRequestDTO;
import p1.service.ChatService;

/**
 * STT 识别结果分发器。
 * <p>
 * 异步将最终识别文本通过 {@link ChatService} 发送给 RP Agent，
 * 完成语音→文本→RP 回复的完整链路。
 */
@Component
@RequiredArgsConstructor
@CustomLog
public class SttResultDispatcher {

    private final ChatService chatService;

    /**
     * 异步分发识别结果到 RP Agent。
     *
     * @param rpSessionId   RP 会话 ID
     * @param characterName 角色卡名称
     * @param text          最终识别文本
     */
    @Async
    public void dispatch(String rpSessionId, String characterName, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            ChatRequestDTO request = new ChatRequestDTO();
            request.setSessionId(rpSessionId);
            request.setCharacterName(characterName);
            request.setMessage(text);
            String response = chatService.sendMsgToRpAgent(request);
            log.info(LogDomain.STT, "stt.result_dispatched", LogOutcome.SUCCEEDED, "rpSessionId", rpSessionId, "characterName", characterName, "textLength", text.length(), "responseLength", response != null ? response.length() : 0);
        } catch (Exception e) {
            log.warn(LogDomain.STT, "stt.result_dispatch_failed", LogOutcome.DEGRADED, "rpSessionId", rpSessionId, "characterName", characterName, "text", text, "reason", e.getMessage());
        }
    }
}
