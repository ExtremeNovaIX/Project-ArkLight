package p1.component.agent.tts;

/**
 * 单段文本合成请求。
 *
 * @param sessionId          RP 会话 id
 * @param source             发言来源
 * @param sequence           当前发言内的文本序号
 * @param text               待合成文本
 */
public record TtsSynthesisRequest(
        String sessionId,
        String source,
        long sequence,
        String text
) {
}
