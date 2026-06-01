package p1.component.agent.gamer.adapter.core;

/**
 * 单条操作执行前的语义前置条件。
 * <p>
 * 前置条件用于阻止已经失效的具体操作落地，例如目标消失、计划卡牌不在手牌、
 * 或计划选项已不在当前界面。它不替代 MCP 的最终合法性判断。
 */
public record GameOperationPrecondition(
        boolean satisfied,
        String signature,
        String reason
) {

    public static GameOperationPrecondition passed() {
        return passed("unchecked");
    }

    public static GameOperationPrecondition passed(String signature) {
        return new GameOperationPrecondition(true, normalize(signature, "unchecked"), "");
    }

    public static GameOperationPrecondition failed(String signature, String reason) {
        return new GameOperationPrecondition(false, normalize(signature, "unknown"), normalize(reason, "前置条件不满足"));
    }

    public String compact() {
        return signature == null || signature.isBlank() ? "unchecked" : signature.trim();
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
