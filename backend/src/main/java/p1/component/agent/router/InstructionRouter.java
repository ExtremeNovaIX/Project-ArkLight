package p1.component.agent.router;

/**
 * 通用短指令路由器。
 * <p>
 * 该接口用于把用户短输入路由到业务场景声明的有限意图集合。具体实现可以是本地小模型、
 * 远程模型、规则引擎或测试替身。
 */
public interface InstructionRouter {

    /**
     * 对用户输入进行意图路由。
     *
     * @param request 路由请求
     * @return 路由结果；不可用时返回 {@link InstructionRouteDecision#unavailable(String)}
     */
    InstructionRouteDecision route(InstructionRouteRequest request);
}
