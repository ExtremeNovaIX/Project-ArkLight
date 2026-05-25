package p1.component.agent.reasoning;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static p1.utils.SessionUtil.normalizeSessionId;

/**
 * LLM 推理内容的轻量暂存器。
 * <p>
 * LangChain4j 会把 OpenAI-compatible 响应里的 reasoning_content 映射到
 * AiMessage.thinking()；这里按会话暂存最近一次结果，供 gamer 桥接层在工具调用后落入
 * 工作记忆和复盘日志。
 */
@Component
public class ReasoningContentRecorder {

    private final Map<String, String> latestReasoningBySession = new ConcurrentHashMap<>();

    /**
     * 记录指定会话最近一次模型返回的 reasoning_content。
     *
     * @param sessionId        会话 id；为空时会归一化为 default
     * @param reasoningContent 模型返回的推理内容
     */
    public void recordLatest(String sessionId, String reasoningContent) {
        if (reasoningContent == null || reasoningContent.isBlank()) {
            return;
        }
        latestReasoningBySession.put(normalizeSessionId(sessionId), reasoningContent.trim());
    }

    /**
     * 取出并删除指定会话最近一次 reasoning_content。
     *
     * @param sessionId 会话 id；为空时会归一化为 default
     * @return 最近一次推理内容；没有时返回空字符串
     */
    public String consumeLatest(String sessionId) {
        String value = latestReasoningBySession.remove(normalizeSessionId(sessionId));
        return value == null ? "" : value;
    }
}
