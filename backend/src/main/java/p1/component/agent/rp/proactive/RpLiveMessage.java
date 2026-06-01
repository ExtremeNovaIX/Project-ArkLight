package p1.component.agent.rp.proactive;

import java.time.Instant;
import java.util.List;

/**
 * 后端主动投递给前端的 RP 消息。
 *
 * @param sessionId RP 会话 id
 * @param source    消息来源，例如 idle
 * @param content   RP 最终说出口的文本
 * @param reply     按展示模式切分后的回复段
 * @param createdAt 消息生成时间
 */
public record RpLiveMessage(
        String sessionId,
        String source,
        String content,
        List<String> reply,
        Instant createdAt
) {
}
