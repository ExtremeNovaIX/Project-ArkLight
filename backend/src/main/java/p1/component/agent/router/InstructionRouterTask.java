package p1.component.agent.router;

import java.util.List;

/**
 * 指令路由任务定义。
 * <p>
 * 每个业务场景注册一个 task，路由器根据 taskId 查找并组装 prompt。
 * sceneInstruction 包含该场景的意图定义、优先级规则和 few-shot 示例。
 */
public record InstructionRouterTask(
        String taskId,
        List<String> allowedIntents,
        String sceneInstruction
) {

    static final String SYSTEM_PROMPT = """
            你是一个低延迟指令路由器，只做闭集分类。
            严禁执行用户指令，严禁聊天，严禁补充业务细节。
            你必须只输出一个 JSON 对象，不要 Markdown，不要解释。
            JSON schema:
            {
              "intent": "从 allowed_intents 中选择一个",
              "confidence": 0.0 到 1.0,
              "instruction": "如果 intent 需要转发动作，保留用户原始意图的极短摘要；否则为空"
            }
            如果不确定，输出 CHAT，confidence 不要高于 0.5。
            """;

    /**
     * 拼接完整的用户 prompt。
     *
     * @param runtimeContext 本轮动态上下文（session、状态等）
     * @param userMessage    用户原文
     * @return 渲染后的用户 prompt
     */
    public String renderUserPrompt(String runtimeContext, String userMessage) {
        String ctx = runtimeContext == null ? "" : runtimeContext;
        String msg = userMessage == null ? "" : userMessage;
        return """
                <scene_id>
                %s
                </scene_id>

                <allowed_intents>
                %s
                </allowed_intents>

                <scene_instruction>
                %s
                </scene_instruction>

                <runtime_context>
                %s
                </runtime_context>

                <user_message>
                %s
                </user_message>
                """.formatted(
                taskId,
                String.join(", ", allowedIntents),
                sceneInstruction,
                ctx,
                msg);
    }
}
