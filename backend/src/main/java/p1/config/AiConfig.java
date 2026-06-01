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
import p1.component.log.LlmServiceLoggingListenerFactory;
import p1.config.prop.AssistantProperties;

/**
 * AI 模型 Bean 配置。
 * <p>
 * yaml 只配置轻/重两组模型；这里根据服务映射生成各条链路实际使用的模型实例。
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class AiConfig {

    private final AssistantProperties props;
    private final LlmServiceLoggingListenerFactory loggingListenerFactory;
    private final ChatModelFactory chatModelFactory;
    private final EmbeddingModelFactory embeddingModelFactory;

    @Bean
    public ChatModel chatLanguageModel() {
        AssistantProperties.ChatModelConfig config = props.activeRpModel();
        return chatModelFactory.buildChatModel(config, loggingListenerFactory.create("rp", config), null);
    }

    @Bean(name = "checkerChatModel")
    public ChatModel checkerChatModel() {
        AssistantProperties.ChatModelConfig config = props.activeCheckerModel();
        return chatModelFactory.buildChatModel(config, loggingListenerFactory.create("checker", config), 0.0);
    }

    @Bean(name = "supervisorChatModel")
    public ChatModel supervisorChatModel() {
        AssistantProperties.ChatModelConfig config = props.activeSupervisorModel();
        return chatModelFactory.buildChatModel(config, loggingListenerFactory.create("supervisor", config), 0.0, true);
    }

    @Bean(name = "parserChatModel")
    public ChatModel parserChatModel() {
        AssistantProperties.ChatModelConfig config = parserChatModelConfig();
        return chatModelFactory.buildChatModel(config, loggingListenerFactory.create("parser", config), 0.0);
    }

    @Bean(name = "parserStreamingChatModel")
    public StreamingChatModel parserStreamingChatModel() {
        AssistantProperties.ChatModelConfig config = parserChatModelConfig();
        return chatModelFactory.buildStreamingChatModel(config, loggingListenerFactory.create("parser", config), 0.0);
    }

    @Bean(name = "rpStreamingChatModel")
    public StreamingChatModel rpStreamingChatModel() {
        AssistantProperties.ChatModelConfig config = rpChatModelConfig();
        return chatModelFactory.buildStreamingChatModel(config, loggingListenerFactory.create("rp", config), 0.8);
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return embeddingModelFactory.buildEmbeddingModel();
    }

    @Bean
    public ApplicationRunner aiModeStartupLogger() {
        return args -> {
            AssistantProperties.ChatModelConfig rpModel = props.activeRpModel();
            AssistantProperties.ChatModelConfig parserModel = props.activeParserModel();
            AssistantProperties.ChatModelConfig checkerModel = props.activeCheckerModel();
            AssistantProperties.ChatModelConfig supervisorModel = props.activeSupervisorModel();
            AssistantProperties.EmbeddingModelConfig embeddingModel = props.activeEmbeddingModel();
            log.info("LLM mode: {} | rp: {} @ {} | parser: {} @ {} | checker: {} @ {} | supervisor: {} @ {} | embedding-model: {} @ {}",
                    props.getMode(),
                    rpModel.getModelName(),
                    rpModel.getBaseUrl(),
                    parserModel.getModelName(),
                    parserModel.getBaseUrl(),
                    checkerModel.getModelName(),
                    checkerModel.getBaseUrl(),
                    supervisorModel.getModelName(),
                    supervisorModel.getBaseUrl(),
                    embeddingModel.getModelName(),
                    embeddingModel.getBaseUrl());
        };
    }

    private AssistantProperties.ChatModelConfig parserChatModelConfig() {
        AssistantProperties.ChatModelConfig copy = copyChatModelConfig(props.activeParserModel());
        disableThinking(copy);
        return copy;
    }

    private AssistantProperties.ChatModelConfig rpChatModelConfig() {
        AssistantProperties.ChatModelConfig copy = copyChatModelConfig(props.activeRpModel());
        disableThinking(copy);
        return copy;
    }

    /**
     * parser 和 RP 流式模型只消费可见文本，关闭 thinking 可以减少延迟和协议污染。
     */
    private void disableThinking(AssistantProperties.ChatModelConfig copy) {
        copy.setReturnThinking(false);
        copy.setSendThinking(false);
        copy.setReasoningEffort(null);
        copy.setThinkingType("disabled");
    }

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
