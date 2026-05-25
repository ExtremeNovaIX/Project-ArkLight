package p1.benchmark.halumem;

import org.springframework.util.StringUtils;
import p1.component.agent.memory.model.ExtractedMemoryEvent;
import p1.component.agent.tools.MemorySearchTools;
import p1.model.document.MemoryArchiveDocument;
import p1.service.markdown.MemoryArchiveStore;

import java.util.*;

final class HaluMemRenderSupport {

    private HaluMemRenderSupport() {
    }

    static List<HaluMemMemoryItem> fromExtractedEvents(List<ExtractedMemoryEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<HaluMemMemoryItem> items = new ArrayList<>();
        for (ExtractedMemoryEvent event : events) {
            if (event == null) {
                continue;
            }
            items.add(new HaluMemMemoryItem(
                    null,
                    trimToEmpty(event.getTopic()),
                    trimToEmpty(event.getKeywordSummary()),
                    trimToEmpty(event.getNarrative()),
                    List.of()
            ));
        }
        return List.copyOf(items);
    }

    static List<HaluMemMemoryItem> fromArchives(List<MemoryArchiveDocument> archives) {
        if (archives == null || archives.isEmpty()) {
            return List.of();
        }
        List<HaluMemMemoryItem> items = new ArrayList<>();
        for (MemoryArchiveDocument archive : archives) {
            if (archive == null) {
                continue;
            }
            items.add(new HaluMemMemoryItem(
                    archive.getId(),
                    trimToEmpty(archive.getTopic()),
                    trimToEmpty(archive.getKeywordSummary()),
                    trimToEmpty(archive.getNarrative()),
                    safeStrings(archive.getSourceRefs())
            ));
        }
        return List.copyOf(items);
    }

    static List<String> toComparableTexts(List<HaluMemMemoryItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (HaluMemMemoryItem item : items) {
            if (item == null) {
                continue;
            }
            String text = renderMemoryItem(item);
            if (!text.isBlank()) {
                texts.add(text);
            }
        }
        return List.copyOf(texts);
    }

    static String renderComparableList(List<String> items) {
        return renderComparableList(items, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    static String renderComparableList(List<String> items, int maxItems, int maxItemChars) {
        if (items == null || items.isEmpty()) {
            return "None";
        }
        StringBuilder builder = new StringBuilder();
        int limit = Math.max(0, Math.min(items.size(), maxItems));
        for (int index = 0; index < limit; index++) {
            String item = clip(trimToEmpty(items.get(index)), maxItemChars);
            if (item.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append(index + 1).append(". ").append(item);
        }
        if (items.size() > limit) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append("... and ").append(items.size() - limit).append(" more items");
        }
        return builder.isEmpty() ? "None" : builder.toString();
    }

    static List<HaluMemRetrievedDocument> toRetrievedDocuments(MemorySearchTools.MemorySearchResult result,
                                                               MemoryArchiveStore archiveStore) {
        if (result == null || result.bundles() == null || result.bundles().isEmpty()) {
            return List.of();
        }
        List<HaluMemRetrievedDocument> documents = new ArrayList<>();
        int documentIndex = 1;
        for (MemorySearchTools.MemorySearchBundle bundle : result.bundles()) {
            if (bundle == null) {
                continue;
            }
            List<Long> groupContextArchiveIds = safeGroupContext(bundle).stream()
                    .map(MemorySearchTools.ArchiveNodeView::archiveId)
                    .toList();
            List<Long> graphExpansionArchiveIds = safeGraphExpansion(bundle).stream()
                    .map(MemorySearchTools.GraphExpansionItem::archiveId)
                    .toList();
            List<String> sourceRefs = collectSourceRefs(groupContextArchiveIds, graphExpansionArchiveIds, archiveStore);
            List<String> sourceSessionIds = sourceRefs.stream()
                    .filter(ref -> ref.startsWith("session:"))
                    .map(ref -> ref.substring("session:".length()))
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();

            documents.add(new HaluMemRetrievedDocument(
                    "bundle-" + documentIndex++,
                    bundle.seedArchiveId(),
                    trimToEmpty(bundle.seedGroupId()),
                    bundle.seedScore(),
                    sourceRefs,
                    sourceSessionIds,
                    groupContextArchiveIds,
                    graphExpansionArchiveIds,
                    renderRetrievedDocument(bundle, sourceRefs)
            ));
        }
        return List.copyOf(documents);
    }

    static String renderRetrievedContext(List<HaluMemRetrievedDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return "No relevant memory was retrieved.";
        }
        StringBuilder builder = new StringBuilder();
        for (HaluMemRetrievedDocument document : documents) {
            if (document == null) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(document.text());
        }
        return builder.toString().trim();
    }

    private static List<MemorySearchTools.ArchiveNodeView> safeGroupContext(MemorySearchTools.MemorySearchBundle bundle) {
        return bundle.groupContext() == null ? List.of() : bundle.groupContext();
    }

    private static List<MemorySearchTools.GraphExpansionItem> safeGraphExpansion(MemorySearchTools.MemorySearchBundle bundle) {
        return bundle.graphExpansion() == null ? List.of() : bundle.graphExpansion();
    }

    private static List<String> collectSourceRefs(List<Long> groupContextArchiveIds,
                                                  List<Long> graphExpansionArchiveIds,
                                                  MemoryArchiveStore archiveStore) {
        Set<String> refs = new LinkedHashSet<>();
        for (Long archiveId : groupContextArchiveIds) {
            collectArchiveSourceRefs(archiveId, refs, archiveStore);
        }
        for (Long archiveId : graphExpansionArchiveIds) {
            collectArchiveSourceRefs(archiveId, refs, archiveStore);
        }
        return List.copyOf(refs);
    }

    private static void collectArchiveSourceRefs(Long archiveId,
                                                 Set<String> sink,
                                                 MemoryArchiveStore archiveStore) {
        if (archiveId == null) {
            return;
        }
        Optional<MemoryArchiveDocument> archive = archiveStore.findById(archiveId);
        archive.map(MemoryArchiveDocument::getSourceRefs)
                .orElse(List.of())
                .stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .forEach(sink::add);
    }

    private static String renderRetrievedDocument(MemorySearchTools.MemorySearchBundle bundle, List<String> sourceRefs) {
        StringBuilder builder = new StringBuilder();
        builder.append("Seed archive: ")
                .append(bundle.seedArchiveId())
                .append(" | group=")
                .append(trimToEmpty(bundle.seedGroupId()))
                .append(" | score=")
                .append(String.format(java.util.Locale.ROOT, "%.4f", bundle.seedScore()));

        if (!sourceRefs.isEmpty()) {
            builder.append("\nSource refs: ").append(String.join(", ", sourceRefs));
        }

        List<MemorySearchTools.ArchiveNodeView> groupContext = safeGroupContext(bundle);
        if (!groupContext.isEmpty()) {
            builder.append("\nGroup context:");
            for (MemorySearchTools.ArchiveNodeView nodeView : groupContext) {
                builder.append("\n- [")
                        .append(nodeView.archiveId())
                        .append("] ")
                        .append(trimToEmpty(nodeView.topic()))
                        .append(" | ")
                        .append(firstNonBlank(nodeView.keywordSummary(), nodeView.narrative()));
            }
        }

        List<MemorySearchTools.GraphExpansionItem> graphExpansion = safeGraphExpansion(bundle);
        if (!graphExpansion.isEmpty()) {
            builder.append("\nGraph expansion:");
            for (MemorySearchTools.GraphExpansionItem item : graphExpansion) {
                builder.append("\n- [")
                        .append(item.archiveId())
                        .append("] ")
                        .append(trimToEmpty(item.priorityBucket()))
                        .append(" | ")
                        .append(trimToEmpty(item.topic()))
                        .append(" | path=")
                        .append(item.pathArchiveIds())
                        .append(" | relations=")
                        .append(item.pathRelations());
            }
        }

        return builder.toString().trim();
    }

    private static String renderMemoryItem(HaluMemMemoryItem item) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(item.topic())) {
            parts.add("topic=" + item.topic().trim());
        }
        if (StringUtils.hasText(item.keywordSummary())) {
            parts.add("summary=" + item.keywordSummary().trim());
        }
        if (StringUtils.hasText(item.narrative())) {
            parts.add("narrative=" + item.narrative().trim());
        }
        return String.join(" | ", parts).trim();
    }

    private static String firstNonBlank(String left, String right) {
        String normalizedLeft = trimToEmpty(left);
        if (!normalizedLeft.isBlank()) {
            return normalizedLeft;
        }
        return trimToEmpty(right);
    }

    private static List<String> safeStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String trimmed = trimToEmpty(value);
            if (!trimmed.isBlank() && !normalized.contains(trimmed)) {
                normalized.add(trimmed);
            }
        }
        return List.copyOf(normalized);
    }

    private static String trimToEmpty(String text) {
        return text == null ? "" : text.trim();
    }

    private static String clip(String text, int maxChars) {
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= 3) {
            return text.substring(0, maxChars);
        }
        return text.substring(0, maxChars - 3) + "...";
    }
}
