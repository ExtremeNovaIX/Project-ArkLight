package p1.component.agent.task.context;

import p1.component.agent.task.state.TaskBlackboard;
import p1.component.agent.task.state.TaskSupervisorBlackboardEntry;

import java.util.List;

public final class TaskBlackboardRenderer {

    /**
     * 渲染任务黑板的只读视图。
     * 包含所有证据和重用次数。
     */
    public String renderReadonlyView(TaskBlackboard blackboard) {
        List<TaskSupervisorBlackboardEntry> entries = blackboard.snapshotEntries();
        if (entries.isEmpty()) {
            return "No evidence collected yet.";
        }

        StringBuilder builder = new StringBuilder();
        for (TaskSupervisorBlackboardEntry entry : entries) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(renderPromptEntry(entry));
        }
        appendReuseNote(builder, blackboard.reusedEvidenceCount());
        return builder.toString();
    }

    /**
     * 渲染任务黑板的证据视图。
     * 这里只会显示存在evidenceIds对应证据的记录。
     */
    public String renderEvidenceView(TaskBlackboard blackboard, List<String> evidenceIds) {
        List<TaskSupervisorBlackboardEntry> matched = blackboard.findEntries(evidenceIds);
        if (matched.isEmpty()) {
            return "No candidate evidence selected.";
        }

        StringBuilder builder = new StringBuilder();
        for (TaskSupervisorBlackboardEntry entry : matched) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(renderPromptEntry(entry));
        }
        return builder.toString();
    }

    public String renderEvidenceIndexView(TaskBlackboard blackboard) {
        List<TaskSupervisorBlackboardEntry> entries = blackboard.snapshotEntries();
        if (entries.isEmpty()) {
            return "No evidence ids available.";
        }

        StringBuilder builder = new StringBuilder();
        for (TaskSupervisorBlackboardEntry entry : entries) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append(renderDigestEntry(entry));
        }
        appendReuseNote(builder, blackboard.reusedEvidenceCount());
        return builder.toString();
    }

    public String renderToolResultEntry(TaskSupervisorBlackboardEntry entry) {
        return """
                evidenceId: %s
                sourceType: %s
                sourceName: %s
                status: %s
                query: %s
                summary:
                %s
                """.formatted(
                entry.evidenceId(),
                entry.sourceType(),
                entry.sourceName(),
                entry.status(),
                compactRequest(entry.request()),
                entry.promptSummary()
        );
    }

    private String renderPromptEntry(TaskSupervisorBlackboardEntry entry) {
        return """
                [evidenceId=%s]
                tool=%s.%s
                status=%s
                query=%s
                summary=
                %s
                """.formatted(
                entry.evidenceId(),
                entry.sourceType(),
                entry.sourceName(),
                entry.status(),
                compactRequest(entry.request()),
                entry.promptSummary()
        );
    }

    private String renderDigestEntry(TaskSupervisorBlackboardEntry entry) {
        return "[%s] %s.%s status=%s query=%s summary=%s".formatted(
                entry.evidenceId(),
                entry.sourceType(),
                entry.sourceName(),
                entry.status(),
                compactRequest(entry.request()),
                compactSummary(entry.promptSummary())
        );
    }

    private void appendReuseNote(StringBuilder builder, int reusedEvidenceCount) {
        if (reusedEvidenceCount <= 0) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n");
        }
        builder.append("(reusedExistingEvidenceCount=").append(reusedEvidenceCount).append(")");
    }

    private String compactRequest(String request) {
        return clip(normalizeWhitespace(request), 160);
    }

    private String compactSummary(String promptSummary) {
        return clip(normalizeWhitespace(promptSummary), 220);
    }

    private String normalizeWhitespace(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    private String clip(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
