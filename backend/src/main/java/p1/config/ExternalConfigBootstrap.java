package p1.config;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动期外部配置引导器。
 * <p>
 * 该引导器在 Spring Boot 读取配置文件之前运行：先把少量用户常改的 yaml 默认配置复制到外部 config 目录，
 * 再把外部目录追加为高优先级配置来源。
 */
public final class ExternalConfigBootstrap {

    private static final String CONFIG_DIR_PROPERTY = "arclight.config.dir";
    private static final String CONFIG_URI_PROPERTY = "arclight.config.uri";
    private static final String CONFIG_DIR_ENV = "ARCLIGHT_CONFIG_DIR";
    private static final String DEFAULT_CONFIG_DIR = "../config";
    private static final String SPRING_ADDITIONAL_LOCATION = "spring.config.additional-location";
    private static final List<YamlTemplate> USER_EDITABLE_YAML_FILES = List.of(
            new YamlTemplate("application-ai.yaml", "classpath:config-template/application-ai.yaml"),
            new YamlTemplate("application-tts.yaml", "classpath:config-template/application-tts.yaml"),
            new YamlTemplate("mcp-catalog.yaml", "classpath:mcp-catalog.yaml")
    );

    private ExternalConfigBootstrap() {
    }

    /**
     * 准备外部配置并配置 Spring Boot 的配置读取位置。
     */
    public static void prepare() {
        Path configDir = resolveConfigDir();
        List<YamlResource> yamlResources = discoverYamlResources();
        copyYamlResourcesIfMissing(configDir, yamlResources);
        configureSpringConfigLocation(configDir);
    }

    /**
     * 解析外部配置目录。
     *
     * @return 归一化后的外部配置目录
     */
    private static Path resolveConfigDir() {
        String configured = firstNonBlank(System.getProperty(CONFIG_DIR_PROPERTY), System.getenv(CONFIG_DIR_ENV));
        String path = configured == null ? DEFAULT_CONFIG_DIR : configured;
        Path configDir = Paths.get(path).toAbsolutePath().normalize();
        // 将最终目录写回系统属性，供 application.yaml 中的 ${arclight.config.uri} import 使用。
        System.setProperty(CONFIG_DIR_PROPERTY, configDir.toString());
        System.setProperty(CONFIG_URI_PROPERTY, configDir.toUri().toString());
        return configDir;
    }

    /**
     * 加载本项目明确支持、且通常需要用户编辑的默认 yaml 配置。
     * <p>
     * 不使用 classpath*:*.yaml 全量扫描，避免把依赖包根目录里的 config.yaml 等无关文件复制到外部配置目录。
     *
     * @return 按 USER_EDITABLE_YAML_FILES 顺序排列的 yaml 资源列表
     */
    private static List<YamlResource> discoverYamlResources() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Map<String, YamlResource> resources = new LinkedHashMap<>();
        for (YamlTemplate template : USER_EDITABLE_YAML_FILES) {
            Resource resource = resolver.getResource(template.resourceLocation());
            if (resource.exists()) {
                resources.put(template.fileName(), new YamlResource(template.fileName(), resource));
            }
        }
        return List.copyOf(resources.values());
    }

    /**
     * 如果外部常用配置文件不存在，则逐个从 resources 复制默认配置。
     *
     * @param configDir     外部配置目录
     * @param yamlResources resources 根目录下的 yaml/yml 配置
     */
    private static void copyYamlResourcesIfMissing(Path configDir, List<YamlResource> yamlResources) {
        if (yamlResources.isEmpty()) {
            throw new IllegalStateException("classpath 根目录下没有找到 yaml/yml 默认配置");
        }

        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            throw new IllegalStateException("创建外部配置目录失败: " + configDir, e);
        }

        for (YamlResource yamlResource : yamlResources) {
            Path target = configDir.resolve(yamlResource.fileName());
            if (Files.exists(target)) {
                if (!Files.isRegularFile(target)) {
                    throw new IllegalStateException("外部配置路径不是文件: " + target);
                }
                continue;
            }

            try (InputStream in = yamlResource.resource().getInputStream()) {
                // 只在缺失时复制，避免覆盖用户已经改过的外部配置。
                Files.copy(in, target);
            } catch (IOException e) {
                throw new IllegalStateException("复制默认配置失败: " + yamlResource.fileName() + " -> " + target, e);
            }
        }
    }

    /**
     * 配置 Spring Boot，使外部 config 目录优先于 classpath 默认配置。
     *
     * @param configDir 外部配置目录
     */
    private static void configureSpringConfigLocation(Path configDir) {
        // additional-location 的优先级高于默认 classpath location，因此外部 application*.yaml 会覆盖 resources 默认值。
        String externalLocation = "optional:" + configDir.toUri();
        String existing = System.getProperty(SPRING_ADDITIONAL_LOCATION);
        if (existing == null || existing.isBlank()) {
            System.setProperty(SPRING_ADDITIONAL_LOCATION, externalLocation);
        } else if (!existing.contains(externalLocation)) {
            System.setProperty(SPRING_ADDITIONAL_LOCATION, existing + "," + externalLocation);
        }
    }

    /**
     * 返回第一个非空字符串。
     *
     * @param values 候选值
     * @return 第一个非空值；没有时返回 null
     */
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

    /**
     * 一个 classpath yaml 默认配置资源。
     *
     * @param fileName 外部配置文件名
     * @param resource classpath 资源
     */
    private record YamlResource(String fileName, Resource resource) {
    }

    /**
     * 外部配置模板资源。
     *
     * @param fileName         复制到外部 config 目录后的文件名
     * @param resourceLocation classpath 模板位置
     */
    private record YamlTemplate(String fileName, String resourceLocation) {
    }
}
