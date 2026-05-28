package p1.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import org.junit.jupiter.api.Test;
import p1.component.agent.factory.ChatModelFactory;
import p1.config.prop.AssistantProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiConfigTest {

    @Test
    void shouldDisableThinkingForRpStreamingModelWithoutMutatingChatModelConfig() {
        AssistantProperties properties = new AssistantProperties();
        properties.setMode(AssistantProperties.Mode.API);
        AssistantProperties.ProviderConfig provider = new AssistantProperties.ProviderConfig();
        AssistantProperties.ChatModelConfig chatModel = chatModelConfig();
        provider.setChatModel(chatModel);
        properties.setApi(provider);

        CapturingChatModelFactory factory = new CapturingChatModelFactory();
        AiConfig aiConfig = new AiConfig(properties, null, null, factory, null);

        aiConfig.rpStreamingChatModel();

        assertFalse(factory.config.isReturnThinking());
        assertFalse(factory.config.isSendThinking());
        assertNull(factory.config.getReasoningEffort());
        assertEquals("disabled", factory.config.getThinkingType());
        assertEquals(0.8, factory.temperature);

        assertTrue(chatModel.isReturnThinking());
        assertTrue(chatModel.isSendThinking());
        assertEquals("high", chatModel.getReasoningEffort());
        assertEquals("enabled", chatModel.getThinkingType());
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

    private static final class CapturingChatModelFactory extends ChatModelFactory {
        private AssistantProperties.ChatModelConfig config;
        private Double temperature;

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
