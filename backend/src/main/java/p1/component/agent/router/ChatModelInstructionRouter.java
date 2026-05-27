package p1.component.agent.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 基于 {@code backendChatModel} 的指令路由器实现。
 * <p>
 * 当本地 LLM sidecar 不可用时降级使用此实现，通过已有的后台对话模型完成意图分类。
 * 提示词组装和 JSON 解析逻辑与 {@link LocalModelInstructionRouter} 保持一致。
 */
@Slf4j
public class ChatModelInstructionRouter implements InstructionRouter {

    private static final String CHAT_INTENT = "CHAT";

    private final ChatModel chatModel;
    private final InstructionRouterTaskRegistry taskRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatModelInstructionRouter(
            @Qualifier("backendChatModel") ChatModel chatModel,
            InstructionRouterTaskRegistry taskRegistry) {
        this.chatModel = chatModel;
        this.taskRegistry = taskRegistry;
    }

    @Override
    public InstructionRouteDecision route(InstructionRouteRequest request) {
        if (request == null || request.userMessage() == null || request.userMessage().isBlank()) {
            return InstructionRouteDecision.chat("empty user message");
        }

        try {
            InstructionRouterTask task = resolveTask(request);

            String content = chatModel.chat(
                    SystemMessage.from(InstructionRouterTask.SYSTEM_PROMPT),
                    UserMessage.from(task.renderUserPrompt(request.runtimeContext(), request.userMessage()))
            ).aiMessage().text();

            return parseDecision(content, task.allowedIntents());
        } catch (Exception e) {
            log.warn("[指令路由] ChatModel 路由失败: scene={}, reason={}",
                    request.sceneId(), e.getMessage());
            return InstructionRouteDecision.unavailable(e.getMessage());
        }
    }

    /**
     * 解析 task 定义；优先用 taskId 查找，否则用请求自带的 prompt 构建临时 task。
     */
    private InstructionRouterTask resolveTask(InstructionRouteRequest request) {
        if (StringUtils.hasText(request.taskId())) {
            return taskRegistry.lookup(request.taskId())
                    .orElseGet(() -> new InstructionRouterTask(
                            request.sceneId(),
                            safeList(request.allowedIntents()),
                            request.sceneInstruction() != null ? request.sceneInstruction() : ""));
        }
        return new InstructionRouterTask(
                request.sceneId(),
                safeList(request.allowedIntents()),
                request.sceneInstruction() != null ? request.sceneInstruction() : "");
    }

    private InstructionRouteDecision parseDecision(String content, List<String> allowedIntents) throws Exception {
        JsonNode decisionNode = objectMapper.readTree(extractJson(content));
        String intent = decisionNode.path("intent").asText(CHAT_INTENT).trim().toUpperCase();
        if (!safeList(allowedIntents).contains(intent)) {
            intent = CHAT_INTENT;
        }
        double confidence = clamp(decisionNode.path("confidence").asDouble(0.0), 0.0, 1.0);
        String instruction = decisionNode.path("instruction").asText("").trim();
        return new InstructionRouteDecision(true, intent, confidence, instruction, content);
    }

    private String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return "{}";
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
    }

    private List<String> safeList(List<String> intents) {
        if (intents == null || intents.isEmpty()) {
            return List.of(CHAT_INTENT);
        }
        return intents.stream()
                .filter(StringUtils::hasText)
                .map(intent -> intent.trim().toUpperCase())
                .toList();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
