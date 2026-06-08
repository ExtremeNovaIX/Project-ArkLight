package p1.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LocalConfigServiceTest {

    @Test
    void shouldListAndPersistWhitelistedConfigFields() throws Exception {
        Path tempDir = Paths.get("target", "local-config-service-test", UUID.randomUUID().toString())
                .toAbsolutePath()
                .normalize();
        String oldConfigDir = System.getProperty("arclight.config.dir");
        try {
            System.setProperty("arclight.config.dir", tempDir.toString());
            Files.createDirectories(tempDir);
            Files.writeString(tempDir.resolve("application-ai.yaml"), """
                    assistant:
                      mode: api
                      api:
                        light-model:
                          api-key: light-key
                          base-url: https://light.example.test
                          model-name: light-model
                        heavy-model:
                          api-key: heavy-key
                          base-url: https://heavy.example.test/v1
                          model-name: heavy-model
                        embedding-model:
                          api-key: embedding-key
                          base-url: https://embedding.example.test/v1
                          model-name: embedding-model
                    """);
            Files.writeString(tempDir.resolve("application-ai-services.yaml"), """
                    assistant:
                      model-services:
                        rp: heavy
                        parser: light
                        checker: light
                        supervisor: heavy
                      llm-logs:
                        console:
                          rp: true
                          parser: false
                          checker: false
                          supervisor: false
                    """);
            Files.writeString(tempDir.resolve("mcp-catalog.yaml"), """
                    mcp:
                      catalog:
                        STS2MCP:
                          display-name: "杀戮尖塔2"
                          args: ["run", "--directory", "{{installPath}}", "python", "server.py"]
                          gameplay-guidelines:
                            - 旧提示
                    """);
            Files.writeString(tempDir.resolve("application-frontend.yaml"), """
                    frontend:
                      settings:
                        backend-base-url: http://localhost:8080
                        workspace-name: ArkLight
                      web:
                        settings:
                          theme-id: arklight
                          boot-animation-enabled: true
                          response-delay-ms: 1000
                      qt:
                        settings:
                          language-id: en
                          ui-scale-percent: 100
                    """);

            LocalConfigService service = new LocalConfigService(null);
            LocalConfigService.ConfigCatalogSnapshot snapshot = service.listConfigs();
            assertEquals(tempDir.toString(), snapshot.configDir());
            assertTrue(snapshot.configs().stream().anyMatch(page -> page.fileName().equals("application-ai-services.yaml")));
            assertTrue(snapshot.configs().stream().anyMatch(page -> page.fileName().equals("application-frontend.yaml")));
            LocalConfigService.EditableConfigPage aiPage = snapshot.configs().stream()
                    .filter(page -> page.fileName().equals("application-ai.yaml"))
                    .findFirst()
                    .orElseThrow();
            assertFieldValue(aiPage, "assistant.mode", "api");
            assertFieldValue(aiPage, "assistant.api.light-model.api-key", "light-key");
            assertFieldValue(aiPage, "assistant.api.light-model.base-url", "https://light.example.test");
            assertFieldValue(aiPage, "assistant.api.light-model.model-name", "light-model");
            assertFieldValue(aiPage, "assistant.api.heavy-model.api-key", "heavy-key");
            assertFieldValue(aiPage, "assistant.api.heavy-model.base-url", "https://heavy.example.test/v1");
            assertFieldValue(aiPage, "assistant.api.heavy-model.model-name", "heavy-model");
            assertFieldValue(aiPage, "assistant.api.embedding-model.api-key", "embedding-key");
            assertFieldValue(aiPage, "assistant.api.embedding-model.base-url", "https://embedding.example.test/v1");
            assertFieldValue(aiPage, "assistant.api.embedding-model.model-name", "embedding-model");

            service.saveConfig("application-ai.yaml", new LocalConfigService.ConfigUpdateRequest(Map.of(
                    "assistant.api.light-model.api-key", "next-light-key",
                    "assistant.api.embedding-model.base-url", "https://embedding.next.test/v1"
            )));
            String ai = Files.readString(tempDir.resolve("application-ai.yaml"));
            assertEquals(1, countRootKey(ai, "assistant"));
            assertTrue(ai.contains("api-key: next-light-key"));
            assertTrue(ai.contains("base-url: https://embedding.next.test/v1"));

            LocalConfigService.EditableConfigPage aiServicesPage = snapshot.configs().stream()
                    .filter(page -> page.fileName().equals("application-ai-services.yaml"))
                    .findFirst()
                    .orElseThrow();
            assertFieldValue(aiServicesPage, "assistant.model-services.rp", "heavy");
            assertFieldValue(aiServicesPage, "assistant.model-services.parser", "light");
            assertFieldValue(aiServicesPage, "assistant.llm-logs.console.rp", "true");

            service.saveConfig("application-ai-services.yaml", new LocalConfigService.ConfigUpdateRequest(Map.of(
                    "assistant.model-services.parser", "heavy",
                    "assistant.llm-logs.console.parser", true
            )));
            String aiServices = Files.readString(tempDir.resolve("application-ai-services.yaml"));
            assertTrue(aiServices.contains("parser: heavy"));
            assertTrue(aiServices.contains("parser: true"));

            service.saveConfig("application-frontend.yaml", new LocalConfigService.ConfigUpdateRequest(Map.of(
                    "frontend.settings.workspace-name", "New Workspace",
                    "frontend.web.settings.response-delay-ms", 500
            )));
            String frontend = Files.readString(tempDir.resolve("application-frontend.yaml"));
            assertTrue(frontend.contains("workspace-name: \"New Workspace\""));
            assertTrue(frontend.contains("response-delay-ms: 500"));

            LocalConfigService.EditableConfigPage mcpPage = service.saveConfig("mcp-catalog.yaml", new LocalConfigService.ConfigUpdateRequest(Map.of(
                    "mcp.catalog.STS2MCP.args", "run\n--directory\n{{installPath}}\npython\nserver.py",
                    "mcp.catalog.STS2MCP.gameplay-guidelines", "第一条\n第二条"
            )));
            assertEquals("mcp-catalog.yaml", mcpPage.fileName());
            assertTrue(mcpPage.fields().stream()
                    .anyMatch(field -> field.key().equals("mcp.catalog.STS2MCP.display-name")
                            && field.value().equals("杀戮尖塔2")));
            String mcp = Files.readString(tempDir.resolve("mcp-catalog.yaml"));
            assertTrue(mcp.contains("- --directory"));
            assertTrue(mcp.contains("- 第一条"));
            assertTrue(mcp.contains("- 第二条"));
        } finally {
            restore("arclight.config.dir", oldConfigDir);
            deleteRecursively(tempDir);
        }
    }

    @Test
    void shouldRejectDuplicateYamlKeysBeforeSaving() throws Exception {
        Path tempDir = Paths.get("target", "local-config-service-test", UUID.randomUUID().toString())
                .toAbsolutePath()
                .normalize();
        String oldConfigDir = System.getProperty("arclight.config.dir");
        try {
            System.setProperty("arclight.config.dir", tempDir.toString());
            Files.createDirectories(tempDir);
            Files.writeString(tempDir.resolve("application-ai.yaml"), """
                    assistant:
                      mode: api
                    assistant:
                      mode: local
                    """);
            LocalConfigService service = new LocalConfigService(null);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.saveConfig("application-ai.yaml", new LocalConfigService.ConfigUpdateRequest(Map.of(
                            "assistant.mode", "api"
                    ))));

            assertTrue(ex.getMessage().contains("重复 YAML key"));
        } finally {
            restore("arclight.config.dir", oldConfigDir);
            deleteRecursively(tempDir);
        }
    }

    @Test
    void shouldRejectUnsupportedConfigFile() throws Exception {
        Path tempDir = Paths.get("target", "local-config-service-test", UUID.randomUUID().toString())
                .toAbsolutePath()
                .normalize();
        String oldConfigDir = System.getProperty("arclight.config.dir");
        try {
            System.setProperty("arclight.config.dir", tempDir.toString());
            LocalConfigService service = new LocalConfigService(null);

            assertThrows(IllegalArgumentException.class,
                    () -> service.saveConfig("../application.yaml", new LocalConfigService.ConfigUpdateRequest(Map.of())));
        } finally {
            restore("arclight.config.dir", oldConfigDir);
            deleteRecursively(tempDir);
        }
    }

    private void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private void assertFieldValue(LocalConfigService.EditableConfigPage page, String key, String expectedValue) {
        String actualValue = page.fields().stream()
                .filter(field -> field.key().equals(key))
                .map(LocalConfigService.EditableConfigField::value)
                .findFirst()
                .orElseThrow();
        assertEquals(expectedValue, actualValue);
    }

    private long countRootKey(String yaml, String key) {
        return yaml.lines()
                .filter(line -> line.equals(key + ":"))
                .count();
    }

    private void deleteRecursively(Path directory) throws Exception {
        if (directory == null || Files.notExists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }
}
