package p1.component.agent.gamer.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.adapter.core.GameActionability;
import p1.component.agent.gamer.adapter.core.GameAdapterContext;
import p1.component.agent.gamer.adapter.core.GameActionWindowSignature;
import p1.component.agent.gamer.adapter.core.GameBridgeException;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.adapter.core.GameOperationPrecondition;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.adapter.core.QueuedGameOperation;
import p1.component.agent.gamer.adapter.sts2.STS2ActionWindowAnalyzer;
import p1.component.agent.gamer.adapter.sts2.STS2OperationRepairer;
import p1.component.agent.gamer.adapter.sts2.STS2OperationPreconditionChecker;
import p1.component.agent.gamer.adapter.sts2.STS2OperationToolRenderer;
import p1.component.agent.gamer.adapter.sts2.STS2OperationPlanCompiler;
import p1.component.agent.gamer.adapter.sts2.STS2StateMonitor;
import p1.component.agent.gamer.adapter.sts2.STS2StateSummaryRenderer;
import p1.config.mcp.MCPProperties;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 杀戮尖塔 2 游戏适配器。
 * <p>
 * 负责 JSON 状态获取、单人/多人模式检测，并把 STS2 专属状态渲染、
 * 操作修复、出牌计划编译和执行后监控委托给同包 helper。
 */
@Component
@Slf4j
public class STS2Adapter implements GameAdapter {

    private static final String MP_PREFIX = "mp_";

    private final Map<String, String> detectedMode = new ConcurrentHashMap<>();
    private volatile String lastFetchedGameName;
    private final STS2StateSummaryRenderer stateSummaryRenderer = new STS2StateSummaryRenderer();
    private final STS2OperationToolRenderer operationToolRenderer = new STS2OperationToolRenderer();
    private final STS2OperationPlanCompiler operationPlanCompiler = new STS2OperationPlanCompiler();
    private final STS2ActionWindowAnalyzer actionWindowAnalyzer = new STS2ActionWindowAnalyzer();
    private final STS2OperationPreconditionChecker preconditionChecker = new STS2OperationPreconditionChecker();
    private final STS2StateMonitor stateMonitor = new STS2StateMonitor();
    private final STS2OperationRepairer operationRepairer = new STS2OperationRepairer(this::currentModePrefix);

    @Override
    public String id() {
        return "sts2";
    }

    /**
     * STS2 的 get_game_state 默认返回 markdown，因此显式要求 JSON。
     */
    @Override
    public String stateToolArguments(GameAdapterContext context) {
        return "{\"format\":\"json\"}";
    }

    /**
     * 自动检测单人/多人模式并获取游戏状态。
     */
    @Override
    public GameStateSnapshot fetchState(GameAdapterContext context) {
        String gameName = context.gameName();
        lastFetchedGameName = gameName;
        String cachedPrefix = detectedMode.get(gameName);

        if (cachedPrefix != null) {
            String toolName = cachedPrefix + context.config().getStateToolName();
            GameStateSnapshot state = tryFetchState(context, toolName);
            if (isValidState(state)) {
                String detectedPrefix = detectModePrefix(state);
                if (detectedPrefix != null && !detectedPrefix.equals(cachedPrefix)) {
                    detectedMode.put(gameName, detectedPrefix);
                    log.info("[STS2] 状态内容修正模式 prefix='{}' -> '{}': game={}",
                            cachedPrefix, detectedPrefix, gameName);
                }
                return state;
            }
            log.info("[STS2] 缓存模式 prefix='{}' 已失效，重新检测: game={}", cachedPrefix, gameName);
            detectedMode.remove(gameName);
        }

        // 自动检测固定先尝试单人端点，再尝试多人端点。
        String spName = context.config().getStateToolName();
        GameStateSnapshot spState = tryFetchState(context, spName);
        if (isValidState(spState)) {
            String detectedPrefix = detectModePrefix(spState);
            String prefix = detectedPrefix == null ? "" : detectedPrefix;
            detectedMode.put(gameName, prefix);
            log.info("[STS2] 检测到{}模式: game={}", MP_PREFIX.equals(prefix) ? "多人" : "单人", gameName);
            return spState;
        }

        String mpName = MP_PREFIX + context.config().getStateToolName();
        GameStateSnapshot mpState = tryFetchState(context, mpName);
        if (isValidState(mpState)) {
            detectedMode.put(gameName, MP_PREFIX);
            log.info("[STS2] 检测到多人模式: game={}", gameName);
            return mpState;
        }

        if (spState != null) {
            return spState;
        }
        throw new GameBridgeException("无法检测STS2游戏模式（单人/多人），get_game_state 和 mp_get_game_state 均不可用");
    }

    @Override
    public boolean isStateTool(String toolName, MCPProperties.GameMCPConfig config) {
        String baseName = config.getStateToolName();
        return toolName != null && (toolName.equals(baseName) || toolName.equals(MP_PREFIX + baseName));
    }

    @Override
    public GameActionability evaluateActionability(GameStateSnapshot state) {
        if (state == null || state.json() == null) {
            return GameActionability.unknown("STS2 状态为空，无法判断行动窗口");
        }

        JsonNode root = state.json();
        if ("error".equals(root.path("status").asText(""))) {
            return GameActionability.unknown("STS2 状态工具返回错误: " + root.path("error").asText("未知错误"));
        }

        String stateType = state.stateType();
        if (stateType.isBlank()) {
            return GameActionability.unknown("STS2 状态缺少 state_type");
        }

        if ("game_over".equals(stateType)) {
            return GameActionability.gameOver("STS2 state_type=" + state.stateType() + "，游戏已结束");
        }

        JsonNode battle = root.path("battle");
        if (battle.isObject() && !battle.isEmpty()) {
            String turn = battle.path("turn").asText("");
            boolean playPhase = battle.path("is_play_phase").asBoolean(false);
            if ("player".equals(turn) && playPhase) {
                return GameActionability.actionable("STS2 战斗中处于玩家行动阶段");
            }
            return GameActionability.waiting("STS2 战斗中暂不可行动: turn="
                    + battle.path("turn").asText("(未知)")
                    + ", is_play_phase=" + playPhase);
        }

        if ("monster".equals(stateType)) {
            return GameActionability.unknown("STS2 state_type=monster 但缺少 battle 信息，暂不行动");
        }

        return GameActionability.actionable("STS2 非战斗交互状态可由 RP 决策: state_type=" + state.stateType());
    }

    @Override
    public String renderStateForAgent(GameStateSnapshot state) {
        return stateSummaryRenderer.render(state);
    }

    @Override
    public QueuedGameOperation prepareOperation(GameOperation operation, GameStateSnapshot plannedState) {
        return operationPlanCompiler.prepareOperation(operation, plannedState);
    }

    @Override
    public ArrayDeque<QueuedGameOperation> prepareBatch(List<GameOperation> operations, GameStateSnapshot plannedState) {
        return operationPlanCompiler.prepareBatch(operations, plannedState);
    }

    @Override
    public boolean shouldRefreshStateBeforeDrain() {
        return true;
    }

    @Override
    public GameActionWindowSignature actionWindowSignature(GameStateSnapshot state) {
        return actionWindowAnalyzer.signature(state, currentModePrefix(state));
    }

    @Override
    public GameOperationPrecondition checkOperationPrecondition(QueuedGameOperation operation,
                                                                GameStateSnapshot currentState) {
        return preconditionChecker.check(operation, currentState);
    }

    @Override
    public ToolExecutionRequest repairBeforeExecute(QueuedGameOperation operation, GameStateSnapshot currentState) {
        return operationRepairer.repairBeforeExecute(operation, currentState);
    }

    @Override
    public void monitorAfterExecute(QueuedGameOperation operation,
                                    GameStateSnapshot beforeState,
                                    GameStateSnapshot afterState,
                                    String toolResult) {
        stateMonitor.monitorAfterExecute(operation, beforeState, afterState, toolResult);
    }

    @Override
    public String resolveStateToolName(MCPProperties.GameMCPConfig config) {
        String prefix = config.getToolPrefix();
        return (prefix == null || prefix.isEmpty() ? "" : prefix) + config.getStateToolName();
    }

    @Override
    public String renderAvailableOperations(ToolProviderResult tools,
                                            MCPProperties.GameMCPConfig config,
                                            GameStateSnapshot state) {
        String modePrefix = lastFetchedGameName == null ? "" : detectedMode.getOrDefault(lastFetchedGameName, "");
        return operationToolRenderer.render(tools, config, state, modePrefix);
    }

    @Override
    public String renderAvailableOperationSummary(ToolProviderResult tools,
                                                  MCPProperties.GameMCPConfig config,
                                                  GameStateSnapshot state) {
        String rendered = renderAvailableOperations(tools, config, state);
        Set<String> actions = new LinkedHashSet<>();
        rendered.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("- "))
                .map(line -> line.substring(2))
                .map(line -> {
                    int colon = line.indexOf(':');
                    return colon >= 0 ? line.substring(0, colon).trim() : line.trim();
                })
                .map(this::actionLabel)
                .filter(label -> !label.isBlank())
                .forEach(actions::add);
        if (actions.isEmpty()) {
            return "(没有可用动作)";
        }
        StringBuilder sb = new StringBuilder();
        for (String action : actions) {
            sb.append("- ").append(action).append("\n");
        }
        return sb.toString().trim();
    }

    private GameStateSnapshot tryFetchState(GameAdapterContext context, String toolName) {
        ToolExecutor executor = context.tools().toolExecutorByName(toolName);
        if (executor == null) {
            return null;
        }
        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .name(toolName)
                    .arguments(stateToolArguments(context))
                    .build();
            String raw = executor.execute(request, context.sessionId());
            return parseState(raw);
        } catch (Exception e) {
            log.debug("[STS2] 状态工具 {} 尝试失败: {}", toolName, e.getMessage());
            return null;
        }
    }

    private String currentModePrefix(GameStateSnapshot currentState) {
        String detectedPrefix = detectModePrefix(currentState);
        if (detectedPrefix != null) {
            return detectedPrefix;
        }
        return lastFetchedGameName == null ? "" : detectedMode.getOrDefault(lastFetchedGameName, "");
    }

    private String detectModePrefix(GameStateSnapshot state) {
        JsonNode root = state == null ? null : state.json();
        if (root == null || root.isMissingNode()) {
            return null;
        }

        String gameMode = root.path("game_mode").asText("");
        if ("multiplayer".equalsIgnoreCase(gameMode)) {
            return MP_PREFIX;
        }
        if ("singleplayer".equalsIgnoreCase(gameMode)) {
            return "";
        }

        String menuScreen = root.path("menu_screen").asText("");
        if (menuScreen.toLowerCase(Locale.ROOT).startsWith("multiplayer")) {
            return MP_PREFIX;
        }

        JsonNode lobby = root.path("lobby");
        if (lobby.isObject()) {
            String lobbyType = lobby.path("type").asText("");
            if ("singleplayer".equalsIgnoreCase(lobbyType)) {
                return "";
            }
            if (!lobbyType.isBlank()
                    || lobby.has("players")
                    || lobby.has("player_count")
                    || lobby.has("all_ready")
                    || lobby.has("is_local_ready")
                    || lobby.has("local_player_id")) {
                return MP_PREFIX;
            }
        }

        if (root.has("local_player_slot") || root.has("player_count") || root.has("net_type")) {
            return MP_PREFIX;
        }
        if (hasOption(root.path("options"), "unready")) {
            return MP_PREFIX;
        }
        return null;
    }

    private boolean hasOption(JsonNode options, String name) {
        if (options == null || !options.isArray() || name == null || name.isBlank()) {
            return false;
        }
        for (JsonNode option : options) {
            String optionName = option.isTextual() ? option.asText("") : option.path("name").asText("");
            if (name.equalsIgnoreCase(optionName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isValidState(GameStateSnapshot state) {
        if (state == null || state.json() == null) {
            return false;
        }
        if ("error".equals(state.json().path("status").asText(""))) {
            return false;
        }
        return !state.json().path("state_type").asText("").isBlank();
    }

    private String actionLabel(String toolName) {
        String name = baseToolName(toolName);
        return switch (name) {
            case "combat_play_card", "play_card" -> "打出卡牌";
            case "combat_end_turn" -> "结束回合";
            case "combat_select_card" -> "选择手牌";
            case "combat_confirm_selection" -> "确认选择";
            case "rewards_claim" -> "领取奖励";
            case "rewards_pick_card" -> "选择奖励牌";
            case "rewards_skip_card" -> "跳过卡牌奖励";
            case "event_choose_option" -> "选择事件选项";
            case "menu_select" -> "选择菜单选项";
            case "map_choose_node", "map_vote" -> "选择地图节点";
            case "proceed_to_map", "crystal_sphere_proceed" -> "继续/前往地图";
            case "use_potion" -> "使用药水";
            case "deck_select_card" -> "选择卡牌";
            default -> fallbackActionLabel(name);
        };
    }

    private String fallbackActionLabel(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        if (name.startsWith("shop_") || name.contains("purchase")) {
            return "商店操作";
        }
        if (name.startsWith("rest_") || name.contains("campfire")) {
            return "营火操作";
        }
        if (name.contains("chest") || name.contains("relic") || name.contains("claim")) {
            return "领取/继续";
        }
        if (name.contains("proceed")) {
            return "继续";
        }
        return "";
    }

    private String baseToolName(String toolName) {
        if (toolName == null) {
            return "";
        }
        return toolName.startsWith(MP_PREFIX) ? toolName.substring(MP_PREFIX.length()) : toolName;
    }
}
