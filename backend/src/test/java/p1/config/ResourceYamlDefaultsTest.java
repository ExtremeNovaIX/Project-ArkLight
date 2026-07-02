package p1.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceYamlDefaultsTest {

    private static final Path RESOURCE_DIR = Path.of("src", "main", "resources");
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{[A-Z][A-Z0-9_]*(?::[^}]*)?}");

    @Test
    void topLevelRuntimeYamlShouldUseLiteralDefaultsInsteadOfEnvironmentPlaceholders() throws Exception {
        List<Path> yamlFiles;
        try (var files = Files.list(RESOURCE_DIR)) {
            yamlFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yaml") || path.getFileName().toString().endsWith(".yml"))
                    .toList();
        }

        for (Path yamlFile : yamlFiles) {
            String fileName = yamlFile.getFileName().toString();
            if ("application.yaml".equals(fileName)) {
                continue;
            }
            String content = Files.readString(yamlFile);
            Matcher matcher = ENV_PLACEHOLDER.matcher(content);
            assertFalse(matcher.find(), fileName + " should not contain environment placeholders");
        }
    }

    @Test
    void aiYamlShouldKeepSecretsBlankAndEnableMediumThinkingForHeavyModel() throws Exception {
        String content = Files.readString(RESOURCE_DIR.resolve("application-ai.yaml"));

        assertTrue(content.contains("api-key: \"\""), "API keys should be blank in resource defaults");
        assertTrue(content.contains("reasoning-effort: medium"), "heavy model should default to medium reasoning effort");
        assertTrue(content.contains("thinking-type: enabled"), "thinking should be enabled by default");
        assertFalse(content.contains("default_value"), "resource defaults should not contain placeholder secret defaults");
        assertFalse(content.contains("${"), "application-ai.yaml should not contain Spring placeholders");
    }
}
