package p1.component.agent.gamer.adapter.sts2;

import com.fasterxml.jackson.databind.JsonNode;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 渲染 STS2 状态摘要。
 * <p>
 * RP 需要完整语义，但不需要直接阅读原始嵌套 JSON；该渲染器保留当前界面、
 * 手牌、奖励、选项等关键信息，并避免暴露容易误导 RP 的底层下标细节。
 */
public class STS2StateSummaryRenderer {

    private static final String HIDDEN_FIELD_CAN_PROCEED = "can_proceed";

    public String render(GameStateSnapshot state) {
        if (state == null || state.json() == null) {
            return "(未能获取 STS2 状态)";
        }
        return renderRpStateSummary(state).trim();
    }

    private String renderRpStateSummary(GameStateSnapshot state) {
        JsonNode root = state.json();
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "state.type", firstNonBlank(text(root.path("state_type")), state.stateType()));
        appendRunSummary(sb, root.path("run"));
        appendBattleSummary(sb, root.path("battle"));
        appendHandSelectSummary(sb, root.path("hand_select"));
        appendPlayerSummary(sb, root.path("player"));
        appendMapSummary(sb, root.path("map"));
        appendOptionSummary(sb, "event.options", root.path("event").path("options"));
        appendOptionSummary(sb, "options", root.path("options"));
        appendRewardsSummary(sb, root.path("rewards"));
        appendCardRewardSummary(sb, root.path("card_reward"));
        appendGenericSummary(sb, "card_select", root.path("card_select"));
        appendGenericSummary(sb, "shop", root.path("shop"));
        appendGenericSummary(sb, "campfire", root.path("campfire"));
        appendGenericSummary(sb, "rest", root.path("rest"));
        appendGenericSummary(sb, "message", root.path("message"));
        return sb.toString();
    }

    private void appendRunSummary(StringBuilder sb, JsonNode run) {
        if (!run.isObject()) {
            return;
        }
        appendLine(sb, "run.act", text(run.path("act")));
        appendLine(sb, "run.floor", text(run.path("floor")));
        appendLine(sb, "run.ascension", text(run.path("ascension")));
    }

    private void appendBattleSummary(StringBuilder sb, JsonNode battle) {
        if (!battle.isObject() || battle.isEmpty()) {
            return;
        }
        appendLine(sb, "battle.round", text(battle.path("round")));
        appendLine(sb, "battle.turn", text(battle.path("turn")));
        appendLine(sb, "battle.is_play_phase", text(battle.path("is_play_phase")));
        appendCardList(sb, "enemies", battle.path("enemies"), true);
    }

    private void appendHandSelectSummary(StringBuilder sb, JsonNode handSelect) {
        if (!handSelect.isObject() || handSelect.isEmpty()) {
            return;
        }
        appendLine(sb, "hand_select.mode", text(handSelect.path("mode")));
        appendLine(sb, "hand_select.prompt", text(handSelect.path("prompt")));
        appendLine(sb, "hand_select.can_confirm", text(handSelect.path("can_confirm")));
        appendCardList(sb, "hand_select.cards", handSelect.path("cards"), false);
        appendCardList(sb, "hand_select.selected", handSelect.path("selected_cards"), false);
    }

    private void appendPlayerSummary(StringBuilder sb, JsonNode player) {
        if (!player.isObject()) {
            return;
        }
        appendLine(sb, "player.character", text(player.path("character")));
        appendLine(sb, "player.hp", hp(player));
        appendLine(sb, "player.block", text(player.path("block")));
        appendLine(sb, "player.energy", energy(player));
        appendLine(sb, "player.gold", text(player.path("gold")));
        appendCardList(sb, "player.status", player.path("status"), true);
        appendCardList(sb, "hand", player.path("hand"), false);
        appendLine(sb, "draw_pile.count", text(player.path("draw_pile_count")));
        appendLine(sb, "discard_pile.count", text(player.path("discard_pile_count")));
        appendLine(sb, "exhaust_pile.count", text(player.path("exhaust_pile_count")));
        appendCardList(sb, "relics", player.path("relics"), true);
        appendCardList(sb, "potions", player.path("potions"), true);
    }

    private void appendMapSummary(StringBuilder sb, JsonNode map) {
        if (!map.isObject() || map.isEmpty()) {
            return;
        }
        appendLine(sb, "map.current", renderMapNode(map.path("current_position")));
        JsonNode options = map.path("next_options");
        if (options.isArray() && !options.isEmpty()) {
            sb.append("map.next_options:\n");
            for (JsonNode option : options) {
                sb.append("- ").append(renderMapNode(option)).append("\n");
            }
        }
    }

    private void appendOptionSummary(StringBuilder sb, String label, JsonNode options) {
        if (!options.isArray() || options.isEmpty()) {
            return;
        }
        sb.append(label).append(":\n");
        for (JsonNode option : options) {
            sb.append("- ").append(inlineScalars(option)).append("\n");
        }
    }

    private void appendRewardsSummary(StringBuilder sb, JsonNode rewards) {
        if (!rewards.isObject() || rewards.isEmpty()) {
            return;
        }
        appendCardList(sb, "rewards.items", rewards.path("items"), true, true);
    }

    private void appendCardRewardSummary(StringBuilder sb, JsonNode cardReward) {
        if (!cardReward.isObject() || cardReward.isEmpty()) {
            return;
        }
        appendCardList(sb, "card_reward.cards", cardReward.path("cards"), false, true);
        appendLine(sb, "card_reward.can_skip", text(cardReward.path("can_skip")));
    }

    private void appendGenericSummary(StringBuilder sb, String label, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isEmpty()) {
            return;
        }
        if (node.isValueNode()) {
            appendLine(sb, label, text(node));
            return;
        }
        if (node.isArray()) {
            appendCardList(sb, label, node, true);
            return;
        }
        appendLine(sb, label, inlineScalars(node));
    }

    private void appendCardList(StringBuilder sb, String label, JsonNode array, boolean includeEntityId) {
        appendCardList(sb, label, array, includeEntityId, false);
    }

    private void appendCardList(StringBuilder sb, String label, JsonNode array, boolean includeEntityId, boolean includeIndex) {
        if (!array.isArray() || array.isEmpty()) {
            return;
        }
        sb.append(label).append(":\n");
        for (JsonNode item : array) {
            sb.append("- ").append(inlineScalars(item, includeEntityId, includeIndex)).append("\n");
        }
    }

    private String inlineScalars(JsonNode node) {
        return inlineScalars(node, true, false);
    }

    private String inlineScalars(JsonNode node, boolean includeEntityId) {
        return inlineScalars(node, includeEntityId, false);
    }

    private String inlineScalars(JsonNode node, boolean includeEntityId, boolean includeIndex) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (!node.isObject()) {
            return text(node);
        }
        List<String> parts = new ArrayList<>();
        if (includeIndex) {
            addPart(parts, "index", text(node.path("index")));
        }
        addPart(parts, "name", firstNonBlank(
                text(node.path("name")),
                text(node.path("title")),
                text(node.path("relic_name")),
                text(node.path("card_name")),
                text(node.path("potion_name")),
                text(node.path("label"))));
        addPart(parts, "type", text(node.path("type")));
        addPart(parts, "cost", text(node.path("cost")));
        addPart(parts, "rarity", text(node.path("rarity")));
        addPart(parts, "hp", hp(node));
        addPart(parts, "block", text(node.path("block")));
        addPart(parts, "intent", compactList(node.path("intents")));
        addPart(parts, "status", compactList(node.path("status")));
        addPart(parts, "description", firstNonBlank(
                text(node.path("description")),
                text(node.path("card_description")),
                text(node.path("potion_description")),
                text(node.path("relic_description"))));
        if (includeEntityId) {
            addPart(parts, "entity_id", text(node.path("entity_id")));
        }
        if (parts.isEmpty()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue().isValueNode()) {
                    addPart(parts, field.getKey(), text(field.getValue()));
                }
            }
        }
        return String.join(" ", parts);
    }

    private String renderMapNode(JsonNode node) {
        if (!node.isObject() || node.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        addPart(parts, "node", coordinate(node));
        addPart(parts, "type", text(node.path("type")));
        addPart(parts, "visited", text(node.path("visited")));
        addPart(parts, "children", children(node.path("children")));
        return String.join(" ", parts);
    }

    private String compactList(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            values.add(inlineScalars(item));
        }
        values.removeIf(String::isBlank);
        return String.join(" | ", values);
    }

    private void appendLine(StringBuilder sb, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(key).append("=").append(value).append("\n");
    }

    private void addPart(List<String> parts, String key, String value) {
        if (HIDDEN_FIELD_CAN_PROCEED.equals(key)) {
            return;
        }
        if (value != null && !value.isBlank()) {
            parts.add(key + "=" + value);
        }
    }

    private String hp(JsonNode node) {
        String hp = text(node.path("hp"));
        String maxHp = text(node.path("max_hp"));
        if (hp.isBlank() && maxHp.isBlank()) {
            return "";
        }
        return hp + "/" + maxHp;
    }

    private String energy(JsonNode player) {
        String energy = text(player.path("energy"));
        String maxEnergy = text(player.path("max_energy"));
        if (energy.isBlank() && maxEnergy.isBlank()) {
            return "";
        }
        return energy + "/" + maxEnergy;
    }

    private String coordinate(JsonNode node) {
        String col = text(node.path("col"));
        String row = text(node.path("row"));
        if (col.isBlank() || row.isBlank()) {
            return "";
        }
        return "(" + col + "," + row + ")";
    }

    private String children(JsonNode children) {
        if (!children.isArray() || children.isEmpty()) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (JsonNode child : children) {
            if (child.isArray() && child.size() >= 2) {
                values.add("(" + text(child.get(0)) + "," + text(child.get(1)) + ")");
            } else if (child.isObject()) {
                values.add(coordinate(child));
            } else {
                values.add(text(child));
            }
        }
        values.removeIf(String::isBlank);
        return String.join(",", values);
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

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        return node.asText(node.toString());
    }
}
