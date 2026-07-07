package p1.config.mcp.registry;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import p1.config.mcp.MCPProperties;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpRegistrySupportTest {

    @Test
    void resolvesInstallPathInTemplateFields() {
        MCPProperties.GameMCPConfig template = new MCPProperties.GameMCPConfig();
        template.setCommand("{{installPath}}/server.exe");
        template.setUrl("file://{{installPath}}/sse");
        template.setArgs(new String[]{"--root", "{{installPath}}/workspace"});

        MCPProperties.GameMCPConfig resolved = new McpTemplateResolver().resolveTemplate(template, "C:/Games/TestMcp");

        assertEquals("C:/Games/TestMcp/server.exe", resolved.getCommand());
        assertEquals("file://C:/Games/TestMcp/sse", resolved.getUrl());
        assertArrayEquals(new String[]{"--root", "C:/Games/TestMcp/workspace"}, resolved.getArgs());
        assertEquals("{{installPath}}/server.exe", template.getCommand());
    }

    @Test
    void loadsRegistryFileWithReadableChineseLog(@TempDir Path tempDir) throws Exception {
        Path registryFile = tempDir.resolve("mcp-registry.json");
        ObjectMapper objectMapper = new ObjectMapper();
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setCommand("uv");
        objectMapper.writeValue(registryFile.toFile(), Map.of("STS2MCP", config));

        Logger logger = (Logger) LoggerFactory.getLogger(McpRegistryFileStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Map<String, MCPProperties.GameMCPConfig> loaded = new McpRegistryFileStore(objectMapper).load(registryFile);

            assertEquals("uv", loaded.get("STS2MCP").getCommand());
            assertTrue(appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("已加载注册表") && message.contains("1 个条目")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void savesRegistryFileWithReadableChineseLog(@TempDir Path tempDir) {
        Path registryFile = tempDir.resolve("mcp-registry.json");
        Map<String, MCPProperties.GameMCPConfig> registered = new LinkedHashMap<>();
        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setCommand("uv");
        registered.put("STS2MCP", config);

        Logger logger = (Logger) LoggerFactory.getLogger(McpRegistryFileStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new McpRegistryFileStore(new ObjectMapper()).save(registryFile, registered);

            assertTrue(appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("已保存注册表") && message.contains("1 个条目")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}