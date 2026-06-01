package p1.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalConfigBootstrapTest {

    @Test
    void copiesUserEditableYamlDefaultsAndRegistersExternalLocation() throws Exception {
        Path tempDir = Paths.get("target", "external-config-bootstrap-test", UUID.randomUUID().toString())
                .toAbsolutePath()
                .normalize();
        String oldConfigDir = System.getProperty("arclight.config.dir");
        String oldConfigUri = System.getProperty("arclight.config.uri");
        String oldAdditionalLocation = System.getProperty("spring.config.additional-location");
        try {
            System.setProperty("arclight.config.dir", tempDir.toString());
            System.clearProperty("spring.config.additional-location");

            ExternalConfigBootstrap.prepare();

            assertTrue(Files.isRegularFile(tempDir.resolve("application-ai.yaml")));
            assertTrue(Files.isRegularFile(tempDir.resolve("application-ai-services.yaml")));
            assertTrue(Files.isRegularFile(tempDir.resolve("application-frontend.yaml")));
            assertTrue(Files.isRegularFile(tempDir.resolve("application-tts.yaml")));
            assertTrue(Files.isRegularFile(tempDir.resolve("mcp-catalog.yaml")));
            assertTrue(Files.notExists(tempDir.resolve("application.yaml")));
            assertTrue(Files.notExists(tempDir.resolve("application-mcp.yaml")));
            assertTrue(Files.notExists(tempDir.resolve("application-benchmark.yaml")));
            String aiConfig = Files.readString(tempDir.resolve("application-ai.yaml"));
            String aiServicesConfig = Files.readString(tempDir.resolve("application-ai-services.yaml"));
            String frontendConfig = Files.readString(tempDir.resolve("application-frontend.yaml"));
            String ttsConfig = Files.readString(tempDir.resolve("application-tts.yaml"));
            assertTrue(aiConfig.contains("light-model:"));
            assertTrue(aiConfig.contains("heavy-model:"));
            assertTrue(aiServicesConfig.contains("parser: light"));
            assertTrue(aiServicesConfig.contains("supervisor: heavy"));
            assertTrue(frontendConfig.contains("game-name: STS2MCP"));
            assertTrue(ttsConfig.contains("gpt-so-vits:"));
            assertTrue(ttsConfig.contains("top-k:"));
            assertTrue(ttsConfig.contains("vox-cpm2:"));
            assertTrue(ttsConfig.contains("cfg-value:"));
            assertFalse(ttsConfig.contains("startup-command:"));
            assertEquals(tempDir.toAbsolutePath().normalize().toString(), System.getProperty("arclight.config.dir"));
            assertSameUri(tempDir.toUri().toString(), System.getProperty("arclight.config.uri"));
            assertTrue(System.getProperty("spring.config.additional-location")
                    .contains(System.getProperty("arclight.config.uri")));
        } finally {
            restore("arclight.config.dir", oldConfigDir);
            restore("arclight.config.uri", oldConfigUri);
            restore("spring.config.additional-location", oldAdditionalLocation);
            deleteRecursively(tempDir);
        }
    }

    /**
     * 还原测试前的系统属性，避免影响后续 Spring 上下文测试。
     *
     * @param key   系统属性名
     * @param value 原始值
     */
    private void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private void assertSameUri(String expected, String actual) {
        String normalizedExpected = expected.endsWith("/") ? expected.substring(0, expected.length() - 1) : expected;
        String normalizedActual = actual.endsWith("/") ? actual.substring(0, actual.length() - 1) : actual;
        assertEquals(normalizedExpected, normalizedActual);
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
