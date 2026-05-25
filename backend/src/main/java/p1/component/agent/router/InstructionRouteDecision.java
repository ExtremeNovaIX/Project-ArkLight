package p1.component.agent.router;

/**
 * 通用指令路由结果。
 * <p>
 * {@code available=false} 表示路由器没有参与决策，例如未启用、超时或本地 sidecar 不可用；
 * 调用方可以在这种情况下选择规则兜底。{@code available=true} 且 intent=CHAT 表示路由器明确判断
 * 本轮输入不应触发业务副作用。
 *
 * @param available   路由器是否成功参与本轮决策
 * @param intent      场景内意图名称
 * @param confidence  置信度，范围由路由模型给出，调用方负责阈值判断
 * @param instruction 需要转发给业务模块的用户意图摘要
 * @param reason      路由理由，主要用于日志和调试
 * @param rawResponse 原始模型响应，便于排查 JSON 解析问题
 */
public record InstructionRouteDecision(
        boolean available,
        String intent,
        double confidence,
        String instruction,
        String reason,
        String rawResponse
) {

    /**
     * 构造一个明确的普通对话结果。
     *
     * @param reason 路由理由
     * @return CHAT 决策
     */
    public static InstructionRouteDecision chat(String reason) {
        return new InstructionRouteDecision(true, "CHAT", 1.0, "", reason, "");
    }

    /**
     * 构造一个路由器不可用结果。
     *
     * @param reason 不可用原因
     * @return 不可用决策
     */
    public static InstructionRouteDecision unavailable(String reason) {
        return new InstructionRouteDecision(false, "UNAVAILABLE", 0.0, "", reason, "");
    }

    /**
     * 判断当前结果是否达到调用方设定的置信度阈值。
     *
     * @param threshold 最小置信度
     * @return true 表示可执行
     */
    public boolean confidentEnough(double threshold) {
        return available && confidence >= threshold;
    }

    /**
     * 返回规范化后的意图名称。
     *
     * @return 大写意图名称
     */
    public String normalizedIntent() {
        return intent == null ? "" : intent.trim().toUpperCase();
    }
}
