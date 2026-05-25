package p1.component.agent.task.context;

import p1.component.agent.task.state.TaskSupervisorBlackboardEntry;

import java.util.List;

public final class TaskExecutionResultRenderer {

    public String renderApprovedResponse(List<TaskSupervisorBlackboardEntry> visibleEntries) {
        StringBuilder builder = new StringBuilder();
        if (!visibleEntries.isEmpty()) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append("以下信息在放行时仍保留在黑板中，可供参考：\n");
            for (TaskSupervisorBlackboardEntry entry : visibleEntries) {
                builder.append("[")
                        .append(entry.evidenceId())
                        .append("] ")
                        .append(entry.response())
                        .append("\n");
            }
        }

        if (builder.isEmpty()) {
            return "后端监督已完成，但黑板中没有可放行的信息。";
        }
        return builder.toString().trim();
    }
}
