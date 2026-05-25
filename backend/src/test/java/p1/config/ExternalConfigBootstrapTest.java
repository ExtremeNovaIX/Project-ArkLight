package p1.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalConfigBootstrapTest {

    @TempDir
    Path tempDir;

    @Test
    void copiesAllYamlDefaultsAndRegistersExternalLocation() throws Exception {
        String oldConfigDir = System.getProperty("arclight.config.dir");
        String oldConfigUri = System.getProperty("arclight.config.uri");
        String oldAdditionalLocation = System.getProperty("spring.config.additional-location");
        try {
            System.setProperty("arclight.config.dir", tempDir.toString());
            System.clearProperty("spring.config.additional-location");

            ExternalConfigBootstrap.prepare();

            assertTrue(Files.isRegularFile(tempDir.resolve("application.yaml")));
            assertTrue(Files.isRegularFile(tempDir.resolve("application-mcp.yaml")));
            assertTrue(Files.isRegularFile(tempDir.resolve("application-benchmark.yaml")));
            assertTrue(Files.isRegularFile(tempDir.resolve("mcp-catalog.yaml")));
            assertTrue(Files.readString(tempDir.resolve("application.yaml")).contains("assistant:"));
            assertEquals(tempDir.toAbsolutePath().normalize().toString(), System.getProperty("arclight.config.dir"));
            assertEquals(tempDir.toUri().toString(), System.getProperty("arclight.config.uri"));
            assertTrue(System.getProperty("spring.config.additional-location").contains(tempDir.toUri().toString()));
        } finally {
            restore("arclight.config.dir", oldConfigDir);
            restore("arclight.config.uri", oldConfigUri);
            restore("spring.config.additional-location", oldAdditionalLocation);
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
}
