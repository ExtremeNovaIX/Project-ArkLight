package p1.component.agent.rp;

import dev.langchain4j.agent.tool.Tool;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import p1.component.agent.task.state.TaskExecuteResult;
import p1.component.agent.task.supervisor.TaskSupervisorAgent;

@Component
@RequiredArgsConstructor
@Slf4j
public class CallSolverTool {

    private final TaskSupervisorAgent taskSupervisorAgent;

    @Tool(name = "solverTool", value = {"""
            当用户提及当前上下文(rolePrompt或summary)中未包含的事件、人物、设定或历史细节时，【必须调用】此工具获取客观事实。
            这是你获取设定和长期记忆的唯一途径。
            
            输入要求：
            1. 必须是客观的名词或陈述（如：“查询角色X的背景”或“了解Y事件的过程”）。
            2. 绝对禁止在输入中包含你的推测、幻觉或假设。
            工具运行结果不保证100%正确，你需要根据结果自行判断。
            """})
    public String solverTool(@NonNull String rawRequest) {
        if (!StringUtils.hasText(rawRequest)) {
            return "后端助手请求不能为空。";
        }
        String request = rawRequest.trim();
        log.info("[TaskSupervisorTool] 请求转发给后端助手，请求内容={}", request);

        TaskExecuteResult result = taskSupervisorAgent.handle(request);

        log.info("[TaskSupervisorTool] 后端助手执行结束，status={}，reasonCode={}，taskRunId={}",
                result.status(), result.reasonCode(), result.taskRunId());

        if (result.status() == TaskExecuteResult.Status.TIMEOUT) {
            return "工具调用超时";
        }
        return StringUtils.hasText(result.responseText()) ? result.responseText() : "后端助手未能完成该任务。";
    }
}
