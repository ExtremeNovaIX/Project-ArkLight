package p1.component.agent.gamer.adapter.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * agent 提交的一条游戏操作。
 *
 * @param toolName 底层 MCP 工具名
 * @param args     透传给 MCP 工具的参数
 */
public record GameOperation(
        String toolName,
        JsonNode args
) {
}
