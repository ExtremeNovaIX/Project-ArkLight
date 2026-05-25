package p1.config.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * MCP 服务器注册表，管理所有 MCP 服务器的发现与注册。
 * <p>
 * 启动时按优先级合并四个来源：
 * 1. config/mcp-catalog.yaml  - 外部预设目录模板
 * 2. mcp-catalog.yaml         - 预设目录模板 (classpath, 默认值)
 * 3. mcp-servers/ 目录扫描     - 自动发现 * /manifest.yaml (无需配置)
 * 4. application-mcp.yaml     - 用户显式配置或 installPath 引用目录模板
 * 5. config/mcp-registry.json - REST API 持久化的运行时注册条目 (最终覆盖)
 * <p>
 * 所有启用的条目最终写入 MCPProperties.games, 供 GamerMCPClientFactory 使用.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class McpServerRegistry {

    private static final String DEFAULT_ADAPTER = "default";
    private static final String DEFAULT_STATE_TOOL_NAME = "get_state";
    private static final String CONFIG_DIR_PROPERTY = "arclight.config.dir";
    private static final String DEFAULT_CONFIG_DIR = "../config";
    private static final String CATALOG_FILE_NAME = "mcp-catalog.yaml";

    private final MCPProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * 运行时注册条目（来自 mcp-registry.json），用于持久化
     */
    private final Map<String, MCPProperties.GameMCPConfig> registered = new LinkedHashMap<>();
    /**
     * mcp-servers/ 自动发现的条目
     */
    private final Map<String, MCPProperties.GameMCPConfig> discovered = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        loadCatalogFromClasspath();
        scanServersDirectory();
        loadRegistryFile();
        resolveCatalog();
        mergeAll();

        log.info("[MCP] 注册表已初始化: catalog={}, discovered={}, configured={}, registered={}, total={}",
                properties.getCatalog().size(),
                discovered.size(),
                countConfigured(),
                registered.size(),
                properties.getGames().size());

        log.info("[MCP] ── 可用 MCP 服务器列表 (共 {} 个) ──", properties.getGames().size());
        properties.getGames().forEach((name, config) -> {
            String source = config.isRegistered() ? "registered"
                    : discovered.containsKey(name) ? "discovered" : "configured";
            log.info("[MCP]   • {} [{}] transport={}, command={}, adapter={}, enabled={}",
                    name, source, config.getTransport(),
                    config.getCommand() != null ? config.getCommand() : config.getUrl(),
                    config.getAdapter(), config.isEnabled());
        });
        log.info("[MCP] ────────────────────────────────");
    }


    /**
     * 注册或更新一个 MCP 服务器，并持久化到 JSON 文件
     */
    public MCPProperties.GameMCPConfig register(String gameName, MCPProperties.GameMCPConfig config) {
        config = withCatalogDefaults(gameName, config);
        config.setRegistered(true);
        registered.put(gameName, config);
        properties.putGame(gameName, config.copy());
        saveRegistryFile();
        log.info("[MCP] 服务器已注册: name={}, transport={}", gameName, config.getTransport());
        return config;
    }

    /**
     * 取消注册一个运行时注册的服务器
     */
    public boolean unregister(String gameName) {
        MCPProperties.GameMCPConfig removed = registered.remove(gameName);
        if (removed != null) {
            saveRegistryFile();
            // 回退到自动发现或目录配置
            properties.removeGame(gameName);
            if (discovered.containsKey(gameName)) {
                properties.putGame(gameName, withCatalogDefaults(gameName, discovered.get(gameName)));
            } else {
                resolveAndMergeCatalogEntry(gameName);
            }
            log.info("[MCP] 服务器已注销: name={}", gameName);
            return true;
        }
        MCPProperties.GameMCPConfig existing = properties.getGames().get(gameName);
        if (existing != null && !existing.isRegistered()) {
            log.warn("[MCP] 不能删除非注册条目: name={}", gameName);
        }
        return false;
    }

    /**
     * 列出所有已注册的运行时服务器
     */
    public Map<String, MCPProperties.GameMCPConfig> listRegistered() {
        return Map.copyOf(registered);
    }

    /**
     * 列出所有自动发现的服务器
     */
    public Map<String, MCPProperties.GameMCPConfig> listDiscovered() {
        return Map.copyOf(discovered);
    }

    /**
     * 列出所有可用的服务器（合并后）
     */
    public Map<String, MCPProperties.GameMCPConfig> listAll() {
        return Map.copyOf(properties.getGames());
    }

    // ── 来源 2：扫描 mcp-servers/ 目录 ──

    private void scanServersDirectory() {
        Path dir = resolvePath(properties.getServersDirectory());
        if (!Files.isDirectory(dir)) {
            log.info("[MCP] MCP servers 目录不存在，跳过自动发现: {}", dir);
            return;
        }

        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(subDir -> {
                String gameName = subDir.getFileName().toString();
                if (gameName.startsWith(".")) return;

                // 先尝试根目录
                if (tryDetect(subDir, gameName)) return;

                // 根目录没找到，深入子目录（处理多层嵌套），用父目录名注册
                boolean foundInChild = false;
                try (Stream<Path> children = Files.list(subDir).filter(Files::isDirectory)) {
                    for (Path child : children.toList()) {
                        if (child.getFileName().toString().startsWith(".")) continue;
                        if (tryDetect(child, gameName)) {
                            foundInChild = true;
                            break;  // 只取第一个匹配的子目录
                        }
                    }
                } catch (IOException ignored) {
                }

                if (!foundInChild) {
                    log.warn("[MCP] 跳过目录，未找到可识别的 MCP 配置: {}", subDir);
                }
            });
        } catch (IOException e) {
            log.error("[MCP] 扫描 mcp-servers/ 失败: {}", e.toString());
        }
        log.info("[MCP] 自动发现 {} 个服务器", discovered.size());
    }

    /**
     * 对一个目录尝试所有检测方式（按优先级）
     */
    private boolean tryDetect(Path dir, String gameName) {
        if (tryStandardFormat(dir.resolve(".mcp.json"), gameName, dir)) return true;
        if (tryStandardFormat(dir.resolve("mcp.json"), gameName, dir)) return true;
        if (tryManifestFormat(dir.resolve("manifest.yaml"), gameName, dir)) return true;
        if (tryAutoDetect(dir, gameName)) return true;
        return false;
    }

    // ── 格式解析：.mcp.json / mcp.json ──

    private boolean tryStandardFormat(Path jsonFile, String gameName, Path baseDir) {
        if (!Files.isRegularFile(jsonFile)) return false;
        try {
            String content = Files.readString(jsonFile);
            @SuppressWarnings("unchecked")
            Map<String, Object> root = objectMapper.readValue(content, Map.class);
            if (root == null) return false;

            @SuppressWarnings("unchecked")
            Map<String, Object> servers = (Map<String, Object>) root.get("mcpServers");
            if (servers == null || servers.isEmpty()) {
                log.warn("[MCP] {} 中没有 mcpServers 条目", jsonFile.getFileName());
                return false;
            }

            for (Map.Entry<String, Object> entry : servers.entrySet()) {
                String serverName = gameName;
                if (servers.size() > 1) {
                    serverName = gameName + "-" + entry.getKey();
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> fields = (Map<String, Object>) entry.getValue();
                MCPProperties.GameMCPConfig config = mapStandardServerConfig(fields);
                resolveRelativePaths(config, baseDir);
                config.setEnabled(true);
                discovered.put(serverName, config);
                log.info("[MCP] 发现服务器 (.mcp.json): name={}, dir={}", serverName, baseDir);
            }
            return true;
        } catch (IOException e) {
            log.error("[MCP] 解析 {} 失败: {}", jsonFile, e.toString());
            return false;
        }
    }

    /**
     * 将 .mcp.json 中的服务器字段映射为 GameMCPConfig
     */
    private MCPProperties.GameMCPConfig mapStandardServerConfig(Map<String, Object> fields) {
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setTransport("stdio");
        if (fields.get("command") != null) config.setCommand(fields.get("command").toString());
        if (fields.get("type") instanceof String t && t.equalsIgnoreCase("sse")) {
            config.setTransport("sse");
        }
        if (fields.get("url") != null) config.setUrl(fields.get("url").toString());
        if (fields.get("display-name") != null) config.setDisplayName(fields.get("display-name").toString());
        if (fields.get("description") != null) config.setDescription(fields.get("description").toString());
        if (fields.get("adapter") != null) config.setAdapter(fields.get("adapter").toString());
        if (fields.get("state-tool-name") != null) config.setStateToolName(fields.get("state-tool-name").toString());
        if (fields.get("stateToolName") != null) config.setStateToolName(fields.get("stateToolName").toString());
        applyStateSettleConfig(fields, config);
        config.setGameplayGuidelines(readGuidelines(fields));
        if (fields.get("args") instanceof List<?> list) {
            config.setArgs(list.stream().map(Object::toString).toArray(String[]::new));
        }
        return config;
    }

    // ── 格式解析：manifest.yaml ──

    private boolean tryManifestFormat(Path manifest, String gameName, Path baseDir) {
        if (!Files.isRegularFile(manifest)) return false;
        try {
            String content = Files.readString(manifest);
            Yaml yaml = new Yaml();
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = yaml.load(content);
            if (fields == null) return false;

            MCPProperties.GameMCPConfig config = mapToConfig(fields);
            resolveRelativePaths(config, baseDir);
            config.setEnabled(true);
            discovered.put(gameName, config);
            log.info("[MCP] 发现服务器 (manifest.yaml): name={}, dir={}", gameName, baseDir);
            return true;
        } catch (IOException e) {
            log.error("[MCP] 读取清单失败: {}", manifest, e.toString());
            return false;
        }
    }

    // ── 格式解析：自动推断 ──

    /**
     * 扫描目录中的构建文件，自动推断启动命令
     */
    private boolean tryAutoDetect(Path dir, String gameName) {
        // 搜 pyproject.toml（最多 2 层）
        Path pyproject = findInTree(dir, "pyproject.toml", 2);
        if (pyproject != null) {
            Path projectDir = pyproject.getParent();
            String absoluteDir = projectDir.toAbsolutePath().normalize().toString();

            // 找入口点
            String entry = findEntryFile(projectDir);
            if (entry == null) entry = "server.py";

            MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
            config.setTransport("stdio");
            config.setCommand("uv");
            config.setArgs(new String[]{"run", "--directory", absoluteDir, "python", entry});
            config.setDisplayName(gameName);
            config.setDescription("自动发现 (pyproject.toml)");
            config.setEnabled(true);
            discovered.put(gameName, config);
            log.info("[MCP] 发现服务器 (自动推断/Python): name={}, dir={}, projectDir={}",
                    gameName, dir, absoluteDir);
            return true;
        }

        // 搜 package.json
        Path pkgJson = findInTree(dir, "package.json", 2);
        if (pkgJson != null) {
            Path projectDir = pkgJson.getParent();
            String absoluteDir = projectDir.toAbsolutePath().normalize().toString();
            String entry = findEntryFile(projectDir);

            MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
            config.setTransport("stdio");
            if (entry != null) {
                config.setCommand("node");
                config.setArgs(new String[]{absoluteDir + "/" + entry});
            } else {
                config.setCommand("npx");
                config.setArgs(new String[]{"--prefix", absoluteDir, "."});
            }
            config.setDisplayName(gameName);
            config.setDescription("自动发现 (package.json)");
            config.setEnabled(true);
            discovered.put(gameName, config);
            log.info("[MCP] 发现服务器 (自动推断/Node): name={}, dir={}", gameName, dir);
            return true;
        }

        return false;
    }

    /**
     * 在目录中递归查找文件（最多 depth 层）
     */
    private Path findInTree(Path root, String fileName, int maxDepth) {
        try {
            Path direct = root.resolve(fileName);
            if (Files.isRegularFile(direct)) return direct;

            if (maxDepth > 0) {
                try (Stream<Path> children = Files.list(root)) {
                    for (Path child : children.filter(Files::isDirectory).toList()) {
                        Path found = findInTree(child, fileName, maxDepth - 1);
                        if (found != null) return found;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /**
     * 在目录中查找已知的 MCP 服务器入口点文件
     */
    private String findEntryFile(Path dir) {
        String[] candidates = {"server.py", "main.py", "app.py", "server.js", "index.js", "mcp.js"};
        for (String name : candidates) {
            if (Files.isRegularFile(dir.resolve(name))) return name;
        }
        return null;
    }

    /**
     * 将配置中的相对路径解析为基于 baseDir 的绝对路径。只解析实际存在或父目录存在的路径。
     */
    private void resolveRelativePaths(MCPProperties.GameMCPConfig config, Path baseDir) {
        if (config.getArgs() != null) {
            String[] resolved = new String[config.getArgs().length];
            for (int i = 0; i < config.getArgs().length; i++) {
                String arg = config.getArgs()[i];
                // 跳过标志、模板占位符
                if (arg.startsWith("-") || arg.startsWith("{{")) {
                    resolved[i] = arg;
                    continue;
                }
                // 只解析文件系统中实际存在的路径
                Path candidate = baseDir.resolve(arg).normalize();
                if (Files.exists(candidate) || Files.exists(candidate.getParent())) {
                    resolved[i] = candidate.toString();
                } else {
                    resolved[i] = arg;
                }
            }
            config.setArgs(resolved);
        }
        if (config.getCommand() != null && !config.getCommand().startsWith("{{")) {
            Path candidate = baseDir.resolve(config.getCommand()).normalize();
            if (Files.exists(candidate)) {
                config.setCommand(candidate.toString());
            }
        }
    }

    // ── 来源 1：mcp-catalog.yaml classpath 文件 ──

    @SuppressWarnings("unchecked")
    private void loadCatalogFromClasspath() {
        loadCatalogFromExternalConfig();
        try {
            ClassPathResource resource = new ClassPathResource(CATALOG_FILE_NAME);
            if (!resource.exists()) {
                log.info("[MCP] 未找到 mcp-catalog.yaml，跳过目录加载");
                return;
            }
            try (InputStream in = resource.getInputStream()) {
                Yaml yaml = new Yaml();
                Map<String, Object> root = yaml.load(in);
                if (root == null) return;

                Map<String, Object> mcpNode = (Map<String, Object>) root.get("mcp");
                if (mcpNode == null) return;
                Map<String, Object> catalogNode = (Map<String, Object>) mcpNode.get("catalog");
                if (catalogNode == null) return;

                for (Map.Entry<String, Object> entry : catalogNode.entrySet()) {
                    String name = entry.getKey();
                    Map<String, Object> fields = (Map<String, Object>) entry.getValue();
                    MCPProperties.GameMCPConfig config = mapToConfig(fields);
                    // 外部 config/mcp-catalog.yaml 优先，classpath 只补齐缺失模板。
                    properties.getCatalog().putIfAbsent(name, config);
                }
                log.info("[MCP] 加载目录: {} 个条目", properties.getCatalog().size());
            }
        } catch (Exception e) {
            log.error("[MCP] 加载 mcp-catalog.yaml 失败: {}", e.toString());
        }
    }

    /**
     * 优先加载外部 config 目录下的 mcp-catalog.yaml。
     * <p>
     * Spring Boot 不会按常规 application 规则加载 mcp-catalog.yaml，因此这里由 MCP 注册表显式读取。
     */
    @SuppressWarnings("unchecked")
    private void loadCatalogFromExternalConfig() {
        Path catalogFile = resolvePath(System.getProperty(CONFIG_DIR_PROPERTY, DEFAULT_CONFIG_DIR)).resolve(CATALOG_FILE_NAME);
        if (!Files.isRegularFile(catalogFile)) {
            return;
        }

        try (InputStream in = Files.newInputStream(catalogFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);
            if (root == null) return;

            Map<String, Object> mcpNode = (Map<String, Object>) root.get("mcp");
            if (mcpNode == null) return;
            Map<String, Object> catalogNode = (Map<String, Object>) mcpNode.get("catalog");
            if (catalogNode == null) return;

            for (Map.Entry<String, Object> entry : catalogNode.entrySet()) {
                String name = entry.getKey();
                Map<String, Object> fields = (Map<String, Object>) entry.getValue();
                MCPProperties.GameMCPConfig config = mapToConfig(fields);
                properties.getCatalog().put(name, config);
            }
            log.info("[MCP] 加载外部目录: file={}, entries={}", catalogFile, catalogNode.size());
        } catch (Exception e) {
            log.error("[MCP] 加载外部 mcp-catalog.yaml 失败: {}", e.toString());
        }
    }

    // ── 来源 3：config/mcp-registry.json ──

    private void loadRegistryFile() {
        Path registryFile = resolvePath(properties.getRegistryFile());
        if (!Files.exists(registryFile)) return;
        try {
            String json = Files.readString(registryFile);
            Map<String, MCPProperties.GameMCPConfig> entries = objectMapper.readValue(
                    json, new TypeReference<LinkedHashMap<String, MCPProperties.GameMCPConfig>>() {
                    });
            registered.putAll(entries);
            log.info("[MCP] 加载注册表: {} 个条目", registered.size());
        } catch (IOException e) {
            log.error("[MCP] 读取注册表文件失败: {}", e.toString());
        }
    }

    private void saveRegistryFile() {
        Path registryFile = resolvePath(properties.getRegistryFile());
        try {
            Path parent = registryFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(registryFile.toFile(), registered);
            log.debug("[MCP] 注册表已保存: {} 个条目", registered.size());
        } catch (IOException e) {
            log.error("[MCP] 保存注册表文件失败: {}", e.toString());
        }
    }

    private Path resolvePath(String value) {
        String path = value == null || value.isBlank() ? "." : value.trim();
        return Paths.get(path).toAbsolutePath().normalize();
    }

    // ── 来源 0：application-mcp.yaml 中 installPath 目录解析 ──

    private void resolveCatalog() {
        for (Map.Entry<String, MCPProperties.GameMCPConfig> entry : properties.getGames().entrySet()) {
            String gameName = entry.getKey();
            MCPProperties.GameMCPConfig userConfig = entry.getValue();
            if (userConfig.getInstallPath() == null) continue;

            MCPProperties.GameMCPConfig template = properties.getCatalog().get(gameName);
            if (template == null) {
                log.warn("[MCP] 找不到目录模板: name={}", gameName);
                continue;
            }
            MCPProperties.GameMCPConfig resolved = resolveTemplate(template, userConfig.getInstallPath());
            resolved.setDisplayName(userConfig.getDisplayName() != null
                    ? userConfig.getDisplayName() : template.getDisplayName());
            resolved.setDescription(template.getDescription());
            resolved.setEnabled(userConfig.isEnabled());
            properties.putGame(gameName, resolved);
            log.info("[MCP] 目录条目已解析: name={}, installPath={}", gameName, userConfig.getInstallPath());
        }
    }

    private MCPProperties.GameMCPConfig resolveTemplate(MCPProperties.GameMCPConfig template, String installPath) {
        MCPProperties.GameMCPConfig resolved = template.copy();
        if (resolved.getArgs() != null) {
            String[] resolvedArgs = new String[resolved.getArgs().length];
            for (int i = 0; i < resolved.getArgs().length; i++) {
                resolvedArgs[i] = resolved.getArgs()[i].replace("{{installPath}}", installPath);
            }
            resolved.setArgs(resolvedArgs);
        }
        if (resolved.getUrl() != null) {
            resolved.setUrl(resolved.getUrl().replace("{{installPath}}", installPath));
        }
        if (resolved.getCommand() != null) {
            resolved.setCommand(resolved.getCommand().replace("{{installPath}}", installPath));
        }
        return resolved;
    }

    // ── 合并 ──

    /**
     * 合并优先级（低 → 高）：
     * 1. 用户显式配置（application-mcp.yaml 中的 mcp.games.*）
     * 2. mcp-servers/ 自动发现
     * 3. 运行时注册表（mcp-registry.json）
     * <p>
     * 后写入的覆盖先写入的。
     */
    private void mergeAll() {
        // 用户显式配置保留命令/路径等字段，但默认 adapter/state tool 从 catalog 继承。
        for (Map.Entry<String, MCPProperties.GameMCPConfig> entry : new LinkedHashMap<>(properties.getGames()).entrySet()) {
            if (!entry.getValue().isRegistered()) {
                properties.putGame(entry.getKey(), withCatalogDefaults(entry.getKey(), entry.getValue()));
            }
        }

        // 首先：自动发现的条目作为基础
        for (Map.Entry<String, MCPProperties.GameMCPConfig> entry : discovered.entrySet()) {
            if (!properties.getGames().containsKey(entry.getKey())) {
                properties.putGame(entry.getKey(), withCatalogDefaults(entry.getKey(), entry.getValue()));
            }
            // 如果用户已配置同名游戏，保留用户配置
        }

        // 然后：注册条目覆盖同名配置
        for (Map.Entry<String, MCPProperties.GameMCPConfig> entry : registered.entrySet()) {
            MCPProperties.GameMCPConfig config = withCatalogDefaults(entry.getKey(), entry.getValue());
            config.setRegistered(true);
            properties.putGame(entry.getKey(), config);
        }

        // 清理未解析的 installPath 占位条目
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, MCPProperties.GameMCPConfig> entry : properties.getGames().entrySet()) {
            MCPProperties.GameMCPConfig config = entry.getValue();
            if (config.getInstallPath() != null
                    && config.getCommand() == null
                    && config.getUrl() == null
                    && !config.isRegistered()) {
                toRemove.add(entry.getKey());
            }
        }
        toRemove.forEach(properties::removeGame);
    }

    private MCPProperties.GameMCPConfig withCatalogDefaults(String gameName, MCPProperties.GameMCPConfig source) {
        MCPProperties.GameMCPConfig merged = source.copy();
        MCPProperties.GameMCPConfig catalogConfig = properties.getCatalog().get(gameName);
        if (catalogConfig == null) {
            return merged;
        }
        if (isBlank(merged.getDisplayName()) && !isBlank(catalogConfig.getDisplayName())) {
            merged.setDisplayName(catalogConfig.getDisplayName());
        }
        if (isBlank(merged.getDescription()) && !isBlank(catalogConfig.getDescription())) {
            merged.setDescription(catalogConfig.getDescription());
        }
        if ((isBlank(merged.getAdapter()) || DEFAULT_ADAPTER.equals(merged.getAdapter()))
                && !isBlank(catalogConfig.getAdapter())) {
            merged.setAdapter(catalogConfig.getAdapter());
        }
        if ((isBlank(merged.getStateToolName()) || DEFAULT_STATE_TOOL_NAME.equals(merged.getStateToolName()))
                && !isBlank(catalogConfig.getStateToolName())) {
            merged.setStateToolName(catalogConfig.getStateToolName());
        }
        if (isBlank(merged.getGameplayGuidelines()) && !isBlank(catalogConfig.getGameplayGuidelines())) {
            merged.setGameplayGuidelines(catalogConfig.getGameplayGuidelines());
        }
        if (merged.getStateSettleMaxAttempts() == new MCPProperties.GameMCPConfig().getStateSettleMaxAttempts()) {
            merged.setStateSettleMaxAttempts(catalogConfig.getStateSettleMaxAttempts());
        }
        if (merged.getStateSettleDelayMs() == new MCPProperties.GameMCPConfig().getStateSettleDelayMs()) {
            merged.setStateSettleDelayMs(catalogConfig.getStateSettleDelayMs());
        }
        return merged;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void resolveAndMergeCatalogEntry(String gameName) {
        // 当注销注册条目时，如果存在同名的目录配置，不做自动回退
        // 用户需要重新 POST 来手动启用
    }

    private int countConfigured() {
        return (int) properties.getGames().values().stream()
                .filter(c -> !c.isRegistered())
                .count();
    }

    private MCPProperties.GameMCPConfig mapToConfig(Map<String, Object> fields) {
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        if (fields.get("transport") != null) config.setTransport(fields.get("transport").toString());
        if (fields.get("command") != null) config.setCommand(fields.get("command").toString());
        if (fields.get("url") != null) config.setUrl(fields.get("url").toString());
        if (fields.get("display-name") != null) config.setDisplayName(fields.get("display-name").toString());
        if (fields.get("description") != null) config.setDescription(fields.get("description").toString());
        if (fields.get("adapter") != null) config.setAdapter(fields.get("adapter").toString());
        if (fields.get("state-tool-name") != null) config.setStateToolName(fields.get("state-tool-name").toString());
        if (fields.get("stateToolName") != null) config.setStateToolName(fields.get("stateToolName").toString());
        applyStateSettleConfig(fields, config);
        config.setGameplayGuidelines(readGuidelines(fields));
        if (fields.get("enabled") instanceof Boolean b) config.setEnabled(b);
        if (fields.get("connect-timeout-seconds") instanceof Number n)
            config.setConnectTimeoutSeconds(n.longValue());
        if (fields.get("args") instanceof List<?> list) {
            config.setArgs(list.stream().map(Object::toString).toArray(String[]::new));
        }
        return config;
    }

    private void applyStateSettleConfig(Map<String, Object> fields, MCPProperties.GameMCPConfig config) {
        Object attempts = fields.get("state-settle-max-attempts");
        if (attempts == null) attempts = fields.get("stateSettleMaxAttempts");
        if (attempts instanceof Number n) {
            config.setStateSettleMaxAttempts(n.intValue());
        }
        Object delay = fields.get("state-settle-delay-ms");
        if (delay == null) delay = fields.get("stateSettleDelayMs");
        if (delay instanceof Number n) {
            config.setStateSettleDelayMs(n.longValue());
        }
    }

    private String readGuidelines(Map<String, Object> fields) {
        Object value = fields.get("gameplay-guidelines");
        if (value == null) value = fields.get("gameplayGuidelines");
        if (value == null) value = fields.get("strategy-guidelines");
        if (value == null) value = fields.get("strategyGuidelines");
        if (value instanceof List<?> list) {
            List<String> lines = list.stream()
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .map(s -> s.startsWith("-") ? s : "- " + s)
                    .toList();
            return String.join("\n", lines);
        }
        return value != null ? value.toString() : null;
    }
}
