package p1.config.mcp.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import p1.config.mcp.MCPProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Slf4j
public final class McpRegistryFileStore {
    private final ObjectMapper objectMapper;

    public Map<String, MCPProperties.GameMCPConfig> load(Path registryFile) {
        if (!Files.exists(registryFile)) {
            return new LinkedHashMap<>();
        }
        try {
            String json = Files.readString(registryFile);
            Map<String, MCPProperties.GameMCPConfig> entries = objectMapper.readValue(
                    json, new TypeReference<LinkedHashMap<String, MCPProperties.GameMCPConfig>>() {
                    });
            log.info("[MCP] 已加载注册表: {} 个条目", entries.size());
            return new LinkedHashMap<>(entries);
        } catch (IOException e) {
            log.error("[MCP] 加载注册表失败: {}", e.toString());
            return new LinkedHashMap<>();
        }
    }

    public void save(Path registryFile, Map<String, MCPProperties.GameMCPConfig> registered) {
        try {
            Path parent = registryFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(registryFile.toFile(), registered);
            log.info("[MCP] 已保存注册表: {} 个条目", registered.size());
        } catch (IOException e) {
            log.error("[MCP] 保存注册表失败: {}", e.toString());
        }
    }
}