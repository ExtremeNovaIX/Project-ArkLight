package p1.component.agent.streaming;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 从模型流式输出中截取到的一段完整 JSON 指令。
 *
 * @param rawJson 原始 JSON 文本
 * @param json    Jackson 解析后的 JSON 对象
 */
public record StreamingJsonInstruction(String rawJson, JsonNode json) {
}
