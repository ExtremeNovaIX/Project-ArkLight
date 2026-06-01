package p1.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalConfigDirectoriesTest {

    @Test
    void resolvesExistingProjectConfigFromRootOrBackendWorkingDirectory() throws Exception {
        Path projectRoot = Paths.get("target", "external-config-directories-test", UUID.randomUUID().toString())
                .toAbsolutePath()
                .normalize();
        Path configDir = projectRoot.resolve("config");
        Path backendDir = projectRoot.resolve("backend");
        try {
            Files.createDirectories(configDir);
            Files.createDirectories(backendDir);

            assertEquals(configDir, ExternalConfigDirectories.resolveDefaultDir(projectRoot));
            assertEquals(configDir, ExternalConfigDirectories.resolveDefaultDir(backendDir));
        } finally {
            deleteRecursively(projectRoot);
        }
    }

    @Test
    void choosesProjectConfigLocationWhenDefaultDirectoryDoesNotExistYet() throws Exception {
        Path projectRoot = Paths.get("target", "external-config-directories-test", UUID.randomUUID().toString())
                .toAbsolutePath()
                .normalize();
        Path backendDir = projectRoot.resolve("backend");
        try {
            Files.createDirectories(backendDir);

            assertEquals(projectRoot.resolve("config"), ExternalConfigDirectories.resolveDefaultDir(projectRoot));
            assertEquals(projectRoot.resolve("config"), ExternalConfigDirectories.resolveDefaultDir(backendDir));
        } finally {
            deleteRecursively(projectRoot);
        }
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
