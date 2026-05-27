package p1.component.agent.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 基于本地 OpenAI-compatible 小模型 sidecar 的指令路由器。
 * <p>
 * 默认面向 llama.cpp server / vLLM / Ollama OpenAI 兼容接口。该实现只要求模型输出短 JSON，
 * 不承担业务动作执行；业务模块拿到 {@link InstructionRouteDecision} 后自行决定是否产生副作用。
 */
@RequiredArgsConstructor
@Slf4j
public class LocalModelInstructionRouter implements InstructionRouter {

    private static final String CHAT_INTENT = "CHAT";

    private final InstructionRouterModelConfig modelConfig;
    private final InstructionRouterTaskRegistry taskRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(1000))
            .build();

    /**
     * 调用本地小模型完成路由。
     *
     * @param request 路由请求
     * @return 路由结果；端点为空、超时或解析失败时返回不可用
     */
    @Override
    public InstructionRouteDecision route(InstructionRouteRequest request) {
        if (request == null || request.userMessage() == null || request.userMessage().isBlank()) {
            return InstructionRouteDecision.chat("empty user message");
        }
        if (!StringUtils.hasText(modelConfig.endpointUrl())) {
            return InstructionRouteDecision.unavailable("instruction router endpoint is empty");
        }

        try {
            String responseBody = execute(request);
            InstructionRouteDecision decision = parseDecision(responseBody, request.allowedIntents());
            log.info("[指令路由] 本地路由判断完成: scene={}, intent={}, confidence={}, instruction={}",
                    request.sceneId(), decision.normalizedIntent(), String.format("%.2f", decision.confidence()),
                    abbreviate(decision.instruction(), 120));
            return decision;
        } catch (Exception e) {
            log.warn("[指令路由] 本地路由模型调用失败: scene={}, reason={}",
                    request.sceneId(), e.getMessage());
            return InstructionRouteDecision.unavailable(e.getMessage());
        }
    }

    /**
     * 执行一次 OpenAI-compatible chat completion 请求。
     *
     * @param request 路由请求
     * @return 原始响应体
     * @throws Exception HTTP 或序列化失败
     */
    private String execute(InstructionRouteRequest request) throws Exception {
        String payload = objectMapper.writeValueAsString(buildPayload(request));
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(modelConfig.endpointUrl()))
                .timeout(Duration.ofMillis(Math.max(100L, modelConfig.timeoutMs())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (StringUtils.hasText(modelConfig.apiKey())) {
            builder.header("Authorization", "Bearer " + modelConfig.apiKey().trim());
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    /**
     * 构造本地模型请求体。
     *
     * @param request 路由请求
     * @return JSON 对象
     */
    private ObjectNode buildPayload(InstructionRouteRequest request) {
        InstructionRouterTask task = resolveTask(request);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelConfig.modelName());
        root.put("temperature", modelConfig.temperature());
        root.put("max_tokens", Math.max(16, modelConfig.maxTokens()));

        root.putObject("chat_template_kwargs")
                .put("enable_thinking", false);

        ArrayNode messages = root.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", InstructionRouterTask.SYSTEM_PROMPT);
        messages.addObject()
                .put("role", "user")
                .put("content", task.renderUserPrompt(request.runtimeContext(), request.userMessage()));
        return root;
    }

    /**
     * 解析 task 定义；优先用 taskId 查找，否则用请求自带的 prompt 构建临时 task。
     */
    private InstructionRouterTask resolveTask(InstructionRouteRequest request) {
        if (org.springframework.util.StringUtils.hasText(request.taskId())) {
            return taskRegistry.lookup(request.taskId())
                    .orElseGet(() -> new InstructionRouterTask(
                            request.sceneId(),
                            safeList(request.allowedIntents()),
                            nullToBlank(request.sceneInstruction())));
        }
        return new InstructionRouterTask(
                request.sceneId(),
                safeList(request.allowedIntents()),
                nullToBlank(request.sceneInstruction()));
    }

    /**
     * 解析 OpenAI-compatible 响应。
     *
     * @param responseBody   原始 HTTP 响应
     * @param allowedIntents 场景允许意图
     * @return 路由结果
     * @throws Exception JSON 解析失败
     */
    private InstructionRouteDecision parseDecision(String responseBody,
                                                   List<String> allowedIntents) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            content = root.path("choices").path(0).path("text").asText("");
        }
        JsonNode decisionNode = objectMapper.readTree(extractJson(content));
        String intent = decisionNode.path("intent").asText(CHAT_INTENT).trim().toUpperCase();
        if (!safeList(allowedIntents).contains(intent)) {
            intent = CHAT_INTENT;
        }
        double confidence = clamp(decisionNode.path("confidence").asDouble(0.0), 0.0, 1.0);
        String instruction = decisionNode.path("instruction").asText("").trim();
        return new InstructionRouteDecision(true, intent, confidence, instruction, content);
    }

    /**
     * 从模型响应中提取 JSON 对象，兼容模型偶尔包一层说明文本。
     *
     * @param content 模型文本
     * @return JSON 对象字符串
     */
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

    /**
     * 规范化允许意图列表。
     *
     * @param intents 原始意图列表
     * @return 大写意图列表
     */
    private List<String> safeList(List<String> intents) {
        if (intents == null || intents.isEmpty()) {
            return List.of(CHAT_INTENT);
        }
        return intents.stream()
                .filter(StringUtils::hasText)
                .map(intent -> intent.trim().toUpperCase())
                .toList();
    }

    /**
     * 限制数值范围。
     *
     * @param value 输入值
     * @param min   最小值
     * @param max   最大值
     * @return 截断后的值
     */
    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 空值兜底。
     *
     * @param value 输入文本
     * @return 非 null 文本
     */
    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    /**
     * 日志文本截断，避免路由输出刷屏。
     *
     * @param value 原始文本
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    private String abbreviate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        int limit = Math.max(1, maxLength);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }
}
