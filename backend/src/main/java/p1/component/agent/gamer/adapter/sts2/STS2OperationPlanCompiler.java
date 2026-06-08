package p1.component.agent.gamer.adapter.sts2;

import com.fasterxml.jackson.databind.JsonNode;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.adapter.core.QueuedGameOperation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * STS2 操作计划编译器。
 * <p>
 * 该组件在操作入队时记录牌名、目标、选项名等语义信息，执行前再由修复器映射到当前 index。
 * 它不判断费用、收益或 can_play，最终合法性仍由 MCP 决定。
 */
public class STS2OperationPlanCompiler {

    public QueuedGameOperation prepareOperation(GameOperation operation, GameStateSnapshot plannedState) {
        QueuedGameOperation queued = QueuedGameOperation.from(operation, plannedState);
        recordOperationMetadata(queued, operation, plannedState);
        return queued;
    }

    public ArrayDeque<QueuedGameOperation> prepareBatch(List<GameOperation> operations, GameStateSnapshot plannedState) {
        ArrayDeque<QueuedGameOperation> queue = new ArrayDeque<>();
        List<JsonNode> mutableHand = mutableHandCopy(plannedState);

        for (GameOperation op : operations) {
            QueuedGameOperation queued = QueuedGameOperation.from(op, plannedState);
            if (isPlayCard(op.toolName())) {
                recordPlayCardMetadata(queued, op.args(), mutableHand);
            } else {
                recordOperationMetadata(queued, op, plannedState);
            }
            queue.offerLast(queued);
        }
        return queue;
    }

    private void recordOperationMetadata(QueuedGameOperation queued,
                                         GameOperation operation,
                                         GameStateSnapshot plannedState) {
        JsonNode args = operation.args();
        String toolName = operation.toolName();
        if (isPlayCard(toolName)) {
            recordPlayCardMetadata(queued, args, mutableHandCopy(plannedState));
            return;
        }
        if (isCombatSelectCard(toolName)) {
            recordCardSelectionMetadata(queued, args, handSelectCards(plannedState));
            return;
        }
        if (isRewardPickCard(toolName)) {
            recordCardSelectionMetadata(queued, args, cardRewardCards(plannedState));
            return;
        }
        if (isIndexedOptionTool(toolName)) {
            recordOptionMetadata(queued, args, optionCandidates(plannedState));
            return;
        }
        if (isMapTool(toolName)) {
            recordMapMetadata(queued, args, plannedState);
        }
    }

    private void recordPlayCardMetadata(QueuedGameOperation queued, JsonNode args, List<JsonNode> mutableHand) {
        String targetId = readTargetId(args);
        if (!targetId.isBlank()) {
            queued.metadata().put(STS2OperationMetadata.PLANNED_TARGET_ID, targetId);
        }

        String requestedCardName = readCardName(args);
        if (!requestedCardName.isBlank()) {
            queued.metadata().put(STS2OperationMetadata.PLANNED_CARD_NAME, requestedCardName);
            removeFirstCardByName(mutableHand, requestedCardName);
            return;
        }

        Integer cardIndex = readCardIndex(args);
        if (cardIndex == null || cardIndex < 0 || cardIndex >= mutableHand.size()) {
            return;
        }
        JsonNode card = mutableHand.get(cardIndex);
        String cardName = card.path("name").asText("");
        if (!cardName.isBlank()) {
            queued.metadata().put(STS2OperationMetadata.PLANNED_CARD_NAME, cardName);
        }
        mutableHand.remove(cardIndex.intValue());
    }

    private void recordCardSelectionMetadata(QueuedGameOperation queued, JsonNode args, JsonNode cards) {
        String requestedCardName = readCardName(args);
        if (!requestedCardName.isBlank()) {
            queued.metadata().put(STS2OperationMetadata.PLANNED_CARD_NAME, requestedCardName);
            return;
        }
        Integer cardIndex = readCardIndex(args);
        if (cardIndex == null || !cards.isArray() || cardIndex < 0 || cardIndex >= cards.size()) {
            return;
        }
        String cardName = cards.path(cardIndex).path("name").asText("");
        if (!cardName.isBlank()) {
            queued.metadata().put(STS2OperationMetadata.PLANNED_CARD_NAME, cardName);
        }
    }

    private void recordOptionMetadata(QueuedGameOperation queued, JsonNode args, List<OptionCandidate> candidates) {
        String optionName = readOptionName(args);
        if (!optionName.isBlank()) {
            queued.metadata().put(STS2OperationMetadata.PLANNED_OPTION_LABEL, optionName);
            return;
        }
        Integer index = readOptionIndex(args);
        if (index == null) {
            return;
        }
        candidates.stream()
                .filter(candidate -> candidate.index() == index)
                .findFirst()
                .ifPresent(candidate -> queued.metadata().put(STS2OperationMetadata.PLANNED_OPTION_LABEL, candidate.label()));
    }

    private void recordMapMetadata(QueuedGameOperation queued, JsonNode args, GameStateSnapshot plannedState) {
        String explicit = readMapNode(args);
        if (!explicit.isBlank()) {
            queued.metadata().put(STS2OperationMetadata.PLANNED_MAP_NODE, explicit);
            return;
        }
        Integer index = readNodeIndex(args);
        JsonNode options = plannedState == null || plannedState.json() == null
                ? null
                : plannedState.json().path("map").path("next_options");
        if (index == null || options == null || !options.isArray() || index < 0 || index >= options.size()) {
            return;
        }
        String node = renderMapNode(options.path(index));
        if (!node.isBlank()) {
            queued.metadata().put(STS2OperationMetadata.PLANNED_MAP_NODE, node);
        }
    }

    private List<JsonNode> mutableHandCopy(GameStateSnapshot state) {
        List<JsonNode> result = new ArrayList<>();
        JsonNode hand = hand(state);
        if (hand.isArray()) {
            hand.forEach(result::add);
        }
        return result;
    }

    private void removeFirstCardByName(List<JsonNode> cards, String cardName) {
        for (int i = 0; i < cards.size(); i++) {
            if (cardName.equals(cards.get(i).path("name").asText(""))) {
                cards.remove(i);
                return;
            }
        }
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

    private boolean isIndexedOptionTool(String toolName) {
        return baseToolName(toolName).endsWith("_choose_option");
    }

    private boolean isMapTool(String toolName) {
        String name = baseToolName(toolName);
        return name.contains("map") || name.contains("node");
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
        return state == null || state.json() == null
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode()
                : state.json().path("player").path("hand");
    }

    private JsonNode handSelectCards(GameStateSnapshot state) {
        return state == null || state.json() == null
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode()
                : state.json().path("hand_select").path("cards");
    }

    private JsonNode cardRewardCards(GameStateSnapshot state) {
        return state == null || state.json() == null
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode()
                : state.json().path("card_reward").path("cards");
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
            String label = renderOptionLabel(option);
            if (!label.isBlank()) {
                candidates.add(new OptionCandidate(readCandidateIndex(option, i), label));
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

    private String renderOptionLabel(JsonNode option) {
        if (option == null || option.isMissingNode() || option.isNull()) {
            return "";
        }
        if (option.isTextual()) {
            return option.asText("").trim();
        }
        for (String field : STS2OperationMetadata.VIRTUAL_OPTION_NAME_FIELDS) {
            JsonNode value = option.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        JsonNode description = option.path("description");
        return description.isTextual() ? description.asText("").trim() : "";
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

    private String readTargetId(JsonNode args) {
        if (args == null || args.isMissingNode() || args.isNull()) {
            return "";
        }
        for (String field : List.of("target", "target_id", "targetId", "enemy_id", "enemyId", "entity_id", "entityId")) {
            JsonNode value = args.path(field);
            if (value.isValueNode() && !value.asText("").isBlank()) {
                return value.asText("").trim();
            }
        }
        return "";
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

    private record OptionCandidate(int index, String label) {
    }
}
