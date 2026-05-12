package p1.component.agent.gamer.adapter.sts2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * STS2 状态差异渲染器。
 * <p>
 * latest_game_state 已经直接注入 MCP 源 JSON；该渲染器只生成白名单摘要，
 * 避免上一轮结果把 gamer prompt 撑大。
 */
public class STS2StateDiffRenderer {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 渲染操作前后状态白名单 diff。
     *
     * @param before 操作前状态
     * @param after  操作后状态
     * @return 面向 gamer 的紧凑状态变化摘要
     */
    public String renderDiff(GameStateSnapshot before, GameStateSnapshot after) {
        JsonNode beforeRoot = before == null ? objectMapper.createObjectNode() : before.json();
        JsonNode afterRoot = after == null ? objectMapper.createObjectNode() : after.json();
        List<String> changes = new ArrayList<>();
        compare(changes, "state.type", stateType(before), stateType(after));
        compare(changes, "run.act", text(beforeRoot.path("run").path("act")), text(afterRoot.path("run").path("act")));
        compare(changes, "run.floor", text(beforeRoot.path("run").path("floor")), text(afterRoot.path("run").path("floor")));
        compare(changes, "battle.round", text(beforeRoot.path("battle").path("round")), text(afterRoot.path("battle").path("round")));
        compare(changes, "battle.turn", text(beforeRoot.path("battle").path("turn")), text(afterRoot.path("battle").path("turn")));
        compare(changes, "battle.is_play_phase", text(beforeRoot.path("battle").path("is_play_phase")), text(afterRoot.path("battle").path("is_play_phase")));
        compare(changes, "player.hp", hp(beforeRoot.path("player")), hp(afterRoot.path("player")));
        compare(changes, "player.block", text(beforeRoot.path("player").path("block")), text(afterRoot.path("player").path("block")));
        compare(changes, "player.energy", energy(beforeRoot.path("player")), energy(afterRoot.path("player")));
        compare(changes, "player.gold", text(beforeRoot.path("player").path("gold")), text(afterRoot.path("player").path("gold")));
        compare(changes, "player.hand", cardNames(beforeRoot.path("player").path("hand")), cardNames(afterRoot.path("player").path("hand")));
        compare(changes, "piles", pileCounts(beforeRoot.path("player")), pileCounts(afterRoot.path("player")));
        compare(changes, "enemies", enemySummary(beforeRoot.path("battle").path("enemies")), enemySummary(afterRoot.path("battle").path("enemies")));
        compare(changes, "map.current", mapCurrent(beforeRoot.path("map")), mapCurrent(afterRoot.path("map")));
        compare(changes, "map.next_options", mapOptions(beforeRoot.path("map").path("next_options")), mapOptions(afterRoot.path("map").path("next_options")));
        compare(changes, "rewards", genericSummary(beforeRoot.path("rewards")), genericSummary(afterRoot.path("rewards")));
        compare(changes, "card_select", genericSummary(beforeRoot.path("card_select")), genericSummary(afterRoot.path("card_select")));
        if (changes.isEmpty()) {
            return "- 状态签名无变化。";
        }
        return String.join("\n", changes);
    }

    /**
     * 渲染地图节点。
     *
     * @param node 地图节点
     * @return 节点摘要
     */
    private String renderMapNode(JsonNode node) {
        if (!node.isObject() || node.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        String coord = coordinate(node);
        if (!coord.isBlank()) {
            parts.add("node=" + coord);
        }
        addPart(parts, "type", text(node.path("type")));
        addPart(parts, "visited", text(node.path("visited")));
        addPart(parts, "children", children(node.path("children")));
        return String.join(" ", parts);
    }

    /**
     * 将对象中的标量字段渲染成单行。
     *
     * @param object JSON 对象
     * @return 单行摘要
     */
    private String inlineScalars(JsonNode object) {
        List<String> parts = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (isScalar(value) && shouldRenderScalar(value)) {
                parts.add(field.getKey() + "=" + scalar(value));
            }
        }
        return parts.isEmpty() ? compact(object) : String.join(" ", parts);
    }

    /**
     * 追加一行发生变化的 diff。
     *
     * @param changes diff 输出列表
     * @param key     字段名
     * @param before  旧值
     * @param after   新值
     */
    private void compare(List<String> changes, String key, String before, String after) {
        String b = firstNonBlank(before, "(空)");
        String a = firstNonBlank(after, "(空)");
        if (!b.equals(a)) {
            changes.add("- " + key + ": " + b + " -> " + a);
        }
    }

    /**
     * 添加非空字段。
     *
     * @param parts 输出列表
     * @param key   字段名
     * @param value 字段值
     */
    private void addPart(List<String> parts, String key, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(key + "=" + value);
        }
    }

    /**
     * 读取 state_type。
     *
     * @param state 状态快照
     * @return 状态类型
     */
    private String stateType(GameStateSnapshot state) {
        return state == null ? "" : firstNonBlank(state.stateType(), text(state.json().path("state_type")));
    }

    /**
     * 渲染玩家生命。
     *
     * @param player 玩家节点
     * @return 当前值和上限
     */
    private String hp(JsonNode player) {
        String hp = text(player.path("hp"));
        String maxHp = text(player.path("max_hp"));
        if (hp.isBlank() && maxHp.isBlank()) {
            return "";
        }
        return hp + "/" + maxHp;
    }

    /**
     * 渲染玩家能量。
     *
     * @param player 玩家节点
     * @return 当前值和上限
     */
    private String energy(JsonNode player) {
        String energy = text(player.path("energy"));
        String maxEnergy = text(player.path("max_energy"));
        if (energy.isBlank() && maxEnergy.isBlank()) {
            return "";
        }
        return energy + "/" + maxEnergy;
    }

    /**
     * 渲染牌堆数量。
     *
     * @param player 玩家节点
     * @return 牌堆数量摘要
     */
    private String pileCounts(JsonNode player) {
        List<String> parts = new ArrayList<>();
        addPart(parts, "draw", text(player.path("draw_pile_count")));
        addPart(parts, "discard", text(player.path("discard_pile_count")));
        addPart(parts, "exhaust", text(player.path("exhaust_pile_count")));
        return String.join(" ", parts);
    }

    /**
     * 保留手牌原顺序渲染牌名。
     *
     * @param cards 手牌数组
     * @return 牌名摘要
     */
    private String cardNames(JsonNode cards) {
        if (!cards.isArray() || cards.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode card : cards) {
            names.add(firstNonBlank(text(card.path("name")), scalar(card)));
        }
        return String.join(" | ", names);
    }

    /**
     * 渲染敌人关键状态。
     *
     * @param enemies 敌人数组
     * @return 敌人摘要
     */
    private String enemySummary(JsonNode enemies) {
        if (!enemies.isArray() || enemies.isEmpty()) {
            return "";
        }
        List<String> rendered = new ArrayList<>();
        for (JsonNode enemy : enemies) {
            String id = firstNonBlank(text(enemy.path("entity_id")), text(enemy.path("name")), "?");
            rendered.add(id + ":" + text(enemy.path("hp")) + "/" + text(enemy.path("max_hp"))
                    + " block=" + text(enemy.path("block"))
                    + " intent=" + genericSummary(enemy.path("intents"))
                    + " status=" + genericSummary(enemy.path("status")));
        }
        return String.join(" | ", rendered);
    }

    /**
     * 渲染当前地图节点。
     *
     * @param map 地图节点
     * @return 地图当前位置摘要
     */
    private String mapCurrent(JsonNode map) {
        if (!map.isObject() || map.isEmpty()) {
            return "";
        }
        return firstNonBlank(renderMapNode(map.path("current_position")), renderMapNode(map.path("current_node")));
    }

    /**
     * 渲染地图候选节点。
     *
     * @param options 候选节点数组
     * @return 候选节点摘要
     */
    private String mapOptions(JsonNode options) {
        if (!options.isArray() || options.isEmpty()) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            values.add(i + ":" + renderMapNode(options.get(i)));
        }
        return String.join(" | ", values);
    }

    /**
     * 渲染通用节点摘要。
     *
     * @param node 任意 JSON 节点
     * @return 紧凑摘要
     */
    private String genericSummary(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isEmpty()) {
            return "";
        }
        if (isScalar(node)) {
            return scalar(node);
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                values.add(item.isObject() ? inlineScalars(item) : scalar(item));
            }
            return String.join(" | ", values);
        }
        if (node.isObject()) {
            return inlineScalars(node);
        }
        return compact(node);
    }

    /**
     * 渲染地图坐标。
     *
     * @param node 地图节点
     * @return 坐标文本
     */
    private String coordinate(JsonNode node) {
        String col = text(node.path("col"));
        String row = text(node.path("row"));
        if (col.isBlank() || row.isBlank()) {
            return "";
        }
        return "(" + col + "," + row + ")";
    }

    /**
     * 渲染地图子节点坐标。
     *
     * @param children 子节点数组
     * @return 坐标摘要
     */
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
                values.add(scalar(child));
            }
        }
        values.removeIf(String::isBlank);
        return String.join(",", values);
    }

    /**
     * 判断节点是否能直接按标量渲染。
     *
     * @param node JSON 节点
     * @return true 表示可直接渲染
     */
    private boolean isScalar(JsonNode node) {
        return node == null
                || node.isMissingNode()
                || node.isNull()
                || node.isValueNode();
    }

    /**
     * 判断标量是否值得输出。
     *
     * @param node JSON 节点
     * @return true 表示可输出
     */
    private boolean shouldRenderScalar(JsonNode node) {
        return node != null
                && !node.isMissingNode()
                && !node.isNull()
                && (!node.isTextual() || !node.asText("").isBlank());
    }

    /**
     * 将标量节点转成文本。
     *
     * @param node JSON 节点
     * @return 标量文本
     */
    private String scalar(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        return node.asText(node.toString());
    }

    /**
     * 读取节点文本。
     *
     * @param node JSON 节点
     * @return 文本
     */
    private String text(JsonNode node) {
        return scalar(node);
    }

    /**
     * 输出 JSON 紧凑文本。
     *
     * @param node JSON 节点
     * @return JSON 文本
     */
    private String compact(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.toString();
    }

    /**
     * 返回首个非空字符串。
     *
     * @param values 候选文本
     * @return 非空文本或空串
     */
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
}
