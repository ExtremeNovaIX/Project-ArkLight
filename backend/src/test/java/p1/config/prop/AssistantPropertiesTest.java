package p1.config.prop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AssistantPropertiesTest {

    @Test
    void shouldUseChatModelWhenGamerModelIsNotConfigured() {
        AssistantProperties properties = new AssistantProperties();
        AssistantProperties.ProviderConfig provider = new AssistantProperties.ProviderConfig();
        AssistantProperties.ChatModelConfig chatModel = chatModel("chat-key", "https://chat.example", "chat-model", 300L);
        provider.setChatModel(chatModel);
        properties.setApi(provider);

        assertSame(chatModel, properties.activeGamerModel());
    }

    @Test
    void shouldOverlayGamerModelOnTopOfChatModel() {
        AssistantProperties properties = new AssistantProperties();
        AssistantProperties.ProviderConfig provider = new AssistantProperties.ProviderConfig();
        provider.setChatModel(chatModel("chat-key", "https://chat.example", "chat-model", 300L));

        AssistantProperties.ChatModelConfig gamerModel = new AssistantProperties.ChatModelConfig();
        gamerModel.setModelName("gamer-model");
        provider.setGamerModel(gamerModel);
        properties.setApi(provider);

        AssistantProperties.ChatModelConfig active = properties.activeGamerModel();

        assertEquals("chat-key", active.getApiKey());
        assertEquals("https://chat.example", active.getBaseUrl());
        assertEquals("gamer-model", active.getModelName());
        assertEquals(300L, active.getTimeoutSeconds());
    }

    private AssistantProperties.ChatModelConfig chatModel(String apiKey, String baseUrl, String modelName, Long timeoutSeconds) {
        AssistantProperties.ChatModelConfig config = new AssistantProperties.ChatModelConfig();
        config.setApiKey(apiKey);
        config.setBaseUrl(baseUrl);
        config.setModelName(modelName);
        config.setTimeoutSeconds(timeoutSeconds);
        return config;
    }
}
