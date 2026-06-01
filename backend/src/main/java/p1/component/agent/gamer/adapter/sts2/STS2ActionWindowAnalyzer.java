package p1.component.agent.gamer.adapter.sts2;

import com.fasterxml.jackson.databind.JsonNode;
import p1.component.agent.gamer.adapter.core.GameActionWindowSignature;
import p1.component.agent.gamer.adapter.core.GameStateSnapshot;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 计算 STS2 的本地行动窗口。
 * <p>
 * 窗口签名只包含会改变本地操作语义的阶段字段，敌人血量、日志、
 * 动画帧等状态变化只进入 trace，不参与执行门禁。
 */
public class STS2ActionWindowAnalyzer {

    public GameActionWindowSignature signature(GameStateSnapshot state, String modePrefix) {
        JsonNode root = state == null ? null : state.json();
        if (root == null || root.isMissingNode() || root.isNull()) {
            return GameActionWindowSignature.unchecked();
        }

        String stateType = firstNonBlank(state.stateType(), root.path("state_type").asText(""));
        String normalizedType = stateType.toLowerCase(Locale.ROOT);
        if ("hand_select".equals(normalizedType)) {
            Map<String, Object> fields = baseFields(root, modePrefix);
            put(fields, "mode", root.path("hand_select").path("mode").asText(""));
            put(fields, "prompt", root.path("hand_select").path("prompt").asText(""));
            return GameActionWindowSignature.of("hand_select", fields);
        }

        if ("card_select".equals(normalizedType)) {
            Map<String, Object> fields = baseFields(root, modePrefix);
            JsonNode cardSelect = root.path("card_select");
            put(fields, "screen_type", cardSelect.path("screen_type").asText(""));
            put(fields, "type", cardSelect.path("type").asText(""));
            put(fields, "prompt", cardSelect.path("prompt").asText(""));
            return GameActionWindowSignature.of("card_select", fields);
        }

        JsonNode battle = root.path("battle");
        if (battle.isObject() && !battle.isEmpty()) {
            Map<String, Object> fields = baseFields(root, modePrefix);
            put(fields, "battle.round", battle.path("round").asText(""));
            put(fields, "battle.turn", battle.path("turn").asText(""));
            put(fields, "battle.is_play_phase", battle.path("is_play_phase").asText(""));
            return GameActionWindowSignature.of("combat", fields);
        }

        Map<String, Object> fields = baseFields(root, modePrefix);
        String category = switch (normalizedType) {
            case "event", "card_reward", "rewards", "map", "shop", "rest", "campfire", "menu", "game_over" ->
                    normalizedType;
            case "" -> "unknown";
            default -> normalizedType;
        };
        return GameActionWindowSignature.of(category, fields);
    }

    private Map<String, Object> baseFields(JsonNode root, String modePrefix) {
        Map<String, Object> fields = new LinkedHashMap<>();
        String gameMode = root.path("game_mode").asText("");
        if (gameMode.isBlank() && STS2OperationMetadata.MP_PREFIX.equals(modePrefix)) {
            gameMode = "multiplayer";
        }
        put(fields, "game_mode", gameMode);
        return fields;
    }

    private void put(Map<String, Object> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, value.trim());
        }
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
}
