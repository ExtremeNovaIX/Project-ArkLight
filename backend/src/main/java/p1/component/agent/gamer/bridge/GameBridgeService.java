package p1.component.agent.gamer.bridge;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import p1.component.agent.gamer.GameSessionKey;
import p1.component.agent.gamer.GamerMCPClientFactory;
import p1.component.agent.gamer.adapter.GameAdapter;
import p1.component.agent.gamer.adapter.core.GameActionability;
import p1.component.agent.gamer.adapter.core.GameAdapterContext;
import p1.component.agent.gamer.adapter.core.GameAdapterRegistry;
import p1.component.agent.gamer.adapter.core.GameStateJsonSanitizer;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.bridge.queue.GameOperationQueueProcessor;
import p1.component.agent.gamer.loop.GameSessionExecutionLockService;
import p1.config.mcp.MCPProperties;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * RP 游戏控制链路与底层 MCP 游戏适配器之间的桥接服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GameBridgeService {

    private final GamerMCPClientFactory mcpClientFactory;
    private final MCPProperties mcpProperties;
    private final GameAdapterRegistry adapterRegistry;
    private final GameOperationQueueProcessor queueProcessor;
    private final GameSessionExecutionLockService executionLockService;

    /**
     * 执行 parser 翻译出的操作队列。
     */
    public String executeOperationQueue(String gameName, String sessionId, String rawArguments) {
        return executionLockService.withLock(gameName, sessionId, () -> {
            String memoryId = GameSessionKey.of(gameName, sessionId);
            MCPProperties.GameMCPConfig config = requireConfig(gameName);
            GameAdapter adapter = adapterRegistry.getAdapter(gameName, config);
            ToolProvider rawProvider = mcpClientFactory.getToolProvider(gameName);
            ToolProviderResult tools = rawProvider.provideTools(new ToolProviderRequest(
                    memoryId,
                    UserMessage.from("operation dispatch")));
            return queueProcessor.enqueueAndDrain(gameName, memoryId, adapter, config, tools, rawArguments);
        });
    }

    /**
     * 读取当前状态下可执行的操作工具。
     * <p>
     * detailed 给 parser 使用，summary 给 RP 使用；两者都来自同一次状态读取，避免工具列表和局势脱节。
     */
    public GameAvailableOperations describeAvailableOperations(String gameName, String sessionId) {
        String memoryId = GameSessionKey.of(gameName, sessionId);
        MCPProperties.GameMCPConfig config = requireConfig(gameName);
        GameAdapter adapter = adapterRegistry.getAdapter(gameName, config);
        ToolProvider rawProvider = mcpClientFactory.getToolProvider(gameName);
        ToolProviderResult tools = rawProvider.provideTools(new ToolProviderRequest(
                memoryId,
                UserMessage.from("available game operations")));
        GameAdapterContext context = new GameAdapterContext(gameName, memoryId, tools, config);
        GameStateSnapshot state = adapter.fetchState(context);
        String detailed = adapter.renderAvailableOperations(context, state);
        String summary = adapter.renderAvailableOperationSummary(context, state);
        return new GameAvailableOperations(detailed, summary, extractToolNames(detailed), renderStateJson(state));
    }

    /**
     * 读取最新游戏状态，并返回循环层判断所需的稳定摘要。
     */
    public GameStateProbe probeState(String gameName, String sessionId) {
        String memoryId = GameSessionKey.of(gameName, sessionId);
        MCPProperties.GameMCPConfig config = requireConfig(gameName);
        GameAdapter adapter = adapterRegistry.getAdapter(gameName, config);
        ToolProvider rawProvider = mcpClientFactory.getToolProvider(gameName);
        ToolProviderResult tools = rawProvider.provideTools(new ToolProviderRequest(
                memoryId,
                UserMessage.from("probe game actionability")));
        GameAdapterContext context = new GameAdapterContext(gameName, memoryId, tools, config);
        GameStateSnapshot state = adapter.fetchState(context);
        return new GameStateProbe(adapter.evaluateActionability(state), renderStateFingerprint(adapter, state));
    }

    /**
     * 读取最新游戏状态，并判断当前是否轮到 RP 行动。
     */
    public GameActionability probeActionability(String gameName, String sessionId) {
        return probeState(gameName, sessionId).actionability();
    }

    /**
     * 获取并校验指定游戏的 MCP 配置。
     */
    private MCPProperties.GameMCPConfig requireConfig(String gameName) {
        MCPProperties.GameMCPConfig config = mcpProperties.getGames().get(gameName);
        if (config == null || !config.isEnabled()) {
            throw new IllegalArgumentException("游戏未配置 MCP: " + gameName);
        }
        return config;
    }

    private Set<String> extractToolNames(String renderedOperations) {
        if (renderedOperations == null || renderedOperations.isBlank()) {
            return Set.of();
        }
        return renderedOperations.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("- "))
                .map(line -> line.substring(2))
                .map(line -> {
                    int colon = line.indexOf(':');
                    return colon >= 0 ? line.substring(0, colon).trim() : line.trim();
                })
                .filter(name -> !name.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String renderStateJson(GameStateSnapshot state) {
        return GameStateJsonSanitizer.sanitizeToString(state);
    }

    private String renderStateFingerprint(GameAdapter adapter, GameStateSnapshot state) {
        if (adapter == null || state == null) {
            return "";
        }
        String rendered = adapter.renderStateForAgent(state);
        return rendered == null ? "" : rendered.trim();
    }
}
