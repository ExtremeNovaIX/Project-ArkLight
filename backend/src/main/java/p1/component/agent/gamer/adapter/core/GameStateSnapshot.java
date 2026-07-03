package p1.component.agent.gamer.adapter.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 游戏状态快照。
 *
 * @param rawJson     MCP 返回的原始 JSON 字符串
 * @param json        解析后的 JSON 树
 * @param stateType   适配器提取的状态类型提示；没有对应字段时为空字符串
 * @param rawMarkdown MCP 返回的原生可读 Markdown；为空时由适配器自行渲染
 */
public record GameStateSnapshot(
        String rawJson,
        JsonNode json,
        String stateType,
        String rawMarkdown
) {
    public GameStateSnapshot(String rawJson, JsonNode json, String stateType) {
        this(rawJson, json, stateType, "");
    }
}
