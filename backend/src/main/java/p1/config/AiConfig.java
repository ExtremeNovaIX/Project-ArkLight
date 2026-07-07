package p1.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
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
@CustomLog
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
            log.info(LogDomain.LLM, "ai.config.active_models", LogOutcome.SUCCEEDED, "mode", props.getMode(), "rpModel", rpModel.getModelName(), "rpBaseUrl", rpModel.getBaseUrl(), "parserModel", parserModel.getModelName(), "parserBaseUrl", parserModel.getBaseUrl(), "checkerModel", checkerModel.getModelName(), "checkerBaseUrl", checkerModel.getBaseUrl(), "supervisorModel", supervisorModel.getModelName(), "supervisorBaseUrl", supervisorModel.getBaseUrl(), "embeddingModel", embeddingModel.getModelName(), "embeddingBaseUrl", embeddingModel.getBaseUrl());
        };
    }

    private AssistantProperties.ChatModelConfig parserChatModelConfig() {
        return copyChatModelConfig(props.activeParserModel());
    }

    private AssistantProperties.ChatModelConfig rpChatModelConfig() {
        return copyChatModelConfig(props.activeRpModel());
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
