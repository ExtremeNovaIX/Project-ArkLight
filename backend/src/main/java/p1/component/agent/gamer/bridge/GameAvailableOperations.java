package p1.component.agent.gamer.bridge;

import java.util.Set;

/**
 * 当前状态下可供 RP/parser 使用的操作工具视图。
 *
 * @param detailed  给 parser 的详细工具说明
 * @param summary   给 RP 的扁平工具名摘要
 * @param toolNames 当前可用工具名白名单
 * @param currentStateJson 当前游戏状态原始 JSON，给 parser 辅助翻译 RP 指令
 */
public record GameAvailableOperations(
        String detailed,
        String summary,
        Set<String> toolNames,
        String currentStateJson
) {
    public GameAvailableOperations(String detailed, String summary, Set<String> toolNames) {
        this(detailed, summary, toolNames, "");
    }
}
