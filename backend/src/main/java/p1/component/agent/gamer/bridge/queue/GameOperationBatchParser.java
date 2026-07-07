package p1.component.agent.gamer.bridge.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.CustomLog;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.adapter.core.GameBridgeException;
import p1.component.agent.gamer.adapter.core.GameOperation;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 RP 动作 parser 产出的操作队列 JSON。
 */
@Component
@CustomLog
public class GameOperationBatchParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 将原始 JSON 参数解析为内部操作批次。
     */
    public ParsedGameOperationBatch parse(String rawArguments) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawArguments == null || rawArguments.isBlank() ? "{}" : rawArguments);
        } catch (Exception e) {
            throw new GameBridgeException("enqueue_operations 参数不是合法 JSON: " + preview(rawArguments), e);
        }

        boolean ignoreRpSpeaking = root.path("_ignore_rp_speaking").asBoolean(false);
        String rpDo = root.path("_rp_do").asText("");
        String parserRaw = root.path("_parser_raw").asText("");
        long parserLatencyMs = root.path("_parser_latency_ms").asLong(0);
        boolean parserEarlyCompleted = root.path("_parser_early_completed").asBoolean(false);
        boolean parserEarlyCancelled = root.path("_parser_early_cancelled").asBoolean(false);
        List<GameOperation> operations = parseOperations(root.path("operations"));
        return new ParsedGameOperationBatch(
                operations,
                ignoreRpSpeaking,
                rpDo,
                parserRaw,
                parserLatencyMs,
                parserEarlyCompleted,
                parserEarlyCancelled);
    }

    private List<GameOperation> parseOperations(JsonNode node) {
        List<GameOperation> operations = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return operations;
        }
        JsonNode array = node;
        if (node.isTextual()) {
            try {
                array = objectMapper.readTree(node.asText());
            } catch (Exception e) {
                throw new GameBridgeException("operations 不是合法 JSON 数组: " + node.asText(), e);
            }
        }
        if (!array.isArray()) {
            throw new GameBridgeException("operations 必须是数组");
        }
        for (JsonNode item : array) {
            String tool = item.path("tool").asText(item.path("toolName").asText(""));
            if (tool.isBlank()) {
                throw new GameBridgeException("operation 缺少 tool 字段: " + item);
            }
            JsonNode args = item.has("args") ? item.path("args") : objectMapper.createObjectNode();
            operations.add(new GameOperation(tool, args));
        }
        return operations;
    }

    private String preview(String value) {
        if (value == null) {
            return "null";
        }
        return value.substring(0, Math.min(value.length(), 500));
    }
}
