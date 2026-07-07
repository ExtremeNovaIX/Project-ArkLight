package p1.component.agent.gamer;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.logging.DefaultMcpLogMessageHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.ToolProvider;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.component.agent.gamer.adapter.core.SchemaNormalizingMcpTransport;
import p1.config.mcp.GameProperties;
import p1.config.mcp.MCPProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 为游戏 MCP 服务器创建 langchain4j MCP 客户端和 ToolProvider。
 */
@Component
@RequiredArgsConstructor
@CustomLog
public class GamerMCPClientFactory {

    private final MCPProperties mcpProperties;
    private final GameProperties gameProperties;
    private final Map<String, McpClient> clientCache = new ConcurrentHashMap<>();
    private final Map<String, ToolProvider> providerCache = new ConcurrentHashMap<>();

    /**
     * 获取或创建一个游戏的 ToolProvider。
     */
    public ToolProvider getToolProvider(String gameName) {
        return providerCache.computeIfAbsent(gameName, name -> {
            McpClient client = getClient(name);
            ToolProvider tp = McpToolProvider.builder()
                    .mcpClients(List.of(client))
                    .failIfOneServerFails(false)
                    .build();
            log.info(LogDomain.MCP, "mcp.tool_provider_created", LogOutcome.SUCCEEDED, "name", name);
            return tp;
        });
    }

    /**
     * 获取或创建一个游戏的 MCP 客户端。
     */
    public McpClient getClient(String gameName) {
        return clientCache.computeIfAbsent(gameName, name -> {
            MCPProperties.GameMCPConfig config = mcpProperties.getGames().get(name);
            if (config == null || !config.isEnabled()) {
                throw new IllegalStateException("未找到已启用的 MCP 配置: " + name);
            }

            McpTransport transport = buildTransport(config, name);
            try {
                McpClient client = new DefaultMcpClient.Builder()
                        .transport(transport)
                        .clientName(gameProperties.getClientName())
                        .clientVersion(gameProperties.getClientVersion())
                        .toolExecutionTimeout(Duration.ofSeconds(
                                mcpProperties.getClient().getToolTimeoutSeconds()))
                        .logHandler(new DefaultMcpLogMessageHandler())
                        .build();
                log.info(LogDomain.MCP, "mcp.client_created", LogOutcome.SUCCEEDED, "name", name);
                return client;
            } catch (RuntimeException e) {
                try {
                    transport.close();
                } catch (Exception ignored) {
                }
                throw e;
            }
        });
    }

    /**
     * 根据配置构建对应的传输层（SSE 或 Stdio）。
     */
    private McpTransport buildTransport(MCPProperties.GameMCPConfig config, String gameName) {
        String transportType = config.getTransport() != null ? config.getTransport().toLowerCase() : "stdio";

        McpTransport transport;
        if ("sse".equals(transportType)) {
            if (!StringUtils.hasText(config.getUrl())) {
                throw new IllegalStateException("MCP SSE 配置缺少 url: " + gameName);
            }
            log.info(LogDomain.MCP, "mcp.sse_transport_created", LogOutcome.SUCCEEDED, "gameName", gameName, "url", config.getUrl());
            transport = new HttpMcpTransport.Builder()
                    .sseUrl(config.getUrl())
                    .timeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                    .logRequests(false)
                    .logResponses(false)
                    .build();
        } else {
            // 默认 stdio
            if (!StringUtils.hasText(config.getCommand())) {
                throw new IllegalStateException("MCP stdio 配置缺少 command: " + gameName);
            }
            List<String> command = new ArrayList<>();
            command.add(config.getCommand());
            if (config.getArgs() != null) {
                command.addAll(Arrays.asList(config.getArgs()));
            }
            log.info(LogDomain.MCP, "mcp.stdio_transport_created", LogOutcome.SUCCEEDED, "gameName", gameName, "command", command);
            transport = new StdioMcpTransport.Builder()
                    .command(command)
                    .logEvents(false)
                    .build();
        }

        return new SchemaNormalizingMcpTransport(transport);
    }

    public boolean isGameConfigured(String gameName) {
        MCPProperties.GameMCPConfig config = mcpProperties.getGames().get(gameName);
        return config != null && config.isEnabled();
    }

    public String getGameDisplayName(String gameName) {
        MCPProperties.GameMCPConfig config = mcpProperties.getGames().get(gameName);
        if (config == null) return gameName;
        return config.getDisplayName() != null ? config.getDisplayName() : gameName;
    }


    /**
     * 强制刷新某个游戏的连接和工具列表。
     */
    public ToolProvider refresh(String gameName) {
        close(gameName);
        return getToolProvider(gameName);
    }

    /**
     * 关闭并移除某个游戏的缓存连接，不重新创建。
     */
    public void close(String gameName) {
        McpClient oldClient = clientCache.remove(gameName);
        if (oldClient != null) {
            try {
                oldClient.close();
            } catch (Exception e) {
                log.warn(LogDomain.MCP, "mcp.client_close_failed", LogOutcome.DEGRADED, "exception", e.toString());
            }
        }
        providerCache.remove(gameName);
    }
}
