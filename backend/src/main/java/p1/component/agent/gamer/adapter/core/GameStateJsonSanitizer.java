package p1.component.agent.gamer.adapter.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * 清理不适合暴露给 LLM 的游戏状态字段。
 */
public final class GameStateJsonSanitizer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CAN_PROCEED = "can_proceed";

    private GameStateJsonSanitizer() {
    }

    public static String sanitizeToString(GameStateSnapshot state) {
        if (state == null) {
            return "{}";
        }
        String rawJson = state.rawJson();
        if (rawJson != null && !rawJson.isBlank()) {
            return sanitizeRawJson(rawJson);
        }
        return sanitizeNodeToString(state.json());
    }

    public static String sanitizeRawJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return "{}";
        }
        try {
            return sanitizeNodeToString(OBJECT_MAPPER.readTree(rawJson));
        } catch (Exception ignored) {
            return rawJson.trim();
        }
    }

    public static String sanitizeNodeToString(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "{}";
        }
        return sanitizeNode(node).toPrettyString();
    }

    private static JsonNode sanitizeNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return NullNode.getInstance();
        }
        if (node.isObject()) {
            ObjectNode copy = OBJECT_MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (CAN_PROCEED.equals(field.getKey())) {
                    continue;
                }
                copy.set(field.getKey(), sanitizeNode(field.getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = OBJECT_MAPPER.createArrayNode();
            for (JsonNode item : node) {
                copy.add(sanitizeNode(item));
            }
            return copy;
        }
        return node.deepCopy();
    }
}
