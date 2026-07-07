package p1.config.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class McpServerRegistryTest {

    @Test
    void discoveredPythonServerUsesCatalogTemplateArgs() throws Exception {
        Path tempDir = Files.createTempDirectory(Path.of("target"), "mcp-registry-");
        Path configDir = tempDir.resolve("config");
        Path serversDir = tempDir.resolve("mcp-servers");
        Path sts2McpDir = serversDir.resolve("STS2MCP").resolve("mcp");
        Files.createDirectories(configDir);
        Files.createDirectories(sts2McpDir);
        Files.writeString(sts2McpDir.resolve("pyproject.toml"), "[project]\nname = \"sts2-mcp\"\n");
        Files.writeString(sts2McpDir.resolve("server.py"), "print('server')\n");
        Files.writeString(configDir.resolve("mcp-catalog.yaml"), """
                mcp:
                  catalog:
                    STS2MCP:
                      display-name: "STS2"
                      transport: stdio
                      adapter: sts2
                      state-tool-name: get_game_state
                      command: "uv"
                      args: ["run", "--directory", "{{installPath}}", "python", "server.py", "--no-trust-env"]
                """);

        MCPProperties properties = new MCPProperties();
        properties.setServersDirectory(serversDir.toString());
        properties.setRegistryFile(tempDir.resolve("mcp-registry.json").toString());
        String previousConfigDir = System.getProperty("arclight.config.dir");
        System.setProperty("arclight.config.dir", configDir.toString());
        try {
            new McpServerRegistry(properties).init();
        } finally {
            if (previousConfigDir == null) {
                System.clearProperty("arclight.config.dir");
            } else {
                System.setProperty("arclight.config.dir", previousConfigDir);
            }
        }

        MCPProperties.GameMCPConfig config = properties.getGames().get("STS2MCP");
        assertNotNull(config);
        assertEquals("uv", config.getCommand());
        assertArrayEquals(new String[]{
                "run",
                "--directory",
                sts2McpDir.toAbsolutePath().normalize().toString(),
                "python",
                "server.py",
                "--no-trust-env"
        }, config.getArgs());
        assertEquals("sts2", config.getAdapter());
        assertEquals("get_game_state", config.getStateToolName());
    }
}