package p1.component.agent.router;

import java.util.List;

/**
 * 通用指令路由请求。
 * <p>
 * 路由器不理解具体业务模块，只根据调用方动态挂载的场景提示词、运行时上下文和用户原文，
 * 把输入归类到调用方声明的有限意图集合中。
 * <p>
 * 调用方可以传入 {@code taskId} 让路由器从 {@link InstructionRouterTaskRegistry} 查找任务定义，
 * 也可以直接传入 {@code sceneInstruction} 和 {@code allowedIntents}（兼容旧调用方式）。
 *
 * @param sceneId          场景 id，例如 rp-game-control
 * @param taskId           路由任务 id；非空时 router 从 registry 查找 task，忽略 sceneInstruction/allowedIntents
 * @param sceneInstruction 场景专用路由规则（taskId 为空时生效）
 * @param runtimeContext   本轮动态上下文
 * @param userMessage      用户原文
 * @param allowedIntents   当前场景允许输出的意图（taskId 为空时生效）
 */
public record InstructionRouteRequest(
        String sceneId,
        String taskId,
        String sceneInstruction,
        String runtimeContext,
        String userMessage,
        List<String> allowedIntents
) {

    /**
     * 通过 taskId 构造请求，由 router 查找 task 组装 prompt。
     */
    public static InstructionRouteRequest byTask(String sceneId, String taskId,
                                                  String runtimeContext, String userMessage) {
        return new InstructionRouteRequest(sceneId, taskId, null, runtimeContext, userMessage, null);
    }

    /**
     * 直接传入 prompt 构造请求（兼容旧调用方）。
     */
    public static InstructionRouteRequest byPrompt(String sceneId, String sceneInstruction,
                                                    String runtimeContext, String userMessage,
                                                    List<String> allowedIntents) {
        return new InstructionRouteRequest(sceneId, null, sceneInstruction, runtimeContext, userMessage, allowedIntents);
    }
}
