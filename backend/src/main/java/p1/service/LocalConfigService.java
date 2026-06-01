package p1.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import p1.config.ExternalConfigDirectories;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地配置文件读写服务。
 * <p>
 * 该服务只允许写入项目明确暴露给前端的配置项，避免本地管理入口退化成任意文件写入。
 */
@Service
@Slf4j
public class LocalConfigService {

    private static final Pattern YAML_SCALAR_PATTERN = Pattern.compile("^(\\s*)([A-Za-z0-9_-]+):(?:\\s*(.*))?$");
    private static final Pattern YAML_LIST_ITEM_PATTERN = Pattern.compile("^(\\s*)-\\s*(.*)$");

    private final ConfigurableApplicationContext applicationContext;
    private final Map<String, ConfigDefinition> definitions;

    public LocalConfigService(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.definitions = buildDefinitions();
    }

    public ConfigCatalogSnapshot listConfigs() {
        Path configDir = resolveConfigDir();
        List<EditableConfigPage> pages = definitions.values().stream()
                .filter(definition -> Files.isRegularFile(configDir.resolve(definition.fileName()).normalize()))
                .map(definition -> renderPage(configDir, definition))
                .toList();
        return new ConfigCatalogSnapshot(configDir.toString(), true, pages, Instant.now().toString());
    }

    public EditableConfigPage getConfig(String fileName) {
        Path configDir = resolveConfigDir();
        ConfigDefinition definition = requireDefinition(fileName);
        Path file = requireConfigFile(configDir, definition);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("配置文件不存在: " + definition.fileName());
        }
        return renderPage(configDir, definition);
    }

    public EditableConfigPage saveConfig(String fileName, ConfigUpdateRequest request) {
        ConfigDefinition definition = requireDefinition(fileName);
        Path configDir = resolveConfigDir();
        Path file = requireConfigFile(configDir, definition);
        Map<String, Object> values = request == null || request.values() == null ? Map.of() : request.values();
        Map<String, Object> updates = new LinkedHashMap<>();
        for (FieldDefinition field : definition.fields()) {
            if (values.containsKey(field.key())) {
                updates.put(field.path(), normalizeValue(field, values.get(field.key())));
            }
        }
        if (updates.isEmpty() && !values.isEmpty()) {
            throw new IllegalArgumentException("没有可写入的配置项: " + definition.fileName());
        }
        updateYaml(file, definition, updates);
        return renderPage(configDir, definition);
    }

    public void requestBackendRestart() {
        Thread restartThread = new Thread(() -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int exitCode = applicationContext == null ? 0 : SpringApplication.exit(applicationContext, () -> 0);
            log.info("[本地配置] 后端重启请求已触发: exitCode={}", exitCode);
            System.exit(exitCode);
        }, "backend-restart-request");
        restartThread.setDaemon(false);
        restartThread.start();
    }

    private EditableConfigPage renderPage(Path configDir, ConfigDefinition definition) {
        Path file = requireConfigFile(configDir, definition);
        List<String> lines = readLines(file);
        List<YamlNode> nodes = parseNodes(lines);
        Properties yamlProperties = readYamlProperties(file);
        List<EditableConfigField> fields = definition.fields().stream()
                .map(field -> renderField(field, lines, nodes, yamlProperties))
                .toList();
        return new EditableConfigPage(
                definition.fileName(),
                definition.title(),
                definition.description(),
                file.toString(),
                fields);
    }

    private EditableConfigField renderField(FieldDefinition field,
                                            List<String> lines,
                                            List<YamlNode> nodes,
                                            Properties yamlProperties) {
        String value = field.type() == FieldType.LIST
                ? readListValue(lines, nodes, field.path())
                : readScalarValue(nodes, yamlProperties, field.path());
        return new EditableConfigField(
                field.key(),
                field.path(),
                field.label(),
                field.description(),
                field.type().wireName(),
                value,
                field.options(),
                field.sensitive(),
                field.placeholder());
    }

    private String readScalarValue(List<YamlNode> nodes, Properties yamlProperties, String path) {
        String value = yamlProperties.getProperty(path);
        if (value != null) {
            return value;
        }
        return findNode(nodes, path)
                .map(node -> normalizeScalar(node.rawValue()))
                .orElse("");
    }

    private String readListValue(List<String> lines, List<YamlNode> nodes, String path) {
        YamlNode node = findNode(nodes, path).orElse(null);
        if (node == null) {
            return "";
        }
        String raw = normalizeScalar(node.rawValue());
        if (raw.startsWith("[") && raw.endsWith("]")) {
            return String.join("\n", parseInlineList(raw));
        }
        List<String> values = new ArrayList<>();
        for (int index = node.lineIndex() + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            Matcher scalarMatcher = YAML_SCALAR_PATTERN.matcher(line);
            if (scalarMatcher.matches() && !line.trim().startsWith("#")) {
                int indent = scalarMatcher.group(1).length();
                if (indent <= node.indent()) {
                    break;
                }
            }
            Matcher listMatcher = YAML_LIST_ITEM_PATTERN.matcher(line);
            if (listMatcher.matches() && listMatcher.group(1).length() > node.indent()) {
                values.add(normalizeScalar(listMatcher.group(2)));
            }
        }
        return String.join("\n", values);
    }

    private void updateYaml(Path file, ConfigDefinition definition, Map<String, Object> updates) {
        List<String> lines = readLines(file);
        for (FieldDefinition field : definition.fields()) {
            if (!updates.containsKey(field.path())) {
                continue;
            }
            Object value = updates.get(field.path());
            lines = field.type() == FieldType.LIST
                    ? upsertList(lines, field.path(), normalizeList(value))
                    : upsertScalar(lines, field.path(), value);
        }
        try {
            Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new IllegalStateException("写入配置文件失败: " + file, e);
        }
    }

    private List<String> upsertScalar(List<String> lines, String path, Object value) {
        List<YamlNode> nodes = parseNodes(lines);
        Optional<YamlNode> existing = findNode(nodes, path);
        String[] parts = path.split("\\.");
        if (existing.isPresent()) {
            YamlNode node = existing.get();
            lines.set(node.lineIndex(), spaces(node.indent()) + parts[parts.length - 1] + ": " + formatScalar(value));
            return lines;
        }
        return insertMissingPath(lines, path, formatScalar(value), false);
    }

    private List<String> upsertList(List<String> lines, String path, List<String> values) {
        List<YamlNode> nodes = parseNodes(lines);
        Optional<YamlNode> existing = findNode(nodes, path);
        String[] parts = path.split("\\.");
        if (existing.isEmpty()) {
            List<String> updated = insertMissingPath(lines, path, "", true);
            return upsertList(updated, path, values);
        }

        YamlNode node = existing.get();
        int endIndex = node.lineIndex() + 1;
        while (endIndex < lines.size()) {
            Matcher matcher = YAML_SCALAR_PATTERN.matcher(lines.get(endIndex));
            if (matcher.matches() && !lines.get(endIndex).trim().startsWith("#")) {
                int indent = matcher.group(1).length();
                if (indent <= node.indent()) {
                    break;
                }
            }
            endIndex++;
        }
        List<String> replacement = new ArrayList<>();
        replacement.add(spaces(node.indent()) + parts[parts.length - 1] + ":");
        for (String value : values) {
            replacement.add(spaces(node.indent() + 2) + "- " + formatScalar(value));
        }
        List<String> updated = new ArrayList<>(lines);
        updated.subList(node.lineIndex(), endIndex).clear();
        updated.addAll(node.lineIndex(), replacement);
        return updated;
    }

    private List<String> insertMissingPath(List<String> lines, String path, String formattedValue, boolean listNode) {
        String[] parts = path.split("\\.");
        String parentPath = "";
        int parentIndent = -2;
        List<String> updated = new ArrayList<>(lines);
        for (int index = 0; index < parts.length; index++) {
            String segment = parts[index];
            String currentPath = parentPath.isBlank() ? segment : parentPath + "." + segment;
            boolean leaf = index == parts.length - 1;
            List<YamlNode> nodes = parseNodes(updated);
            Optional<YamlNode> current = findNode(nodes, currentPath);
            if (current.isPresent()) {
                parentPath = currentPath;
                parentIndent = current.get().indent();
                continue;
            }
            int indent = parentIndent + 2;
            String line = spaces(indent) + segment + (leaf && !listNode ? ": " + formattedValue : ":");
            int insertionIndex = insertionIndex(updated, nodes, parentPath);
            updated.add(insertionIndex, line);
            parentPath = currentPath;
            parentIndent = indent;
        }
        return updated;
    }

    private int insertionIndex(List<String> lines, List<YamlNode> nodes, String parentPath) {
        if (parentPath == null || parentPath.isBlank()) {
            return lines.size();
        }
        YamlNode parent = findNode(nodes, parentPath).orElse(null);
        if (parent == null) {
            return lines.size();
        }
        int index = parent.lineIndex() + 1;
        while (index < lines.size()) {
            Matcher matcher = YAML_SCALAR_PATTERN.matcher(lines.get(index));
            if (matcher.matches() && !lines.get(index).trim().startsWith("#")) {
                int indent = matcher.group(1).length();
                if (indent <= parent.indent()) {
                    break;
                }
            }
            index++;
        }
        return index;
    }

    private List<YamlNode> parseNodes(List<String> lines) {
        List<YamlNode> nodes = new ArrayList<>();
        List<YamlStackEntry> stack = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            Matcher matcher = YAML_SCALAR_PATTERN.matcher(line);
            if (!matcher.matches() || line.trim().startsWith("#")) {
                continue;
            }
            int indent = matcher.group(1).length();
            String key = normalizeKey(matcher.group(2));
            String rawValue = matcher.group(3) == null ? "" : matcher.group(3);
            while (!stack.isEmpty() && stack.getLast().indent() >= indent) {
                stack.removeLast();
            }
            String path = joinPath(stack, key);
            nodes.add(new YamlNode(path, indent, index, rawValue));
            if (rawValue.isBlank()) {
                stack.add(new YamlStackEntry(indent, path));
            }
        }
        return nodes;
    }

    private Path resolveConfigDir() {
        Path configDir = ExternalConfigDirectories.resolveConfiguredDir();
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            throw new IllegalStateException("创建外部配置目录失败: " + configDir, e);
        }
        return configDir;
    }

    private Path requireConfigFile(Path configDir, ConfigDefinition definition) {
        Path file = configDir.resolve(definition.fileName()).normalize();
        if (!file.startsWith(configDir)) {
            throw new IllegalArgumentException("配置文件路径越界: " + definition.fileName());
        }
        if (Files.exists(file)) {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("配置路径不是文件: " + file);
            }
            return file;
        }
        if (definition.templateLocation() == null || definition.templateLocation().isBlank()) {
            throw new IllegalArgumentException("配置文件不存在: " + definition.fileName());
        }
        ClassPathResource resource = new ClassPathResource(definition.templateLocation());
        try (InputStream in = resource.getInputStream()) {
            Files.copy(in, file);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("创建外部配置失败: " + file, e);
        }
    }

    private ConfigDefinition requireDefinition(String fileName) {
        String normalized = normalizeFileName(fileName);
        ConfigDefinition definition = definitions.get(normalized);
        if (definition == null) {
            throw new IllegalArgumentException("不支持编辑该配置文件: " + fileName);
        }
        return definition;
    }

    private List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取配置文件失败: " + file, e);
        }
    }

    private Properties readYamlProperties(Path file) {
        try {
            YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
            factory.setResources(new FileSystemResource(file));
            Properties properties = factory.getObject();
            return properties == null ? new Properties() : properties;
        } catch (Exception e) {
            log.debug("[本地配置] YAML 属性解析失败，回退到文本解析: file={}, reason={}", file, e.getMessage());
            return new Properties();
        }
    }

    private Optional<YamlNode> findNode(List<YamlNode> nodes, String path) {
        return nodes.stream()
                .filter(node -> node.path().equals(path))
                .findFirst();
    }

    private Object normalizeValue(FieldDefinition field, Object rawValue) {
        return switch (field.type()) {
            case BOOLEAN -> normalizeBoolean(rawValue);
            case NUMBER -> normalizeNumber(rawValue);
            case LIST -> normalizeList(rawValue);
            default -> rawValue == null ? "" : rawValue.toString().trim();
        };
    }

    private boolean normalizeBoolean(Object rawValue) {
        if (rawValue instanceof Boolean bool) {
            return bool;
        }
        String text = rawValue == null ? "" : rawValue.toString().trim().toLowerCase();
        return List.of("true", "yes", "1", "on").contains(text);
    }

    private Number normalizeNumber(Object rawValue) {
        if (rawValue instanceof Number number) {
            return number;
        }
        String text = rawValue == null ? "0" : rawValue.toString().trim();
        if (text.contains(".")) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private List<String> normalizeList(Object rawValue) {
        if (rawValue instanceof Collection<?> collection) {
            return collection.stream()
                    .map(value -> value == null ? "" : value.toString().trim())
                    .filter(value -> !value.isBlank())
                    .toList();
        }
        String text = rawValue == null ? "" : rawValue.toString();
        return text.lines()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private String normalizeScalar(String value) {
        String trimmed = stripInlineComment(value).trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private List<String> parseInlineList(String rawValue) {
        String body = rawValue.substring(1, rawValue.length() - 1).trim();
        if (body.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char quoteChar = 0;
        for (int index = 0; index < body.length(); index++) {
            char ch = body.charAt(index);
            if ((ch == '"' || ch == '\'') && (index == 0 || body.charAt(index - 1) != '\\')) {
                if (!quoted) {
                    quoted = true;
                    quoteChar = ch;
                } else if (quoteChar == ch) {
                    quoted = false;
                }
            }
            if (ch == ',' && !quoted) {
                values.add(normalizeScalar(current.toString()));
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        values.add(normalizeScalar(current.toString()));
        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private String stripInlineComment(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        boolean quoted = false;
        char quoteChar = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if ((current == '"' || current == '\'') && (index == 0 || value.charAt(index - 1) != '\\')) {
                if (!quoted) {
                    quoted = true;
                    quoteChar = current;
                } else if (quoteChar == current) {
                    quoted = false;
                }
            }
            if (current == '#' && !quoted && (index == 0 || Character.isWhitespace(value.charAt(index - 1)))) {
                return value.substring(0, index);
            }
        }
        return value;
    }

    private String formatScalar(Object value) {
        if (value instanceof Boolean bool) {
            return Boolean.toString(bool);
        }
        if (value instanceof Float || value instanceof Double) {
            return value.toString();
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        String text = value == null ? "" : value.toString().trim();
        if (text.isEmpty()) {
            return "\"\"";
        }
        if (text.matches("[\\p{L}\\p{N}_./:@${}\\\\\\-\\[\\],]+")) {
            return text;
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String joinPath(List<YamlStackEntry> stack, String key) {
        if (stack.isEmpty()) {
            return key;
        }
        return stack.getLast().path() + "." + key;
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().replace('_', '-');
    }

    private String normalizeFileName(String fileName) {
        Path path = Path.of(fileName == null ? "" : fileName);
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    private String spaces(int count) {
        return " ".repeat(Math.max(0, count));
    }

    private Map<String, ConfigDefinition> buildDefinitions() {
        Map<String, ConfigDefinition> result = new LinkedHashMap<>();
        register(result, new ConfigDefinition(
                "application-ai.yaml",
                "AI 模型",
                "配置轻模型、重模型和 embedding 模型的 API 地址、模型名与密钥。",
                "config-template/application-ai.yaml",
                List.of(
                        select("assistant.mode", "assistant.mode", "运行模式", "api 使用远程 API；local 使用本地模型配置。", List.of("api", "local")),
                        password("assistant.api.light-model.api-key", "assistant.api.light-model.api-key", "轻模型 API Key", "parser、checker 等低延迟任务使用的模型密钥。"),
                        text("assistant.api.light-model.base-url", "assistant.api.light-model.base-url", "轻模型地址", "轻模型 OpenAI 兼容接口地址。"),
                        text("assistant.api.light-model.model-name", "assistant.api.light-model.model-name", "轻模型名称", "轻量任务实际请求的模型名。"),
                        password("assistant.api.heavy-model.api-key", "assistant.api.heavy-model.api-key", "重模型 API Key", "RP、supervisor 等复杂任务使用的模型密钥。"),
                        text("assistant.api.heavy-model.base-url", "assistant.api.heavy-model.base-url", "重模型地址", "重模型 OpenAI 兼容接口地址；留空时由配置绑定逻辑继承轻模型。"),
                        text("assistant.api.heavy-model.model-name", "assistant.api.heavy-model.model-name", "重模型名称", "复杂任务实际请求的模型名。"),
                        password("assistant.api.embedding-model.api-key", "assistant.api.embedding-model.api-key", "Embedding API Key", "长期记忆向量化使用的密钥。"),
                        text("assistant.api.embedding-model.base-url", "assistant.api.embedding-model.base-url", "Embedding 地址", "Embedding OpenAI 兼容接口地址。"),
                        text("assistant.api.embedding-model.model-name", "assistant.api.embedding-model.model-name", "Embedding 模型", "长期记忆向量化使用的模型名。")
                )));
        register(result, new ConfigDefinition(
                "application-ai-services.yaml",
                "AI 服务分配",
                "把 RP、parser、checker、supervisor 分配到轻模型或重模型，并控制应用侧 LLM 日志显示。",
                "config-template/application-ai-services.yaml",
                List.of(
                        modelTier("assistant.model-services.rp", "RP 模型", "角色扮演主回复使用的模型强度。"),
                        modelTier("assistant.model-services.parser", "Parser 模型", "把 RP 自然语言动作翻译成游戏操作的模型强度。"),
                        modelTier("assistant.model-services.checker", "Checker 模型", "轻量校验与降级链路使用的模型强度。"),
                        modelTier("assistant.model-services.supervisor", "Supervisor 模型", "复杂工具任务调度使用的模型强度。"),
                        bool("assistant.llm-logs.console.rp", "RP 控制台日志", "是否在控制台打印 RP 的最新请求和回复。"),
                        bool("assistant.llm-logs.console.parser", "Parser 控制台日志", "是否在控制台打印 parser 的最新请求和回复。"),
                        bool("assistant.llm-logs.console.checker", "Checker 控制台日志", "是否在控制台打印 checker 的最新请求和回复。"),
                        bool("assistant.llm-logs.console.supervisor", "Supervisor 控制台日志", "是否在控制台打印 supervisor 的最新请求和回复。")
                )));
        register(result, new ConfigDefinition(
                "application-frontend.yaml",
                "前端默认值",
                "配置 Web 和 Qt 前端启动时读取的默认设置；用户在前端本地保存过设置时，本文件只作为重置和首次启动默认值。",
                "application-frontend.yaml",
                List.of(
                        text("frontend.settings.backend-base-url", "本机后端地址", "Web 前端默认连接的后端服务地址。"),
                        text("frontend.settings.character-name", "默认角色名", "为空时由前端当前角色选择或请求参数决定。"),
                        text("frontend.settings.workspace-name", "工作区名称", "主界面展示的工作区名称。"),
                        text("frontend.settings.operator-name", "用户名称", "主界面展示的用户称呼。"),
                        select("frontend.web.settings.theme-id", "frontend.web.settings.theme-id", "Web 主题", "Web 前端首次启动使用的主题。", List.of("arklight", "plain-web")),
                        text("frontend.web.settings.session-id", "Web 会话 ID", "为空时使用前端生成或本地保存的会话。"),
                        bool("frontend.web.settings.boot-animation-enabled", "Web 启动动画", "是否在 Web 前端显示启动动画。"),
                        number("frontend.web.settings.boot-duration-ms", "Web 启动动画时长", "Web 前端启动动画时长，单位毫秒。"),
                        number("frontend.web.settings.response-delay-ms", "Web 回复显示延迟", "Web 前端展示回复前的默认延迟，单位毫秒。"),
                        bool("frontend.web.settings.short-mode-enabled", "Web 短句模式", "是否默认按短句模式展示和请求回复。"),
                        number("frontend.web.settings.mote-count", "Web 背景粒子数量", "Web 前端背景粒子的默认数量。"),
                        text("frontend.web.settings.game-name", "Web 默认游戏名", "游戏模式默认连接的 MCP 游戏名。"),
                        text("frontend.web.settings.game-session-id", "Web 游戏会话 ID", "为空时使用聊天会话作为游戏会话。"),
                        text("frontend.web.settings.game-rp-session-id", "Web 游戏 RP 会话 ID", "为空时使用聊天会话作为游戏 RP 会话。")
                )));
        register(result, new ConfigDefinition(
                "application-tts.yaml",
                "语音合成",
                "配置当前 TTS 引擎、切段参数、VoxCPM2 和 GPT-SoVITS 的推理参数。",
                "config-template/application-tts.yaml",
                List.of(
                        bool("tts.enabled", "启用语音", "关闭后不再请求 TTS 合成。"),
                        select("tts.provider", "tts.provider", "语音引擎", "选择当前使用的 TTS provider。", List.of("voxcpm2-http", "gpt-sovits-http")),
                        number("tts.first-chunk-min-chars", "首段最小长度", "首个语音片段至少积累多少个非空白字符再合成。"),
                        number("tts.first-chunk-chars", "首段目标长度", "首个语音片段的目标字符数，影响首响速度。"),
                        number("tts.max-chunk-chars", "后续分段长度", "后续语音片段超出该长度后寻找最近句末标点切分。"),
                        text("tts.gpt-so-vits.base-url", "tts.gpt-so-vits.base-url", "GPT-SoVITS 地址", "GPT-SoVITS HTTP 服务地址。"),
                        text("tts.gpt-so-vits.ref-audio-path", "GPT-SoVITS 主参考音频", "克隆音色使用的主参考音频路径。"),
                        list("tts.gpt-so-vits.aux-ref-audio-paths", "GPT-SoVITS 辅助参考音频", "每行一个辅助参考音频路径。"),
                        textarea("tts.gpt-so-vits.prompt-text", "GPT-SoVITS 参考文本", "参考音频对应文本。"),
                        select("tts.gpt-so-vits.text-lang", "tts.gpt-so-vits.text-lang", "合成文本语言", "待合成文本语言。", List.of("zh", "en", "ja")),
                        select("tts.gpt-so-vits.prompt-lang", "tts.gpt-so-vits.prompt-lang", "参考文本语言", "参考音频文本语言。", List.of("zh", "en", "ja")),
                        number("tts.gpt-so-vits.top-k", "GPT-SoVITS top-k", "采样候选数量。"),
                        number("tts.gpt-so-vits.top-p", "GPT-SoVITS top-p", "核采样阈值。"),
                        number("tts.gpt-so-vits.temperature", "GPT-SoVITS temperature", "采样温度，越高越随机。"),
                        number("tts.gpt-so-vits.speed-factor", "GPT-SoVITS 语速", "合成侧语速倍率。"),
                        number("tts.gpt-so-vits.repetition-penalty", "GPT-SoVITS 重复惩罚", "降低重复音节或重复片段的采样惩罚。"),
                        number("tts.gpt-so-vits.sample-steps", "GPT-SoVITS 推理步数", "推理步数越低速度越快，稳定性可能下降。"),
                        text("tts.vox-cpm2.base-url", "tts.vox-cpm2.base-url", "VoxCPM2 地址", "VoxCPM2 HTTP 服务地址。"),
                        text("tts.vox-cpm2.reference-wav-path", "VoxCPM2 参考音频", "VoxCPM2 高保真克隆使用的参考音频路径。"),
                        textarea("tts.vox-cpm2.prompt-text", "VoxCPM2 参考文本", "VoxCPM2 参考音频对应文本。"),
                        number("tts.vox-cpm2.cfg-value", "VoxCPM2 CFG", "控制条件强度，过高可能导致声音用力或撕裂。"),
                        number("tts.vox-cpm2.inference-timesteps", "VoxCPM2 推理步数", "推理步数越低速度越快，稳定性可能下降。"),
                        bool("tts.vox-cpm2.normalize", "VoxCPM2 normalize", "是否对输入音频进行响度归一化。"),
                        bool("tts.vox-cpm2.denoise", "VoxCPM2 denoise", "是否对参考音频进行降噪。"),
                        bool("tts.vox-cpm2.streaming-enabled", "VoxCPM2 流式", "是否启用 VoxCPM2 流式输出。")
                )));
        register(result, new ConfigDefinition(
                "mcp-catalog.yaml",
                "MCP 游戏目录",
                "配置本地 MCP 游戏服务目录、启动命令、状态读取和 RP 可见策略提示。",
                "mcp-catalog.yaml",
                List.of(
                        text("mcp.catalog.STS2MCP.display-name", "显示名称", "前端和日志里展示的游戏名称。"),
                        textarea("mcp.catalog.STS2MCP.description", "说明", "MCP 条目的用途和安装前提。"),
                        text("mcp.catalog.STS2MCP.command", "启动命令", "启动 MCP server 的命令。"),
                        list("mcp.catalog.STS2MCP.args", "启动参数", "每行一个启动参数，{{installPath}} 会替换为安装目录。"),
                        list("mcp.catalog.STS2MCP.gameplay-guidelines", "游戏策略提示", "每行一条注入给 RP 游戏控制协议的策略提示。")
                )));
        return result;
    }

    private void register(Map<String, ConfigDefinition> result, ConfigDefinition definition) {
        result.put(definition.fileName(), definition);
    }

    private FieldDefinition text(String key, String label, String description) {
        return text(key, key, label, description);
    }

    private FieldDefinition text(String key, String path, String label, String description) {
        return new FieldDefinition(key, path, label, description, FieldType.TEXT, List.of(), false, "");
    }

    private FieldDefinition password(String key, String path, String label, String description) {
        return new FieldDefinition(key, path, label, description, FieldType.PASSWORD, List.of(), true, "");
    }

    private FieldDefinition textarea(String key, String label, String description) {
        return new FieldDefinition(key, key, label, description, FieldType.TEXTAREA, List.of(), false, "");
    }

    private FieldDefinition number(String key, String label, String description) {
        return new FieldDefinition(key, key, label, description, FieldType.NUMBER, List.of(), false, "");
    }

    private FieldDefinition bool(String key, String label, String description) {
        return new FieldDefinition(key, key, label, description, FieldType.BOOLEAN, List.of(), false, "");
    }

    private FieldDefinition list(String key, String label, String description) {
        return new FieldDefinition(key, key, label, description, FieldType.LIST, List.of(), false, "");
    }

    private FieldDefinition modelTier(String key, String label, String description) {
        return select(key, key, label, description, List.of("light", "heavy"));
    }

    private FieldDefinition select(String key, String path, String label, String description, List<String> options) {
        return new FieldDefinition(key, path, label, description, FieldType.SELECT, options, false, "");
    }

    private enum FieldType {
        TEXT("text"),
        PASSWORD("password"),
        NUMBER("number"),
        BOOLEAN("boolean"),
        SELECT("select"),
        TEXTAREA("textarea"),
        LIST("list");

        private final String wireName;

        FieldType(String wireName) {
            this.wireName = wireName;
        }

        private String wireName() {
            return wireName;
        }
    }

    private record ConfigDefinition(
            String fileName,
            String title,
            String description,
            String templateLocation,
            List<FieldDefinition> fields
    ) {
    }

    private record FieldDefinition(
            String key,
            String path,
            String label,
            String description,
            FieldType type,
            List<String> options,
            boolean sensitive,
            String placeholder
    ) {
    }

    public record ConfigCatalogSnapshot(
            String configDir,
            boolean restartSupported,
            List<EditableConfigPage> configs,
            String readAt
    ) {
    }

    public record EditableConfigPage(
            String fileName,
            String title,
            String description,
            String filePath,
            List<EditableConfigField> fields
    ) {
    }

    public record EditableConfigField(
            String key,
            String path,
            String label,
            String description,
            String type,
            String value,
            List<String> options,
            boolean sensitive,
            String placeholder
    ) {
    }

    public record ConfigUpdateRequest(Map<String, Object> values) {
    }

    private record YamlStackEntry(int indent, String path) {
    }

    private record YamlNode(String path, int indent, int lineIndex, String rawValue) {
    }
}
