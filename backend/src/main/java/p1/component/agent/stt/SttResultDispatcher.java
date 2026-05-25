package p1.component.agent.stt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
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
@Slf4j
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
            log.info("[STT] 识别文本已发送到 RP: session={}, character={}, textLen={}, responseLen={}",
                    rpSessionId, characterName, text.length(),
                    response != null ? response.length() : 0);
        } catch (Exception e) {
            log.warn("[STT] 发送识别文本到 RP 失败: session={}, character={}, text={}, error={}",
                    rpSessionId, characterName, text, e.getMessage());
        }
    }
}
