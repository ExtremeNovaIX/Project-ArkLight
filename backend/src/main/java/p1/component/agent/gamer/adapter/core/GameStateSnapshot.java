package p1.component.agent.gamer.adapter.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 游戏状态快照。
 *
 * @param rawJson    MCP 返回的原始 JSON 字符串
 * @param json       解析后的 JSON 树
 * @param stateType  游戏大状态类型，用于监视场景级变化
 */
public record GameStateSnapshot(
        String rawJson,
        JsonNode json,
        String stateType
) {
}
