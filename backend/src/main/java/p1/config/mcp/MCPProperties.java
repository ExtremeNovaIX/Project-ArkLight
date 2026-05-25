package p1.config.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 配置属性，从 application-mcp.yaml 和 mcp-catalog.yaml 加载。
 * <p>
 * 三个配置来源，最终合并到 games 中：
 * 1. mcp-catalog.yaml  — 预设模板，通过 installPath 启用
 * 2. application-mcp.yaml — 用户显式配置或设置 installPath
 * 3. mcp-registry.json — 运行时通过 REST API 注册（McpServerRegistry 管理）
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp")
public class MCPProperties {
    /**
     * 预设服务器目录（模板），用户提供 installPath 后解析为 games
     */
    private Map<String, GameMCPConfig> catalog = new HashMap<>();

    /**
     * 已解析并启用的游戏服务器配置（catalog解析后 + 用户配置 + 注册表）
     */
    private Map<String, GameMCPConfig> games = new HashMap<>();

    /**
     * 全局 MCP 客户端设置
     */
    private ClientConfig client = new ClientConfig();
    /**
     * 自动扫描 MCP server 的目录。相对路径基于后端进程工作目录。
     */
    private String serversDirectory = "../mcp-servers";
    /**
     * REST API 注册项持久化文件。相对路径基于后端进程工作目录。
     */
    private String registryFile = "config/mcp-registry.json";

    @Data
    public static class GameMCPConfig {
        /**
         * 传输类型："stdio" 或 "sse"
         */
        private String transport = "stdio";
        /**
         * SSE 模式：MCP 服务器 SSE URL
         */
        private String url;
        /**
         * Stdio 模式：可执行文件（例如 "uv"、"python"）
         */
        private String command;
        /**
         * Stdio 模式：命令行参数
         */
        private String[] args = {};
        /**
         * 连接超时时间（秒）
         */
        private long connectTimeoutSeconds = 30;
        /**
         * 是否启用该游戏的 MCP 服务器
         */
        private boolean enabled = true;
        /**
         * 游戏显示名称
         */
        private String displayName;
        /**
         * 描述（来自目录或注册信息）
         */
        private String description;
        /**
         * 是否为运行时注册的条目
         */
        private boolean registered;
        /**
         * 安装路径，用于解析目录模板中的 {{installPath}} 占位符
         */
        private String installPath;
        /**
         * 负责该游戏状态监视和操作修复的 adapter id。
         */
        private String adapter = "default";
        /**
         * MCP 状态查询工具名。该工具由 bridge 内部调用，不直接暴露给 gamer agent。
         */
        private String stateToolName = "get_state";
        /**
         * 该游戏的策略提示。不同游戏可在 catalog、manifest 或运行时注册配置中覆盖。
         */
        private String gameplayGuidelines;
        /**
         * MCP 工具名前缀。用于区分单人/多人模式（如 STS2 的 mp_ 前缀）。
         * 由具体适配器（如 STS2Adapter）读取使用，通用桥接层不感知此字段。
         */
        private String toolPrefix = "";
        /**
         * 操作执行后状态重读的最大次数，用于过滤 MCP/游戏状态刷新延迟导致的过渡态。
         */
        private int stateSettleMaxAttempts = 3;
        /**
         * 操作执行后状态重读之间的等待时间（毫秒）。
         */
        private long stateSettleDelayMs = 200;

        public GameMCPConfig copy() {
            GameMCPConfig c = new GameMCPConfig();
            c.transport = this.transport;
            c.url = this.url;
            c.command = this.command;
            c.args = this.args != null ? this.args.clone() : null;
            c.connectTimeoutSeconds = this.connectTimeoutSeconds;
            c.enabled = this.enabled;
            c.displayName = this.displayName;
            c.description = this.description;
            c.registered = this.registered;
            c.installPath = this.installPath;
            c.adapter = this.adapter;
            c.stateToolName = this.stateToolName;
            c.gameplayGuidelines = this.gameplayGuidelines;
            c.toolPrefix = this.toolPrefix;
            c.stateSettleMaxAttempts = this.stateSettleMaxAttempts;
            c.stateSettleDelayMs = this.stateSettleDelayMs;
            return c;
        }
    }

    @Data
    public static class ClientConfig {
        /**
         * MCP 工具调用的默认读取超时时间（秒）
         */
        private long toolTimeoutSeconds = 60;
        /**
         * 最大并发 MCP 连接数
         */
        private int maxConnections = 4;
    }

    /**
     * 运行时添加或覆盖一个游戏配置（不持久化，由调用方负责持久化）
     */
    public void putGame(String name, GameMCPConfig config) {
        games.put(name, config);
    }

    /**
     * 运行时移除一个游戏配置
     */
    public void removeGame(String name) {
        games.remove(name);
    }
}
