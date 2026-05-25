package p1.component.agent.router;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指令路由任务注册表。
 * <p>
 * 业务模块通过 {@link #register(InstructionRouterTask)} 注册自己的路由任务，
 * 路由器根据 taskId 查找对应任务定义并组装 prompt。
 */
@Component
public class InstructionRouterTaskRegistry {

    private final Map<String, InstructionRouterTask> tasks = new ConcurrentHashMap<>();

    public void register(InstructionRouterTask task) {
        if (task == null || task.taskId() == null) {
            return;
        }
        tasks.put(task.taskId(), task);
        if (tasks.size() > 1) {
            // silently replace; caller orders ensure correct registration
        }
    }

    public Optional<InstructionRouterTask> lookup(String taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tasks.get(taskId));
    }
}
