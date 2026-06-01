package p1.config.prop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantPropertiesTest {

    @Test
    void shouldResolveServiceModelsFromLightAndHeavyMapping() {
        AssistantProperties properties = new AssistantProperties();
        AssistantProperties.ProviderConfig provider = new AssistantProperties.ProviderConfig();
        provider.setLightModel(chatModel("light-key", "https://light.example", "light-model", 30L));
        provider.setHeavyModel(chatModel("", "", "heavy-model", null));
        properties.setApi(provider);

        AssistantProperties.ModelServicesConfig services = new AssistantProperties.ModelServicesConfig();
        services.setRp("heavy");
        services.setParser("light");
        services.setChecker("lite");
        services.setSupervisor("heavy-model");
        properties.setModelServices(services);

        assertEquals("heavy-model", properties.activeRpModel().getModelName());
        assertEquals("https://light.example", properties.activeRpModel().getBaseUrl());
        assertEquals("light-key", properties.activeRpModel().getApiKey());
        assertEquals(30L, properties.activeRpModel().getTimeoutSeconds());

        assertEquals("light-model", properties.activeParserModel().getModelName());
        assertEquals("light-model", properties.activeCheckerModel().getModelName());
        assertEquals("heavy-model", properties.activeSupervisorModel().getModelName());
    }

    @Test
    void shouldOnlyPrintRpLlmTraceByDefault() {
        AssistantProperties properties = new AssistantProperties();

        assertTrue(properties.getLlmLogs().consoleEnabled("rp"));
        assertFalse(properties.getLlmLogs().consoleEnabled("parser"));
        assertFalse(properties.getLlmLogs().consoleEnabled("checker"));
        assertFalse(properties.getLlmLogs().consoleEnabled("supervisor"));
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
