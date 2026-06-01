package p1.component.gamer.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.logging.DefaultMcpLogMessageHandler;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.adapter.core.GameAdapterContext;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.adapter.STS2Adapter;
import p1.component.agent.gamer.adapter.core.SchemaNormalizingMcpTransport;
import p1.component.agent.gamer.bridge.queue.GameOperationQueueProcessor;
import p1.config.mcp.MCPProperties;

import java.time.Duration;
import java.util.List;

/**
 * 桥接层真实路径计时测试。
 * <p>
 * 连接真实的 STS2 MCP server（Python stdio），测量完整链路的耗时分布。
 * 需要游戏 + MCP mod 正在运行，否则状态查询会返回错误文本。
 */
@Tag("manual")
class GameBridgeTimingTest {

    private static final String STS2_SERVER_DIR =
            "..\\mcp-servers\\STS2MCP\\mcp";

    private static final STS2Adapter adapter = new STS2Adapter();
    private static final GameOperationQueueProcessor processor = new GameOperationQueueProcessor();

    private static ToolProviderResult tools;
    private static ToolProvider mcpToolProvider;
    private static McpClient mcpClient;
    private static MCPProperties.GameMCPConfig config;

    @BeforeAll
    static void setUpMCP() throws Exception {
        long t0 = System.nanoTime();

        // 1. 启动 stdio MCP transport → Python server
        StdioMcpTransport transport = new StdioMcpTransport.Builder()
                .command(List.of("uv", "run", "--directory", STS2_SERVER_DIR, "python", "server.py"))
                .logEvents(false)
                .build();

        // 2. 创建 MCP client（会触发 initialize + listTools）
        mcpClient = new DefaultMcpClient.Builder()
                .transport(new SchemaNormalizingMcpTransport(transport))
                .clientName("ArcLight-TimingTest")
                .clientVersion("1.0")
                .toolExecutionTimeout(Duration.ofSeconds(30))
                .logHandler(new DefaultMcpLogMessageHandler())
                .build();

        // 诊断：直接用 McpClient 查工具列表
        try {
            var rawTools = mcpClient.listTools();
            System.out.printf("  McpClient.listTools() → %d tools%n", rawTools.size());
        } catch (Exception e) {
            System.out.println("  McpClient.listTools() 异常: " + e.getMessage());
        }

        mcpToolProvider = McpToolProvider.builder()
                .mcpClients(List.of(mcpClient))
                .failIfOneServerFails(false)
                .build();

        long tMcpInitMs = (System.nanoTime() - t0) / 1_000_000;

        // 3. 获取工具列表
        long t1 = System.nanoTime();
        tools = mcpToolProvider.provideTools(
                new ToolProviderRequest("test", dev.langchain4j.data.message.UserMessage.from("timing-test")));
        long tListToolsMs = (System.nanoTime() - t1) / 1_000_000;

        System.out.printf("=== MCP 连接耗时 ===%n");
        System.out.printf("  MCP 初始化 (initialize+listTools): %d ms%n", tMcpInitMs);
        System.out.printf("  provideTools 调用:                   %d ms%n", tListToolsMs);
        System.out.printf("  已发现工具数:                         %d%n", tools.tools().size());
        tools.tools().keySet().stream()
                .map(s -> "    - " + s.name())
                .sorted()
                .forEach(System.out::println);

        // 4. 配置
        config = new MCPProperties.GameMCPConfig();
        config.setStateToolName("get_game_state");
        config.setToolPrefix("");
    }

    @Test
    void timeFetchStateAndDrainQueue() {
        String memoryId = "real-timing-" + System.currentTimeMillis();
        GameAdapterContext context = new GameAdapterContext("sts2", memoryId, tools, config);

        // ════════════════════════════════════
        // 阶段 A：获取游戏状态
        // ════════════════════════════════════
        long tA = System.nanoTime();
        GameStateSnapshot state;
        try {
            state = adapter.fetchState(context);
        } catch (Exception e) {
            long tFetchMs = (System.nanoTime() - tA) / 1_000_000;
            System.out.printf("%n=== 状态获取失败 (耗时 %d ms) ===%n", tFetchMs);
            System.out.printf("  错误: %s%n", e.getMessage());
            System.out.println("  请确认游戏正在运行且 MCP mod 已加载。");
            return;
        }
        long tFetchMs = (System.nanoTime() - tA) / 1_000_000;
        System.out.printf("%n=== 状态获取 ===%n");
        System.out.printf("  耗时:      %d ms%n", tFetchMs);
        System.out.printf("  stateType: %s%n", state.stateType());
        String rawPreview = state.rawJson().length() > 200
                ? state.rawJson().substring(0, 200) + "..."
                : state.rawJson();
        System.out.printf("  状态预览:  %s%n", rawPreview);

        // 从游戏状态中取第一个敌人的 entity_id 作为 target
        String target = extractFirstEnemy(state);
        System.out.printf("  目标敌人:  %s%n", target);

        // ════════════════════════════════════
        // 阶段 B：提交操作队列并计时
        // ════════════════════════════════════
        String enqueueArgs = String.format("""
                {
                  "status":"CONTINUE",
                  "summary":"真实路径计时测试 — 打出3张牌",
                  "operations":[
                    {"tool":"combat_play_card","args":{"card_index":0,"target":"%1$s"}},
                    {"tool":"combat_play_card","args":{"card_index":0,"target":"%1$s"}},
                    {"tool":"combat_play_card","args":{"card_index":0,"target":"%1$s"}}
                  ]
                }
                """, target);

        long tB = System.nanoTime();
        String result;
        try {
            result = processor.enqueueAndDrain("sts2", memoryId, adapter, config, tools, enqueueArgs);
        } catch (Exception e) {
            long tDrainMs = (System.nanoTime() - tB) / 1_000_000;
            System.out.printf("%n=== 队列执行异常 (耗时 %d ms) ===%n", tDrainMs);
            System.out.printf("  错误: %s%n", e.getMessage());
            return;
        }
        long tDrainMs = (System.nanoTime() - tB) / 1_000_000;

        System.out.printf("%n=== 队列执行结果 ===%n");
        System.out.printf("  总耗时: %d ms%n", tDrainMs);
        String shortResult = result.length() > 120 ? result.substring(0, 120) + "..." : result;
        System.out.printf("  结果:   %s%n", shortResult);
    }

    private static String extractFirstEnemy(GameStateSnapshot state) {
        JsonNode enemies = state.json().path("battle").path("enemies");
        if (enemies.isArray() && enemies.size() > 0) {
            return enemies.path(0).path("entity_id").asText("JAW_WORM_0");
        }
        return "JAW_WORM_0";
    }
}
