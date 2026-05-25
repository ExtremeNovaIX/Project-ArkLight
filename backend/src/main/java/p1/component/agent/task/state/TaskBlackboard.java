package p1.component.agent.task.state;

import org.springframework.util.StringUtils;

import java.util.*;

public final class TaskBlackboard {

    private static final String DEDUPE_SEPARATOR = Character.toString((char) 1);

    private final List<TaskSupervisorBlackboardEntry> entries = new ArrayList<>();
    private final Map<String, TaskSupervisorBlackboardEntry> dedupeIndex = new LinkedHashMap<>();
    private final Map<String, Integer> sequenceByPrefix = new LinkedHashMap<>();
    private int reusedEvidenceCount;

    public synchronized TaskSupervisorBlackboardAppendResult appendMethodSuccess(String methodName,
                                                                                 String request,
                                                                                 String promptSummary,
                                                                                 String response) {
        return append("method", methodName, "success", request, promptSummary, response);
    }

    public synchronized TaskSupervisorBlackboardAppendResult appendMethodError(String methodName,
                                                                               String request,
                                                                               String promptSummary,
                                                                               String response) {
        return append("method", methodName, "error", request, promptSummary, response);
    }

    public synchronized List<String> filterExistingIds(List<String> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return List.of();
        }

        Set<String> existingIds = new LinkedHashSet<>();
        for (TaskSupervisorBlackboardEntry entry : entries) {
            existingIds.add(entry.evidenceId());
        }

        List<String> approvedIds = new ArrayList<>();
        for (String requestedId : requestedIds) {
            String candidate = trimToEmpty(requestedId);
            if (!candidate.isBlank() && existingIds.contains(candidate) && !approvedIds.contains(candidate)) {
                approvedIds.add(candidate);
            }
        }
        return List.copyOf(approvedIds);
    }

    public synchronized List<TaskSupervisorBlackboardEntry> findEntries(List<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return List.of();
        }

        Map<String, TaskSupervisorBlackboardEntry> byId = new LinkedHashMap<>();
        for (TaskSupervisorBlackboardEntry entry : entries) {
            byId.put(entry.evidenceId(), entry);
        }

        List<TaskSupervisorBlackboardEntry> matched = new ArrayList<>();
        for (String evidenceId : evidenceIds) {
            TaskSupervisorBlackboardEntry entry = byId.get(trimToEmpty(evidenceId));
            if (entry != null) {
                matched.add(entry);
            }
        }
        return List.copyOf(matched);
    }

    public synchronized List<TaskSupervisorBlackboardEntry> snapshotEntries() {
        return List.copyOf(entries);
    }

    public synchronized List<String> snapshotEvidenceIds() {
        if (entries.isEmpty()) {
            return List.of();
        }

        List<String> evidenceIds = new ArrayList<>(entries.size());
        for (TaskSupervisorBlackboardEntry entry : entries) {
            evidenceIds.add(entry.evidenceId());
        }
        return List.copyOf(evidenceIds);
    }

    public synchronized TaskSupervisorBlackboardEntry removeEvidence(String evidenceId) {
        String normalizedEvidenceId = trimToEmpty(evidenceId);
        if (normalizedEvidenceId.isBlank()) {
            return null;
        }

        for (int index = 0; index < entries.size(); index++) {
            TaskSupervisorBlackboardEntry entry = entries.get(index);
            if (!normalizedEvidenceId.equals(entry.evidenceId())) {
                continue;
            }

            entries.remove(index);
            dedupeIndex.remove(buildDedupeKey(
                    entry.sourceType(),
                    entry.sourceName(),
                    entry.status(),
                    entry.request(),
                    entry.promptSummary()
            ));
            return entry;
        }
        return null;
    }

    public synchronized int reusedEvidenceCount() {
        return reusedEvidenceCount;
    }

    private TaskSupervisorBlackboardAppendResult append(String sourceType,
                                                        String sourceName,
                                                        String status,
                                                        String request,
                                                        String promptSummary,
                                                        String response) {
        String normalizedRequest = trimToEmpty(request);
        String normalizedPromptSummary = trimToEmpty(promptSummary);
        String dedupeKey = buildDedupeKey(sourceType, sourceName, status, normalizedRequest, normalizedPromptSummary);
        TaskSupervisorBlackboardEntry existingEntry = dedupeIndex.get(dedupeKey);
        if (existingEntry != null) {
            reusedEvidenceCount++;
            return new TaskSupervisorBlackboardAppendResult(existingEntry, false);
        }

        String evidenceId = nextEvidenceId(sourceType, sourceName);
        TaskSupervisorBlackboardEntry entry = new TaskSupervisorBlackboardEntry(
                evidenceId,
                sourceType,
                sourceName,
                status,
                normalizedRequest,
                normalizedPromptSummary,
                trimToEmpty(response)
        );
        entries.add(entry);
        dedupeIndex.put(dedupeKey, entry);
        return new TaskSupervisorBlackboardAppendResult(entry, true);
    }

    private String nextEvidenceId(String sourceType, String sourceName) {
        String prefix = sourceType + "." + sanitizeSegment(sourceName);
        int next = sequenceByPrefix.merge(prefix, 1, Integer::sum);
        return prefix + "#" + String.format(Locale.ROOT, "%02d", next);
    }

    private String sanitizeSegment(String rawValue) {
        String trimmed = trimToEmpty(rawValue);
        if (trimmed.isBlank()) {
            return "unknown";
        }

        String sanitized = trimmed.replaceAll("[^A-Za-z0-9]+", "_");
        return StringUtils.hasText(sanitized) ? sanitized : "unknown";
    }

    private String buildDedupeKey(String sourceType,
                                  String sourceName,
                                  String status,
                                  String request,
                                  String promptSummary) {
        return String.join(DEDUPE_SEPARATOR,
                trimToEmpty(sourceType),
                trimToEmpty(sourceName),
                trimToEmpty(status),
                normalizeForKey(request),
                normalizeForKey(promptSummary)
        );
    }

    private String normalizeForKey(String value) {
        return trimToEmpty(value).replaceAll("\\s+", " ");
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
