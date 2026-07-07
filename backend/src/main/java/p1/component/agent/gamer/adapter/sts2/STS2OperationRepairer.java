package p1.component.agent.gamer.adapter.sts2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.CustomLog;
import p1.component.agent.gamer.adapter.core.GameAdapterContext;
import p1.component.agent.gamer.adapter.core.GameBridgeException;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.adapter.core.QueuedGameOperation;

import java.util.*;

/**
 * STS2 操作执行前修复器。
 * <p>
 * RP/parser 可以用更自然的牌名、选项名表达动作；执行前由该组件翻译成当前状态下的 MCP 参数。
 */
@CustomLog
public class STS2OperationRepairer {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final STS2ModeDetector modeDetector;

    public STS2OperationRepairer(STS2ModeDetector modeDetector) {
        this.modeDetector = modeDetector;
    }

    public ToolExecutionRequest repairBeforeExecute(GameAdapterContext context, QueuedGameOperation operation, GameStateSnapshot currentState) {
        operation = repairOperationModePrefix(context, operation, currentState);
        rejectStaleMenuOperation(operation, currentState);
        if (isCombatSelectCard(operation.request().name())) {
            return repairCombatSelectCard(operation, currentState);
        }
        if (isRewardPickCard(operation.request().name())) {
            return repairRewardPickCard(operation, currentState);
        }
        if (isMapNodeTool(operation.request().name())) {
            return repairMapNodeSelection(operation, currentState);
        }
        if (isIndexedOptionTool(operation.request().name())) {
            return repairIndexedOptionSelection(operation, currentState);
        }
        if (!isPlayCard(operation.request().name())) {
            return operation.request();
        }
        String plannedCardName = metadata(operation, STS2OperationMetadata.PLANNED_CARD_NAME);

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

    private ToolExecutionRequest repairCombatSelectCard(QueuedGameOperation operation, GameStateSnapshot currentState) {
        JsonNode args = parseArgs(operation.request().arguments());
        JsonNode cards = handSelectCards(currentState);
        int cardCount = cards.isArray() ? cards.size() : 0;
        Integer requestedIndex = readCardIndex(args);
        if (requestedIndex != null && requestedIndex >= 0 && requestedIndex < cardCount) {
            ObjectNode repairedArgs = args.deepCopy();
            writeMcpCardIndex(repairedArgs, requestedIndex);
            return operation.request().toBuilder()
                    .arguments(repairedArgs.toString())
                    .build();
        }

        throw new GameBridgeException("STS2 战斗内选牌缺少有效 card_index。当前可选牌: "
                + renderHandNames(cards));
    }

    private ToolExecutionRequest repairRewardPickCard(QueuedGameOperation operation, GameStateSnapshot currentState) {
        JsonNode args = parseArgs(operation.request().arguments());
        JsonNode cards = cardRewardCards(currentState);
        int cardCount = cards.isArray() ? cards.size() : 0;
        Integer requestedIndex = readCardIndex(args);
        if (requestedIndex != null && requestedIndex >= 0 && requestedIndex < cardCount) {
            ObjectNode repairedArgs = args.deepCopy();
            writeMcpCardIndex(repairedArgs, requestedIndex);
            return operation.request().toBuilder()
                    .arguments(repairedArgs.toString())
                    .build();
        }

        throw new GameBridgeException("STS2 奖励选牌缺少有效 card_index。当前奖励牌: "
                + renderHandNames(cards));
    }

    private ToolExecutionRequest repairMapNodeSelection(QueuedGameOperation operation, GameStateSnapshot currentState) {
        JsonNode args = parseArgs(operation.request().arguments());
        if (!args.isObject()) {
            throw new GameBridgeException("STS2 地图节点操作参数必须是 JSON 对象: " + operation.request().arguments());
        }
        ObjectNode repairedArgs = args.deepCopy();
        String plannedNode = firstNonBlank(
                readMapNode(args),
                metadata(operation, STS2OperationMetadata.PLANNED_MAP_NODE));
        List<MapNodeCandidate> candidates = mapNodeCandidates(currentState);
        if (!plannedNode.isBlank()) {
            MapNodeCandidate matched = findUniqueMapNode(candidates, plannedNode);
            writeNodeIndex(repairedArgs, matched.index());
            return operation.request().toBuilder()
                    .arguments(repairedArgs.toString())
                    .build();
        }

        Integer requestedIndex = readNodeIndex(args);
        if (requestedIndex != null && candidates.stream().anyMatch(candidate -> candidate.index() == requestedIndex)) {
            writeNodeIndex(repairedArgs, requestedIndex);
            return operation.request().toBuilder()
                    .arguments(repairedArgs.toString())
                    .build();
        }

        throw new GameBridgeException("STS2 地图节点操作缺少 node 文本或 node_index；当前节点="
                + renderMapNodeCandidates(candidates));
    }

    private QueuedGameOperation repairOperationModePrefix(GameAdapterContext context, QueuedGameOperation operation, GameStateSnapshot currentState) {
        String originalName = operation.request().name();
        String repairedName = resolveModeAwareOperationName(context, originalName, currentState);
        if (safeEquals(originalName, repairedName)) {
            return operation;
        }
        ToolExecutionRequest repairedRequest = operation.request().toBuilder()
                .name(repairedName)
                .build();
        log.debug("[STS2] 操作工具名前缀已按当前模式修复: {} -> {}", originalName, repairedName);
        return operation.withRequest(repairedRequest);
    }

    private String resolveModeAwareOperationName(GameAdapterContext context, String toolName, GameStateSnapshot currentState) {
        if (toolName == null || toolName.isBlank()) {
            return toolName;
        }
        String modePrefix = modeDetector.modePrefix(context, currentState);
        String baseName = baseToolName(toolName);
        if (STS2OperationMetadata.MP_PREFIX.equals(modePrefix)
                && !toolName.startsWith(STS2OperationMetadata.MP_PREFIX)
                && isModePrefixedOperation(baseName)) {
            return STS2OperationMetadata.MP_PREFIX + baseName;
        }
        if (!STS2OperationMetadata.MP_PREFIX.equals(modePrefix)
                && toolName.startsWith(STS2OperationMetadata.MP_PREFIX)) {
            return baseName;
        }
        return toolName;
    }

    private boolean isModePrefixedOperation(String baseName) {
        return baseName.startsWith("combat_")
                || baseName.startsWith("event_")
                || baseName.startsWith("rest_")
                || baseName.startsWith("map_")
                || baseName.startsWith("shop_")
                || baseName.startsWith("rewards_")
                || baseName.startsWith("claim_")
                || baseName.startsWith("use_potion");
    }

    private ToolExecutionRequest repairIndexedOptionSelection(QueuedGameOperation operation,
                                                              GameStateSnapshot currentState) {
        JsonNode args = parseArgs(operation.request().arguments());
        if (!args.isObject()) {
            throw new GameBridgeException("STS2 选项操作参数必须是 JSON 对象: " + operation.request().arguments());
        }
        ObjectNode repairedArgs = args.deepCopy();
        String requestedOptionName = firstNonBlank(
                readOptionName(args),
                metadata(operation, STS2OperationMetadata.PLANNED_OPTION_LABEL));
        Integer requestedIndex = readOptionIndex(args);

        List<OptionCandidate> candidates = optionCandidates(currentState);
        if (!requestedOptionName.isBlank()) {
            OptionCandidate matched = findUniqueOptionByName(candidates, requestedOptionName);
            removeVirtualOptionNameFields(repairedArgs);
            writeOptionIndex(repairedArgs, matched.index());
            log.debug("[STS2] 选项文本已修复为 option_index: tool={}, option={}, index={}",
                    operation.request().name(), requestedOptionName, matched.index());
            return operation.request().toBuilder()
                    .arguments(repairedArgs.toString())
                    .build();
        }

        if (requestedIndex != null) {
            if (!candidates.isEmpty() && candidates.stream().noneMatch(candidate -> candidate.index() == requestedIndex)) {
                throw new GameBridgeException("STS2 选项索引不在当前可选范围内: index="
                        + requestedIndex + "，当前选项=" + renderOptionCandidates(candidates));
            }
            removeVirtualOptionNameFields(repairedArgs);
            writeOptionIndex(repairedArgs, requestedIndex);
            return operation.request().toBuilder()
                    .arguments(repairedArgs.toString())
                    .build();
        }

        throw new GameBridgeException("STS2 选项操作缺少 option 文本或 option_index；当前选项="
                + renderOptionCandidates(candidates));
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
                + "，丢弃旧的 menu_select。");
    }

    private boolean isPlayCard(String toolName) {
        String name = baseToolName(toolName);
        return STS2OperationMetadata.TOOL_COMBAT_PLAY_CARD.equals(name)
                || STS2OperationMetadata.LEGACY_TOOL_PLAY_CARD.equals(name);
    }

    private boolean isCombatSelectCard(String toolName) {
        return STS2OperationMetadata.TOOL_COMBAT_SELECT_CARD.equals(baseToolName(toolName));
    }

    private boolean isRewardPickCard(String toolName) {
        return STS2OperationMetadata.TOOL_REWARDS_PICK_CARD.equals(baseToolName(toolName));
    }

    private boolean isMapNodeTool(String toolName) {
        String name = baseToolName(toolName);
        return "map_choose_node".equals(name) || "map_vote".equals(name);
    }

    private boolean isMenuSelect(String toolName) {
        return "menu_select".equals(toolName);
    }

    private boolean isIndexedOptionTool(String toolName) {
        String name = baseToolName(toolName);
        return name.endsWith("_choose_option");
    }

    private String baseToolName(String toolName) {
        if (toolName == null) {
            return "";
        }
        return toolName.startsWith(STS2OperationMetadata.MP_PREFIX)
                ? toolName.substring(STS2OperationMetadata.MP_PREFIX.length())
                : toolName;
    }

    private JsonNode hand(GameStateSnapshot state) {
        if (state == null || state.json() == null) {
            return objectMapper.createArrayNode();
        }
        return state.json().path("player").path("hand");
    }

    private JsonNode handSelectCards(GameStateSnapshot state) {
        if (state == null || state.json() == null) {
            return objectMapper.createArrayNode();
        }
        return state.json().path("hand_select").path("cards");
    }

    private JsonNode cardRewardCards(GameStateSnapshot state) {
        if (state == null || state.json() == null) {
            return objectMapper.createArrayNode();
        }
        return state.json().path("card_reward").path("cards");
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

    private String readOptionName(JsonNode args) {
        if (args == null || args.isMissingNode() || args.isNull()) {
            return "";
        }
        for (String field : STS2OperationMetadata.VIRTUAL_OPTION_NAME_FIELDS) {
            JsonNode value = args.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private Integer readOptionIndex(JsonNode args) {
        if (args == null || args.isMissingNode() || args.isNull()) {
            return null;
        }
        if (args.has("option_index")) return args.path("option_index").asInt();
        if (args.has("optionIndex")) return args.path("optionIndex").asInt();
        if (args.has("index")) return args.path("index").asInt();
        return null;
    }

    private Integer readNodeIndex(JsonNode args) {
        if (args == null || args.isMissingNode() || args.isNull()) {
            return null;
        }
        if (args.has("node_index")) return args.path("node_index").asInt();
        if (args.has("nodeIndex")) return args.path("nodeIndex").asInt();
        if (args.has("index")) return args.path("index").asInt();
        return null;
    }

    private String readMapNode(JsonNode args) {
        if (args == null || args.isMissingNode() || args.isNull()) {
            return "";
        }
        String col = args.path("col").asText("");
        String row = args.path("row").asText("");
        if (!col.isBlank() && !row.isBlank()) {
            return "(" + col.trim() + "," + row.trim() + ")";
        }
        for (String field : List.of("node", "node_label", "nodeLabel", "label", "type")) {
            JsonNode value = args.path(field);
            if (value.isValueNode() && !value.asText("").isBlank()) {
                return value.asText("").trim();
            }
        }
        return "";
    }

    private List<OptionCandidate> optionCandidates(GameStateSnapshot state) {
        JsonNode root = state == null ? null : state.json();
        if (root == null || root.isMissingNode() || root.isNull()) {
            return List.of();
        }
        List<OptionCandidate> candidates = new ArrayList<>();
        collectOptionCandidates(candidates, root.path("options"));
        collectOptionCandidates(candidates, root.path("event").path("options"));
        collectOptionCandidates(candidates, root.path("screen").path("options"));
        collectOptionCandidates(candidates, root.path("rest").path("options"));
        collectOptionCandidates(candidates, root.path("rest_site").path("options"));
        collectOptionCandidates(candidates, root.path("campfire").path("options"));
        return candidates;
    }

    private void collectOptionCandidates(List<OptionCandidate> candidates, JsonNode options) {
        if (options == null || !options.isArray()) {
            return;
        }
        for (int i = 0; i < options.size(); i++) {
            JsonNode option = options.path(i);
            int index = readCandidateIndex(option, i);
            String label = renderOptionLabel(option);
            if (!label.isBlank()) {
                OptionCandidate candidate = new OptionCandidate(index, label);
                if (!candidates.contains(candidate)) {
                    candidates.add(candidate);
                }
            }
        }
    }

    private int readCandidateIndex(JsonNode option, int fallback) {
        if (option != null && option.isObject()) {
            if (option.has("option_index")) return option.path("option_index").asInt();
            if (option.has("optionIndex")) return option.path("optionIndex").asInt();
            if (option.has("index")) return option.path("index").asInt();
        }
        return fallback;
    }

    private List<MapNodeCandidate> mapNodeCandidates(GameStateSnapshot state) {
        JsonNode root = state == null ? null : state.json();
        JsonNode options = root == null ? objectMapper.createArrayNode() : root.path("map").path("next_options");
        if (!options.isArray()) {
            return List.of();
        }
        List<MapNodeCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            JsonNode option = options.path(i);
            String label = renderMapNode(option);
            if (!label.isBlank()) {
                candidates.add(new MapNodeCandidate(readNodeCandidateIndex(option, i), label));
            }
        }
        return candidates;
    }

    private int readNodeCandidateIndex(JsonNode option, int fallback) {
        if (option != null && option.isObject()) {
            if (option.has("node_index")) return option.path("node_index").asInt();
            if (option.has("nodeIndex")) return option.path("nodeIndex").asInt();
            if (option.has("index")) return option.path("index").asInt();
        }
        return fallback;
    }

    private String renderMapNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "";
        }
        String col = node.path("col").asText("");
        String row = node.path("row").asText("");
        String type = node.path("type").asText("");
        String label = node.path("label").asText("");
        List<String> parts = new ArrayList<>();
        if (!col.isBlank() && !row.isBlank()) {
            parts.add("(" + col + "," + row + ")");
        }
        if (!type.isBlank()) {
            parts.add(type);
        }
        if (!label.isBlank()) {
            parts.add(label);
        }
        return String.join(" ", parts);
    }

    private String renderOptionLabel(JsonNode option) {
        if (option == null || option.isMissingNode() || option.isNull()) {
            return "";
        }
        if (option.isTextual()) {
            return option.asText("").trim();
        }
        if (!option.isObject()) {
            return "";
        }
        Set<String> primaryParts = new LinkedHashSet<>();
        for (String field : STS2OperationMetadata.VIRTUAL_OPTION_NAME_FIELDS) {
            JsonNode value = option.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                primaryParts.add(value.asText().trim());
            }
        }
        if (!primaryParts.isEmpty()) {
            return String.join(" ", primaryParts).trim();
        }
        JsonNode description = option.path("description");
        if (description.isTextual() && !description.asText().isBlank()) {
            return description.asText().trim();
        }
        return "";
    }

    private OptionCandidate findUniqueOptionByName(List<OptionCandidate> candidates, String requestedOptionName) {
        if (candidates == null || candidates.isEmpty()) {
            throw new GameBridgeException("STS2 当前状态没有可匹配的 options，无法选择：" + requestedOptionName);
        }
        String requested = normalizeOptionText(requestedOptionName);
        List<OptionCandidate> matches = candidates.stream()
                .filter(candidate -> optionMatches(requested, candidate.label()))
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.isEmpty()) {
            OptionCandidate ordinalCandidate = findOptionByNaturalOrdinal(candidates, requestedOptionName);
            if (ordinalCandidate != null) {
                return ordinalCandidate;
            }
            throw new GameBridgeException("STS2 当前选项中找不到：" + requestedOptionName
                    + "；当前选项=" + renderOptionCandidates(candidates));
        }
        throw new GameBridgeException("STS2 选项文本匹配到多个候选，拒绝猜测："
                + requestedOptionName + "；候选=" + renderOptionCandidates(matches));
    }

    private MapNodeCandidate findUniqueMapNode(List<MapNodeCandidate> candidates, String plannedNode) {
        if (candidates == null || candidates.isEmpty()) {
            throw new GameBridgeException("STS2 当前状态没有可匹配的地图节点，无法选择：" + plannedNode);
        }
        String requested = normalizeOptionText(plannedNode);
        List<MapNodeCandidate> matches = candidates.stream()
                .filter(candidate -> {
                    String label = normalizeOptionText(candidate.label());
                    return requested.equals(label) || requested.contains(label) || label.contains(requested);
                })
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.isEmpty()) {
            throw new GameBridgeException("STS2 当前地图节点中找不到：" + plannedNode
                    + "；当前节点=" + renderMapNodeCandidates(candidates));
        }
        throw new GameBridgeException("STS2 地图节点匹配到多个候选，拒绝猜测："
                + plannedNode + "；候选=" + renderMapNodeCandidates(matches));
    }

    private boolean optionMatches(String requested, String candidateLabel) {
        String candidate = normalizeOptionText(candidateLabel);
        if (requested.isBlank() || candidate.isBlank()) {
            return false;
        }
        return requested.equals(candidate) || requested.contains(candidate) || candidate.contains(requested);
    }

    private OptionCandidate findOptionByNaturalOrdinal(List<OptionCandidate> candidates, String requestedOptionName) {
        Integer ordinal = readNaturalOptionOrdinal(requestedOptionName);
        if (ordinal == null) {
            return null;
        }
        int position = ordinal - 1;
        if (position < 0 || position >= candidates.size()) {
            throw new GameBridgeException("STS2 选项序号不在当前可选范围内: 第"
                    + ordinal + "个，当前选项=" + renderOptionCandidates(candidates));
        }
        return candidates.get(position);
    }

    private Integer readNaturalOptionOrdinal(String requestedOptionName) {
        if (requestedOptionName == null || requestedOptionName.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("第\\s*([一二三四五六七八九十两俩0-9]+)\\s*(?:个)?\\s*(?:选项|项)")
                .matcher(requestedOptionName.trim());
        if (!matcher.find()) {
            return null;
        }
        return parseChineseOrArabicNumber(matcher.group(1));
    }

    private Integer parseChineseOrArabicNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(normalized);
        }
        return switch (normalized) {
            case "一" -> 1;
            case "二", "两", "俩" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> null;
        };
    }

    private String normalizeOptionText(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s:：,，.。!！?？、;；()（）\\[\\]【】{}<>《》\"'“”‘’_\\-/|]+", "");
    }

    private String renderOptionCandidates(List<OptionCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "(无)";
        }
        List<String> rendered = new ArrayList<>();
        for (OptionCandidate candidate : candidates) {
            rendered.add(candidate.index() + ":" + candidate.label());
        }
        return String.join(", ", rendered);
    }

    private String renderMapNodeCandidates(List<MapNodeCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "(无)";
        }
        List<String> rendered = new ArrayList<>();
        for (MapNodeCandidate candidate : candidates) {
            rendered.add(candidate.index() + ":" + candidate.label());
        }
        return String.join(", ", rendered);
    }

    private void removeVirtualOptionNameFields(ObjectNode args) {
        for (String field : STS2OperationMetadata.VIRTUAL_OPTION_NAME_FIELDS) {
            args.remove(field);
        }
    }

    private void writeOptionIndex(ObjectNode args, int index) {
        args.remove("index");
        args.remove("optionIndex");
        args.put("option_index", index);
    }

    private void writeNodeIndex(ObjectNode args, int index) {
        args.remove("index");
        args.remove("nodeIndex");
        args.put("node_index", index);
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
        for (String field : STS2OperationMetadata.VIRTUAL_CARD_NAME_FIELDS) {
            JsonNode value = args.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private void removeVirtualCardNameFields(ObjectNode args) {
        for (String field : STS2OperationMetadata.VIRTUAL_CARD_NAME_FIELDS) {
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

    private void writeMcpCardIndex(ObjectNode args, int index) {
        args.remove("cardIndex");
        args.remove("index");
        args.put("card_index", index);
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

    private String metadata(QueuedGameOperation operation, String key) {
        Object value = operation.metadata().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record OptionCandidate(int index, String label) {
    }

    private record MapNodeCandidate(int index, String label) {
    }
}
