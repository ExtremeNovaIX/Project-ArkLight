package p1.component.agent.gamer.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.adapter.core.*;
import p1.component.agent.gamer.adapter.sts2.STS2OperationToolRenderer;
import p1.component.agent.gamer.adapter.sts2.STS2StateDiffRenderer;
import p1.config.mcp.MCPProperties;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 杀戮尖塔 2 游戏适配器
 * <p>
 * 负责三件事：用 JSON 模式获取状态、修复出牌索引漂移、
 * 以及在 state_type 或手牌数量出现意外变化（比如出牌后手牌增加）时中断剩余队列。
 * <p>
 * 首次获取状态时自动检测单人/多人模式，并优先信任状态内容中的多人特征，
 * 无需手动在 mcp-catalog.yaml 中配置 tool-prefix 或 state-tool-name。
 */
@Component
@Slf4j
public class STS2Adapter implements GameAdapter {

    private static final String TOOL_COMBAT_PLAY_CARD = "combat_play_card";
    private static final String TOOL_COMBAT_END_TURN = "combat_end_turn";
    private static final String LEGACY_TOOL_PLAY_CARD = "play_card";
    private static final String MP_PREFIX = "mp_";
    private static final String META_PLANNED_CARD_NAME = "plannedCardName";
    private static final String META_PLANNED_COMBAT_ROUND = "plannedCombatRound";
    private static final String STALE_END_TURN_REASON = "STS2 结束回合操作已过期";
    private static final List<String> VIRTUAL_CARD_NAME_FIELDS = List.of("card", "card_name", "cardName", "name");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> detectedMode = new ConcurrentHashMap<>();
    private volatile String lastFetchedGameName;
    private final STS2StateDiffRenderer stateDiffRenderer = new STS2StateDiffRenderer();
    private final STS2OperationToolRenderer operationToolRenderer = new STS2OperationToolRenderer();
    private final PlayCardPlanCompiler playCardPlanCompiler = new PlayCardPlanCompiler();

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
     * <p>
     * 首次调用时会先读取单人端点的状态；如果状态内容显示处于多人大厅或多人 run，
     * 即使该状态来自 get_game_state，也缓存为多人模式。多人 run 中单人端点会被
     * HTTP 409 拒绝，此时再回退到 mp_get_game_state。
     * 后续调用优先使用缓存模式；缓存失效时自动重新检测。
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

        // 自动检测：先试单人，再试多人
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

    /**
     * 判断 STS2 当前状态是否需要 agent 行动。
     * <p>
     * 战斗内只在玩家回合且行动阶段时行动；非战斗交互状态先交给 agent 判断具体操作。
     */
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

        return GameActionability.actionable("STS2 非战斗交互状态可由 agent 决策: state_type=" + state.stateType());
    }

    /**
     * 将 STS2 MCP 返回的源状态直接注入给 agent。
     * <p>
     * 状态不再做 K-V 扁平化或字段清洗，避免界面特有字段、嵌套说明和 MCP 原生结构在渲染层丢失。
     */
    @Override
    public String renderStateForAgent(GameStateSnapshot state) {
        if (state == null || state.json() == null) {
            return "(未能获取 STS2 状态)";
        }

        String raw = state.rawJson();
        return raw == null || raw.isBlank() ? state.json().toString() : raw;
    }

    /**
     * 渲染 STS2 操作前后的白名单状态差异。
     */
    @Override
    public String renderStateDiffForAgent(GameStateSnapshot before, GameStateSnapshot after) {
        return stateDiffRenderer.renderDiff(before, after);
    }

    /**
     * 在出牌操作入队时记录计划打出的牌名，供后续修复使用。
     */
    @Override
    public QueuedGameOperation prepareOperation(GameOperation operation, GameStateSnapshot plannedState) {
        return playCardPlanCompiler.prepareOperation(operation, plannedState);
    }

    /**
     * 批量入队时模拟手牌变化，确保每个操作的 plannedCardName 都基于前序操作
     * 执行后的预期手牌（而非静态快照的同一位置）。
     * <p>
     * 这里不做能量、can_play 等合法性裁剪；MCP 是最终裁判。adapter 只记录计划牌名，
     * 便于执行前把牌名修复为当前真实手牌下标。单条失败由队列处理器按最新状态软化或中断。
     */
    @Override
    public ArrayDeque<QueuedGameOperation> prepareBatch(List<GameOperation> operations, GameStateSnapshot plannedState) {
        return playCardPlanCompiler.prepareBatch(operations, plannedState);
    }

    /**
     * STS2 多人模式下队友可能在模型思考期间推进回合。
     * <p>
     * 队列真正落到 MCP 前必须重读状态，避免第一条操作仍然拿 prompt 注入时的旧回合快照执行。
     */
    @Override
    public boolean shouldRefreshStateBeforeDrain() {
        return true;
    }

    /**
     * 执行出牌前把 card 名称翻译成 card_index。
     * <p>
     * agent 面向牌名决策；真实 STS2 MCP 仍然需要 card_index，因此这里在当前手牌中按名字查找。
     * 旧格式 card_index 仍保留兼容，但名字匹配优先于索引匹配。
     */
    @Override
    public ToolExecutionRequest repairBeforeExecute(QueuedGameOperation operation, GameStateSnapshot currentState) {
        rejectStaleMenuOperation(operation, currentState);
        rejectStaleEndTurn(operation, currentState);
        if (!isPlayCard(operation.request().name())) {
            return operation.request();
        }
        String plannedCardName = String.valueOf(operation.metadata().getOrDefault(META_PLANNED_CARD_NAME, ""));

        JsonNode args = parseArgs(operation.request().arguments());
        String requestedCardName = readCardName(args);
        if (!requestedCardName.isBlank()) {
            plannedCardName = requestedCardName;
        }

        JsonNode currentHand = hand(currentState);
        int handSize = currentHand.isArray() ? currentHand.size() : 0;

        if (!plannedCardName.isBlank()) {
            int foundByName = findCardByName(currentHand, plannedCardName);
            if (foundByName >= 0) {
                ObjectNode repairedArgs = args.deepCopy();
                removeVirtualCardNameFields(repairedArgs);
                writeCardIndex(repairedArgs, foundByName);
                if (isSelfTargetCard(currentHand.path(foundByName))) {
                    repairedArgs.remove("target");
                }
                return operation.request().toBuilder()
                        .arguments(repairedArgs.toString())
                        .build();
            }
        }

        Integer requestedIndex = readCardIndex(args);
        if (requestedIndex == null) {
            throw new GameBridgeException("STS2 出牌缺少 card 牌名：请使用 args.card 指定要打出的手牌名称。当前手牌: "
                    + renderHandNames(currentHand));
        }

        // 兜底：名字找不到，但原始索引仍在当前手牌范围内，保持原样。
        if (requestedIndex >= 0 && requestedIndex < handSize) {
            ObjectNode repairedArgs = args.deepCopy();
            if (isSelfTargetCard(currentHand.path(requestedIndex))) {
                repairedArgs.remove("target");
            }
            return operation.request().toBuilder()
                    .arguments(repairedArgs.toString())
                    .build();
        }

        throw new GameBridgeException("STS2 出牌索引已失效：计划打出「"
                + (plannedCardName.isBlank() ? "(未知)" : plannedCardName)
                + "」，当前手牌共 " + handSize + " 张，请求索引=" + requestedIndex + "。");
    }

    /**
     * 执行后监视：state_type 变化或手牌数量非预期变化时中断队列。
     */
    @Override
    public void monitorAfterExecute(QueuedGameOperation operation,
                                    GameStateSnapshot beforeState,
                                    GameStateSnapshot afterState,
                                    String toolResult,
                                    boolean hasRemainingOperations) {
        String mcpError = extractToolError(toolResult);
        if (mcpError != null) {
            throw new GameBridgeException("MCP 工具执行失败: " + mcpError);
        }

        if (!hasRemainingOperations) {
            return;
        }

        if (!safeEquals(beforeState.stateType(), afterState.stateType())) {
            throw new GameBridgeException("STS2 state_type 从 " + beforeState.stateType() + " 变为 " + afterState.stateType());
        }

        if (isPlayCard(operation.request().name())) {
            int beforeHand = hand(beforeState).size();
            int afterHand = hand(afterState).size();
            if (afterHand != beforeHand - 1) {
                String s = "STS2 出牌后手牌数量变化不符合预期：执行前 " + beforeHand + "，" +
                        "执行后 " + afterHand + "，" +
                        "剩余队列基于旧手牌不可靠；" +
                        "出现问题时执行的操作：" + operation.request().name() + "|" +
                        "agent操作描述：" + operation.note();
                throw new GameBridgeException(s);
            }
        }
    }

    // ── SP/MP 前缀处理 ──

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

    // ── 内部工具 ──

    private boolean isPlayCard(String toolName) {
        return TOOL_COMBAT_PLAY_CARD.equals(toolName)
                || LEGACY_TOOL_PLAY_CARD.equals(toolName)
                || (MP_PREFIX + TOOL_COMBAT_PLAY_CARD).equals(toolName)
                || (MP_PREFIX + LEGACY_TOOL_PLAY_CARD).equals(toolName);
    }

    /**
     * 判断工具是否会结束当前战斗回合。
     */
    private boolean isEndTurn(String toolName) {
        return TOOL_COMBAT_END_TURN.equals(toolName)
                || (MP_PREFIX + TOOL_COMBAT_END_TURN).equals(toolName);
    }

    private boolean isMenuSelect(String toolName) {
        return "menu_select".equals(toolName);
    }

    /**
     * 给结束回合操作记录 agent 决策时看到的战斗轮次。
     * <p>
     * STS2 的 `battle.round` 是当前战斗回合指纹。多人模式里队友可能在模型思考期间结束旧回合，
     * 该字段用于在真正执行前识别旧回合遗留的结束指令。
     */
    private void rememberEndTurnRound(QueuedGameOperation operation,
                                      String toolName,
                                      GameStateSnapshot plannedState) {
        if (!isEndTurn(toolName)) {
            return;
        }
        String round = combatRound(plannedState);
        if (!round.isBlank()) {
            operation.metadata().put(META_PLANNED_COMBAT_ROUND, round);
        }
    }

    /**
     * 在 MCP 真正收到结束回合指令前拦截过期回合操作。
     * <p>
     * 只有结束回合需要这一层护栏：出牌等动作本来就允许 MCP 按最新状态返回失败，
     * 但把上一回合的结束指令打进新回合会直接跳过本回合行动。
     */
    private void rejectStaleEndTurn(QueuedGameOperation operation, GameStateSnapshot currentState) {
        if (!isEndTurn(operation.request().name())) {
            return;
        }
        if (!isCombatPlayWindow(currentState)) {
            throw new GameBridgeException("STS2 结束回合执行前已不在玩家出牌窗口，丢弃旧结束指令。");
        }

        String plannedRound = String.valueOf(operation.metadata().getOrDefault(META_PLANNED_COMBAT_ROUND, ""));
        String currentRound = combatRound(currentState);
        if (!plannedRound.isBlank() && !currentRound.isBlank() && !plannedRound.equals(currentRound)) {
            throw new GameBridgeException(STALE_END_TURN_REASON
                    + "：决策时 battle.round=" + plannedRound
                    + "，执行前 battle.round=" + currentRound
                    + "，丢弃旧回合的结束指令。");
        }
    }

    private void rejectStaleMenuOperation(QueuedGameOperation operation, GameStateSnapshot currentState) {
        if (!isMenuSelect(operation.request().name())) {
            return;
        }
        String stateType = currentState == null ? "" : currentState.stateType();
        if ("menu".equalsIgnoreCase(stateType) || "game_over".equalsIgnoreCase(stateType)) {
            return;
        }
        String displayStateType = stateType == null || stateType.isBlank() ? "unknown" : stateType;
        throw new GameBridgeException("STS2 菜单操作已过期：当前 state_type="
                + displayStateType
                + "，丢弃旧的 menu_select。agent操作描述："
                + operation.note());
    }

    /**
     * 读取当前战斗轮次；状态未携带该字段时返回空串并放弃轮次比较。
     */
    private String combatRound(GameStateSnapshot state) {
        if (state == null || state.json() == null) {
            return "";
        }
        JsonNode round = state.json().path("battle").path("round");
        return round.isMissingNode() || round.isNull() ? "" : round.asText("");
    }

    private JsonNode hand(GameStateSnapshot state) {
        if (state == null || state.json() == null) {
            return objectMapper.createArrayNode();
        }
        return state.json().path("player").path("hand");
    }

    /**
     * STS2 出牌计划编译器。
     * <p>
     * 该内部类只记录 agent 计划中的牌名语义，不判断费用或 can_play；真正能否执行交给 MCP 返回结果决定。
     */
    private class PlayCardPlanCompiler {
        /**
         * 准备单条操作的元数据。
         */
        private QueuedGameOperation prepareOperation(GameOperation operation, GameStateSnapshot plannedState) {
            QueuedGameOperation queued = QueuedGameOperation.from(operation, plannedState);
            rememberEndTurnRound(queued, operation.toolName(), plannedState);
            if (!isPlayCard(operation.toolName())) {
                return queued;
            }

            String requestedCardName = readCardName(operation.args());
            if (!requestedCardName.isBlank()) {
                queued.metadata().put(META_PLANNED_CARD_NAME, requestedCardName);
                return queued;
            }

            Integer cardIndex = readCardIndex(operation.args());
            if (cardIndex == null) {
                return queued;
            }
            JsonNode card = hand(plannedState).path(cardIndex);
            if (!card.isMissingNode()) {
                queued.metadata().put(META_PLANNED_CARD_NAME, card.path("name").asText(""));
            }
            return queued;
        }

        /**
         * 准备一批操作的元数据，同时模拟已经计划过的同名牌消耗，避免后续 index 取到旧位置。
         */
        private ArrayDeque<QueuedGameOperation> prepareBatch(List<GameOperation> operations, GameStateSnapshot plannedState) {
            ArrayDeque<QueuedGameOperation> queue = new ArrayDeque<>();
            List<JsonNode> mutableHand = mutableHandCopy(plannedState);

            for (GameOperation op : operations) {
                QueuedGameOperation queued = QueuedGameOperation.from(op, plannedState);
                rememberEndTurnRound(queued, op.toolName(), plannedState);
                if (isPlayCard(op.toolName())) {
                    String requestedCardName = readCardName(op.args());
                    if (!requestedCardName.isBlank()) {
                        queued.metadata().put(META_PLANNED_CARD_NAME, requestedCardName);
                        removeFirstCardByName(mutableHand, requestedCardName);
                    } else {
                        Integer cardIndex = readCardIndex(op.args());
                        if (cardIndex != null && cardIndex >= 0 && cardIndex < mutableHand.size()) {
                            JsonNode card = mutableHand.get(cardIndex);
                            if (!card.isMissingNode()) {
                                queued.metadata().put(META_PLANNED_CARD_NAME, card.path("name").asText(""));
                            }
                            mutableHand.remove(cardIndex.intValue());
                        }
                    }
                }

                queue.offerLast(queued);
            }
            return queue;
        }

        /**
         * 创建可变手牌副本，供批量计划模拟消耗使用。
         */
        private List<JsonNode> mutableHandCopy(GameStateSnapshot state) {
            List<JsonNode> result = new ArrayList<>();
            JsonNode hand = hand(state);
            if (hand.isArray()) {
                hand.forEach(result::add);
            }
            return result;
        }

        /**
         * 移除模拟手牌中的第一张同名牌。
         */
        private void removeFirstCardByName(List<JsonNode> cards, String cardName) {
            for (int i = 0; i < cards.size(); i++) {
                if (cardName.equals(cards.get(i).path("name").asText(""))) {
                    cards.remove(i);
                    return;
                }
            }
        }
    }

    /**
     * 判断最新状态是否仍然是玩家战斗出牌窗口。
     */
    private boolean isCombatPlayWindow(GameStateSnapshot state) {
        if (state == null || state.json() == null) {
            return false;
        }
        JsonNode battle = state.json().path("battle");
        return battle.isObject()
                && !battle.isEmpty()
                && "player".equals(battle.path("turn").asText(""))
                && battle.path("is_play_phase").asBoolean(false);
    }

    /**
     * STS2 的第一版软错误策略：失败后只要仍处于玩家战斗出牌窗口，就认为单条指令失败但队列可继续。
     */
    @Override
    public boolean shouldContinueAfterOperationFailure(QueuedGameOperation operation,
                                                       GameStateSnapshot beforeState,
                                                       GameStateSnapshot afterState,
                                                       String reason) {
        if (isEndTurn(operation.request().name())
                && reason != null
                && reason.contains(STALE_END_TURN_REASON)) {
            return false;
        }
        return isCombatPlayWindow(afterState);
    }

    private String renderHandNames(JsonNode hand) {
        if (!hand.isArray() || hand.isEmpty()) {
            return "(空)";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode card : hand) {
            names.add(card.path("name").asText("(未知牌)"));
        }
        return String.join(", ", names);
    }

    private JsonNode parseArgs(String rawArgs) {
        try {
            return objectMapper.readTree(rawArgs == null || rawArgs.isBlank() ? "{}" : rawArgs);
        } catch (Exception e) {
            throw new GameBridgeException("解析工具参数失败: " + rawArgs, e);
        }
    }

    private Integer readCardIndex(JsonNode args) {
        if (args == null || args.isMissingNode() || args.isNull()) {
            return null;
        }
        if (args.has("card_index")) return args.path("card_index").asInt();
        if (args.has("cardIndex")) return args.path("cardIndex").asInt();
        if (args.has("index")) return args.path("index").asInt();
        return null;
    }

    private String readCardName(JsonNode args) {
        if (args == null || args.isMissingNode() || args.isNull()) {
            return "";
        }
        for (String field : VIRTUAL_CARD_NAME_FIELDS) {
            JsonNode value = args.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private void removeVirtualCardNameFields(ObjectNode args) {
        for (String field : VIRTUAL_CARD_NAME_FIELDS) {
            args.remove(field);
        }
    }

    private void writeCardIndex(ObjectNode args, int index) {
        if (args.has("cardIndex")) {
            args.put("cardIndex", index);
        } else if (args.has("index")) {
            args.put("index", index);
        } else {
            args.put("card_index", index);
        }
    }

    private int findCardByName(JsonNode hand, String name) {
        if (!hand.isArray()) {
            return -1;
        }
        for (int i = 0; i < hand.size(); i++) {
            if (name.equals(hand.path(i).path("name").asText(""))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isSelfTargetCard(JsonNode card) {
        if (card == null || !card.isObject()) {
            return false;
        }
        String targetType = card.path("target_type").asText("");
        return "self".equalsIgnoreCase(targetType);
    }

    private boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

}
