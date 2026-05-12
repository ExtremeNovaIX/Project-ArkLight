package p1.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import p1.component.agent.factory.ChatModelFactory;
import p1.component.agent.factory.EmbeddingModelFactory;
import p1.component.log.AiServiceLoggingListener;
import p1.component.log.AssistantLoggingListener;
import p1.config.prop.AssistantProperties;

/**
 * AI 模型 Bean 配置。
 * <p>
 * 该类只负责把 yaml 中的模型配置转成 LangChain4j 模型实例；
 * 各类 AiService 组装和聊天记忆 provider 由独立配置类负责。
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class AiConfig {

    private final AssistantProperties props;
    private final AiServiceLoggingListener aiServiceLoggingListener;
    private final AssistantLoggingListener assistantLoggingListener;
    private final ChatModelFactory chatModelFactory;
    private final EmbeddingModelFactory embeddingModelFactory;

    /**
     * 构建默认同步对话模型。
     *
     * @return 默认对话模型
     */
    @Bean
    public ChatModel chatLanguageModel() {
        return chatModelFactory.buildChatModel(props.activeChatModel(), assistantLoggingListener, null);
    }

    /**
     * 构建后台任务对话模型。
     *
     * @return 后台任务模型
     */
    @Bean(name = "backendChatModel")
    public ChatModel backendChatModel() {
        return chatModelFactory.buildChatModel(props.activeChatModel(), aiServiceLoggingListener, 0.0);
    }

    /**
     * 构建结构化输出更稳定的监督模型。
     *
     * @return 监督任务模型
     */
    @Bean(name = "supervisorChatModel")
    public ChatModel supervisorChatModel() {
        return chatModelFactory.buildChatModel(props.activeChatModel(), aiServiceLoggingListener, 0.0, true);
    }

    /**
     * 构建 gamer 流式模型。
     *
     * @return gamer 流式模型
     */
    @Bean(name = "gamerStreamingChatModel")
    public StreamingChatModel gamerStreamingChatModel() {
        return chatModelFactory.buildStreamingChatModel(gamerChatModelConfig(), aiServiceLoggingListener, 0.3);
    }

    /**
     * 构建 RP 流式对话模型。
     * <p>
     * 第一阶段仍可在 HTTP 入口汇总最终文本，但内部必须以 stream 生命周期判断
     * RP 是否已经开口，给后续 TTS 和 gamer 调度提供稳定时序。
     *
     * @return RP 流式模型
     */
    @Bean(name = "rpStreamingChatModel")
    public StreamingChatModel rpStreamingChatModel() {
        return chatModelFactory.buildStreamingChatModel(props.activeChatModel(), assistantLoggingListener, 0.8);
    }

    /**
     * 构建向量模型。
     *
     * @return 向量模型
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return embeddingModelFactory.buildEmbeddingModel();
    }

    /**
     * 在启动日志中打印当前模型来源。
     *
     * @return 启动日志任务
     */
    @Bean
    public ApplicationRunner aiModeStartupLogger() {
        return args -> {
            AssistantProperties.ChatModelConfig chatModel = props.activeChatModel();
            AssistantProperties.EmbeddingModelConfig embeddingModel = props.activeEmbeddingModel();
            log.info("LLM mode: {} | chat-model: {} @ {} | embedding-model: {} @ {}",
                    props.getMode(),
                    chatModel.getModelName(),
                    chatModel.getBaseUrl(),
                    embeddingModel.getModelName(),
                    embeddingModel.getBaseUrl());
        };
    }

    /**
     * 构建 gamer 专用模型配置。
     * <p>
     * gamer 的操作 JSON 从可见响应流解析。thinking 会拖慢首个 ACTION，
     * 因此这里只在副本上关闭 thinking，不影响 RP 和 TaskSupervisor。
     *
     * @return 禁用 thinking 的 gamer 模型配置副本
     */
    private AssistantProperties.ChatModelConfig gamerChatModelConfig() {
        AssistantProperties.ChatModelConfig copy = copyChatModelConfig(props.activeChatModel());
        copy.setReturnThinking(false);
        copy.setSendThinking(false);
        copy.setReasoningEffort(null);
        copy.setThinkingType("disabled");
        return copy;
    }

    /**
     * 复制聊天模型配置，避免修改全局 activeChatModel。
     *
     * @param source 当前激活的模型配置
     * @return 可独立修改的配置副本
     */
    private AssistantProperties.ChatModelConfig copyChatModelConfig(AssistantProperties.ChatModelConfig source) {
        AssistantProperties.ChatModelConfig copy = new AssistantProperties.ChatModelConfig();
        copy.setApiKey(source.getApiKey());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setModelName(source.getModelName());
        copy.setTimeoutSeconds(source.getTimeoutSeconds());
        copy.setLogEnabled(source.isLogEnabled());
        copy.setPrompt(source.getPrompt());
        copy.setReturnThinking(source.isReturnThinking());
        copy.setSendThinking(source.isSendThinking());
        copy.setReasoningEffort(source.getReasoningEffort());
        copy.setThinkingType(source.getThinkingType());
        return copy;
    }
}
