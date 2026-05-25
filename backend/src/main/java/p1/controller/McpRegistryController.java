package p1.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import p1.component.agent.gamer.GamerMCPClientFactory;
import p1.config.mcp.MCPProperties;
import p1.config.mcp.McpServerRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
@Slf4j
public class McpRegistryController {

    private final McpServerRegistry registry;
    private final MCPProperties properties;
    private final GamerMCPClientFactory clientFactory;

    /**
     * 列出所有可用mcp服务器
     */
    @GetMapping("/servers")
    public ResponseEntity<?> listServers() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, MCPProperties.GameMCPConfig> entry : properties.getGames().entrySet()) {
            result.put(entry.getKey(), serverToMap(entry.getKey(), entry.getValue()));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 列出自动发现的mcp服务器
     */
    @GetMapping("/discovered")
    public ResponseEntity<?> listDiscovered() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, MCPProperties.GameMCPConfig> entry : registry.listDiscovered().entrySet()) {
            result.put(entry.getKey(), serverToMap(entry.getKey(), entry.getValue()));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 列出预设目录模板
     */
    @GetMapping("/catalog")
    public ResponseEntity<?> listCatalog() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, MCPProperties.GameMCPConfig> entry : properties.getCatalog().entrySet()) {
            MCPProperties.GameMCPConfig c = entry.getValue();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("displayName", c.getDisplayName());
            info.put("description", c.getDescription());
            info.put("transport", c.getTransport());
            info.put("command", c.getCommand());
            info.put("args", c.getArgs());
            info.put("enabled", properties.getGames().containsKey(entry.getKey())
                    && properties.getGames().get(entry.getKey()).isEnabled());
            result.put(entry.getKey(), info);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 注册或更新一个 MCP 服务器
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody McpRegisterRequest request) {
        if (request.gameName == null || request.gameName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "gameName 不能为空"));
        }

        MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
        config.setTransport(request.transport != null ? request.transport : "stdio");
        config.setCommand(request.command);
        config.setArgs(request.args);
        config.setUrl(request.url);
        config.setEnabled(request.enabled != null ? request.enabled : true);
        config.setDisplayName(request.displayName != null ? request.displayName : request.gameName);
        config.setDescription(request.description);
        config.setAdapter(request.adapter != null ? request.adapter : "default");
        config.setStateToolName(request.stateToolName != null ? request.stateToolName : "get_state");
        config.setConnectTimeoutSeconds(request.connectTimeoutSeconds > 0
                ? request.connectTimeoutSeconds : 30);

        registry.register(request.gameName, config);
        clientFactory.refresh(request.gameName);

        log.info("[MCP-API] 注册服务器: name={}, transport={}", request.gameName, config.getTransport());
        return ResponseEntity.ok(Map.of(
                "message", "服务器已注册: " + request.gameName,
                "server", serverToMap(request.gameName, config)
        ));
    }

    /**
     * 删除一个运行时注册的服务器
     */
    @DeleteMapping("/servers/{name}")
    public ResponseEntity<?> deleteServer(@PathVariable String name) {
        boolean removed = registry.unregister(name);
        if (removed) {
            clientFactory.close(name);
            log.info("[MCP-API] 删除服务器: name={}", name);
            return ResponseEntity.ok(Map.of("message", "服务器已删除: " + name));
        }
        return ResponseEntity.badRequest().body(Map.of(
                "error", "无法删除: " + name + "（不存在或不是运行时注册的条目）"
        ));
    }

    /**
     * 刷新指定服务器的连接
     */
    @PostMapping("/servers/{name}/refresh")
    public ResponseEntity<?> refresh(@PathVariable String name) {
        if (!properties.getGames().containsKey(name)) {
            return ResponseEntity.badRequest().body(Map.of("error", "服务器不存在: " + name));
        }
        clientFactory.refresh(name);
        return ResponseEntity.ok(Map.of("message", "连接已刷新: " + name));
    }

    private Map<String, Object> serverToMap(String name, MCPProperties.GameMCPConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("displayName", c.getDisplayName());
        m.put("description", c.getDescription());
        m.put("transport", c.getTransport());
        m.put("command", c.getCommand());
        m.put("args", c.getArgs());
        m.put("url", c.getUrl());
        m.put("enabled", c.isEnabled());
        m.put("registered", c.isRegistered());
        m.put("connectTimeoutSeconds", c.getConnectTimeoutSeconds());
        m.put("adapter", c.getAdapter());
        m.put("stateToolName", c.getStateToolName());

        // 检测来源
        String source = "configured";
        if (c.isRegistered()) source = "registered";
        else if (registry.listDiscovered().containsKey(name)) source = "discovered";
        m.put("source", source);

        return m;
    }

    public static class McpRegisterRequest {
        public String gameName;
        public String displayName;
        public String description;
        public String transport;
        public String command;
        public String[] args;
        public String url;
        public String adapter;
        public String stateToolName;
        public Boolean enabled;
        public long connectTimeoutSeconds;
    }
}
