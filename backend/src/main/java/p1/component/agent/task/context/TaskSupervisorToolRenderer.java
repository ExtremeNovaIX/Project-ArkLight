package p1.component.agent.task.context;

import p1.component.agent.task.state.TaskSupervisorBlackboardAppendResult;
import p1.component.agent.task.state.TaskSupervisorBlackboardEntry;
import p1.component.agent.tools.MemorySearchTools;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TaskSupervisorToolRenderer {

    private final TaskBlackboardRenderer blackboardRenderer = new TaskBlackboardRenderer();

    public String renderToolResult(int round,
                                   int maxRounds,
                                   TaskSupervisorBlackboardAppendResult appendResult) {
        return """
                readOnlyBlackboardUpdated: true
                blackboardAction: %s
                toolRound: %d/%d
                %s
                """.formatted(
                appendResult.appendedNewEvidence() ? "appended" : "reused_existing",
                round,
                maxRounds,
                blackboardRenderer.renderToolResultEntry(appendResult.entry())
        );
    }

    public String renderRemovalResult(int round,
                                      int maxRounds,
                                      String evidenceId,
                                      TaskSupervisorBlackboardEntry removedEntry,
                                      int remainingEvidenceCount) {
        if (removedEntry == null) {
            return """
                    readOnlyBlackboardUpdated: false
                    blackboardAction: not_found
                    toolRound: %d/%d
                    evidenceId: %s
                    remainingEvidenceCount: %d
                    """.formatted(round, maxRounds, evidenceId, remainingEvidenceCount);
        }

        return """
                readOnlyBlackboardUpdated: true
                blackboardAction: removed
                toolRound: %d/%d
                remainingEvidenceCount: %d
                %s
                """.formatted(
                round,
                maxRounds,
                remainingEvidenceCount,
                blackboardRenderer.renderToolResultEntry(removedEntry)
        );
    }

    public String buildErrorSummary(RuntimeException exception) {
        return "status=ERROR\nmessage=" + renderError(exception);
    }

    public String summarizeMemorySearchResult(MemorySearchTools.MemorySearchResult result) {
        if (result == null) {
            return "status=ERROR\nmessage=memory search returned null";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("status=").append(normalizeText(result.status())).append("\n");
        builder.append("message=").append(normalizeText(result.message()));

        List<MemorySearchTools.MemorySearchBundle> bundles = safeBundles(result);
        if (!bundles.isEmpty()) {
            builder.append("\nhits:");
            int detailCount = Math.min(2, bundles.size());
            for (int index = 0; index < detailCount; index++) {
                appendBundleSummary(builder, bundles.get(index), index + 1);
            }
            if (bundles.size() > detailCount) {
                builder.append("\n- moreHits=").append(bundles.size() - detailCount);
            }
        }

        if (result.truncated()) {
            builder.append("\nnotes=graph_expansion_truncated");
        }
        return builder.toString();
    }

    private void appendBundleSummary(StringBuilder builder,
                                     MemorySearchTools.MemorySearchBundle bundle,
                                     int index) {
        builder.append("\n- hit#").append(index)
                .append(" seedArchiveId=").append(bundle.seedArchiveId() == null ? "n/a" : bundle.seedArchiveId())
                .append(" score=").append(String.format(Locale.ROOT, "%.2f", bundle.seedScore()));

        List<String> facts = summarizeFacts(bundle.groupContext());
        if (!facts.isEmpty()) {
            builder.append("\n  facts: ").append(String.join(" | ", facts));
        }

        List<String> relatedTopics = summarizeRelatedTopics(bundle.graphExpansion());
        if (!relatedTopics.isEmpty()) {
            builder.append("\n  relatedTopics: ").append(String.join(" | ", relatedTopics));
        }

        if (bundle.graphExpansionTruncated()) {
            builder.append("\n  note: graph_expansion_truncated");
        }
    }

    private List<MemorySearchTools.MemorySearchBundle> safeBundles(MemorySearchTools.MemorySearchResult result) {
        if (result == null || result.bundles() == null) {
            return List.of();
        }
        return result.bundles();
    }

    private List<String> summarizeFacts(List<MemorySearchTools.ArchiveNodeView> groupContext) {
        if (groupContext == null || groupContext.isEmpty()) {
            return List.of();
        }

        Set<String> facts = new LinkedHashSet<>();
        for (MemorySearchTools.ArchiveNodeView nodeView : groupContext) {
            if (nodeView == null) {
                continue;
            }

            String detail = firstNonBlank(nodeView.keywordSummary(), nodeView.narrative(), nodeView.topic());
            String topic = normalizeText(nodeView.topic());
            String fact = topic.isBlank()
                    ? abbreviate(detail, 110)
                    : abbreviate(topic + ": " + detail, 140);
            if (!fact.isBlank()) {
                facts.add(fact);
            }
            if (facts.size() >= 2) {
                break;
            }
        }
        return List.copyOf(facts);
    }

    private List<String> summarizeRelatedTopics(List<MemorySearchTools.GraphExpansionItem> graphExpansion) {
        if (graphExpansion == null || graphExpansion.isEmpty()) {
            return List.of();
        }

        Set<String> relatedTopics = new LinkedHashSet<>();
        for (MemorySearchTools.GraphExpansionItem item : graphExpansion) {
            if (item == null) {
                continue;
            }

            String topic = abbreviate(firstNonBlank(item.topic(), item.keywordSummary(), item.narrative()), 90);
            if (!topic.isBlank()) {
                relatedTopics.add(topic);
            }
            if (relatedTopics.size() >= 2) {
                break;
            }
        }
        return List.copyOf(relatedTopics);
    }

    private String renderError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return exception.getClass().getSimpleName() + ": " + message.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = normalizeText(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String abbreviate(String text, int maxLength) {
        String normalized = normalizeText(text);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }
}
