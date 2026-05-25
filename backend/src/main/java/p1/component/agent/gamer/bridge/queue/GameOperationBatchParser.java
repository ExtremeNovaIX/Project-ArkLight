package p1.component.agent.gamer.bridge.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import p1.component.agent.gamer.adapter.core.GameBridgeException;
import p1.component.agent.gamer.adapter.core.GameOperation;
import p1.component.agent.gamer.bridge.GameBridgeActionStatus;
import p1.component.agent.gamer.bridge.GameExpressionPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * gamer提交的 enqueue_operations 参数解析器。
 * <p>
 * 该组件只负责把gamer提交的 JSON 操作队列变成内部结构，并对 summary 做兼容兜底。
 */
@Component
@Slf4j
public class GameOperationBatchParser {

    private static final int FALLBACK_SUMMARY_OPERATION_LIMIT = 4;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析 enqueue_operations 的原始参数。
     *
     * @param rawArguments enqueue_operations 参数 JSON
     * @return 结构化操作批次
     */
    public ParsedGameOperationBatch parse(String rawArguments) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawArguments == null || rawArguments.isBlank() ? "{}" : rawArguments);
        } catch (Exception e) {
            throw new GameBridgeException("enqueue_operations 参数不是合法 JSON: " + preview(rawArguments), e);
        }

        GameBridgeActionStatus status = GameBridgeActionStatus.from(root.path("status").asText("CONTINUE"));
        boolean expectMoreOperations = root.path("_expect_more_operations").asBoolean(false);
        List<GameOperation> operations = parseOperations(root.path("operations"));
        String summary = normalizeSummary(root, status, operations);
        GameExpressionPayload expression = parseExpression(root.path("expression"));
        return new ParsedGameOperationBatch(status, summary, operations, expectMoreOperations, expression);
    }

    /**
     * 将 operations 字段解析成内部操作列表。
     *
     * @param node operations 字段，可以是数组，也可以是字符串形式的 JSON 数组
     * @return 按模型声明顺序排列的操作列表
     */
    private List<GameOperation> parseOperations(JsonNode node) {
        List<GameOperation> operations = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return operations;
        }
        JsonNode array = node;
        if (node.isTextual()) {
            try {
                // 兼容部分模型把 operations 当成字符串输出的情况。
                array = objectMapper.readTree(node.asText());
            } catch (Exception e) {
                throw new GameBridgeException("operations 不是合法 JSON 数组: " + node.asText(), e);
            }
        }
        if (!array.isArray()) {
            throw new GameBridgeException("operations 必须是数组");
        }
        for (JsonNode item : array) {
            // toolName 是兼容字段，正常情况下模型应该使用 tool。
            String tool = item.path("tool").asText(item.path("toolName").asText(""));
            if (tool.isBlank()) {
                throw new GameBridgeException("operation 缺少 tool 字段: " + item);
            }
            JsonNode args = item.has("args") ? item.path("args") : objectMapper.createObjectNode();
            operations.add(new GameOperation(tool, args, normalizeText(item.path("note").asText(""))));
        }
        return operations;
    }

    /**
     * 解析 ACTION 中的表达欲字段。
     * <p>
     * 表达欲不是执行游戏操作的必要字段，因此缺失或类型不匹配时只退化为空候选，
     * 避免表达系统影响真实游戏推进。
     *
     * @param node expression 字段
     * @return 结构化表达欲候选
     */
    private GameExpressionPayload parseExpression(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return GameExpressionPayload.empty();
        }
        return new GameExpressionPayload(
                clampScore(node.path("score").asInt(0)),
                normalizeText(text(node.path("type"))),
                normalizeText(text(node.path("urgency"))),
                normalizeText(firstNonBlank(text(node.path("inner_thought")), text(node.path("innerThought")))),
                normalizeText(text(node.path("reason")))
        );
    }

    /**
     * 将模型自评表达欲限制在 0-100。
     *
     * @param score 原始分数
     * @return 合法分数
     */
    private int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    /**
     * 解析并规范化本批操作摘要。
     *
     * @param root       enqueue_operations 的根 JSON
     * @param status     本批队列状态
     * @param operations 已解析的操作队列
     * @return 规范化后的摘要
     */
    private String normalizeSummary(JsonNode root, GameBridgeActionStatus status, List<GameOperation> operations) {
        String explicitSummary = firstNonBlank(
                text(root.path("summary")),
                text(root.path("decision_summary")));
        if (!explicitSummary.isBlank()) {
            return normalizeText(explicitSummary);
        }

        String fallback = buildFallbackSummary(status, operations);
        log.warn("[游戏桥接] enqueue_operations 缺少 summary，已自动生成摘要: {}", fallback);
        return fallback;
    }

    /**
     * 根据操作队列生成摘要兜底文本。
     *
     * @param status     本批队列状态
     * @param operations 已解析的操作队列
     * @return 可写入记忆和复盘日志的简短摘要
     */
    private String buildFallbackSummary(GameBridgeActionStatus status, List<GameOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            return status == GameBridgeActionStatus.WAIT
                    ? "当前无可执行操作，等待下一次状态刷新。"
                    : "未提交具体操作，等待桥接层返回最新状态。";
        }

        StringBuilder sb = new StringBuilder("执行：");
        int count = 0;
        for (GameOperation operation : operations) {
            if (count > 0) {
                sb.append("；");
            }
            sb.append(operationBrief(operation));
            count++;
            if (count >= FALLBACK_SUMMARY_OPERATION_LIMIT) {
                break;
            }
        }
        if (operations.size() > FALLBACK_SUMMARY_OPERATION_LIMIT) {
            sb.append("；等").append(operations.size()).append("步");
        }
        return sb.toString();
    }

    /**
     * 渲染单条操作的兜底摘要片段。
     *
     * @param operation 操作对象
     * @return 优先使用 note；没有 note 时退化为工具名
     */
    private String operationBrief(GameOperation operation) {
        if (operation == null) {
            return "未知操作";
        }
        String note = normalizeText(operation.note());
        if (!note.isBlank()) {
            return note;
        }
        return operation.toolName() == null ? "未知操作" : operation.toolName();
    }

    /**
     * 提取 JSON 节点文本。
     *
     * @param node JSON 节点
     * @return 缺失时返回空字符串
     */
    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.asText("");
    }

    /**
     * 返回第一段非空文本。
     *
     * @param values 候选文本
     * @return 非空文本；都为空时返回空字符串
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = normalizeText(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    /**
     * 合并多余空白，避免模型输出换行或 JSON 代码块污染摘要。
     *
     * @param value 原始文本
     * @return 单行文本
     */
    static String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    /**
     * 截断过长参数，避免错误信息污染日志。
     *
     * @param value 原始文本
     * @return 简短预览
     */
    private String preview(String value) {
        if (value == null) {
            return "null";
        }
        return value.substring(0, Math.min(value.length(), 500));
    }
}
