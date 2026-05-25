package p1.component.agent.factory;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import p1.config.prop.AssistantProperties;
import p1.utils.HttpUtil;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

@Component
public class ChatModelFactory {

    public ChatModel buildChatModel(AssistantProperties.ChatModelConfig config,
                                    ChatModelListener listener,
                                    Double temperature) {
        return buildChatModel(config, listener, temperature, false);
    }

    public ChatModel buildChatModel(AssistantProperties.ChatModelConfig config,
                                    ChatModelListener listener,
                                    Double temperature,
                                    boolean structuredOutputFriendly) {
        boolean responseFormatJsonSchemaSupported = supportsResponseFormatJsonSchema(config);
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .logRequests(config.isLogEnabled())
                .logResponses(config.isLogEnabled())
                // 保留 OpenAI-compatible 接口返回的 reasoning_content，便于日志诊断。
                .returnThinking(config.isReturnThinking())
                // thinking 工具循环依赖 reasoning_content 回传；是否发送由配置控制。
                .sendThinking(config.isSendThinking(), "reasoning_content");

        if (StringUtils.hasText(config.getApiKey())) {
            builder.apiKey(config.getApiKey());
        }

        applyReasoningOptions(builder, config);

        if (HttpUtil.isLocalEndpoint(config.getBaseUrl())) {
            builder.httpClientBuilder(HttpUtil.getLocalClientBuilder(Duration.ofSeconds(config.getTimeoutSeconds())));
        }

        if (listener != null) {
            builder.listeners(Collections.singletonList(listener));
        }
        if (temperature != null) {
            builder.temperature(temperature);
        }
        if (structuredOutputFriendly) {
            builder.parallelToolCalls(false);
            if (responseFormatJsonSchemaSupported) {
                builder.supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                        .strictJsonSchema(true);
            }
        }
        return builder.build();
    }

    /**
     * 构建 OpenAI-compatible 流式聊天模型。
     *
     * @param config      模型配置
     * @param listener    LangChain4j 调用监听器
     * @param temperature 温度；为空时使用供应商默认值
     * @return 流式聊天模型
     */
    public StreamingChatModel buildStreamingChatModel(AssistantProperties.ChatModelConfig config,
                                                      ChatModelListener listener,
                                                      Double temperature) {
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .logRequests(config.isLogEnabled())
                .logResponses(config.isLogEnabled())
                .returnThinking(config.isReturnThinking())
                .sendThinking(config.isSendThinking(), "reasoning_content");

        if (StringUtils.hasText(config.getApiKey())) {
            builder.apiKey(config.getApiKey());
        }

        applyReasoningOptions(builder, config);

        if (HttpUtil.isLocalEndpoint(config.getBaseUrl())) {
            builder.httpClientBuilder(HttpUtil.getLocalClientBuilder(Duration.ofSeconds(config.getTimeoutSeconds())));
        }

        if (listener != null) {
            builder.listeners(Collections.singletonList(listener));
        }
        if (temperature != null) {
            builder.temperature(temperature);
        }
        return builder.build();
    }

    private boolean supportsResponseFormatJsonSchema(AssistantProperties.ChatModelConfig config) {
        String baseUrl = config.getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            return false;
        }
        try {
            String host = URI.create(baseUrl).getHost();
            if (host == null) {
                return false;
            }
            String normalizedHost = host.toLowerCase();
            return normalizedHost.endsWith("openai.com");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * 应用推理相关的可选参数。
     *
     * @param builder OpenAI 同步模型 builder
     * @param config  模型配置
     */
    private void applyReasoningOptions(OpenAiChatModel.OpenAiChatModelBuilder builder,
                                       AssistantProperties.ChatModelConfig config) {
        if (StringUtils.hasText(config.getReasoningEffort())) {
            builder.reasoningEffort(config.getReasoningEffort().trim());
        }
        if (StringUtils.hasText(config.getThinkingType())) {
            builder.customParameters(Map.of("thinking", Map.of("type", config.getThinkingType().trim())));
        }
    }

    /**
     * 应用推理相关的可选参数。
     *
     * @param builder OpenAI 流式模型 builder
     * @param config  模型配置
     */
    private void applyReasoningOptions(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder,
                                       AssistantProperties.ChatModelConfig config) {
        if (StringUtils.hasText(config.getReasoningEffort())) {
            builder.reasoningEffort(config.getReasoningEffort().trim());
        }
        if (StringUtils.hasText(config.getThinkingType())) {
            builder.customParameters(Map.of("thinking", Map.of("type", config.getThinkingType().trim())));
        }
    }

}
