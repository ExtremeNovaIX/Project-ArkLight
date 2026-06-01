package p1.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 外部配置目录解析规则。
 */
public final class ExternalConfigDirectories {

    public static final String CONFIG_DIR_PROPERTY = "arclight.config.dir";
    public static final String CONFIG_DIR_ENV = "ARCLIGHT_CONFIG_DIR";

    private ExternalConfigDirectories() {
    }

    public static Path resolveConfiguredDir() {
        String configured = firstNonBlank(System.getProperty(CONFIG_DIR_PROPERTY), System.getenv(CONFIG_DIR_ENV));
        if (configured != null) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        return resolveDefaultDir(Paths.get("").toAbsolutePath().normalize());
    }

    static Path resolveDefaultDir(Path workingDir) {
        Path normalizedWorkingDir = workingDir.toAbsolutePath().normalize();
        Path currentConfig = normalizedWorkingDir.resolve("config").normalize();
        if (Files.isDirectory(currentConfig)) {
            return currentConfig;
        }

        Path parentConfig = normalizedWorkingDir.resolve("..").resolve("config").normalize();
        if (Files.isDirectory(parentConfig)) {
            return parentConfig;
        }

        Path directoryName = normalizedWorkingDir.getFileName();
        if (directoryName != null && "backend".equalsIgnoreCase(directoryName.toString())) {
            return parentConfig;
        }
        return currentConfig;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
