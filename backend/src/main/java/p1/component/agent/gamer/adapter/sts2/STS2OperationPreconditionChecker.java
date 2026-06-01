package p1.component.agent.gamer.adapter.sts2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import p1.component.agent.gamer.adapter.core.GameOperationPrecondition;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;
import p1.component.agent.gamer.adapter.core.QueuedGameOperation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * STS2 单条操作执行前置条件检查。
 * <p>
 * 这里只确认计划中的牌、目标、选项等对象仍然存在；费用、回合合法性、
 * can_play 等最终裁判仍交给 MCP。
 */
public class STS2OperationPreconditionChecker {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public GameOperationPrecondition check(QueuedGameOperation operation, GameStateSnapshot currentState) {
        if (operation == null || operation.request() == null) {
            return GameOperationPrecondition.failed("empty_operation", "操作为空");
        }
        JsonNode args = parseArgs(operation.request().arguments());
        String toolName = baseToolName(operation.request().name());
        if (isPlayCard(toolName)) {
            return checkPlayCard(operation, currentState, args);
        }
        if (STS2OperationMetadata.TOOL_COMBAT_SELECT_CARD.equals(toolName)) {
            return checkCardSelection(operation, handSelectCards(currentState), args, "hand_select_card");
        }
        if (STS2OperationMetadata.TOOL_REWARDS_PICK_CARD.equals(toolName)) {
            return checkCardSelection(operation, cardRewardCards(currentState), args, "reward_card");
        }
        if (toolName.endsWith("_choose_option")) {
            return checkOption(operation, currentState, args, "option");
        }
        if (toolName.contains("map") || toolName.contains("node")) {
            return checkMapNode(operation, currentState);
        }
        return GameOperationPrecondition.passed(toolName);
    }

    private GameOperationPrecondition checkPlayCard(QueuedGameOperation operation,
                                                    GameStateSnapshot currentState,
                                                    JsonNode args) {
        String cardName = firstNonBlank(metadata(operation, STS2OperationMetadata.PLANNED_CARD_NAME), readCardName(args));
        String targetId = firstNonBlank(metadata(operation, STS2OperationMetadata.PLANNED_TARGET_ID), readTargetId(args));
        String signature = "play_card card=" + blankTo(cardName, "(未记录)")
                + " target=" + blankTo(targetId, "(无)");
        JsonNode hand = hand(currentState);
        int cardIndex = cardName.isBlank() ? readCardIndexOrInvalid(args) : findCardByName(hand, cardName);
        if (!cardName.isBlank() && cardIndex < 0) {
            return GameOperationPrecondition.failed(signature, "计划手牌已不存在：" + cardName
                    + "；当前手牌=" + renderCardNames(hand));
        }
        if (cardName.isBlank() && cardIndex < 0) {
            return GameOperationPrecondition.failed(signature, "出牌操作缺少可验证的牌名或有效索引；当前手牌="
                    + renderCardNames(hand));
        }
        if (!targetStillExists(currentState, targetId, hand.path(cardIndex))) {
            return GameOperationPrecondition.failed(signature, "计划目标已不存在：" + targetId
                    + "；当前敌人=" + renderEnemies(currentState));
        }
        return GameOperationPrecondition.passed(signature);
    }

    private GameOperationPrecondition checkCardSelection(QueuedGameOperation operation,
                                                         JsonNode cards,
                                                         JsonNode args,
                                                         String signaturePrefix) {
        String cardName = firstNonBlank(metadata(operation, STS2OperationMetadata.PLANNED_CARD_NAME), readCardName(args));
        String signature = signaturePrefix + " card=" + blankTo(cardName, "(未记录)");
        if (!cardName.isBlank() && findCardByName(cards, cardName) < 0) {
            return GameOperationPrecondition.failed(signature, "计划卡牌已不存在：" + cardName
                    + "；当前候选=" + renderCardNames(cards));
        }
        if (cardName.isBlank() && readCardIndexOrInvalid(args) < 0) {
            return GameOperationPrecondition.failed(signature, "选牌操作缺少可验证的牌名或有效索引；当前候选="
                    + renderCardNames(cards));
        }
        return GameOperationPrecondition.passed(signature);
    }

    private GameOperationPrecondition checkOption(QueuedGameOperation operation,
                                                  GameStateSnapshot currentState,
                                                  JsonNode args,
                                                  String signaturePrefix) {
        String optionLabel = firstNonBlank(metadata(operation, STS2OperationMetadata.PLANNED_OPTION_LABEL), readOptionName(args));
        String signature = signaturePrefix + " label=" + blankTo(optionLabel, "(未记录)");
        List<OptionCandidate> candidates = optionCandidates(currentState);
        if (!optionLabel.isBlank() && candidates.stream().noneMatch(candidate -> optionMatches(optionLabel, candidate.label()))) {
            return GameOperationPrecondition.failed(signature, "计划选项已不存在：" + optionLabel
                    + "；当前选项=" + renderOptionCandidates(candidates));
        }
        if (optionLabel.isBlank()) {
            Integer index = readOptionIndex(args);
            if (index == null || candidates.stream().noneMatch(candidate -> candidate.index() == index)) {
                return GameOperationPrecondition.failed(signature, "选项操作缺少可验证的选项名或有效索引；当前选项="
                        + renderOptionCandidates(candidates));
            }
        }
        return GameOperationPrecondition.passed(signature);
    }

    private GameOperationPrecondition checkMapNode(QueuedGameOperation operation, GameStateSnapshot currentState) {
        String plannedNode = metadata(operation, STS2OperationMetadata.PLANNED_MAP_NODE);
        String signature = "map_node=" + blankTo(plannedNode, "(未记录)");
        if (plannedNode.isBlank()) {
            return GameOperationPrecondition.passed(signature);
        }
        List<String> candidates = mapNodes(currentState);
        boolean exists = candidates.stream().anyMatch(candidate -> normalize(candidate).contains(normalize(plannedNode))
                || normalize(plannedNode).contains(normalize(candidate)));
        if (!exists) {
            return GameOperationPrecondition.failed(signature, "计划地图节点已不存在：" + plannedNode
                    + "；当前节点=" + String.join(", ", candidates));
        }
        return GameOperationPrecondition.passed(signature);
    }

    private boolean targetStillExists(GameStateSnapshot state, String targetId, JsonNode plannedCard) {
        if (targetId == null || targetId.isBlank()) {
            return true;
        }
        if (isSelfTargetCard(plannedCard) || targetId.toUpperCase(Locale.ROOT).startsWith("PLAYER")) {
            return true;
        }
        JsonNode enemies = state == null || state.json() == null
                ? objectMapper.createArrayNode()
                : state.json().path("battle").path("enemies");
        if (!enemies.isArray()) {
            return true;
        }
        for (JsonNode enemy : enemies) {
            if (targetId.equals(enemy.path("entity_id").asText(""))
                    || targetId.equals(enemy.path("id").asText(""))
                    || targetId.equals(enemy.path("name").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private JsonNode parseArgs(String rawArgs) {
        try {
            return objectMapper.readTree(rawArgs == null || rawArgs.isBlank() ? "{}" : rawArgs);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode hand(GameStateSnapshot state) {
        return state == null || state.json() == null
                ? objectMapper.createArrayNode()
                : state.json().path("player").path("hand");
    }

    private JsonNode handSelectCards(GameStateSnapshot state) {
        return state == null || state.json() == null
                ? objectMapper.createArrayNode()
                : state.json().path("hand_select").path("cards");
    }

    private JsonNode cardRewardCards(GameStateSnapshot state) {
        return state == null || state.json() == null
                ? objectMapper.createArrayNode()
                : state.json().path("card_reward").path("cards");
    }

    private String metadata(QueuedGameOperation operation, String key) {
        Object value = operation.metadata().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean isPlayCard(String toolName) {
        return STS2OperationMetadata.TOOL_COMBAT_PLAY_CARD.equals(toolName)
                || STS2OperationMetadata.LEGACY_TOOL_PLAY_CARD.equals(toolName);
    }

    private String baseToolName(String toolName) {
        if (toolName == null) {
            return "";
        }
        return toolName.startsWith(STS2OperationMetadata.MP_PREFIX)
                ? toolName.substring(STS2OperationMetadata.MP_PREFIX.length())
                : toolName;
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

    private int readCardIndexOrInvalid(JsonNode args) {
        Integer index = readCardIndex(args);
        return index == null ? -1 : index;
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

    private int findCardByName(JsonNode cards, String name) {
        if (!cards.isArray()) {
            return -1;
        }
        for (int i = 0; i < cards.size(); i++) {
            if (name.equals(cards.path(i).path("name").asText(""))) {
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

    private List<String> mapNodes(GameStateSnapshot state) {
        JsonNode options = state == null || state.json() == null
                ? objectMapper.createArrayNode()
                : state.json().path("map").path("next_options");
        if (!options.isArray()) {
            return List.of();
        }
        List<String> nodes = new ArrayList<>();
        for (JsonNode option : options) {
            String rendered = renderMapNode(option);
            if (!rendered.isBlank()) {
                nodes.add(rendered);
            }
        }
        return nodes;
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

    private String renderCardNames(JsonNode cards) {
        if (!cards.isArray() || cards.isEmpty()) {
            return "(空)";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode card : cards) {
            names.add(card.path("name").asText("(未知牌)"));
        }
        return String.join(", ", names);
    }

    private String renderEnemies(GameStateSnapshot state) {
        JsonNode enemies = state == null || state.json() == null
                ? objectMapper.createArrayNode()
                : state.json().path("battle").path("enemies");
        if (!enemies.isArray() || enemies.isEmpty()) {
            return "(无)";
        }
        List<String> rendered = new ArrayList<>();
        for (JsonNode enemy : enemies) {
            rendered.add(firstNonBlank(
                    enemy.path("entity_id").asText(""),
                    enemy.path("id").asText(""),
                    enemy.path("name").asText("(未知敌人)")));
        }
        return String.join(", ", rendered);
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

    private boolean optionMatches(String requested, String candidateLabel) {
        String requestedNormalized = normalize(requested);
        String candidateNormalized = normalize(candidateLabel);
        if (requestedNormalized.isBlank() || candidateNormalized.isBlank()) {
            return false;
        }
        return requestedNormalized.equals(candidateNormalized)
                || requestedNormalized.contains(candidateNormalized)
                || candidateNormalized.contains(requestedNormalized);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s:：,，.。!！?？、;；()（）\\[\\]【】{}<>《》\"'“”‘’_\\-/|]+", "");
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

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record OptionCandidate(int index, String label) {
    }
}
