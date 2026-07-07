package p1.config.mcp.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import p1.config.mcp.MCPProperties;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@CustomLog
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
            log.info(LogDomain.MCP, "registry.loaded", LogOutcome.SUCCEEDED, fields(registryFile, "entries", entries.size()));
            return new LinkedHashMap<>(entries);
        } catch (IOException e) {
            log.error(LogDomain.MCP, "registry.load_failed", LogOutcome.FAILED, fields(registryFile, "reason", e.toString()), e);
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
            log.info(LogDomain.MCP, "registry.saved", LogOutcome.SUCCEEDED, fields(registryFile, "entries", registered.size()));
        } catch (IOException e) {
            log.error(LogDomain.MCP, "registry.load_failed", LogOutcome.FAILED, fields(registryFile, "reason", e.toString()), e);
        }
    }
    private Map<String, Object> fields(Path registryFile, Object... keyValues) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("file", registryFile);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            fields.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return fields;
    }
}