package p1.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import org.junit.jupiter.api.Test;
import p1.component.agent.factory.ChatModelFactory;
import p1.component.agent.reasoning.ReasoningContentRecorder;
import p1.component.log.LlmServiceLoggingListenerFactory;
import p1.config.prop.AssistantProperties;
import p1.infrastructure.mdc.ChatSessionMetrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiConfigTest {

    @Test
    void shouldBuildRpStreamingModelWithConfiguredThinkingWithoutMutatingChatModelConfig() {
        AssistantProperties properties = new AssistantProperties();
        properties.setMode(AssistantProperties.Mode.API);
        AssistantProperties.ProviderConfig provider = new AssistantProperties.ProviderConfig();
        AssistantProperties.ChatModelConfig heavyModel = chatModelConfig();
        provider.setLightModel(chatModel("light-model"));
        provider.setHeavyModel(heavyModel);
        properties.setApi(provider);

        CapturingChatModelFactory factory = new CapturingChatModelFactory();
        AiConfig aiConfig = new AiConfig(properties, loggingFactory(properties), factory, null);

        aiConfig.rpStreamingChatModel();

        assertTrue(factory.config.isReturnThinking());
        assertTrue(factory.config.isSendThinking());
        assertEquals("high", factory.config.getReasoningEffort());
        assertEquals("enabled", factory.config.getThinkingType());
        assertEquals(0.8, factory.temperature);

        assertTrue(heavyModel.isReturnThinking());
        assertTrue(heavyModel.isSendThinking());
        assertEquals("high", heavyModel.getReasoningEffort());
        assertEquals("enabled", heavyModel.getThinkingType());
    }

    @Test
    void shouldBuildParserModelFromMappedLightModel() {
        AssistantProperties properties = new AssistantProperties();
        properties.setMode(AssistantProperties.Mode.API);
        AssistantProperties.ProviderConfig provider = new AssistantProperties.ProviderConfig();
        provider.setLightModel(chatModel("light-parser"));
        provider.setHeavyModel(chatModelConfig());
        properties.setApi(provider);

        CapturingChatModelFactory factory = new CapturingChatModelFactory();
        AiConfig aiConfig = new AiConfig(properties, loggingFactory(properties), factory, null);

        aiConfig.parserChatModel();

        assertEquals("light-parser", factory.config.getModelName());
        assertTrue(factory.config.isReturnThinking());
        assertTrue(factory.config.isSendThinking());
        assertEquals("high", factory.config.getReasoningEffort());
        assertEquals("enabled", factory.config.getThinkingType());
        assertEquals(0.0, factory.temperature);
    }

    @Test
    void shouldBuildParserStreamingModelFromMappedLightModel() {
        AssistantProperties properties = new AssistantProperties();
        properties.setMode(AssistantProperties.Mode.API);
        AssistantProperties.ProviderConfig provider = new AssistantProperties.ProviderConfig();
        provider.setLightModel(chatModel("light-parser"));
        provider.setHeavyModel(chatModelConfig());
        properties.setApi(provider);

        CapturingChatModelFactory factory = new CapturingChatModelFactory();
        AiConfig aiConfig = new AiConfig(properties, loggingFactory(properties), factory, null);

        aiConfig.parserStreamingChatModel();

        assertEquals("light-parser", factory.config.getModelName());
        assertTrue(factory.config.isReturnThinking());
        assertTrue(factory.config.isSendThinking());
        assertEquals("high", factory.config.getReasoningEffort());
        assertEquals("enabled", factory.config.getThinkingType());
        assertEquals(0.0, factory.temperature);
    }

    private AssistantProperties.ChatModelConfig chatModelConfig() {
        AssistantProperties.ChatModelConfig config = new AssistantProperties.ChatModelConfig();
        config.setApiKey("test-key");
        config.setBaseUrl("https://example.test/v1");
        config.setModelName("test-model");
        config.setTimeoutSeconds(30L);
        config.setReturnThinking(true);
        config.setSendThinking(true);
        config.setReasoningEffort("high");
        config.setThinkingType("enabled");
        return config;
    }

    private AssistantProperties.ChatModelConfig chatModel(String modelName) {
        AssistantProperties.ChatModelConfig config = chatModelConfig();
        config.setModelName(modelName);
        return config;
    }

    private LlmServiceLoggingListenerFactory loggingFactory(AssistantProperties properties) {
        return new LlmServiceLoggingListenerFactory(properties, new ChatSessionMetrics(), new ReasoningContentRecorder());
    }

    private static final class CapturingChatModelFactory extends ChatModelFactory {
        private AssistantProperties.ChatModelConfig config;
        private Double temperature;

        @Override
        public dev.langchain4j.model.chat.ChatModel buildChatModel(AssistantProperties.ChatModelConfig config,
                                                                   ChatModelListener listener,
                                                                   Double temperature) {
            this.config = config;
            this.temperature = temperature;
            return null;
        }

        @Override
        public StreamingChatModel buildStreamingChatModel(AssistantProperties.ChatModelConfig config,
                                                          ChatModelListener listener,
                                                          Double temperature) {
            this.config = config;
            this.temperature = temperature;
            return null;
        }
    }
}
